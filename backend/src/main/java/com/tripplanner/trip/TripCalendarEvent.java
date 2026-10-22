package com.tripplanner.trip;

import jakarta.persistence.*;

@Entity
@Table(name = "trip_calendar_events", uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "user_id"}))
public class TripCalendarEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trip_id", nullable = false) private Long tripId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 20) private String status;
    @Column(length = 300) private String eventId;
    @Column(length = 500) private String message;
    protected TripCalendarEvent() {}
    public TripCalendarEvent(Long tripId, Long userId) { this.tripId = tripId; this.userId = userId; }
    public void result(String status, String eventId, String message) { this.status = status; this.eventId = eventId; this.message = message; }
    public TripDtos.CalendarResult response() { return new TripDtos.CalendarResult(status, message, eventId); }
}
