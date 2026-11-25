package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.place.Geo;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;
import java.util.function.BiPredicate;

/** Daily profile counts cover optional visits. Meals and fixed commitments have their own rules. */
final class DraftVisitPlanner {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DraftVisitPlanner.class);
    private static final int[] MIN = {3, 2, 4};
    private static final int[] MAX = {4, 2, 5};
    private static final int[] TARGET = {3, 2, 5};
    private DraftVisitPlanner() {}
    private static final ThreadLocal<IdentityHashMap<TripDraft,Boolean>> MEAL_CHECKS=new ThreadLocal<>();
    private static <T> T cachedMealChecks(java.util.function.Supplier<T> task) {
        if(MEAL_CHECKS.get()!=null)return task.get();
        MEAL_CHECKS.set(new IdentityHashMap<>());
        try{return task.get();}finally{MEAL_CHECKS.remove();}
    }
    private static boolean validMeals(TripDraft draft,boolean checkCafes) {
        var cache=MEAL_CHECKS.get();
        // The cafe policy is checked separately in safe; cache only the stricter complete meal check.
        if(cache!=null&&checkCafes&&cache.containsKey(draft))return cache.get(draft);
        boolean valid;
        try{DraftMealPlanner.require(draft,checkCafes);valid=true;}catch(IllegalArgumentException | ApiException failure){valid=false;}
        if(cache!=null&&checkCafes){if(cache.size()>4096)cache.clear();cache.put(draft,valid);}
        return valid;
    }

    static boolean counted(PlaceDraft place) {
        return place.fixedEventId() == null && !"식당".equals(place.category());
    }

    /** Remove only redundant visits after hours verification; never relax the profile or add an unchecked venue. */
    static TripDraft trimExcess(TripDraft draft,int profile) {
        return trimExcess(draft,profile,(day,p)->true,(a,b)->visitGap(draft));
    }
    private static int visitGap(TripDraft draft){return Math.max(0,TravelSchedule.gap(draft.travelDetails())-10);}
    static TripDraft trimExcess(TripDraft draft,int profile,BiPredicate<DayDraft,PlaceDraft> open,java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel) {
        return cachedMealChecks(()->trimExcessInternal(draft,profile,open,travel));
    }
    private static TripDraft trimExcessInternal(TripDraft draft,int profile,BiPredicate<DayDraft,PlaceDraft> open,java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel) {
        TripDraft result=draft;
        for(var original:draft.days()) {
            var day=result.days().get(original.dayNumber()-1);
            var optional=new ArrayList<>(day.places().stream().filter(DraftVisitPlanner::counted).toList());
            Collections.reverse(optional);
            for(var place:optional) {
                if(day.places().stream().filter(DraftVisitPlanner::counted).count()<=MAX[profile])break;
                var visits=new ArrayList<>(day.places());visits.remove(place);
                var trial=replace(result,day,visits);
                if(safe(result,trial)){result=trial;day=result.days().get(original.dayNumber()-1);}
                else if(profile==1) {
                    var relocated=relocate(result,day,place,open,travel);
                    if(relocated==null)relocated=repackRelocation(result,day,place,open,travel);
                    if(relocated!=null){result=relocated;day=result.days().get(original.dayNumber()-1);}
                }
            }
        }
        return result;
    }

    static void require(TripDraft draft, int profile) {
        for (var day : draft.days()) {
            long count = day.places().stream().filter(DraftVisitPlanner::counted).count();
            if (count < MIN[profile] || count > MAX[profile])
                throw ApiException.badRequest(day.dayNumber() + "일차 식당 제외 방문 수 " + count
                        + "곳으로, 이 유형의 기준인 " + (MIN[profile] == MAX[profile] ? "하루 정확히 " + MIN[profile] : "하루 " + MIN[profile] + "~" + MAX[profile])
                        + "곳과 맞지 않습니다. " + day.places().stream().map(p -> p.visitTime()+"/"+p.category()).toList());
        }
    }

    static String fingerprint(TripDraft draft) {
        return draft.days().stream().map(day -> day.dayNumber() + ":" + day.places().stream()
                .filter(DraftVisitPlanner::counted).map(PlaceDraft::placeId).sorted().toList())
                .reduce("", (a, b) -> a + ";" + b);
    }

    private static Integer duration(PlaceDraft place, int profile) {
        return profile == 2 && Set.of("식당", "카페").contains(Objects.toString(place.category(), "")) ? 45 : null;
    }

    static TripDraft durations(TripDraft draft, int profile) {
        var days = draft.days().stream().map(day -> new DayDraft(day.dayNumber(), day.date(), day.places().stream()
                .map(p -> p.fixedEventId() == null ? p.withDurationMinutes(duration(p, profile)) : p).toList())).toList();
        return DraftVariants.withDays(draft, days, draft.warnings(), null, null);
    }

    static TripDraft fit(TripDraft draft, Map<Integer, List<PlaceDraft>> pool, int profile,
                         BiPredicate<DayDraft, PlaceDraft> open, java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        return cachedMealChecks(()->fit(draft, pool, profile, open, travel, true));
    }

    private static TripDraft fit(TripDraft draft, Map<Integer, List<PlaceDraft>> pool, int profile,
                         BiPredicate<DayDraft, PlaceDraft> open, java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel,
                         boolean allowReplacement) {
        return fit(draft,pool,profile,open,travel,allowReplacement,0);
    }
    private static TripDraft fit(TripDraft draft,Map<Integer,List<PlaceDraft>> pool,int profile,
            BiPredicate<DayDraft,PlaceDraft> open,java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel,boolean allowReplacement,int focusDay) {
        TripDraft result = repairCafeOrder(durations(draft, profile), pool, profile, open, travel);
        for (var originalDay : result.days()) {
            if(focusDay>0&&originalDay.dayNumber()!=focusDay)continue;
            int number = originalDay.dayNumber();
            DayDraft day = result.days().get(number - 1);
            if (profile == 2 || day.places().stream().filter(DraftVisitPlanner::counted).count() < MIN[profile]) {
                result = earlierMeals(result, day, open, travel);
                result = earlierVisits(result, result.days().get(number - 1), open, travel);
                result = earlierMeals(result, result.days().get(number - 1), open, travel);
                day = result.days().get(number - 1);
            }
            var removable = new ArrayList<>(day.places().stream().filter(DraftVisitPlanner::counted).toList());
            Collections.reverse(removable);
            for (var place : removable) {
                if (day.places().stream().filter(DraftVisitPlanner::counted).count() <= TARGET[profile]) break;
                var visits = new ArrayList<>(day.places());
                visits.remove(place);
                var trial = replace(result, day, visits);
                if (safe(result, trial)) { result = trial; day = result.days().get(number - 1); }
                else {
                    var relocated = relocate(result, day, place, open, travel);
                    if(relocated==null&&profile==1)relocated=repackRelocation(result,day,place,open,travel);
                    if (relocated != null) { result = relocated; day = result.days().get(number - 1); }
                }
            }
            Map<String, PlaceDraft> candidates = new LinkedHashMap<>();
            pool.getOrDefault(number, List.of()).forEach(p -> candidates.putIfAbsent(p.placeId(), p));
            originalDay.places().forEach(p -> candidates.putIfAbsent(p.placeId(), p));
            // Prefer this day's candidates, retaining other days' originals for their own profiles.
            Set<String> used = new HashSet<>();
            result.days().forEach(d -> d.places().forEach(p -> used.add(p.placeId())));
            List<PlaceDraft> available = candidates.values().stream().filter(DraftVisitPlanner::counted)
                    .filter(PlaceDraft::verified).filter(p -> !used.contains(p.placeId()))
                    .map(p -> p.withDurationMinutes(duration(p, profile))).toList();
            while (day.places().stream().filter(DraftVisitPlanner::counted).count() < TARGET[profile]) {
                Placement best = null;
                var events = TravelSchedule.events(result.travelDetails(), result.days().size());
                var visits = result.days().stream().flatMap(d -> d.places().stream().filter(p -> p.fixedEventId() == null)
                        .map(p -> new ExtraRequestRules.Visit(d.dayNumber(),p.name(),p.category(),p.categoryPath(),p.placeId()))).toList();
                for (var candidate : available) {
                    if (used.contains(candidate.placeId()) || !ExtraRequestRules.allowsAdditional(result.effectiveRequest(),
                            new ExtraRequestRules.Visit(number,candidate.name(),candidate.category(),candidate.categoryPath(),candidate.placeId()), result.days().size(), visits)) continue;
                    int start = ExtraRequestRules.visitStart(result.effectiveRequest(), TravelSchedule.start(number, result.travelDetails()));
                    int end = ExtraRequestRules.visitEnd(result.effectiveRequest(), TravelSchedule.end(number, result.days().size(), result.travelDetails()));
                    for (int time = ((start + 14) / 15) * 15; time + candidate.stayMinutes() <= end; time += 15) {
                        if (!TravelSchedule.allowed(number, result.days().size(), time, result.travelDetails(), events, candidate.stayMinutes())) continue;
                        String clock = TravelSchedule.clock(time);
                        if (!TripRouteOptimizer.fits(candidate, clock, Map.of(candidate, TravelSchedule.minute(candidate.visitTime())))) continue;
                        var moved = TripRouteOptimizer.at(candidate, clock).withBusinessHours(null);
                        double detour = detour(day.places(), moved);
                        if (best != null) {
                            boolean cafe = "카페".equals(moved.category()), bestCafe = "카페".equals(best.place().category());
                            if (cafe && !bestCafe || cafe == bestCafe && detour >= best.detour()) continue;
                        }
                        var trial = insert(result, day, moved, open, travel);
                        if (trial != null) best = new Placement(moved, trial, detour);
                    }
                }
                if (best == null) {
                    if (allowReplacement && day.places().stream().filter(DraftVisitPlanner::counted).count() < MIN[profile]) {
                        var repaired = replaceAndFit(result, day, pool, profile, open, travel);
                        if (repaired != null) { result = repaired; day = result.days().get(number - 1); }
                    }
                    break;
                }
                result = best.draft(); used.add(best.place().placeId()); day = result.days().get(number - 1);
            }
        }
        return restoreCoverage(result, pool, profile, open, travel);
    }

    // Adding a stop to the current slots can fail even when another venue mix fits.
    // Try a bounded replacement, then repack; never recurse into another replacement search.
    private static TripDraft replaceAndFit(TripDraft draft, DayDraft day, Map<Integer,List<PlaceDraft>> pool,
                                           int profile, BiPredicate<DayDraft,PlaceDraft> open,
                                           java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel) {
        Set<String> used = new HashSet<>();
        draft.days().forEach(d -> d.places().forEach(p -> used.add(p.placeId())));
        int attempts = 0;
        for (var previous : day.places()) {
            if (!counted(previous)) continue;
            for (var candidate : pool.getOrDefault(day.dayNumber(), List.of())) {
                if (!counted(candidate) || !candidate.verified() || used.contains(candidate.placeId())) continue;
                if (++attempts > 12) return null;
                var moved = TripRouteOptimizer.at(candidate, previous.visitTime())
                        .withDurationMinutes(duration(candidate, profile)).withBusinessHours(null);
                if (!TripRouteOptimizer.fits(candidate, moved.visitTime(), Map.of(candidate, TravelSchedule.minute(candidate.visitTime())))) continue;
                var others = without(day, previous);
                var replaced = insert(draft, others, moved, open, travel);
                if (replaced == null) continue;
                var expanded = new HashMap<Integer,List<PlaceDraft>>(pool);
                var choices = new ArrayList<>(pool.getOrDefault(day.dayNumber(), List.of()));
                choices.add(previous);
                expanded.put(day.dayNumber(), choices);
                var trial = fit(replaced, expanded, profile, open, travel, false,day.dayNumber());
                if (trial.days().get(day.dayNumber()-1).places().stream().filter(DraftVisitPlanner::counted).count() >= MIN[profile]
                        && safe(draft, trial)) return trial;
            }
        }
        return null;
    }

    private static int cafeViolationCount(TripDraft draft) {
        return draft.days().stream().mapToInt(day -> DraftMealPlanner.cafeViolations(day.places()).size()).sum();
    }

    private static TripDraft repairCafeOrder(TripDraft draft, Map<Integer,List<PlaceDraft>> pool, int profile,
                                            BiPredicate<DayDraft,PlaceDraft> open,
                                            java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel) {
        TripDraft result = draft;
        while (cafeViolationCount(result) > 0) {
            TripDraft repaired = null;
            Set<String> used = new HashSet<>();
            result.days().forEach(day -> day.places().forEach(p -> used.add(p.placeId())));
            for (var day : result.days()) {
                for (var cafe : DraftMealPlanner.cafeViolations(day.places())) {
                    for (var candidate : pool.getOrDefault(day.dayNumber(),List.of())) {
                        if (!counted(candidate) || "카페".equals(candidate.category()) || !candidate.verified() || used.contains(candidate.placeId())) continue;
                        var moved = TripRouteOptimizer.at(candidate, cafe.visitTime()).withDurationMinutes(duration(candidate,profile)).withBusinessHours(null);
                        if (!TripRouteOptimizer.fits(candidate,moved.visitTime(),Map.of(candidate,TravelSchedule.minute(candidate.visitTime())))) continue;
                        var others = without(day,cafe);
                        var trial = insert(result,others,moved,open,travel);
                        if (trial != null) { repaired=trial; break; }
                    }
                    if (repaired != null) break;
                }
                if (repaired != null) break;
            }
            if (repaired == null) break;
            result = repaired;
        }
        return result;
    }

    // Restore requested styles on the final meal/route schedule, without replanning it away again.
    static TripDraft restoreCoverage(TripDraft draft, Map<Integer, List<PlaceDraft>> pool, int profile,
                                     BiPredicate<DayDraft, PlaceDraft> open,
                                     java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        TripDraft result = draft;
        var missing = new LinkedHashSet<>(draft.styles() == null ? List.<String>of() : draft.styles());
        draft.days().forEach(day -> missing.removeAll(DraftOptionComposer.coverage(day.places(), draft.styles())));
        for (String style : missing) {
            if (result.days().stream().anyMatch(day -> DraftOptionComposer.coverage(day.places(), draft.styles()).contains(style))) continue;
            Set<String> used = new HashSet<>();
            result.days().forEach(day -> day.places().forEach(p -> used.add(p.placeId())));
            Placement best = null;
            for (var day : result.days()) {
                var candidates = pool.getOrDefault(day.dayNumber(), List.of()).stream()
                        .filter(DraftVisitPlanner::counted).filter(PlaceDraft::verified)
                        .filter(p -> !used.contains(p.placeId()))
                        .filter(p -> DraftOptionComposer.coverage(List.of(p), draft.styles()).contains(style))
                        .map(p -> p.withDurationMinutes(duration(p, profile))).toList();
                for (var candidate : candidates) for (var previous : day.places()) {
                    if (!counted(previous)) continue;
                    var moved = TripRouteOptimizer.at(candidate, previous.visitTime()).withBusinessHours(null);
                    if (!TripRouteOptimizer.fits(candidate, moved.visitTime(), Map.of(candidate, TravelSchedule.minute(candidate.visitTime())))) continue;
                    var others = without(day, previous);
                    double score = detour(others.places(), moved);
                    if (best != null && score >= best.detour()) continue;
                    var visits = new ArrayList<>(others.places()); visits.add(moved);
                    var trial = replace(result, day, visits);
                    if (safe(result, trial) && reachable(trial, others, moved, travel) && open.test(day, moved))
                        best = new Placement(moved, trial, score);
                }
                if (best == null && day.places().stream().filter(DraftVisitPlanner::counted).count() < MAX[profile]) {
                    int start = ExtraRequestRules.visitStart(result.effectiveRequest(), TravelSchedule.start(day.dayNumber(), result.travelDetails()));
                    int end = ExtraRequestRules.visitEnd(result.effectiveRequest(), TravelSchedule.end(day.dayNumber(), result.days().size(), result.travelDetails()));
                    for (var candidate : candidates) for (int time = ((start + 14) / 15) * 15; time + candidate.stayMinutes() <= end; time += 15) {
                        var moved = TripRouteOptimizer.at(candidate, TravelSchedule.clock(time)).withBusinessHours(null);
                        if (!TripRouteOptimizer.fits(candidate, moved.visitTime(), Map.of(candidate, TravelSchedule.minute(candidate.visitTime())))) continue;
                        var trial = insert(result, day, moved, open, travel);
                        if (trial != null) { best = new Placement(moved, trial, detour(day.places(), moved)); break; }
                    }
                }
            }
            if (best != null) {
                result = best.draft();
                log.info("여행 스타일 최종 보완: 유형={} 스타일={} 장소id={} 시각={}", profile, style, best.place().placeId(), best.place().visitTime());
            }
        }
        return result;
    }

    // Free a small gap by delaying one visit. Scheduled meals must remain inside their requested windows.
    static TripDraft insert(TripDraft draft, DayDraft day, PlaceDraft candidate,
                                    BiPredicate<DayDraft, PlaceDraft> open,
                                    java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        var visits = new ArrayList<>(day.places());
        PlaceDraft shifted = null;
        int time = TravelSchedule.minute(candidate.visitTime());
        int gap = TravelSchedule.gap(draft.travelDetails());
        for (var place : day.places()) {
            if (place.fixedEventId() != null) continue;
            int other = TravelSchedule.minute(place.visitTime());
            if (other <= time) {
                if (time < other + place.stayMinutes() + gap) return null;
            } else if (time + candidate.stayMinutes() + gap > other) {
                int next = time + candidate.stayMinutes() + gap;
                boolean meal = DraftMealPlanner.scheduledMeal(place);
                if ((!counted(place) && !meal) || shifted != null || next - other > (meal ? 60 : 45)) return null;
                String clock = TravelSchedule.clock(next);
                if (!TripRouteOptimizer.fits(place, clock, Map.of(place, other))
                        || (meal && !ExtraRequestRules.mealTimeAllowed(place, clock, draft.effectiveRequest(), day.dayNumber(), draft.days().size()))) return null;
                shifted = TripRouteOptimizer.at(place, clock).withBusinessHours(null);
                visits.remove(place);
                visits.add(shifted);
            }
        }
        visits.add(candidate);
        var trial = replace(draft, day, visits);
        var updated = trial.days().get(day.dayNumber() - 1);
        if (!reachable(trial, without(updated, candidate), candidate, travel)
                || !open.test(updated, candidate)) return null;
        if (shifted != null && (!free(without(updated, shifted), shifted, draft.travelDetails())
                || !open.test(updated, shifted) || !reachable(trial, without(updated, shifted), shifted, travel))) return null;
        return safe(draft, trial) ? trial : null;
    }

    private static DayDraft without(DayDraft day, PlaceDraft place) {
        return new DayDraft(day.dayNumber(), day.date(), day.places().stream().filter(p -> p != place).toList());
    }

    private static TripDraft earlierMeals(TripDraft draft, DayDraft day,
                                         BiPredicate<DayDraft, PlaceDraft> open,
                                         java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        TripDraft result = draft;
        for (var meal : day.places()) {
            if (!DraftMealPlanner.scheduledMeal(meal)) continue;
            int original = TravelSchedule.minute(meal.visitTime());
            for (int time = TravelSchedule.start(day.dayNumber(), draft.travelDetails()); time < original; time += 5) {
                if (!ExtraRequestRules.mealTimeAllowed(meal, TravelSchedule.clock(time), draft.effectiveRequest(), day.dayNumber(), draft.days().size())) continue;
                var moved = TripRouteOptimizer.at(meal, TravelSchedule.clock(time)).withBusinessHours(null);
                var visits = new ArrayList<>(result.days().get(day.dayNumber() - 1).places()); visits.remove(meal);
                var without = new DayDraft(day.dayNumber(), day.date(), visits);
                visits = new ArrayList<>(visits); visits.add(moved);
                var trial = replace(result, day, visits);
                if (free(without, moved, draft.travelDetails()) && reachable(trial, without, moved, travel)
                        && open.test(day, moved) && safe(result, trial)) { result = trial; break; }
            }
        }
        return result;
    }

    private static TripDraft earlierVisits(TripDraft draft, DayDraft day,
                                          BiPredicate<DayDraft, PlaceDraft> open,
                                          java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        TripDraft result = draft;
        for (var place : day.places()) {
            if (!counted(place)) continue;
            int original = TravelSchedule.minute(place.visitTime());
            for (int time = TravelSchedule.start(day.dayNumber(), draft.travelDetails()); time < original; time += 5) {
                String clock = TravelSchedule.clock(time);
                if (!TripRouteOptimizer.fits(place, clock, Map.of(place, original))) continue;
                var moved = TripRouteOptimizer.at(place, clock).withBusinessHours(null);
                var visits = new ArrayList<>(result.days().get(day.dayNumber() - 1).places()); visits.remove(place);
                var without = new DayDraft(day.dayNumber(), day.date(), visits);
                visits = new ArrayList<>(visits); visits.add(moved);
                var trial = replace(result, day, visits);
                if (free(without, moved, draft.travelDetails()) && reachable(trial, without, moved, travel)
                        && open.test(day, moved) && safe(result, trial)) { result = trial; break; }
            }
        }
        return result;
    }

    private static TripDraft relocate(TripDraft draft, DayDraft day, PlaceDraft place,
                                     BiPredicate<DayDraft, PlaceDraft> open,
                                     java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        for (var other : draft.days()) {
            if (other.dayNumber() == day.dayNumber()) continue;
            for (var replaced : other.places()) {
                if (!counted(replaced)) continue;
                var moved = TripRouteOptimizer.at(place, replaced.visitTime()).withBusinessHours(null);
                var remaining = new ArrayList<>(day.places()); remaining.remove(place);
                var replacements = new ArrayList<>(other.places()); replacements.remove(replaced); replacements.add(moved);
                var trial = replace(replace(draft, day, remaining), other, replacements);
                if (safe(draft, trial) && reachable(trial, new DayDraft(other.dayNumber(), other.date(),
                        replacements.stream().filter(p -> p != moved).sorted(Comparator.comparing(PlaceDraft::visitTime)).toList()), moved, travel)
                        && open.test(other, moved)) return trial;
            }
        }
        return null;
    }

    // A café may fit another day only when its companion visit is moved as well.
    // Try meal-boundary slots, preserving visit counts, meals, commitments and style coverage.
    private static TripDraft repackRelocation(TripDraft draft,DayDraft source,PlaceDraft moving,
            BiPredicate<DayDraft,PlaceDraft> open,
            java.util.function.ToIntBiFunction<PlaceDraft,PlaceDraft> travel) {
        int attempts=0;
        for(var target:draft.days()) {
            if(target.dayNumber()==source.dayNumber())continue;
            var optional=target.places().stream().filter(DraftVisitPlanner::counted).toList();
            // The minimal profile needs exactly two optional visits. Bound this recovery search.
            if(optional.size()!=2)continue;
            for(var replaced:optional) {
                var retained=optional.stream().filter(p->p!=replaced).findFirst().orElseThrow();
                var slots=new TreeSet<Integer>();
                slots.add(TravelSchedule.minute(moving.visitTime()));
                optional.forEach(p->slots.add(TravelSchedule.minute(p.visitTime())));
                int gap=TravelSchedule.gap(draft.travelDetails());
                target.places().stream().filter(p->"식당".equals(p.category())).forEach(p->{
                    int time=TravelSchedule.minute(p.visitTime());
                    slots.add(time+p.stayMinutes()+gap);
                    slots.add(time-moving.stayMinutes()-gap);
                    slots.add(time-retained.stayMinutes()-gap);
                });
                var baseSlots=new ArrayList<>(slots);
                for(int time:baseSlots){slots.add(time+moving.stayMinutes()+gap);slots.add(time+retained.stayMinutes()+gap);}
                int start=ExtraRequestRules.visitStart(draft.effectiveRequest(),TravelSchedule.start(target.dayNumber(),draft.travelDetails()));
                int end=ExtraRequestRules.visitEnd(draft.effectiveRequest(),TravelSchedule.end(target.dayNumber(),draft.days().size(),draft.travelDetails()));
                var validSlots=slots.stream().filter(t->t>=start&&t+Math.max(moving.stayMinutes(),retained.stayMinutes())<=end).toList();
                for(int first:validSlots)for(int second:validSlots) {
                    if(++attempts>1200)return null;
                    if(Math.abs(first-second)<Math.min(moving.stayMinutes(),retained.stayMinutes())+gap)continue;
                    var moved=TripRouteOptimizer.at(moving,TravelSchedule.clock(first)).withBusinessHours(null);
                    var companion=TripRouteOptimizer.at(retained,TravelSchedule.clock(second)).withBusinessHours(null);
                    if(!TripRouteOptimizer.fits(moving,moved.visitTime(),Map.of(moving,TravelSchedule.minute(moving.visitTime())))
                        ||!TripRouteOptimizer.fits(retained,companion.visitTime(),Map.of(retained,TravelSchedule.minute(retained.visitTime()))))continue;
                    var visits=new ArrayList<>(target.places().stream().filter(p->p!=replaced&&p!=retained).toList());
                    visits.add(moved);visits.add(companion);
                    var trial=replace(replace(draft,source,new ArrayList<>(without(source,moving).places())),target,visits);
                    if(!safe(draft,trial))continue;
                    var targetDay=trial.days().get(target.dayNumber()-1);
                    if(reachable(trial,without(targetDay,moved),moved,travel)
                        &&reachable(trial,without(targetDay,companion),companion,travel)
                        &&open.test(target,moved)&&open.test(target,companion)) {
                        log.info("최소 방문 수 보완: 원래일차={} 이동일차={} 장소id={} 시각={}",source.dayNumber(),target.dayNumber(),moving.placeId(),moved.visitTime());
                        return trial;
                    }
                }
            }
        }
        return null;
    }

    private static boolean free(DayDraft day, PlaceDraft candidate, TravelDetails details) {
        int time = TravelSchedule.minute(candidate.visitTime());
        int gap = TravelSchedule.gap(details);
        return day.places().stream().filter(p -> p.fixedEventId() == null).noneMatch(p -> {
            int other = TravelSchedule.minute(p.visitTime());
            return other <= time ? time < other + p.stayMinutes() + gap : time + candidate.stayMinutes() + gap > other;
        });
    }

    private static boolean reachable(TripDraft draft, DayDraft day, PlaceDraft candidate,
                                     java.util.function.ToIntBiFunction<PlaceDraft, PlaceDraft> travel) {
        PlaceDraft before = null, after = null;
        for (var place : day.places()) {
            if (place.visitTime().compareTo(candidate.visitTime()) < 0) before = place;
            else { after = place; break; }
        }
        int time = TravelSchedule.minute(candidate.visitTime());
        if (before == null) {
            var hotel = RoutedItineraryService.overnight(draft.travelDetails(), day.dayNumber(), true);
            if (hotel != null) {
                int minutes = travel.applyAsInt(hotel, candidate);
                if (time < TravelSchedule.start(day.dayNumber(), draft.travelDetails()) + minutes + (minutes > 0 ? 10 : 0)) return false;
            }
        }
        if (before != null) {
            int minutes = travel.applyAsInt(before, candidate);
            int ready = TravelSchedule.minute(before.visitTime()) + RoutedItineraryService.dwell(before, draft.travelDetails())
                    + minutes + (minutes > 0 ? 10 : 0);
            if (time < ready) return false;
        }
        if (after != null) {
            int minutes = travel.applyAsInt(candidate, after);
            if (time + candidate.stayMinutes() + minutes + (minutes > 0 ? 10 : 0) > TravelSchedule.minute(after.visitTime())) return false;
        }
        return true;
    }

    private record Placement(PlaceDraft place, TripDraft draft, double detour) {}

    private static double detour(List<PlaceDraft> visits, PlaceDraft candidate) {
        if (!TripRouteOptimizer.known(candidate)) return Double.MAX_VALUE;
        PlaceDraft before = null, after = null;
        for (var place : visits) {
            if (!TripRouteOptimizer.known(place)) continue;
            if (place.visitTime().compareTo(candidate.visitTime()) < 0) before = place;
            else { after = place; break; }
        }
        return (before == null ? 0 : meters(before, candidate)) + (after == null ? 0 : meters(candidate, after))
                - (before == null || after == null ? 0 : meters(before, after));
    }
    private static double meters(PlaceDraft a, PlaceDraft b) {
        return Geo.distanceMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }
    private static TripDraft replace(TripDraft draft, DayDraft day, List<PlaceDraft> visits) {
        visits.sort(Comparator.comparing(PlaceDraft::visitTime));
        var days = draft.days().stream().map(d -> d.dayNumber() == day.dayNumber()
                ? new DayDraft(d.dayNumber(), d.date(), List.copyOf(visits)) : d).toList();
        return DraftVariants.withDays(draft, days, draft.warnings(), null, null);
    }
    private static boolean mealsNotWorse(TripDraft before,TripDraft trial,boolean checkCafes) {
        boolean same=Objects.equals(before.travelDetails(),trial.travelDetails())&&Objects.equals(before.effectiveRequest(),trial.effectiveRequest())&&before.days().size()==trial.days().size();
        boolean removed=false,subset=true;
        if(same)for(int i=0;i<before.days().size();i++) {
            var original=before.days().get(i);var changed=trial.days().get(i);
            if(original.dayNumber()!=changed.dayNumber()||!Objects.equals(original.date(),changed.date())
                ||!original.places().stream().filter(p->"식당".equals(p.category())).toList().equals(changed.places().stream().filter(p->"식당".equals(p.category())).toList())){same=false;break;}
            if(DraftMealPlanner.mealOrderViolations(changed.places())>DraftMealPlanner.mealOrderViolations(original.places()))return false;
            subset&=original.places().containsAll(changed.places());
            removed|=changed.places().size()<original.places().size();
        }
        if(same) {
            // Strict timing and cafe checks have already run in safe. Unchanged meals retain their checked windows and menus.
            if(validMeals(before,checkCafes))return true;
            return subset&&removed;
        }
        return validMeals(trial,checkCafes);
    }

    private static boolean safe(TripDraft before, TripDraft trial) {
        Set<String> covered = new HashSet<>(), after = new HashSet<>();
        before.days().forEach(d -> covered.addAll(DraftOptionComposer.coverage(d.places(), before.styles())));
        trial.days().forEach(d -> after.addAll(DraftOptionComposer.coverage(d.places(), trial.styles())));
        if (!after.containsAll(covered)) return false;
        try {
            ExtraRequestRules.require(trial);
            TravelSchedule.validateDraft(trial);
            int oldCafes = cafeViolationCount(before), newCafes = cafeViolationCount(trial);
            if (newCafes > 0 && newCafes >= oldCafes) return false;
            if (trial.days().stream().flatMap(d -> d.places().stream()).anyMatch(DraftMealPlanner::scheduledMeal) && !mealsNotWorse(before,trial,newCafes==0))return false;
            return true;
        } catch (IllegalArgumentException | ApiException e) { return false; }
    }
}
