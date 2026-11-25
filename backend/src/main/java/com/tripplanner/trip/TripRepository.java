package com.tripplanner.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> lockById(@Param("id") Long id);
    @Query("select m.trip from TripMember m where m.user.id = :userId and m.trip.deletedAt is null order by m.trip.updatedAt desc")
    List<Trip> findAllForMember(@Param("userId") Long userId);
    List<Trip> findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerId);
    @Query("select t.id from Trip t where t.deletedAt < :cutoff")
    List<Long> expiredTrashIds(@Param("cutoff") java.time.Instant cutoff);
    @Query("select t.id from Trip t where t.deletedPlaces is not null")
    List<Long> archivedPlaceIds();
    @Query("select count(t) from Trip t where t.id=:id and t.owner.id=:user and t.deletedAt is not null")
    long countOwnedTrash(@Param("id") Long id,@Param("user") Long user);
}
