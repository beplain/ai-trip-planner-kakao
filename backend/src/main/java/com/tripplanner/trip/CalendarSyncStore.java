package com.tripplanner.trip;

import com.tripplanner.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Commit a durable claim before the external call, so retries cannot create duplicate events. */
@Service
public class CalendarSyncStore {
    private final TripCalendarEventRepository events;
    private final UserRepository users;
    public record Claim(boolean acquired, TripDtos.CalendarResult result) {}
    public CalendarSyncStore(TripCalendarEventRepository events, UserRepository users) { this.events = events; this.users = users; }
    @Transactional(readOnly = true)
    public Optional<TripDtos.CalendarResult> find(Long tripId, Long userId) {
        return events.findByTripIdAndUserId(tripId, userId).map(TripCalendarEvent::response);
    }
    @Transactional
    public Claim claim(Long tripId, Long userId) {
        users.findForCalendar(userId).orElseThrow();
        var event = events.findByTripIdAndUserId(tripId, userId).orElseGet(() -> new TripCalendarEvent(tripId, userId));
        var prior = event.response();
        if (prior.status() != null && !prior.status().equals("FAILED")) return new Claim(false, prior);
        event.result("PENDING", null, "등록 처리 중이거나 결과 확인이 필요합니다. 톡캘린더를 확인해 주세요. 중복 등록을 막기 위해 재전송하지 않습니다.");
        events.saveAndFlush(event);
        return new Claim(true, event.response());
    }
    @Transactional
    public TripDtos.CalendarResult finish(Long tripId, Long userId, String status, String eventId, String message) {
        var event = events.findByTripIdAndUserId(tripId, userId).orElseThrow();
        event.result(status, eventId, message);
        return event.response();
    }
}
