package com.tripplanner.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Component
public class TalkCalendarClient {
    private final RestClient http;
    private final ObjectMapper mapper;
    public TalkCalendarClient(@Qualifier("calendarRestClient") RestClient http, ObjectMapper mapper) { this.http = http; this.mapper = mapper; }
    public static Map<String, Object> event(TripDtos.TripResponse trip) {
        String title = trip.title().codePoints().limit(50).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String description = "AI 여행 플래너에 저장한 여행\n목적지: " + trip.destination() + "\n여행 기간: " + trip.startDate() + " ~ " + trip.endDate()
                + (trip.budget() == null ? "" : "\n예산: " + trip.budget());
        // All-day API uses midnight UTC as a date label; end is the next day after the last travel day.
        return Map.of("title", title, "description", description, "location", Map.of("name", trip.destination()),
                "time", Map.of("start_at", trip.startDate() + "T00:00:00Z", "end_at", trip.endDate().plusDays(1) + "T00:00:00Z",
                        "time_zone", "Asia/Seoul", "all_day", true));
    }
    public String create(String accessToken, TripDtos.TripResponse trip) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("calendar_id", "primary");
        try { form.add("event", mapper.writeValueAsString(event(trip))); }
        catch (Exception e) { throw new IllegalArgumentException("캘린더 일정 구성 실패"); }
        JsonNode result = http.post().uri("https://kapi.kakao.com/v2/api/calendar/create/event")
                .header("Authorization", "Bearer " + accessToken).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        String eventId = result == null ? "" : result.path("event_id").asText("");
        if (eventId.isBlank()) throw new IllegalStateException("캘린더 등록 결과 확인 필요");
        return eventId;
    }
}
