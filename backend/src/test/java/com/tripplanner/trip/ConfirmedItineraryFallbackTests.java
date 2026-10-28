package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmedItineraryFallbackTests {
    static class Maps extends KakaoLocalClient {
        int calls;
        Maps() { super(null, null); }
        @Override public boolean enabled() { return true; }
        @Override public Optional<Area> resolveArea(String destination) { return Optional.of(new Area(35.16, 129.16, "부산")); }
        @Override public synchronized List<PlaceHit> search(String query, Area center, int size) {
            calls++;
            // AI's original recommendation is not found. Only category searches supply confirmed places.
            if (!query.startsWith("부산 ")) return List.of();
            String group = query.contains("맛집") ? "음식점" : query.contains("카페") ? "카페" : query.contains("쇼핑") ? "대형마트" : "관광명소";
            return IntStream.range(0, 8).mapToObj(n -> new PlaceHit(Math.abs(query.hashCode()) + "-" + n, query + " 실제장소" + n,
                    "부산 해운대구", 35.16, 129.16, null, null, group, 500)).toList();
        }
    }
    static OpenAiItineraryClient broken(boolean network) {
        return new OpenAiItineraryClient(null, null, null) {
            @Override public boolean enabled() { return true; }
            @Override public AiItinerary generate(ItineraryRequest request, String feedback) {
                if (network) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "응답 실패");
                return new AiItinerary("부산 여행", List.of(new AiDay(1, List.of(
                        new AiPlace("없는 장소", "없는 장소", "09:00", "관광", "", "", List.of())), List.of())));
            }
        };
    }
    static GenerateRequest request(List<String> styles) {
        var d = TravelScheduleTests.selectedHotel();
        return new GenerateRequest("부산", LocalDate.of(2026, 11, 1), 2, "200000", styles, null, d);
    }
    @Test void missingAiDayAndMissingStyleStillProduceConfirmedNonDuplicateDraft() {
        var maps = new Maps(); var service = new TripGenerationService(broken(false), new SampleItineraryGenerator(), maps);
        try {
            var result = service.generate(request(List.of("맛집", "쇼핑")));
            assertEquals(2, result.days().size());
            assertEquals(LocalDate.of(2026, 11, 2), result.days().get(1).date());
            var visits = result.days().stream().flatMap(d -> d.places().stream()).filter(p -> p.fixedEventId() == null).toList();
            assertFalse(visits.isEmpty()); assertTrue(visits.stream().allMatch(PlaceDraft::verified));
            assertEquals(visits.size(), visits.stream().map(PlaceDraft::placeId).distinct().count());
            assertTrue(result.days().stream().allMatch(d -> d.places().stream().anyMatch(p -> p.fixedEventId() == null)));
            assertTrue(result.warnings().stream().noneMatch(w -> w.contains("없는 장소")));
            assertTrue(maps.calls < 40, "Search requests should be cached and bounded");
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(result));
        } finally { service.shutdown(); }
    }
    @Test void impossibleNightStyleOnEarlyDepartureIsExplainedRatherThanBlockingWholeTrip() {
        var original = TravelScheduleTests.selectedHotel();
        var input = new GenerateRequest("부산", null, 2, null, List.of("야경"), null,
                new TravelDetails(original.movementMode(), original.stays(), new Transport(TransportMode.KTX, "부산역", "10:00", "부산역", "15:00")));
        var service = new TripGenerationService(broken(false), new SampleItineraryGenerator(), new Maps());
        try {
            var result = service.generate(input);
            assertEquals(2, result.days().size());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("2일차") && w.contains("야경")));
            assertTrue(result.days().get(1).places().stream().filter(p -> p.fixedEventId() == null)
                    .allMatch(p -> TravelSchedule.minute(p.visitTime()) + 60 + TravelSchedule.gap(result.travelDetails()) <= 14 * 60 + 30));
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(result));
        } finally { service.shutdown(); }
    }
    @Test void unusableAiResponseFallsBackToExplicitlyLabeledMapBasedDraft() {
        var service = new TripGenerationService(broken(true), new SampleItineraryGenerator(), new Maps());
        try {
            var result = service.generate(request(List.of("맛집")));
            assertEquals("SAMPLE", result.source());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("AI 응답")));
            assertTrue(result.days().stream().flatMap(d -> d.places().stream()).anyMatch(p -> p.fixedEventId() == null && p.verified()));
        } finally { service.shutdown(); }
    }
    @Test void invalidFixedCommitmentsAreStillRejectedBeforeLookingForPlaces() {
        var maps = new Maps(); var service = new TripGenerationService(broken(false), new SampleItineraryGenerator(), maps);
        try {
            var invalid = new GenerateRequest("부산", null, 1, null, List.of(), null, new TravelDetails(MovementMode.CAR, List.of(),
                    new Transport(TransportMode.CAR, "부산역", "20:00", "부산역", "10:00")));
            assertThrows(ApiException.class, () -> service.generate(invalid));
            assertEquals(0, maps.calls);
        } finally { service.shutdown(); }
    }
}
