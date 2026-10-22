package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.auth.KakaoTokenVault;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import com.tripplanner.trip.TripDtos.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TalkCalendarTests {
    private static TripResponse trip() {
        return new TripResponse(1L, "부산 여행", "부산", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2),
                4, "200,000원", List.of(), 0L, Instant.now(), MemberRole.OWNER, List.of(), List.of(), null);
    }
    @Test
    void eventIncludesEntireTravelPeriodAndPrimaryCalendar() {
        var event = TalkCalendarClient.event(trip());
        var time = (Map<?, ?>) event.get("time");
        assertEquals("2026-10-30T00:00:00Z", time.get("start_at"));
        assertEquals("2026-11-03T00:00:00Z", time.get("end_at"));
        assertEquals(true, time.get("all_day"));
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://kapi.kakao.com/v2/api/calendar/create/event"))
                .andExpect(header("Authorization", "Bearer fake-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("calendar_id", "primary")))
                .andRespond(withSuccess("{\"event_id\":\"calendar-event-1\"}", MediaType.APPLICATION_JSON));
        assertEquals("calendar-event-1", new TalkCalendarClient(builder.build(), new ObjectMapper()).create("fake-token", trip()));
        server.verify();
    }
    private static class Store extends CalendarSyncStore {
        CalendarResult value;
        Store() { super(null, null); }
        @Override public Optional<CalendarResult> find(Long t, Long u) { return Optional.ofNullable(value); }
        @Override public Claim claim(Long t, Long u) {
            if (value != null && !value.status().equals("FAILED")) return new Claim(false, value);
            value = new CalendarResult("PENDING", "pending", null);
            return new Claim(true, value);
        }
        @Override public CalendarResult finish(Long t, Long u, String status, String id, String message) {
            return value = new CalendarResult(status, message, id);
        }
    }
    @Test
    void duplicateRegistrationAndUncertainNetworkResultsAreNotResent() {
        Store store = new Store();
        int[] calls = {0};
        TripService trips = new TripService(null, null, null, null) { @Override public TripResponse get(Long id, Long user) { return trip(); } };
        KakaoTokenVault vault = new KakaoTokenVault(null, null, null, null, null) {
            @Override public boolean connected(Long id) { return true; }
            @Override public String accessToken(Long id) { return "fake"; }
        };
        TalkCalendarClient client = new TalkCalendarClient(null, null) {
            @Override public String create(String token, TripResponse trip) { calls[0]++; return "event"; }
        };
        var service = new TalkCalendarService(trips, vault, store, client);
        assertEquals("CREATED", service.sync(1L, 2L).status());
        assertEquals("CREATED", service.sync(1L, 2L).status());
        assertEquals(1, calls[0]);
        Store uncertain = new Store();
        client = new TalkCalendarClient(null, null) {
            @Override public String create(String token, TripResponse trip) { calls[0]++; throw new ResourceAccessException("timeout"); }
        };
        service = new TalkCalendarService(trips, vault, uncertain, client);
        assertEquals("UNKNOWN", service.sync(1L, 2L).status());
        assertEquals("UNKNOWN", service.sync(1L, 2L).status());
        assertEquals(2, calls[0]);
    }
    @Test
    void missingConsentSkipsRemoteCallAndReturnsConnectionGuidance() {
        TripService trips = new TripService(null, null, null, null) { @Override public TripResponse get(Long id, Long user) { return trip(); } };
        KakaoTokenVault vault = new KakaoTokenVault(null, null, null, null, null) {
            @Override public boolean connected(Long id) { return false; }
        };
        var service = new TalkCalendarService(trips, vault, new Store(), null);
        assertEquals("NOT_CONNECTED", service.syncAfterSave(1L, 2L).status());
    }
}
