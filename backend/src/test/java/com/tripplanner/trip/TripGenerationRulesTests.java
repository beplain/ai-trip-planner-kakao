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
            assertEquals("AI_CONSTRAINTS", assertThrows(ApiException.class, () -> service.generate(request())).getCode());
            assertEquals(2, ai.calls);
            assertNotNull(ai.feedback);
        } finally { service.shutdown(); }
    }
    @Test void unrelatedAlternativeCannotReplaceShopping() {
        var ai = new FakeAi(input(List.of(food("식당대체"))));
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), new FakePlaces());
        try {
            assertThrows(ApiException.class, () -> service.generate(request()));
        } finally { service.shutdown(); }
    }
    @Test void sampleModeStillWorksWithoutExternalKeys() {
        var ai = new FakeAi(null) { @Override public boolean enabled() { return false; } };
        var places = new FakePlaces() { @Override public boolean enabled() { return false; } };
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places);
        try {
            var draft = service.generate(request());
            assertEquals("SAMPLE", draft.source());
            assertEquals(2, draft.days().size());
            assertEquals(0, ai.calls);
        } finally { service.shutdown(); }
    }
}
