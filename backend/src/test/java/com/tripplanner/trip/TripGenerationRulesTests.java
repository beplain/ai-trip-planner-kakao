package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TripGenerationRulesTests {
    static AiPlace shop(String name) { return new AiPlace(name, name, "10:00", "쇼핑", "", "", List.of("쇼핑")); }
    static AiPlace food(String name) { return new AiPlace(name, name, "12:00", "식당", "", "", List.of()); }
    static AiItinerary input(List<AiPlace> alternatives) {
        return new AiItinerary("부산", List.of(
                new AiDay(1, List.of(shop("상점A"), food("식당A")), List.of()),
                new AiDay(2, List.of(shop("상점별칭"), food("식당B")), alternatives)));
    }
    static class FakeAi extends OpenAiItineraryClient {
        final AiItinerary result; int calls; String feedback;
        FakeAi(AiItinerary result) { super(null, null, new ObjectMapper()); this.result = result; }
        @Override public boolean enabled() { return true; }
        @Override public AiItinerary generate(ItineraryRequest r, String feedback) {
            calls++; this.feedback = feedback; return result;
        }
    }
    static class FakePlaces extends KakaoLocalClient {
        FakePlaces() { super(null, null); }
        @Override public boolean enabled() { return true; }
        @Override public Optional<Area> resolveArea(String d) { return Optional.empty(); }
        @Override public List<PlaceHit> search(String q, Area a, int size) {
            String id = q.equals("상점별칭") ? "상점A" : q;
            return List.of(new PlaceHit(id, q, "부산", 35, 129, null, null,
                    q.startsWith("식당") ? "음식점" : "대형마트", null));
        }
    }
    static TripDtos.GenerateRequest request() {
        return new TripDtos.GenerateRequest("부산", null, 2, null, List.of("쇼핑"), null);
    }
    @Test void duplicateKakaoIdUsesSameStyleAlternativeAtOriginalTime() {
        var ai = new FakeAi(input(List.of(new AiPlace("상점B", "상점B", "16:00", "쇼핑", "", "", List.of("쇼핑")))));
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            var draft = service.generate(request());
            var replacement = draft.days().get(1).places().get(0);
            assertEquals("상점B", replacement.name());
            assertEquals("10:00", replacement.visitTime());
            assertEquals(4, draft.days().stream().flatMap(d -> d.places().stream()).map(TripDtos.PlaceDraft::placeId).distinct().count());
            assertEquals(1, ai.calls);
        } finally { service.shutdown(); }
    }
    @Test void duplicateCannotReturnAsUnverifiedPlaceAndRetryIsBounded() {
        var ai = new FakeAi(input(List.of()));
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            var result = service.generate(request());
            var visits = result.days().stream().flatMap(d -> d.places().stream()).filter(p -> p.fixedEventId() == null).toList();
            assertTrue(visits.stream().allMatch(TripDtos.PlaceDraft::verified));
            assertEquals(visits.size(), visits.stream().map(TripDtos.PlaceDraft::placeId).distinct().count());
            assertEquals(2, ai.calls);
            assertNotNull(ai.feedback);
        } finally { service.shutdown(); }
    }
    @Test void unrelatedAlternativeCannotReplaceShopping() {
        var ai = new FakeAi(input(List.of(food("식당대체"))));
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            var result = service.generate(request());
            assertTrue(result.days().stream().flatMap(d -> d.places().stream())
                    .noneMatch(p -> p.name().startsWith("식당") && "쇼핑".equals(p.category())));
            assertFalse(result.warnings().isEmpty());
        } finally { service.shutdown(); }
    }
    @Test void missingMapKeyStopsGenerationBeforeAiInsteadOfReturningUnknownPlaces() {
        var ai = new FakeAi(null) { @Override public boolean enabled() { return false; } };
        var places = new FakePlaces() { @Override public boolean enabled() { return false; } };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places);
        try {
            assertEquals("PLACES_REQUIRED", assertThrows(ApiException.class, () -> service.generate(request())).getCode());
            assertEquals(0, ai.calls);
        } finally { service.shutdown(); }
    }
    @Test void unknownRecommendationUsesVerifiedAlternativeAndNeverAppearsInWarnings() {
        var ai = new FakeAi(new AiItinerary("부산", List.of(
                new AiDay(1, List.of(shop("없는상점"), food("식당A")), List.of(shop("실제상점A"))),
                new AiDay(2, List.of(shop("실제상점B"), food("식당B")), List.of()))));
        var places = new FakePlaces() { @Override public List<PlaceHit> search(String q, Area a, int size) {
            return q.equals("없는상점") ? List.of() : super.search(q, a, size);
        } };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places);
        try {
            var draft = service.generate(request());
            assertEquals("실제상점A", draft.days().get(0).places().get(0).name());
            assertTrue(draft.days().stream().flatMap(d -> d.places().stream()).allMatch(TripDtos.PlaceDraft::verified));
            assertTrue(draft.warnings().stream().noneMatch(w -> w.contains("없는상점")));
        } finally { service.shutdown(); }
    }
    @Test void noMapResultsCannotReturnUnverifiedPlacesEvenWhenAiReturnsValidJson() {
        var ai = new FakeAi(input(List.of(shop("대체상점"))));
        var places = new FakePlaces() { @Override public List<PlaceHit> search(String q, Area a, int size) { return List.of(); } };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places);
        try {
            assertEquals("PLACES_UNAVAILABLE", assertThrows(ApiException.class, () -> service.generate(request())).getCode());
            assertEquals(2, ai.calls);
        } finally { service.shutdown(); }
    }
    @Test void aiGenerationReceivesDetailsAndInjectsFixedItemsWithoutConsumingStyles() {
        var details = TravelScheduleTests.details();
        var ai = new FakeAi(new AiItinerary("부산", List.of(
                new AiDay(1, List.of(new AiPlace("상점A", "상점A", "17:00", "쇼핑", "", "", List.of("쇼핑")),
                        new AiPlace("식당A", "식당A", "19:00", "식당", "", "", List.of())), List.of()),
                new AiDay(2, List.of(new AiPlace("상점B", "상점B", "13:00", "쇼핑", "", "", List.of("쇼핑")),
                        new AiPlace("식당B", "식당B", "15:00", "식당", "", "", List.of())), List.of())))) {
            @Override public AiItinerary generate(ItineraryRequest r, String feedback) {
                assertEquals(details, r.travelDetails()); return super.generate(r, feedback);
            }
        };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            var draft = service.generate(new TripDtos.GenerateRequest("부산", null, 2, null, List.of("쇼핑"), null, details));
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft));
            assertEquals(details, draft.travelDetails());
            assertTrue(draft.days().stream().allMatch(d -> d.places().stream().anyMatch(p -> "쇼핑".equals(p.category()))));
            assertEquals(1, ai.calls);
        } finally { service.shutdown(); }
    }
    @Test void aiActivitiesBeforeArrivalAreRescheduledWithoutChangingFixedEvents() {
        var ai = new FakeAi(new AiItinerary("부산", List.of(
                new AiDay(1, List.of(shop("상점A"), food("식당A")), List.of()),
                new AiDay(2, List.of(shop("상점B"), food("식당B")), List.of()))));
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            var draft = service.generate(new TripDtos.GenerateRequest("부산", null, 2, null,
                    List.of("쇼핑"), null, TravelScheduleTests.details()));
            assertEquals(1, ai.calls);
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(draft));
            assertEquals("10:00", draft.days().get(0).places().stream().filter(p -> "transport:arrival".equals(p.fixedEventId())).findFirst().orElseThrow().visitTime());
            assertTrue(draft.days().stream().allMatch(d -> d.places().stream().anyMatch(p -> "쇼핑".equals(p.category()))));
        } finally { service.shutdown(); }
    }
}
