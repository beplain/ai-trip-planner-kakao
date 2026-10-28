package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TravelScheduleTests {
    static TravelDetails details() {
        return new TravelDetails(MovementMode.PUBLIC_TRANSIT,
                List.of(new Stay("해운대 숙소", "부산 해운대구", 1, 2, "15:00", "11:00", LuggagePlan.HOTEL, LuggagePlan.HOTEL)),
                new Transport(TransportMode.KTX, "부산역", "10:00", "부산역", "19:00"));
    }
    static TripDraft draft(TravelDetails details, List<DayDraft> days) {
        return new TripDraft("여행", "부산", null, null, null, List.of(), days, "AI", List.of(), null, details);
    }
    static List<DayDraft> fixedDays(TravelDetails details, int count) {
        var events = TravelSchedule.events(details, count);
        List<DayDraft> days = new ArrayList<>();
        for (int day = 1; day <= count; day++) {
            final int n = day;
            days.add(new DayDraft(day, null, events.stream().filter(e -> e.day() == n).map(TravelSchedule.Event::place).toList()));
        }
        return days;
    }
    @Test void injectsArrivalAccommodationStorageRetrievalAndBoardingInTimeOrder() {
        var details = details(); var days = fixedDays(details, 2);
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(details, days)));
        assertEquals("10:00", days.get(0).places().get(0).visitTime());
        assertTrue(days.get(0).places().stream().anyMatch(p -> p.fixedEventId().endsWith(":drop")));
        assertEquals("18:30", days.get(1).places().stream().filter(p -> p.fixedEventId().equals("transport:boarding")).findFirst().orElseThrow().visitTime());
        assertTrue(days.get(1).places().stream().anyMatch(p -> p.name().contains("맡긴 짐 찾기")));
    }
    @Test void blocksVisitsBeforeArrivalDuringCheckInAndAfterReturnBoundary() {
        var d = details(); var events = TravelSchedule.events(d, 2);
        assertFalse(TravelSchedule.allowed(1, 2, 9 * 60, d, events));
        assertFalse(TravelSchedule.allowed(1, 2, 15 * 60, d, events));
        assertFalse(TravelSchedule.allowed(2, 2, 18 * 60, d, events));
        assertTrue(TravelSchedule.allowed(1, 2, 17 * 60, d, events));
    }
    @Test void cannotDeleteOrTamperWithFixedEventWhenEditing() {
        var d = details(); var days = new ArrayList<>(fixedDays(d, 2));
        var places = new ArrayList<>(days.get(0).places()); places.remove(0);
        days.set(0, new DayDraft(1, null, places));
        assertThrows(ApiException.class, () -> TravelSchedule.validateDraft(draft(d, days)));
        var changed = new ArrayList<>(fixedDays(d, 2));
        var first = changed.get(0).places().get(0);
        var altered = new PlaceDraft(first.placeId(), first.name(), first.address(), null, null, "09:00", first.reason(), first.note(), first.category(), false, null, null, first.fixedEventId());
        var updated = new ArrayList<>(changed.get(0).places()); updated.set(0, altered);
        changed.set(0, new DayDraft(1, null, updated));
        assertThrows(ApiException.class, () -> TravelSchedule.validateDraft(draft(d, changed)));
    }
    @Test void handlesHotelChangesWithoutOverlappingNightBookings() {
        var d = new TravelDetails(MovementMode.CAR, List.of(
                new Stay("A호텔", "부산 중구", 1, 2, "15:00", "11:00", LuggagePlan.CARRY, LuggagePlan.HOTEL),
                new Stay("B호텔", "부산 해운대구", 2, 3, "15:00", "11:00", LuggagePlan.HOTEL, LuggagePlan.CARRY)), null);
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(d, fixedDays(d, 3))));
        var events = TravelSchedule.events(d, 3);
        int oldPickup = events.stream().filter(e -> e.id().equals("stay:0:pickup")).findFirst().orElseThrow().minute();
        int nextDrop = events.stream().filter(e -> e.id().equals("stay:1:drop")).findFirst().orElseThrow().minute();
        assertTrue(oldPickup < nextDrop);
        var overlapping = new TravelDetails(d.movementMode(), List.of(d.stays().get(0), new Stay("C", "부산", 1, 3, "15:00", "11:00", LuggagePlan.CARRY, LuggagePlan.CARRY)), null);
        assertThrows(ApiException.class, () -> TravelSchedule.validate(overlapping, 3));
    }
    @Test void sameDayTravelAndFlightBuffersRejectInfeasibleInputBeforeCallingAi() {
        var d = new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(), new Transport(TransportMode.PLANE, "김해공항", "10:00", "김해공항", "18:00"));
        assertEquals(11 * 60, TravelSchedule.start(1, d));
        assertEquals(16 * 60, TravelSchedule.end(1, 1, d));
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(d, fixedDays(d, 1))));
        var impossible = new TravelDetails(d.movementMode(), List.of(), new Transport(TransportMode.PLANE, "김해공항", "17:00", "김해공항", "18:00"));
        assertThrows(ApiException.class, () -> TravelSchedule.validate(impossible, 1));
        assertThrows(ApiException.class, () -> TravelSchedule.minute("25:00"));
    }
    @Test void promptIncludesUserMovementHotelsAndExplicitStorageLimitations() {
        String prompt = TravelSchedule.prompt(details(), 2);
        assertTrue(prompt.contains("대중교통")); assertTrue(prompt.contains("부산 해운대구"));
        assertTrue(prompt.contains("15:00")); assertTrue(prompt.contains("부산역"));
        assertTrue(prompt.contains("이용 가능 여부 확인"));
    }
    @Test void lockerPlansIncludeRetrievalBeforeCheckInAndAfterCheckOut() {
        var d = new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(new Stay("A", "부산", 1, 2,
                "15:00", "11:00", LuggagePlan.LOCKER, LuggagePlan.LOCKER)), details().transport());
        var events = TravelSchedule.events(d, 2);
        var retrieve = events.stream().filter(e -> e.id().equals("stay:0:before-pickup")).findFirst().orElseThrow();
        assertEquals("14:00", TravelSchedule.clock(retrieve.minute()));
        assertTrue(events.stream().anyMatch(e -> e.id().equals("stay:0:pickup")));
        assertTrue(events.stream().filter(e -> e.id().contains("drop")).allMatch(e -> e.note().contains("확인")));
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(d, fixedDays(d, 2))));
    }
    static TravelDetails selectedHotel() {
        var old = details().stays().get(0);
        return new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(new Stay(old.name(), old.address(), old.checkInDay(), old.checkOutDay(),
                old.checkInTime(), old.checkOutTime(), old.beforeCheckIn(), old.afterCheckOut(), "123456", 35.16, 129.16, "https://untrusted.example")), details().transport());
    }
    @Test void selectedHotelCoordinatesAppearOnFixedEventsButNotStationOrLockers() {
        var details = selectedHotel();
        var events = TravelSchedule.events(details, 2);
        var hotel = events.stream().filter(e -> e.id().equals("stay:0:in")).findFirst().orElseThrow().place();
        assertEquals("123456", hotel.placeId()); assertEquals(35.16, hotel.latitude()); assertEquals(129.16, hotel.longitude());
        assertEquals("https://place.map.kakao.com/123456", hotel.placeUrl()); assertTrue(hotel.verified());
        assertNull(events.stream().filter(e -> e.id().equals("transport:arrival")).findFirst().orElseThrow().place().latitude());
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft(details, fixedDays(details, 2))));
    }
    @Test void partialOrInvalidLodgingCoordinatesAreRejected() {
        var stay = new Stay("숙소", "부산", 1, 2, "15:00", "11:00", LuggagePlan.CARRY, LuggagePlan.CARRY, "123", null, 129.0, null);
        assertThrows(ApiException.class, () -> TravelSchedule.validate(new TravelDetails(MovementMode.CAR, List.of(stay), null), 2));
    }
    @Test void selectedArrivalAndDepartureLocationsHaveMapCoordinates() {
        var station = new SelectedPlace("222", "부산역", "부산 동구", 35.11, 129.04, null);
        var d = new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(),
                new Transport(TransportMode.KTX, "부산역", "10:00", "부산역", "19:00", station, station));
        assertTrue(TravelSchedule.events(d, 2).stream().map(TravelSchedule.Event::place)
                .allMatch(p -> p.verified() && "222".equals(p.placeId()) && p.latitude().equals(35.11)));
    }
    @Test void sampleModeHonorsFixedConstraintsWithoutPaidApiCalls() {
        var ai = new OpenAiItineraryClient(null, null, null) { @Override public boolean enabled() { return false; } };
        var places = new com.tripplanner.place.KakaoLocalClient(null, null) {
            @Override public boolean enabled() { return true; }
            @Override public Optional<Area> resolveArea(String d) { return Optional.empty(); }
            @Override public List<PlaceHit> search(String q, Area a, int size) {
                return java.util.stream.IntStream.range(0, 8).mapToObj(n -> new PlaceHit(q + n, q + n, "부산", 35, 129,
                        null, null, q.contains("맛집") ? "음식점" : q.contains("카페") ? "카페" : "관광명소", null)).toList();
            }
        };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places);
        try {
            var d = service.generate(new GenerateRequest("부산", null, 2, null, List.of(), null, details()));
            assertEquals(details(), d.travelDetails());
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(d));
            assertTrue(d.days().stream().flatMap(day -> day.places().stream()).anyMatch(p -> p.fixedEventId() != null));
        } finally { service.shutdown(); }
    }
}
