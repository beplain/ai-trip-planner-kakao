package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.DayDraft;
import com.tripplanner.trip.TripDtos.PlaceDraft;
import com.tripplanner.trip.TripDtos.TripDraft;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DraftMealPlannerTests {
    @Test
    void schedulesEveryFeasibleMealAndDoesNotRepeatMenus() {
        var draft = draft(null);
        var planned = DraftMealPlanner.plan(draft, Map.of(), restaurants(), (day, place) -> true, 0);

        DraftMealPlanner.require(planned);
        for (var day : planned.days()) {
            assertEquals(List.of("08:30", "12:00", "18:00"), day.places().stream()
                    .filter(p -> p.category().equals("식당")).map(PlaceDraft::visitTime).toList());
        }
        assertEquals(6, planned.days().stream().flatMap(d -> d.places().stream())
                .filter(p -> p.category().equals("식당")).map(DraftMealPlanner::dish).distinct().count());
    }

    @Test
    void warmsOnlySmallCandidateBatchesAndSkipsAClosedFirstChoice() {
        var batches = new java.util.ArrayList<List<PlaceDraft>>();
        var planned = DraftMealPlanner.plan(draft(null), Map.of(), restaurants(),
                (day, place) -> !place.placeId().equals("1"), batches::add, 0);

        DraftMealPlanner.require(planned);
        assertEquals(List.of("1", "2", "3"), batches.get(0).stream().map(PlaceDraft::placeId).toList());
        assertTrue(batches.stream().allMatch(batch -> batch.size() <= 3));
        assertEquals("2", planned.days().get(0).places().get(0).placeId());
    }

    @Test
    void skipsBreakfastAfterRequestedStartAndRejectsClosedTimes() {
        var planned = DraftMealPlanner.plan(draft("모든 일정을 10시 이후로 생성해줘"), Map.of(),
                restaurants(), (day, place) -> !place.category().equals("식당") || !place.visitTime().equals("12:00"), 0);

        DraftMealPlanner.require(planned);
        assertTrue(planned.days().stream().allMatch(d -> d.places().stream().filter(p -> p.category().equals("식당")).count() == 2));
        assertTrue(planned.days().stream().flatMap(d -> d.places().stream())
                .noneMatch(p -> p.visitTime().compareTo("10:00") < 0 || p.category().equals("식당") && p.visitTime().equals("12:00")));
    }

    @Test
    void borrowsUnusedActivitiesFromOtherDaysToSeparateMeals() {
        var original = draft(null);
        var sparse = DraftVariants.withDays(original, List.of(new DayDraft(1, original.startDate(), List.of()),
                original.days().get(1)), List.of(), null, null);
        var sharedCandidates = Map.of(2, List.of(place("e", "정원", "10:15", "자연"),
                place("f", "전시관", "14:30", "문화")));

        var planned = DraftMealPlanner.plan(sparse, sharedCandidates, restaurants(), (day, place) -> true, 0);

        DraftMealPlanner.require(planned);
        assertTrue(planned.days().stream().allMatch(day -> day.places().size() == 5));
        assertEquals(10, planned.days().stream().flatMap(day -> day.places().stream())
                .map(PlaceDraft::placeId).distinct().count());
    }

    @Test
    void honorsExplicitMealStart() {
        var planned = DraftMealPlanner.plan(draft("저녁은 20시에 배치해줘"), Map.of(), restaurants(), (day, place) -> true, 0);
        DraftMealPlanner.require(planned);
        var dinner = planned.days().get(0).places().stream().filter(p -> p.category().equals("식당"))
                .reduce((first, last) -> last).orElseThrow();
        assertEquals("20:00", dinner.visitTime());
        assertFalse(ExtraRequestRules.mealTimeAllowed(dinner, "19:00", planned.effectiveRequest(), 1, 2));
    }

    @Test
    void skipsOnlyFirstDayBreakfastForLateArrival() {
        var base = draft(null);
        var details = new TripDtos.TravelDetails(TripDtos.MovementMode.CAR, List.of(),
                new TripDtos.Transport(TripDtos.TransportMode.KTX, "부산역", "09:10", "부산역", "21:30"));
        var draft = new TripDraft(base.title(), base.destination(), base.startDate(), base.endDate(), null, List.of(),
                base.days(), "sample", List.of(), null, details);
        var planned = DraftMealPlanner.plan(draft, Map.of(), restaurants(), (day, place) -> true, 0);
        DraftMealPlanner.require(planned);
        assertEquals(2, planned.days().get(0).places().stream().filter(p -> p.category().equals("식당")).count());
        assertEquals(3, planned.days().get(1).places().stream().filter(p -> p.category().equals("식당")).count());
    }

    @Test
    void checkoutSeparatesMealsWhenThereIsNoRoomForAnExtraVisit() {
        var date = LocalDate.of(2026, 11, 23);
        var details = new TripDtos.TravelDetails(TripDtos.MovementMode.CAR,
                List.of(new TripDtos.Stay("숙소", "경주시", 1, 2, "15:00", "10:30",
                        TripDtos.LuggagePlan.CARRY, TripDtos.LuggagePlan.CARRY)), null);
        var base = new TripDraft("경주", "경주시", date, date.plusDays(1), null, List.of(),
                TravelScheduleTests.fixedDays(details, 2), "sample", List.of(),
                "점심은 11:35에 배치해줘", details);
        var candidates = Map.of(1, List.of(place("park", "공원", "10:00", "관광")),
                2, List.of(place("museum", "박물관", "14:45", "문화")));
        var plan = DraftMealPlanner.plan(base, candidates, restaurants(), (day, place) -> true, 0);

        DraftMealPlanner.require(plan);
        TravelSchedule.validateDraft(plan);
        var lastDay = plan.days().get(1).places();
        assertEquals(List.of("식당", "고정 일정", "식당", "문화", "식당"),
                lastDay.stream().map(PlaceDraft::category).toList());
        assertEquals("11:35", lastDay.get(2).visitTime());
        assertFalse(DraftMealPlanner.validOrder(lastDay.stream().filter(p -> p.fixedEventId() == null).toList()));
    }

    @Test
    void checksTheTwoForbiddenPatternsAndMealDuration() {
        var meal = place("1", "삼겹살 전문점", "08:30", "식당");
        var lunch = place("2", "설렁탕", "12:00", "식당");
        assertFalse(DraftMealPlanner.validOrder(List.of(meal, lunch)));
        assertFalse(DraftMealPlanner.validOrder(List.of(meal, place("3", "카페", "10:15", "카페"), lunch)));
        assertTrue(DraftMealPlanner.validOrder(List.of(meal, place("3", "공방", "10:15", "체험"), lunch)));
        assertTrue(DraftMealPlanner.validOrder(List.of(meal, place("3", "공원", "10:15", "관광"), lunch)));
        assertTrue(DraftMealPlanner.validOrder(List.of(meal, place("3", "카페", "09:40", "카페"),
                place("4", "카페2", "10:50", "카페"), lunch)));
        assertNotEquals(DraftMealPlanner.dish(meal), DraftMealPlanner.dish(place("5", "닭갈비", "18:00", "식당")));

        var draft = draft(null);
        var planned = DraftMealPlanner.plan(draft, Map.of(), restaurants(), (day, place) -> true, 0);
        var visits = new java.util.ArrayList<>(planned.days().get(0).places());
        visits.add(place("too-close", "공방", "09:00", "체험"));
        var invalid = DraftVariants.withDays(planned, List.of(new DayDraft(1, draft.startDate(), visits),
                planned.days().get(1)), List.of(), null, null);
        assertThrows(ApiException.class, () -> DraftMealPlanner.require(invalid));
    }

    private static TripDraft draft(String request) {
        var date = LocalDate.of(2026, 11, 23);
        return new TripDraft("경주", "경주시", date, date.plusDays(1), null, List.of(), List.of(
                new DayDraft(1, date, List.of(place("a", "공원", "10:15", "관광"), place("b", "미술관", "14:30", "문화"))),
                new DayDraft(2, date.plusDays(1), List.of(place("c", "박물관", "10:15", "문화"), place("d", "공방", "14:30", "체험")))),
                "sample", List.of(), request);
    }

    private static List<PlaceDraft> restaurants() {
        return List.of(place("1", "삼겹살", "12:00", "식당"), place("2", "다른 삼겹살집", "12:00", "식당"),
                place("3", "설렁탕", "12:00", "식당"), place("4", "닭갈비", "12:00", "식당"),
                place("5", "칼국수", "12:00", "식당"), place("6", "비빔밥", "12:00", "식당"),
                place("7", "돼지국밥", "12:00", "식당"));
    }

    private static PlaceDraft place(String id, String name, String time, String category) {
        return new PlaceDraft(id, name, "경주시", 35.8, 129.2, time, null, null, category, true, null, null);
    }
}
