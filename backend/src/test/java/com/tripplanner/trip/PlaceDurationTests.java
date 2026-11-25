package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaceDurationTests {
    @Test void durationSurvivesJsonTimeChangesAndHoursReceipts() throws Exception {
        var mapper = new ObjectMapper();
        var place = place("카페", "10:00").withDurationMinutes(45);
        var decoded = mapper.readValue(mapper.writeValueAsString(place), PlaceDraft.class);
        assertEquals(45, decoded.stayMinutes());
        assertEquals(45, TripRouteOptimizer.at(decoded, "11:00").withBusinessHours(null).stayMinutes());
        assertEquals(60, place("카페", "10:00").stayMinutes());
        assertEquals(45, place("식당", "10:00").withDurationMinutes(45).stayMinutes());
        assertEquals(45, place("문화", "10:00").withDurationMinutes(45).stayMinutes(), "Stored durations from older drafts remain valid");
        assertEquals(60, place("식당", "10:00").stayMinutes());
    }
    @Test void variedProfileShortensOnlyRestaurantsAndCafes() {
        var places = List.of(place("식당", "08:30"), place("카페", "10:00"), place("관광", "12:00"),
                place("쇼핑", "14:00"), place("체험", "16:00"), place("문화", "18:00"));
        var original = new TripDraft("여행", "부산", null, null, null, List.of(), List.of(new DayDraft(1, null, places)), "AI", List.of(), null);
        var varied = DraftVisitPlanner.durations(original, 2);
        assertEquals(List.of(45, 45, 60, 60, 60, 60), varied.days().get(0).places().stream().map(PlaceDraft::stayMinutes).toList());
        for (int profile : List.of(0, 1)) {
            var other = DraftVisitPlanner.durations(varied, profile);
            assertTrue(other.days().get(0).places().stream().allMatch(p -> p.stayMinutes() == 60));
        }
    }
    @Test void fortyFiveMinuteVisitCanPrecedeNextVisitBySeventyFiveMinutes() {
        var first = place("카페", "10:00");
        var second = place("문화", "11:15");
        assertThrows(com.tripplanner.common.ApiException.class, () -> TravelSchedule.validateDraft(draft(first, second)));
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(first.withDurationMinutes(45), second)));
    }
    @Test void fortyFiveMinuteCafeFitsBeforeCheckoutWithThirtyMinutesForMovement() {
        var stay = new Stay("숙소", "부산", 1, 2, "15:00", "11:00", LuggagePlan.CARRY, LuggagePlan.CARRY);
        var details = new TravelDetails(MovementMode.CAR, List.of(stay), null);
        var events = TravelSchedule.events(details, 2);
        assertTrue(TravelSchedule.allowed(2, 2, 9 * 60 + 45, details, events, 45));
        assertFalse(TravelSchedule.allowed(2, 2, 9 * 60 + 45, details, events, 60));
    }

    @Test void persistedPlaceResponseRetainsTheDuration() {
        var entity = new ItineraryPlace("1", "카페", "부산", 35.16, 129.16, "10:00", "", "", "카페", true, null, null);
        entity.setDurationMinutes(45);
        assertEquals(45, TripMapper.place(entity).durationMinutes());
    }
    private static PlaceDraft place(String category, String time) {
        return new PlaceDraft(category, category, "부산", 35.16, 129.16, time, "", "", category, true, null, null);
    }
    private static TripDraft draft(PlaceDraft first, PlaceDraft second) {
        return new TripDraft("여행", "부산", null, null, null, List.of(), List.of(new DayDraft(1, null, List.of(first, second))),
                "AI", List.of(), null, new TravelDetails(MovementMode.CAR, List.of(), null));
    }
}
