package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftVisitPlannerTests {
    @Test void minimalRelocatesCafeByRepackingAnotherDaysTwoVisits() {
        var details=new TravelDetails(MovementMode.CAR,List.of(),new Transport(TransportMode.KTX,"부산역","09:00","부산역","20:00"));
        var first=new ArrayList<>(List.of(place("tour1","관광1","관광","10:00"),meal("밀면","점심","12:00"),place("tour2","관광2","관광","14:00"),meal("삼겹살","저녁","18:00")));
        var second=new ArrayList<>(List.of(meal("김밥","아침","08:30"),place("experience","체험관","체험","10:15"),meal("칼국수","점심","12:00"),place("cafe","카페","카페","13:30"),place("shop","상점","쇼핑","15:00"),meal("비빔밥","저녁","18:00")));
        for(var e:TravelSchedule.events(details,2))(e.day()==1?first:second).add(e.place());
        first.sort(Comparator.comparing(PlaceDraft::visitTime));second.sort(Comparator.comparing(PlaceDraft::visitTime));
        var base=new TripDraft("여행","부산",null,null,null,List.of("맛집","쇼핑","체험","카페"),List.of(new DayDraft(1,null,first),new DayDraft(2,null,second)),"AI",List.of(),"다이소는 제외해줘",details);
        var result=DraftVisitPlanner.fit(base,Map.of(),1,(day,p)->!"카페".equals(p.category())||(TravelSchedule.minute(p.visitTime())>=13*60&&TravelSchedule.minute(p.visitTime())<=17*60),(a,b)->20);
        DraftVisitPlanner.require(result,1);DraftMealPlanner.require(result);DraftOptionComposer.requireCoverage(result);TravelSchedule.validateDraft(result);
        assertTrue(result.days().get(0).places().stream().anyMatch(p->"cafe".equals(p.placeId())));
        assertFalse(result.days().get(1).places().stream().anyMatch(p->"cafe".equals(p.placeId())));
    }
    @Test void fixedVisitCountUsesExactCountInErrorMessage() {
        var draft=new TripDraft("여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(
            place("a","공원","자연","10:00"),place("b","전시관","문화","13:00"),place("c","상점","쇼핑","16:00")))),"AI",List.of());
        var error=assertThrows(com.tripplanner.common.ApiException.class,()->DraftVisitPlanner.require(draft,1));
        assertTrue(error.getMessage().contains("하루 정확히 2곳"));
        assertFalse(error.getMessage().contains("2~2"));
    }
    @Test void variedRepacksVenueMixWhenInsertionAloneCannotFitBeforeReturnTrain() {
        var details = new TravelDetails(MovementMode.CAR, List.of(),
                new Transport(TransportMode.KTX, "부산역", "10:26", "부산역", "19:54"));
        var visits = new ArrayList<>(List.of(meal("밀면", "점심", "11:11"),
                place("n1", "공원", "자연", "12:26"), place("n2", "해변", "자연", "14:00"),
                place("c1", "카페1", "카페", "15:30"), meal("삼겹살", "저녁", "17:01")));
        TravelSchedule.events(details, 1).forEach(e -> visits.add(e.place()));
        visits.sort(Comparator.comparing(PlaceDraft::visitTime));
        var base = new TripDraft("여행", "부산", null, null, null, List.of("자연", "카페"),
                List.of(new DayDraft(1, null, visits)), "AI", List.of(), null, details);
        var pool = Map.of(1, List.of(place("c2", "카페2", "카페", "14:00"), place("tour", "전시관", "문화", "14:00")));
        var result = DraftVisitPlanner.fit(base, pool, 2, (d,p) -> true, (a,b) -> 20);
        DraftVisitPlanner.require(result, 2);
        DraftMealPlanner.require(result);
        TravelSchedule.validateDraft(result);
        assertTrue(DraftMealPlanner.validOrder(result.days().get(0).places()));
        assertEquals(3, result.days().get(0).places().stream().filter(p -> p.fixedEventId()!=null).count());
        assertTrue(result.days().get(0).places().stream().filter(p -> p.fixedEventId()==null)
                .allMatch(p -> p.stayMinutes() == (Set.of("카페","식당").contains(p.category()) ? 45 : 60)));
        var closed = DraftVisitPlanner.fit(base, pool, 2, (d,p) -> !"c2".equals(p.placeId()), (a,b) -> 20);
        assertThrows(RuntimeException.class, () -> DraftVisitPlanner.require(closed, 2));
    }
    @Test void profilesCountOptionalPlacesSeparatelyFromRestaurants() {
        List<PlaceDraft> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) candidates.add(place("visit" + i, "관광" + i, "관광", "10:00"));
        var meal = place("meal", "식당", "식당", "12:00");
        List<PlaceDraft> original = new ArrayList<>(List.of(candidates.get(0), meal,
                TripRouteOptimizer.at(candidates.get(1), "14:00")));
        var base = new TripDraft("여행", "부산", null, null, null, List.of(), List.of(new DayDraft(1, null, original)),
                "AI", List.of(), null, new TravelDetails(MovementMode.CAR, List.of(), null));
        List<TripDraft> results = new ArrayList<>();
        for (int profile = 0; profile < 3; profile++) {
            var result = DraftVisitPlanner.fit(base, Map.of(1, candidates), profile, (d, p) -> true, (a, b) -> 0);
            DraftVisitPlanner.require(result, profile);
            assertTrue(result.days().get(0).places().stream().anyMatch(place -> place.placeId().equals(meal.placeId())));
            results.add(result);
        }
        assertEquals(List.of(3L, 2L, 5L), results.stream().map(d -> d.days().get(0).places().stream().filter(DraftVisitPlanner::counted).count()).toList());
        assertEquals(3, results.stream().map(DraftVisitPlanner::fingerprint).distinct().count());
    }
    @Test void changingRestaurantOrReversingVisitsDoesNotCreateAnotherVenueSet() {
        var a = place("a", "카페", "카페", "10:00");
        var b = place("b", "공원", "자연", "14:00");
        var original = new TripDraft("여행", "부산", null, null, null, List.of(),
                List.of(new DayDraft(1, null, List.of(a, place("meal1", "식당1", "식당", "12:00"), b))), "AI", List.of(), null);
        var changed = DraftVariants.withDays(original, List.of(new DayDraft(1, null, List.of(b,
                place("meal2", "식당2", "식당", "12:00"), a))), List.of(), null, null);
        assertEquals(DraftVisitPlanner.fingerprint(original), DraftVisitPlanner.fingerprint(changed));
    }
    @Test void fillsAfternoonGapByDelayingOneVisitFiveMinutes() {
        var culture = place("culture", "박물관", "문화", "14:55");
        var lunch = place("lunch", "식당", "식당", "12:00");
        var dinner = place("dinner", "식당", "식당", "17:50");
        var base = day(lunch, culture, dinner);
        var added = place("park", "공원", "자연", "13:30");
        var result = DraftVisitPlanner.insert(base, base.days().get(0), added, (d,p) -> true, (a,b) -> 20);
        assertNotNull(result);
        var visits = result.days().get(0).places();
        assertEquals("15:00", visits.stream().filter(p -> p.placeId().equals("culture")).findFirst().orElseThrow().visitTime());
        assertTrue(visits.contains(lunch));
        assertTrue(visits.contains(dinner));
        DraftVisitPlanner.require(result, 1);
        TravelSchedule.validateDraft(result);
    }

    @Test void rejectsDelayWhenChangedVenueIsClosedOrRouteDoesNotFit() {
        var base = day(place("lunch", "식당", "식당", "12:00"), place("culture", "박물관", "문화", "14:55"),
                place("dinner", "식당", "식당", "17:50"));
        var added = place("park", "공원", "자연", "13:30");
        assertNull(DraftVisitPlanner.insert(base, base.days().get(0), added,
                (d,p) -> !p.placeId().equals("culture"), (a,b) -> 20));
        assertNull(DraftVisitPlanner.insert(base, base.days().get(0), added, (d,p) -> true, (a,b) -> 40));
    }

    @Test void doesNotMoveRestaurantsOrAllowAnUnboundedVisitDelay() {
        var added = place("park", "공원", "자연", "13:30");
        var meal = day(place("meal", "식당", "식당", "14:55"));
        assertNull(DraftVisitPlanner.insert(meal, meal.days().get(0), added, (d,p) -> true, (a,b) -> 0));
        var tooEarly = day(place("culture", "박물관", "문화", "14:00"));
        assertNull(DraftVisitPlanner.insert(tooEarly, tooEarly.days().get(0), added, (d,p) -> true, (a,b) -> 0));
    }

    @Test void insertsAfternoonVisitByDelayingDinnerWithinItsWindow() {
        var breakfast = meal("김밥", "아침", "08:00");
        var lunch = meal("밀면", "점심", "11:30");
        var dinner = meal("삼겹살", "저녁", "17:00");
        var base = day(breakfast, place("morning", "공원", "자연", "09:30"), lunch,
                place("culture", "박물관", "문화", "13:15"), place("nature", "해변", "자연", "14:45"), dinner);
        var candidate = place("cafe", "카페", "카페", "16:15");
        var result = DraftVisitPlanner.insert(base, base.days().get(0), candidate, (d,p) -> true, (a,b) -> 20);
        assertNotNull(result);
        assertEquals("17:45", result.days().get(0).places().stream().filter(p -> DraftMealPlanner.isMeal(p,"저녁"))
                .findFirst().orElseThrow().visitTime());
        DraftMealPlanner.require(result);
        assertNull(DraftVisitPlanner.insert(base, base.days().get(0), candidate,
                (d,p) -> !DraftMealPlanner.isMeal(p,"저녁") || p.visitTime().compareTo("17:30") <= 0, (a,b) -> 20));
    }

    private static PlaceDraft meal(String dish, String label, String time) {
        return new PlaceDraft(dish, dish, "부산", 35.16, 129.16, time, "식사 일정 · " + label + " · " + dish,
                "", "식당", true, null, null);
    }

    @Test void unknownRouteFallbackIncludesOnlyOneArrivalBuffer() {
        var details = new TravelDetails(MovementMode.CAR, List.of(), null);
        assertEquals(TravelSchedule.gap(details), TripGenerationService.visitFallbackTravelMinutes(details) + 10);
    }

    @Test void restoresMissingActivityAfterFinalSchedulingWithoutExceedingProfileCount() {
        var base = withStyles(day(place("cafe1", "카페1", "카페", "10:00"), place("cafe2", "카페2", "카페", "14:00")),
                List.of("카페", "체험"));
        var activity = place("activity", "체험관", "체험", "14:00");
        var result = DraftVisitPlanner.fit(base, Map.of(1, List.of(activity)), 1, (d,p) -> true, (a,b) -> 20);
        DraftVisitPlanner.require(result, 1);
        DraftOptionComposer.requireCoverage(result);
        assertEquals(2, result.days().get(0).places().size());
        assertEquals(1, result.days().get(0).places().stream().filter(p -> "체험".equals(p.category())).count());
    }

    @Test void coverageRepairKeepsEachProfilesExistingVisitCountAndDurationRules() {
        for (int profile = 0; profile < 3; profile++) {
            int count = new int[]{3,2,5}[profile];
            var visits = new ArrayList<PlaceDraft>();
            for (int i = 0; i < count; i++) visits.add(place("cafe"+i,"카페"+i,"카페",TravelSchedule.clock(8*60+i*105)));
            var base = DraftVisitPlanner.durations(withStyles(day(visits.toArray(PlaceDraft[]::new)), List.of("카페","체험")), profile);
            var activity = place("activity","체험관","체험","14:00");
            var result = DraftVisitPlanner.restoreCoverage(base, Map.of(1,List.of(activity)), profile, (d,p)->true, (a,b)->20);
            DraftVisitPlanner.require(result,profile);
            DraftOptionComposer.requireCoverage(result);
            assertEquals(count,result.days().get(0).places().size());
            assertEquals(60,result.days().get(0).places().stream().filter(p->"체험".equals(p.category())).findFirst().orElseThrow().stayMinutes());
        }
    }

    @Test void triesAnotherActivityWhenClosestCandidateIsClosed() {
        var base = withStyles(day(place("cafe1", "카페1", "카페", "10:00"), place("cafe2", "카페2", "카페", "14:00")),
                List.of("카페", "체험"));
        var closed = place("closed", "휴무 체험관", "체험", "14:00");
        var open = place("open", "다른 체험관", "체험", "14:00");
        var result = DraftVisitPlanner.restoreCoverage(base, Map.of(1, List.of(closed, open)), 1,
                (d,p) -> !p.placeId().equals("closed"), (a,b) -> 20);
        DraftOptionComposer.requireCoverage(result);
        assertTrue(result.days().get(0).places().stream().anyMatch(p -> p.placeId().equals("open")));
        var unreachable = DraftVisitPlanner.restoreCoverage(base, Map.of(1, List.of(open)), 1, (d,p) -> true, (a,b) -> 300);
        assertThrows(IllegalArgumentException.class, () -> DraftOptionComposer.requireCoverage(unreachable));
    }

    @Test void restoringOneStyleCannotDropAnotherOrRelabelAnUnrelatedPlace() {
        var base = withStyles(day(place("nature", "공원", "자연", "10:00"), place("culture", "박물관", "문화", "14:00")),
                List.of("자연", "문화", "체험"));
        var result = DraftVisitPlanner.restoreCoverage(base, Map.of(1, List.of(place("activity", "체험관", "체험", "14:00"))),
                1, (d,p) -> true, (a,b) -> 20);
        assertEquals(base, result);
        assertThrows(IllegalArgumentException.class, () -> DraftOptionComposer.requireCoverage(result));
    }

    @Test void repairsCafeRunsWithRealNonCafeCandidatesInEveryProfile() {
        var base = withStyles(day(place("c1","카페1","카페","08:30"), place("c2","카페2","카페","11:00"),
                place("c3","카페3","카페","14:00")), List.of("카페"));
        var pool = List.of(place("a","공원","자연","14:00"), place("b","박물관","문화","14:00"),
                place("c","체험관","체험","14:00"), place("d","시장","쇼핑","14:00"), place("e","전시관","문화","14:00"));
        for (int profile=0; profile<3; profile++) {
            var result = DraftVisitPlanner.fit(base,Map.of(1,pool),profile,(d,p)->true,(a,b)->20);
            DraftVisitPlanner.require(result,profile);
            DraftOptionComposer.requireCoverage(result);
            assertTrue(DraftMealPlanner.validOrder(result.days().get(0).places()));
            assertTrue(result.days().get(0).places().stream().anyMatch(p->!"카페".equals(p.category())));
        }
    }

    @Test void replacesCafeAcrossRestaurantWithoutRemovingTheRestaurant() {
        var meal = place("meal","식당","식당","12:00");
        var base = day(place("c1","카페1","카페","10:00"),meal,place("c2","카페2","카페","14:00"));
        var result = DraftVisitPlanner.fit(base,Map.of(1,List.of(place("tour","박물관","문화","14:00"))),1,
                (d,p)->true,(a,b)->20);
        DraftVisitPlanner.require(result,1);
        assertTrue(DraftMealPlanner.validOrder(result.days().get(0).places()));
        assertTrue(result.days().get(0).places().contains(meal));
    }

    @Test void fillsMissingVisitWithNonCafeBeforeCloserCafe() {
        var base = day(place("original","박물관","문화","10:00"));
        var nearbyCafe = place("cafe","가까운 카페","카페","14:00");
        var activity = new PlaceDraft("activity","체험관","부산",35.161,129.161,"14:00","","","체험",true,null,null);
        var result = DraftVisitPlanner.fit(base,Map.of(1,List.of(nearbyCafe,activity)),1,(d,p)->true,(a,b)->20);
        assertTrue(result.days().get(0).places().stream().anyMatch(p->p.placeId().equals("activity")));
        assertFalse(result.days().get(0).places().stream().anyMatch(p->p.placeId().equals("cafe")));
    }

    private static TripDraft withStyles(TripDraft draft, List<String> styles) {
        return new TripDraft(draft.title(), draft.destination(), draft.startDate(), draft.endDate(), draft.budget(), styles,
                draft.days(), draft.source(), draft.warnings(), draft.request(), draft.travelDetails());
    }

    private static TripDraft day(PlaceDraft... places) {
        return new TripDraft("여행", "부산", null, null, null, List.of(),
                List.of(new DayDraft(1, null, List.of(places))), "AI", List.of(), null,
                new TravelDetails(MovementMode.CAR, List.of(), null));
    }

    private static PlaceDraft place(String id, String name, String category, String time) {
        return new PlaceDraft(id, name, "부산", 35.16, 129.16, time, "", "", category, true, null, null);
    }
}
