package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.place.Geo;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.place.PlaceCategories;
import com.tripplanner.trip.TripDtos.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class DraftMealPlanner {
    private static final String PREFIX = "식사 일정 · ";
    private static final int STAY = 60;
    private static final int MEAL_LOOKUP_BATCH = 3;
    private static final List<String> MENUS = List.of(
            "삼겹살", "돼지국밥", "밀면", "비빔밥", "순두부", "설렁탕", "돈까스", "칼국수",
            "순대국밥", "곰탕", "샌드위치", "콩나물국밥", "해장국", "김밥", "갈비찜", "초밥",
            "냉면", "쌀국수", "햄버거", "백반", "추어탕", "삼계탕", "갈비탕", "장어구이",
            "닭갈비", "닭볶음탕", "갈치조림", "족발", "보쌈", "떡볶이", "파스타", "피자",
            "오겹살", "감자탕", "불고기", "막국수", "만두", "수제비", "짜장면", "짬뽕",
            "라멘", "오므라이스", "스테이크", "치킨", "곱창", "대창", "오리구이", "복국");
    private static final List<String> MATCH_ORDER = MENUS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    private static final Pattern FISH = Pattern.compile("(?:^|[\\s>])회(?:$|[\\s>])|횟집|회센터");
    private record Meal(String label, int start, int end) {}
    private static final List<Meal> DEFAULTS = List.of(
            new Meal("아침", 480, 540), new Meal("점심", 660, 780), new Meal("저녁", 1020, 1140));

    static List<PlaceDraft> search(String destination, int days, KakaoLocalClient lookups, ExecutorService executor) {
        var area = lookups.resolveArea(destination).orElse(null);
        int searchCount = Math.min(MENUS.size(), 12 + days * 5);
        List<Callable<List<PlaceDraft>>> searches = MENUS.stream().limit(searchCount).<Callable<List<PlaceDraft>>>map(menu -> () ->
                lookups.search(destination + " " + menu, area, 8).stream()
                    .filter(hit -> hit.placeId() != null && PlaceCategories.matches("식당", hit)
                            && Double.isFinite(hit.lat()) && Double.isFinite(hit.lng()))
                    .map(hit -> new PlaceDraft(hit.placeId(), hit.name(), hit.address(), hit.lat(), hit.lng(),
                            "12:00", null, null, "식당", true, hit.placeUrl(), hit.phone(), null, hit.categoryName()))
                    .filter(place -> menu.equals(dish(place))).toList()).toList();
        Map<String, PlaceDraft> restaurants = new LinkedHashMap<>();
        try {
            for (var search : executor.invokeAll(searches, 8, TimeUnit.SECONDS)) {
                if (search.isCancelled()) continue;
                try { search.get().forEach(p -> restaurants.putIfAbsent(p.placeId(), p)); }
                catch (ExecutionException ignored) { /* Other menu searches can still provide candidates. */ }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badRequest("식당 후보 조회가 중단됐습니다. 다시 시도해 주세요.");
        }
        return List.copyOf(restaurants.values());
    }

    static TripDraft plan(TripDraft draft, Map<Integer, List<PlaceDraft>> alternatives, List<PlaceDraft> restaurants,
                          BiPredicate<DayDraft, PlaceDraft> open, int variant) {
        return plan(draft, alternatives, restaurants, open, candidates -> {}, variant);
    }

    static TripDraft plan(TripDraft draft, Map<Integer, List<PlaceDraft>> alternatives, List<PlaceDraft> restaurants,
                          BiPredicate<DayDraft, PlaceDraft> open, Consumer<List<PlaceDraft>> prefetch, int variant) {
        Set<String> usedPlaces = new HashSet<>();
        Set<String> usedMenus = new HashSet<>();
        List<DayDraft> days = new ArrayList<>();
        List<ExtraRequestRules.Visit> counted = new ArrayList<>();
        String request = draft.effectiveRequest();
        var events = TravelSchedule.events(draft.travelDetails(), draft.days().size());
        int gap = TravelSchedule.gap(draft.travelDetails());
        Map<String, PlaceDraft> activities = new LinkedHashMap<>();
        draft.days().stream().flatMap(day -> day.places().stream()).filter(DraftMealPlanner::canSeparateMeals)
                .forEach(p -> activities.putIfAbsent(p.placeId(), p));
        alternatives.values().stream().flatMap(Collection::stream).filter(DraftMealPlanner::canSeparateMeals)
                .forEach(p -> activities.putIfAbsent(p.placeId(), p));
        for (DayDraft day : draft.days()) {
            Map<String, PlaceDraft> pool = new LinkedHashMap<>();
            day.places().stream().filter(p -> p.fixedEventId() == null && p.verified()).forEach(p -> pool.put(p.placeId(), p));
            alternatives.getOrDefault(day.dayNumber(), List.of()).stream().filter(PlaceDraft::verified)
                    .forEach(p -> pool.putIfAbsent(p.placeId(), p));
            List<PlaceDraft> visits = new ArrayList<>();
            List<Meal> windows = windows(draft, day);
            List<PlaceDraft> candidates = new ArrayList<>(pool.values());
            if (!restaurants.isEmpty()) {
                var rotated = new ArrayList<>(restaurants);
                Collections.rotate(rotated, -variant * 7);
                candidates.addAll(rotated);
            }
            var requests = ExtraRequestRules.meals(request, day.dayNumber(), draft.days().size());
            Map<PlaceDraft, Integer> priority = new HashMap<>();
            candidates.forEach(p -> priority.put(p, ExtraRequestRules.requiredPriority(request, p.name(), p.category())));
            PlaceDraft anchor = day.places().stream().filter(TripRouteOptimizer::known).findFirst().orElse(null);
            candidates.sort(Comparator.comparingInt((PlaceDraft p) -> priority.get(p)).reversed()
                    .thenComparingInt(p -> distanceBand(p, anchor)));
            BiPredicate<DayDraft, PlaceDraft> eligibleVisit = (d, p) -> ExtraRequestRules.allowsAdditional(
                    request, p.name(), p.category(), d.dayNumber(), draft.days().size(), counted) && open.test(d, p);
            var future = futureRequests(draft, day);
            for (int index = 0; index < windows.size(); index++) {
                Meal window = windows.get(index);
                boolean last = index == windows.size() - 1;
                var required = requests.stream().filter(m -> m.meal().equals(window.label()) || last && m.lastRestaurant()).toList();
                List<PlaceDraft> eligible = required.isEmpty() ? candidates.stream().filter(p ->
                        requests.stream().noneMatch(m -> m.exact(p.name(), p.categoryPath()))
                        && future.stream().noneMatch(m -> m.exact(p.name(), p.categoryPath()))).toList() : candidates;
                var chosen = chooseMeal(draft, day, events, window, eligible, required, visits, usedPlaces, usedMenus, eligibleVisit, prefetch, gap);
                if (chosen == null) throw ApiException.badRequest(day.dayNumber() + "일차 " + window.label()
                        + "에 배치할 수 있는 중복 없는 식당을 찾지 못했습니다. 여행 시간이나 추가 요청을 확인해 주세요.");
                visits.add(chosen);
                usedPlaces.add(chosen.placeId());
                usedMenus.add(dish(chosen));
                counted.add(new ExtraRequestRules.Visit(day.dayNumber(), chosen.name(), chosen.category()));
            }
            int limit = ExtraRequestRules.visitLimit(request, 20);
            Map<String, PlaceDraft> separators = new LinkedHashMap<>();
            pool.values().stream().filter(DraftMealPlanner::canSeparateMeals).forEach(p -> separators.put(p.placeId(), p));
            activities.forEach(separators::putIfAbsent);
            separateMeals(draft, day, events, separators.values(), visits, usedPlaces, counted, eligibleVisit, gap, limit);
            for (PlaceDraft original : day.places()) {
                if (visits.size() >= limit) break;
                if (original.fixedEventId() != null || restaurant(original) || usedPlaces.contains(original.placeId())
                        || !ExtraRequestRules.allowsAdditional(request, original.name(), original.category(),
                                day.dayNumber(), draft.days().size(), counted)) continue;
                int originalTime = minute(original);
                var nearby = new Meal("", Math.max(0, originalTime - 90), originalTime + 90);
                var time = slots(draft, day, events, nearby).stream().filter(t -> free(visits, t, gap))
                        .min(Comparator.comparingInt(t -> Math.abs(t - originalTime)));
                time.ifPresent(t -> {
                    visits.add(TripRouteOptimizer.at(original, TravelSchedule.clock(t)));
                    usedPlaces.add(original.placeId());
                    counted.add(new ExtraRequestRules.Visit(day.dayNumber(), original.name(), original.category()));
                });
            }
            visits.addAll(day.places().stream().filter(p -> p.fixedEventId() != null).toList());
            visits.sort(Comparator.comparingInt(DraftMealPlanner::minute));
            days.add(new DayDraft(day.dayNumber(), day.date(), List.copyOf(visits)));
        }
        return DraftVariants.withDays(draft, days, draft.warnings(), null, null);
    }

    private static int distanceBand(PlaceDraft place, PlaceDraft anchor) {
        if (anchor == null || !TripRouteOptimizer.known(place)) return 0;
        return (int) (Geo.distanceMeters(anchor.latitude(), anchor.longitude(), place.latitude(), place.longitude()) / 1000);
    }

    private static List<ExtraRequestRules.Meal> futureRequests(TripDraft draft, DayDraft day) {
        return draft.days().stream().filter(d -> d.dayNumber() > day.dayNumber())
                .flatMap(d -> ExtraRequestRules.meals(draft.effectiveRequest(), d.dayNumber(), draft.days().size()).stream()).toList();
    }

    private static PlaceDraft chooseMeal(TripDraft draft, DayDraft day, List<TravelSchedule.Event> events,
                                        Meal meal, List<PlaceDraft> candidates, List<ExtraRequestRules.Meal> required,
                                        List<PlaceDraft> visits, Set<String> ids, Set<String> menus,
                                        BiPredicate<DayDraft, PlaceDraft> open,
                                        Consumer<List<PlaceDraft>> prefetch, int gap) {
        int middle = (meal.start() + meal.end()) / 2;
        List<Integer> slots = slots(draft, day, events, meal).stream()
                .filter(t -> visits.stream().noneMatch(p -> {
                    int from = minute(p);
                    int minimumGap = hasFixedStop(day, from, t) ? STAY + gap : 2 * (STAY + gap);
                    return t - from < minimumGap;
                }))
                .sorted(Comparator.comparingInt(t -> Math.abs(t - middle))).toList();
        if (slots.isEmpty()) return null;
        List<PlaceDraft> eligible = candidates.stream()
                .filter(p -> restaurant(p) && !ids.contains(p.placeId()))
                .filter(p -> { String menu = dish(p); return menu != null && !menus.contains(menu); })
                .filter(p -> ExtraRequestRules.allows(draft.effectiveRequest(), p.name(), p.category()))
                .filter(p -> required.stream().allMatch(m -> m.matchesPlace(p.name(), p.category(), p.categoryPath())))
                .toList();
        for (int i = 0; i < eligible.size(); i++) {
            prefetch.accept(eligible.subList(i, Math.min(eligible.size(), i + MEAL_LOOKUP_BATCH)));
            PlaceDraft candidate = eligible.get(i);
            String menu = dish(candidate);
            for (int time : slots) {
                PlaceDraft visit = new PlaceDraft(candidate.placeId(), candidate.name(), candidate.address(), candidate.latitude(),
                        candidate.longitude(), TravelSchedule.clock(time), PREFIX + meal.label() + " · " + menu, candidate.note(),
                        candidate.category(), candidate.verified(), candidate.placeUrl(), candidate.phone(), null, candidate.categoryPath());
                if (ExtraRequestRules.mealTimeAllowed(visit, visit.visitTime(), draft.effectiveRequest(), day.dayNumber(), draft.days().size())
                        && open.test(day, visit)) return visit;
            }
        }
        return null;
    }

    private static void separateMeals(TripDraft draft, DayDraft day, List<TravelSchedule.Event> events,
                                      Collection<PlaceDraft> pool, List<PlaceDraft> visits, Set<String> used,
                                      List<ExtraRequestRules.Visit> counted, BiPredicate<DayDraft, PlaceDraft> open,
                                      int gap, int limit) {
        var meals = visits.stream().filter(DraftMealPlanner::restaurant).sorted(Comparator.comparingInt(DraftMealPlanner::minute)).toList();
        for (int i = 1; i < meals.size() && visits.size() < limit; i++) {
            PlaceDraft previous = meals.get(i - 1), next = meals.get(i);
            int from = minute(previous), to = minute(next);
            if (hasFixedStop(day, from, to)) continue;
            var range = new Meal("", from + STAY + gap, to - STAY - gap);
            var times = slots(draft, day, events, range).stream().filter(t -> free(visits, t, gap))
                    .sorted(Comparator.comparingInt(t -> Math.abs(t - (from + to) / 2))).toList();
            var replacement = pool.stream().filter(p -> !used.contains(p.placeId()))
                    .sorted(Comparator.comparingDouble(p -> distance(p, previous) + distance(p, next)))
                    .flatMap(p -> times.stream().map(t -> TripRouteOptimizer.at(p, TravelSchedule.clock(t))))
                    .filter(p -> ExtraRequestRules.allows(draft.effectiveRequest(), p.name(), p.category()))
                    .filter(p -> open.test(day, p)).findFirst();
            replacement.ifPresent(p -> {
                visits.add(p);
                used.add(p.placeId());
                counted.add(new ExtraRequestRules.Visit(day.dayNumber(), p.name(), p.category()));
            });
        }
    }

    private static boolean hasFixedStop(DayDraft day, int from, int to) {
        return day.places().stream().filter(p -> p.fixedEventId() != null)
                .anyMatch(p -> minute(p) > from && minute(p) < to);
    }

    private static boolean canSeparateMeals(PlaceDraft place) {
        return place.fixedEventId() == null && place.verified() && TripRouteOptimizer.known(place)
                && !restaurant(place) && !"카페".equals(place.category());
    }

    private static double distance(PlaceDraft a, PlaceDraft b) {
        return Geo.distanceMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    private static List<Meal> windows(TripDraft draft, DayDraft day) {
        if (!ExtraRequestRules.allows(draft.effectiveRequest(), "", "식당")) return List.of();
        var events = TravelSchedule.events(draft.travelDetails(), draft.days().size());
        var overrides = ExtraRequestRules.mealTimes(draft.effectiveRequest(), day.dayNumber(), draft.days().size());
        List<Meal> windows = new ArrayList<>();
        for (Meal meal : DEFAULTS) {
            Integer requested = overrides.get(meal.label());
            Meal window = requested == null ? meal : new Meal(meal.label(), requested, requested);
            if (!slots(draft, day, events, window).isEmpty()) windows.add(window);
            else if (requested != null) throw ApiException.badRequest(day.dayNumber() + "일차 " + meal.label()
                    + " 요청 시각에 교통편·숙소 일정과 겹치지 않는 식사 60분을 확보하지 못했습니다.");
        }
        return windows.stream().sorted(Comparator.comparingInt(Meal::start)).toList();
    }

    private static List<Integer> slots(TripDraft draft, DayDraft day, List<TravelSchedule.Event> events, Meal meal) {
        List<Integer> result = new ArrayList<>();
        int from = ExtraRequestRules.visitStart(draft.effectiveRequest(),
                Math.max(meal.start(), TravelSchedule.start(day.dayNumber(), draft.travelDetails())));
        int to = Math.min(meal.end(), ExtraRequestRules.visitEnd(draft.effectiveRequest(),
                TravelSchedule.end(day.dayNumber(), draft.days().size(), draft.travelDetails())) - STAY);
        for (int t = meal.start() == meal.end() ? from : ((from + 4) / 5) * 5; t <= to; t += 5) {
            if (TravelSchedule.allowed(day.dayNumber(), draft.days().size(), t, draft.travelDetails(), events)) result.add(t);
        }
        return result;
    }

    static boolean scheduledMeal(PlaceDraft p) {
        return restaurant(p) && p.reason() != null && p.reason().startsWith(PREFIX);
    }

    static boolean isMeal(PlaceDraft place, String label) {
        return scheduledMeal(place) && place.reason().startsWith(PREFIX + label + " · ");
    }

    static boolean withinWindow(PlaceDraft p, String time, Map<String, Integer> overrides) {
        if (!scheduledMeal(p)) return true;
        int start = TravelSchedule.minute(time);
        return DEFAULTS.stream().anyMatch(m -> isMeal(p, m.label())
                && start >= overrides.getOrDefault(m.label(), m.start())
                && start <= overrides.getOrDefault(m.label(), m.end()));
    }

    static void require(TripDraft draft) {
        Set<String> menus = new HashSet<>();
        for (DayDraft day : draft.days()) {
            var visits = day.places().stream().filter(p -> p.fixedEventId() == null)
                    .sorted(Comparator.comparingInt(DraftMealPlanner::minute)).toList();
            if (!validOrder(day.places())) throw ApiException.badRequest(day.dayNumber()
                    + "일차 식당 사이에 방문을 확보하지 못했습니다. 최종 순서: "
                    + day.places().stream().map(p -> p.visitTime() + " " + p.category())
                            .reduce((a, b) -> a + " → " + b).orElse(""));
            var meals = visits.stream().filter(DraftMealPlanner::restaurant).toList();
            var windows = windows(draft, day);
            if (meals.size() != windows.size()
                    || windows.stream().anyMatch(m -> meals.stream().filter(p ->
                            isMeal(p, m.label())
                            && minute(p) >= m.start() && minute(p) <= m.end()).count() != 1))
                throw ApiException.badRequest(day.dayNumber() + "일차에 필요한 식사 시간대를 채우지 못했습니다.");
            for (PlaceDraft meal : meals) {
                String dish = dish(meal);
                if (dish == null || !menus.add(dish)) throw ApiException.badRequest("여행 중 식사 메뉴를 서로 다르게 배치하지 못했습니다.");
                if (visits.stream().anyMatch(p -> p != meal && Math.abs(minute(p) - minute(meal)) < STAY))
                    throw ApiException.badRequest("식사 시간 1시간을 확보하지 못했습니다.");
            }
        }
    }

    static boolean validOrder(List<PlaceDraft> visits) {
        var ordered = visits.stream().sorted(Comparator.comparingInt(DraftMealPlanner::minute)).toList();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            if (!restaurant(ordered.get(i))) continue;
            if (restaurant(ordered.get(i + 1)) || i + 2 < ordered.size()
                    && "카페".equals(ordered.get(i + 1).category()) && restaurant(ordered.get(i + 2))) return false;
        }
        return true;
    }

    private static boolean free(List<PlaceDraft> visits, int time, int gap) {
        return visits.stream().noneMatch(p -> Math.abs(time - minute(p)) < STAY + gap);
    }

    private static boolean restaurant(PlaceDraft p) { return "식당".equals(p.category()); }
    private static int minute(PlaceDraft p) { return TravelSchedule.minute(p.visitTime()); }

    static String dish(PlaceDraft p) {
        String evidence = p.name() + " " + Objects.toString(p.categoryPath(), "");
        String compact = evidence.replaceAll("\\s+", "").replace("순댓국", "순대국밥")
                .replace("돼지국밥집", "돼지국밥").replace("돈가스", "돈까스").replace("자장면", "짜장면");
        return MATCH_ORDER.stream().filter(compact::contains).findFirst()
                .orElseGet(() -> FISH.matcher(evidence).find() ? "회" : null);
    }
}
