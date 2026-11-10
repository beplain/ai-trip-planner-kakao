package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.place.KakaoLocalClient.*;
import com.tripplanner.trip.TripDtos.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.*;

/** Recover a usable draft using confirmed candidates. Preferences are soft; time/location integrity is not. */
final class ConfirmedItineraryFallback {
    private final KakaoLocalClient maps;
    private final ExecutorService pool;
    private record Query(String text, Area area) {}
    private record Seed(AiPlace place, Query query) {}
    ConfirmedItineraryFallback(KakaoLocalClient maps, ExecutorService pool) { this.maps = maps; this.pool = pool; }

    TripDraft generate(GenerateRequest input, AiItinerary candidate, boolean aiEnabled) {
        return generate(input, candidate, aiEnabled, null);
    }
    TripDraft generate(GenerateRequest input, AiItinerary candidate, boolean aiEnabled, Map<Integer,List<PlaceDraft>> candidatePool) {
        TravelDetails details = input.travelDetails() == null ? new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(), null) : input.travelDetails();
        var request = new ItineraryRequest(input.destination().trim(), input.startDate(), input.days(), input.budget(),
                input.styles() == null ? List.of() : input.styles(), input.request(), details);
        List<String> warnings = new ArrayList<>();
        warnings.add("일부 조건을 모두 맞추지 못해 카카오맵에서 확인된 장소로 가능한 초안을 구성했습니다. 날짜별 안내를 확인해 주세요.");
        if (aiEnabled && candidate == null) warnings.add("AI 응답을 완성하지 못해 카카오맵 검색 결과를 기준으로 작성했습니다.");
        Area destination;
        try { destination = maps.resolveArea(request.destination()).orElse(null); }
        catch (RuntimeException e) { throw unavailable(); }

        List<List<Seed>> dailySeeds = new ArrayList<>();
        LinkedHashMap<Query, Future<List<PlaceHit>>> pending = new LinkedHashMap<>();
        for (int n = 1; n <= request.days(); n++) {
            Area center = center(details, n, destination);
            List<AiPlace> seeds = new ArrayList<>(ExtraRequestRules.mealSeeds(input.request(),request.destination(),n,request.days()));
            seeds.addAll(ExtraRequestRules.includeSeeds(input.request(),request.destination(),n,request.days()));
            if (candidate != null && candidate.days() != null) for (AiDay day : candidate.days()) {
                if (day == null || day.dayNumber() != n) continue;
                if (day.places() != null) day.places().stream().filter(Objects::nonNull).limit(6).forEach(seeds::add);
                if (day.alternatives() != null) day.alternatives().stream().filter(Objects::nonNull).limit(2).forEach(seeds::add);
            }
            for (String style : request.styles()) seeds.add(theme(style, request.destination()));
            seeds.add(theme("맛집", request.destination()));
            seeds.add(theme("카페", request.destination()));
            seeds.add(theme("관광", request.destination()));
            List<Seed> entries = new ArrayList<>();
            for (AiPlace seed : seeds) {
                if (seed.name() == null || seed.name().isBlank() || seed.category() == null || excluded(seed.category(), input.request())) continue;
                String text = seed.searchQuery() == null || seed.searchQuery().isBlank() ? request.destination() + " " + seed.name() : seed.searchQuery();
                Query query = new Query(text, center);
                entries.add(new Seed(seed, query));
                pending.computeIfAbsent(query, q -> pool.submit(() -> {
                    List<PlaceHit> found=new ArrayList<>(maps.search(q.text(),q.area(),15));
                    if(q.area()!=null) found.sort(Comparator.comparingDouble(h -> com.tripplanner.place.Geo.distanceMeters(q.area().lat(),q.area().lng(),h.lat(),h.lng())));
                    return found;
                }));
            }
            dailySeeds.add(entries);
        }
        Map<Query, List<PlaceHit>> hits = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (var entry : pending.entrySet()) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                hits.put(entry.getKey(), entry.getValue().get(remaining, TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                entry.getValue().cancel(true); hits.put(entry.getKey(), List.of());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        var reservedMeals=ExtraRequestRules.reserveMeals(input.request(),request.days(),m->hits.getOrDefault(new Query(m.query(request.destination()),center(details,m.day(),destination)),List.of()));
        Set<String> usedIds = new HashSet<>(), usedNames = new HashSet<>();
        List<DayDraft> days = new ArrayList<>();
        List<TravelSchedule.Event> events = TravelSchedule.events(details, request.days());
        for (int n = 1; n <= request.days(); n++) {
            LinkedHashMap<String, PlaceDraft> known = new LinkedHashMap<>();
            LinkedHashMap<String, AiPlace> recommendations = new LinkedHashMap<>();
            final int currentDay = n;
            // Round-robin prevents the first search query from crowding out other styles.
            for (int rank = 0; rank < 15; rank++) for (Seed seed : dailySeeds.get(n - 1)) {
                List<PlaceHit> result = hits.getOrDefault(seed.query(), List.of()).stream()
                        .filter(h -> valid(h, seed.place(), request.destination()) && ExtraRequestRules.allows(input.request(), h.name(), seed.place().category())
                            && ExtraRequestRules.allowsMealHit(input.request(),currentDay,request.days(),seed.place().name(),seed.place().category(),seed.place().reason()+" "+seed.place().searchQuery(),h.name(),h.categoryName())).toList();
                if (rank >= result.size()) continue;
                PlaceHit h = result.get(rank);
                if (h == null || (reservedMeals.containsKey(h.placeId()) && reservedMeals.get(h.placeId())!=n)) continue;
                String name = ItineraryRules.nameKey(h.name(), request.destination());
                if (!valid(h, seed.place(), request.destination()) || usedIds.contains(h.placeId()) || usedNames.contains(name)
                        || known.containsKey(h.placeId()) || recommendations.values().stream().anyMatch(p -> ItineraryRules.nameKey(p.name(), request.destination()).equals(name))) continue;
                List<ExtraRequestRules.Visit> counted=new ArrayList<>();
                for(DayDraft previousDay:days) for(PlaceDraft previous:previousDay.places()) if(previous.fixedEventId()==null)
                    counted.add(new ExtraRequestRules.Visit(previousDay.dayNumber(),previous.name(),previous.category()));
                for(PlaceDraft current:known.values()) counted.add(new ExtraRequestRules.Visit(n,current.name(),current.category()));
                if(!ExtraRequestRules.allowsAdditional(input.request(),h.name(),seed.place().category(),n,request.days(),counted)) continue;
                var p = seed.place();
                String time = p.visitTime() == null ? "10:00" : p.visitTime();
                var confirmed = ExtraRequestRules.annotateMeal(new PlaceDraft(h.placeId(), h.name(), h.address(), h.lat(), h.lng(), time,
                        "카카오맵에서 확인한 장소로 이동 가능한 시간대에 배치했습니다.", "임시휴무는 방문 전 확인해주세요.", p.category(), true, h.placeUrl(), h.phone(),null,h.categoryName()),input.request(),n,request.days());
                known.put(h.placeId(), confirmed);
                recommendations.put(h.placeId(), new AiPlace(h.name(), h.placeId(), time, p.category(), confirmed.reason()+" "+Objects.toString(confirmed.categoryPath(),""), confirmed.note(), p.matchedStyles()));
            }
            if (candidatePool != null) candidatePool.put(n, List.copyOf(known.values()));
            // This day always exists even if the AI omitted it. Only real map results can populate it.
            var remaining=new LinkedHashSet<>(request.styles());
            for(var previousDay:days)for(var previous:previousDay.places()) {
                var aiPlace=new AiPlace(previous.name(),previous.placeId(),previous.visitTime(),previous.category(),previous.reason(),previous.note(),request.styles());
                remaining.removeAll(ItineraryRules.stylesFor(aiPlace));
            }
            var dailyRequest=new ItineraryRequest(request.destination(),request.startDate(),request.days(),request.budget(),List.copyOf(remaining),request.extraRequest(),details);
            var planned = ItineraryScheduler.prepareBestEffort(new AiItinerary("초안", List.of(new AiDay(n, List.of(),
                    recommendations.values().stream().limit(32).toList()))), dailyRequest).days().get(0);
            List<PlaceDraft> output = new ArrayList<>();
            Set<String> covered = new HashSet<>();
            for (AiPlace p : planned.places()) {
                PlaceDraft found = known.get(p.searchQuery());
                if (found == null) continue;
                output.add(new PlaceDraft(found.placeId(), found.name(), found.address(), found.latitude(), found.longitude(), p.visitTime(),
                        found.reason(), found.note(), found.category(), true, found.placeUrl(), found.phone(),null,found.categoryPath()));
                usedIds.add(found.placeId()); usedNames.add(ItineraryRules.nameKey(found.name(), request.destination()));
                covered.addAll(ItineraryRules.stylesFor(p));
            }
            if (output.isEmpty()) warnings.add(n + "일차: 추가 방문을 배치할 시간이 부족하거나 확인된 후보를 찾지 못해 고정 일정만 남겼습니다.");
            int evening = TravelSchedule.eveningStart(n, request.days(), details, events);
            if (evening >= 0 && output.stream().noneMatch(p -> TravelSchedule.minute(p.visitTime()) >= evening))
                warnings.add("첫날: 고정 시간과 확인된 후보의 제약으로 20시까지 이어지는 저녁 일정을 충분히 채우지 못했습니다.");
            final int day = n;
            events.stream().filter(e -> e.day() == day).map(TravelSchedule.Event::place).forEach(output::add);
            output.sort(Comparator.comparing(PlaceDraft::visitTime));
            days.add(new DayDraft(n, input.startDate() == null ? null : input.startDate().plusDays(n - 1L), output));
        }
        if (usedIds.isEmpty() && events.stream().noneMatch(e -> e.place().verified())) throw unavailable();
        warnings.add("이동·방문 시간은 예상치이며 실제 영업시간과 교통 상황을 확인해 주세요.");
        String title = candidate != null && candidate.title() != null && !candidate.title().isBlank() ? candidate.title().trim() : request.destination() + " " + request.days() + "일 여행";
        if (title.length() > 100) title = title.substring(0, 100);
        var draft = new TripDraft(title, request.destination(), input.startDate(), input.startDate() == null ? null : input.startDate().plusDays(request.days() - 1L),
                input.budget(), request.styles(), days, aiEnabled && candidate != null ? "AI" : "SAMPLE", warnings, input.request(), details);
        draft = TripRouteOptimizer.optimize(draft);
        TravelSchedule.validateDraft(draft);
        return draft;
    }
    private static Area center(TravelDetails details, int day, Area fallback) {
        if (details.stays() != null) for (Stay stay : details.stays()) if (stay.checkInDay() <= day && stay.checkOutDay() >= day
                && stay.latitude() != null && stay.longitude() != null) return new Area(stay.latitude(), stay.longitude(), stay.name());
        return fallback;
    }
    private static AiPlace theme(String style, String destination) {
        String category = switch (style) {
            case "맛집" -> "식당"; case "쇼핑" -> "쇼핑"; case "자연", "힐링" -> "자연";
            case "문화" -> "문화"; case "카페" -> "카페"; case "액티비티" -> "체험"; default -> "관광";
        };
        String term = switch (style) {
            case "맛집" -> "맛집"; case "자연", "힐링" -> "공원"; case "문화" -> "박물관";
            case "액티비티" -> "체험"; case "관광" -> "관광명소"; default -> style;
        };
        return new AiPlace(destination + " " + term, destination + " " + term, style.equals("야경") ? "19:00" : "10:00", category,
                "카카오 검색 대체 후보", "", ItineraryRules.STYLES.contains(style) ? List.of(style) : List.of());
    }
    private static boolean excluded(String category, String extra) {
        if (extra == null || category == null) return false;
        String term = category.equals("식당") ? "(식당|맛집|식사)" : java.util.regex.Pattern.quote(category);
        return extra.matches("(?s).*" + term + "\\s*(은|는|을|를)?\\s*(제외|빼|말고|없이|안\\s*가).*" );
    }
    private static boolean valid(PlaceHit h, AiPlace p, String destination) {
        if (h == null || h.placeId() == null || h.placeId().isBlank() || h.name() == null || h.name().isBlank() || h.name().length() > 200
                || !Double.isFinite(h.lat()) || !Double.isFinite(h.lng()) || Math.abs(h.lat()) > 90 || Math.abs(h.lng()) > 180) return false;
        if (!com.tripplanner.place.PlaceCategories.matches(p.category(), h)) return false;
        return com.tripplanner.place.DestinationRegions.contains(destination, h.address());
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACES_UNAVAILABLE", "카카오맵에서 확인된 장소를 찾지 못했습니다. 지도 검색 상태와 목적지를 확인해 주세요.");
    }
}
