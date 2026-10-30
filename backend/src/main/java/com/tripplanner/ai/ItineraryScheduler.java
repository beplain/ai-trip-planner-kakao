package com.tripplanner.ai;

import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.trip.TravelSchedule;
import com.tripplanner.trip.ExtraRequestRules;
import com.tripplanner.trip.TripDtos.TravelDetails;
import java.util.*;

/** Fit recommendations into safe slots. Strict mode requires all styles; repair mode returns the best feasible coverage. */
public final class ItineraryScheduler {
    private ItineraryScheduler() {}
    public static class SchedulingException extends IllegalArgumentException {
        public SchedulingException(String message) { super(message); }
    }
    private record Plan(int last, int coverage, List<AiPlace> places, int quality) {}
    public static AiItinerary prepare(AiItinerary input, ItineraryRequest request) {
        return prepare(input, request, true, 5);
    }
    public static AiItinerary prepareBestEffort(AiItinerary input, ItineraryRequest request) {
        return prepare(input, request, false, 5);
    }
    public static AiItinerary prepareBestEffort(AiItinerary input, ItineraryRequest request, int maxPlaces) {
        return prepare(input, request, false, Math.max(1, Math.min(10, maxPlaces)));
    }
    private static AiItinerary prepare(AiItinerary input, ItineraryRequest request, boolean strict, int maxPlaces) {
        var details = request.travelDetails();
        if (details == null || input == null || input.days() == null) return input;
        var events = TravelSchedule.events(details, request.days());
        int requestLimit = ExtraRequestRules.visitLimit(request.extraRequest(), strict ? 10 : maxPlaces);
        int requestStart = ExtraRequestRules.visitStart(request.extraRequest(),0);
        int requestEnd = ExtraRequestRules.visitEnd(request.extraRequest(),1440);
        List<AiDay> days = new ArrayList<>();
        for (AiDay day : input.days()) {
            if (day == null || day.places() == null || day.dayNumber() < 1 || day.dayNumber() > request.days()) {
                days.add(day); continue; // Structural errors must be retried, not fabricated into missing days.
            }
            List<AiPlace> ordinary = day.places().stream().filter(Objects::nonNull)
                    .filter(p -> !fixedActivity(p, details, request.destination())).toList();
            List<AiPlace> alternatives = (day.alternatives() == null ? List.<AiPlace>of() : day.alternatives()).stream()
                    .filter(Objects::nonNull).filter(p -> !fixedActivity(p, details, request.destination())).toList();
            var requestedMeals = ExtraRequestRules.meals(request.extraRequest(),day.dayNumber(),request.days());
            if(requestedMeals.size()>16) throw new SchedulingException("하루에 배치할 수 있는 식사 요청이 너무 많아요.");
            // Valid recommendations are left untouched (important for user-facing consistency).
            boolean fits = !ordinary.isEmpty(); int previous = -1;
            for (AiPlace p : ordinary) {
                try {
                    int time = TravelSchedule.minute(p.visitTime());
                    if (previous >= 0 && time < previous + 60 + TravelSchedule.gap(details)) fits = false;
                    if (!TravelSchedule.allowed(day.dayNumber(), request.days(), time, details, events) || (time < requestStart || time+60 > requestEnd)) fits = false;
                    previous = time;
                } catch (RuntimeException e) { fits = false; }
            }
            var required = new LinkedHashSet<>(request.styles() == null ? List.<String>of() : request.styles());
            var covered = new HashSet<String>(); ordinary.forEach(p -> covered.addAll(ItineraryRules.stylesFor(p)));
            int evening = TravelSchedule.eveningStart(day.dayNumber(), request.days(), details, events);
            boolean hasEvening = evening < 0 || ordinary.stream().anyMatch(p -> {
                try { return TravelSchedule.minute(p.visitTime()) >= evening; } catch (RuntimeException e) { return false; }
            });
            boolean hasMeals=requestedMeals.stream().allMatch(m->{
                var mealCandidates=m.lastRestaurant()?ordinary.stream().filter(p->"식당".equals(p.category())).reduce((a,b)->b).map(List::of).orElse(List.of()):ordinary;
                return mealCandidates.stream().anyMatch(p->m.claims(p.name(),p.category(),p.reason()+" "+p.searchQuery()) && m.containsTime(p.visitTime()));
            });
            if (fits && hasMeals && hasEvening && covered.containsAll(required) && ordinary.size() <= requestLimit) {
                days.add(new AiDay(day.dayNumber(), ordinary, alternatives)); continue;
            }
            LinkedHashMap<String, AiPlace> unique = new LinkedHashMap<>();
            for (AiPlace p : ordinary) if (p.name() != null && !p.name().isBlank()) unique.putIfAbsent(ItineraryRules.nameKey(p.name(), request.destination()), p);
            Set<String> primary = Set.copyOf(unique.keySet());
            for (AiPlace p : alternatives) if (p.name() != null && !p.name().isBlank()) unique.putIfAbsent(ItineraryRules.nameKey(p.name(), request.destination()), p);
            List<AiPlace> candidates = new ArrayList<>(unique.values().stream().limit(strict ? 16 : 32).toList());
            if(!requestedMeals.isEmpty()) candidates.sort(Comparator.comparingInt(p->requestedMeals.stream().anyMatch(m->m.lastRestaurant() && m.claims(p.name(),p.category(),p.reason()+" "+p.searchQuery()))?10000:requestedMeals.stream()
                .filter(m->m.claims(p.name(),p.category(),p.reason()+" "+p.searchQuery())).mapToInt(ExtraRequestRules.Meal::start).min().orElseGet(()->{
                    try{return TravelSchedule.minute(p.visitTime());}catch(RuntimeException e){return 10*60;}
                })));
            final int eveningBit = 1 << ItineraryRules.STYLES.size();
            int mealMask=0;
            for(int index=0;index<requestedMeals.size();index++) mealMask|=1 << (ItineraryRules.STYLES.size()+1+index);
            int lastBits=0;
            for(int index=0;index<requestedMeals.size();index++) if(requestedMeals.get(index).lastRestaurant()) lastBits|=1 << (ItineraryRules.STYLES.size()+1+index);
            final int lastRestaurantMask=lastBits;
            final int requestedMealMask=mealMask;
            int requiredMask = mask(required) | (evening >= 0 ? eveningBit : 0) | mealMask;
            int limit = strict ? Math.min(10, Math.max(evening >= 0 ? 2 : 1, Math.max(ordinary.size(), required.size() + requestedMeals.size()))) : maxPlaces;
            limit = Math.min(limit, requestLimit);
            Map<String, Plan> plans = new HashMap<>();
            plans.put("0:-1000", new Plan(-1000, 0, List.of(), 0));
            for (AiPlace p : candidates) {
                var candidateMeals=requestedMeals.stream().filter(m->m.claims(p.name(),p.category(),p.reason()+" "+p.searchQuery())).toList();
                int requestedPriority = ExtraRequestRules.requiredPriority(request.extraRequest(),p.name(),p.category()) + (candidateMeals.isEmpty()?0:10000);
                Map<String, Plan> next = new HashMap<>(plans); // Skipping only optional recommendations is allowed.
                for (Plan plan : plans.values()) {
                    if (plan.places().size() >= limit) continue;
                    if("식당".equals(p.category()) && (plan.coverage() & lastRestaurantMask)!=0) continue;
                    int earliest = Math.max(Math.max(requestStart,TravelSchedule.start(day.dayNumber(), details)), plan.last() + 60 + TravelSchedule.gap(details));
                    int latest = Math.min(TravelSchedule.end(day.dayNumber(), request.days(), details), requestEnd-60);
                    if (p.matchedStyles() != null && p.matchedStyles().contains("야경")) earliest = Math.max(earliest, 18 * 60);
                    String description = Objects.toString(p.name(), "") + Objects.toString(p.reason(), "") + Objects.toString(p.note(), "");
                    if ("식당".equals(p.category()) && candidateMeals.stream().noneMatch(ExtraRequestRules.Meal::lastRestaurant)) {
                        if (description.matches("(?s).*(저녁|디너).*")) earliest = Math.max(earliest, 17 * 60);
                        else if (description.matches("(?s).*(점심|런치).*")) { earliest = Math.max(earliest, 11 * 60); latest = Math.min(latest, 14 * 60); }
                        else if (description.matches("(?s).*(아침|조식).*")) latest = Math.min(latest, 10 * 60 + 30);
                    }
                    if(!candidateMeals.isEmpty()) {
                        earliest=Math.max(earliest,candidateMeals.stream().mapToInt(ExtraRequestRules.Meal::start).min().orElse(earliest));
                        latest=Math.min(latest,candidateMeals.stream().mapToInt(ExtraRequestRules.Meal::end).max().orElse(latest+60)-60);
                    }
                    Set<Integer> starts=new LinkedHashSet<>(); starts.add(earliest);
                    if(evening>=0) starts.add(Math.max(earliest,evening));
                    for(var meal:candidateMeals) {
                        starts.add(Math.max(earliest,meal.start()));
                        if(meal.lastRestaurant()) {
                            int beforeReturn=TravelSchedule.end(day.dayNumber(),request.days(),details)-60-TravelSchedule.gap(details);
                            starts.add(Math.max(earliest,Math.min(latest,(beforeReturn/15)*15)));
                        }
                    }
                    for (int starting : starts) {
                    int time = ((starting + 14) / 15) * 15;
                    while (time <= latest && !TravelSchedule.allowed(day.dayNumber(), request.days(), time, details, events)) time += 15;
                    if (time > latest) continue;
                    var scheduled = ItineraryRules.atTime(p, TravelSchedule.clock(time));
                    var chosen = new ArrayList<>(plan.places()); chosen.add(scheduled);
                    int coverage = plan.coverage() | mask(ItineraryRules.stylesFor(scheduled)) | (evening >= 0 && time >= evening ? eveningBit : 0);
                    for(int index=0;index<requestedMeals.size();index++) {
                        var meal=requestedMeals.get(index);
                        if(meal.claims(p.name(),p.category(),p.reason()+" "+p.searchQuery()) && meal.containsTime(scheduled.visitTime())) coverage|=1 << (ItineraryRules.STYLES.size()+1+index);
                    }
                    int quality = plan.quality() + requestedPriority + (candidateMeals.stream().anyMatch(ExtraRequestRules.Meal::lastRestaurant)?time/15:0) + (primary.contains(ItineraryRules.nameKey(p.name(), request.destination())) ? 100 : 1);
                    Plan candidate = new Plan(time, coverage, List.copyOf(chosen), quality);
                    String key = coverage + ":" + time;
                    Plan existing = next.get(key);
                    if (existing == null || candidate.quality() > existing.quality()) next.put(key, candidate);
                    }
                }
                plans = strict || next.size() <= 3000 ? next : next.values().stream()
                        .sorted(Comparator.<Plan>comparingInt(state -> Integer.bitCount(state.coverage() & requiredMask)).reversed()
                                .thenComparing(Comparator.comparingInt(Plan::quality).reversed()).thenComparingInt(Plan::last))
                        .limit(3000).collect(java.util.stream.Collectors.toMap(state -> state.coverage() + ":" + state.last(), state -> state));
            }
            Comparator<Plan> preference = strict ? Comparator.comparingInt(Plan::quality).thenComparingInt(p -> -p.last())
                    : Comparator.<Plan>comparingInt(p -> Integer.bitCount(p.coverage() & requestedMealMask))
                        .thenComparingInt(p -> Integer.bitCount(p.coverage() & mask(required)))
                        .thenComparingInt(p -> evening < 0 || (p.coverage() & eveningBit) != 0 ? 1 : 0)
                        .thenComparingInt(Plan::quality).thenComparingInt(p -> -p.last());
            Plan best = plans.values().stream().filter(p -> !strict || (p.coverage() & requiredMask) == requiredMask && !p.places().isEmpty())
                    .max(preference).orElseThrow(() ->
                            new SchedulingException(day.dayNumber() + "일차: 도착·출발 및 숙소 고정 시간을 지키며 선택 스타일(" + String.join(", ", required)
                                    + ")을 반영할 수 없습니다. 일반 장소 후보를 보완하거나 도착·출발 시간/스타일을 조정해 주세요."));
            days.add(new AiDay(day.dayNumber(), best.places(), alternatives));
        }
        return new AiItinerary(input.title(), days);
    }
    private static int mask(Collection<String> styles) {
        int mask = 0;
        for (String style : styles) { int index = ItineraryRules.STYLES.indexOf(style); if (index >= 0) mask |= 1 << index; }
        return mask;
    }
    private static boolean fixedActivity(AiPlace p, TravelDetails details, String destination) {
        String name = Objects.toString(p.name(), "");
        String key = ItineraryRules.nameKey(name, destination);
        if (details.stays() != null && details.stays().stream().anyMatch(s -> key.equals(ItineraryRules.nameKey(s.name(), destination)))) return true;
        if (details.transport() != null && (key.equals(ItineraryRules.nameKey(details.transport().arrivalPlace(), destination))
                || key.equals(ItineraryRules.nameKey(details.transport().departurePlace(), destination)))) return true;
        return name.matches("(?s).*(체크인|체크아웃|체크 인|체크 아웃|짐 맡기|짐 찾기|짐 보관|보관함 이용|택시로 이동|버스로 이동|지하철로 이동).*")
                || (name.matches("(?s).*(도착|출발|탑승|복귀).*") && name.matches("(?s).*(역|공항|터미널|숙소|호텔).*")
                    && !Set.of("식당", "카페").contains(Objects.toString(p.category(), "")));
    }
}
