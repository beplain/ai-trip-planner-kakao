package com.tripplanner.trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DraftDocumentRepository extends JpaRepository<DraftDocument, String> {
    interface EditMembership {
        String getParticipants();
        Long getVersion();
        Long getSavedTripId();
        java.time.Instant getDeletedAt();
    }
    @Query("select d.participants as participants,d.version as version,d.savedTripId as savedTripId,d.deletedAt as deletedAt from DraftDocument d where d.id=:id")
    Optional<EditMembership> editMembership(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DraftDocument d where d.id = :id")
    Optional<DraftDocument> lockById(@Param("id") String id);
    @Query("select d from DraftDocument d where d.savedTripId is null and d.deletedAt is null order by d.updatedAt desc")
    List<DraftDocument> findBySavedTripIdIsNullOrderByUpdatedAtDesc();
    interface DraftMembership {
        String getId();
        String getParticipants();
    }

    interface DraftContent {
        String getId();
        String getContent();
    }

    @Query("""
            select d.id as id, d.participants as participants from DraftDocument d
            where d.savedTripId is null and d.deletedAt is null order by d.updatedAt desc
            """)
    List<DraftMembership> findActiveMemberships();

    @Query("""
            select d.id as id, d.content as content from DraftDocument d
            where d.savedTripId is null and d.deletedAt is null and d.id in :ids
            """)
    List<DraftContent> findActiveContents(@Param("ids") List<String> ids);

    @Query("select count(d) from DraftDocument d where d.ownerId=:ownerId and d.savedTripId is null and d.deletedAt is null")
    long countByOwnerIdAndSavedTripIdIsNull(@Param("ownerId") Long ownerId);
    List<DraftDocument> findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerId);
    List<DraftDocument> findBySavedTripId(Long tripId);
    @Query("select d.id from DraftDocument d where d.deletedAt < :cutoff")
    List<String> expiredTrashIds(@Param("cutoff") java.time.Instant cutoff);
    @Query("select d.id from DraftDocument d where d.deletedPlaces is not null")
    List<String> archivedPlaceIds();
}
