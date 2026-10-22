package com.tripplanner.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {
    Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);
}
