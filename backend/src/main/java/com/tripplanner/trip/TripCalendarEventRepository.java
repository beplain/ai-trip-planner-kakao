package com.tripplanner.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TripCalendarEventRepository extends JpaRepository<TripCalendarEvent, Long> {
    Optional<TripCalendarEvent> findByTripIdAndUserId(Long tripId, Long userId);
    void deleteByTripId(Long tripId);
}
