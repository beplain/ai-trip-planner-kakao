package com.tripplanner.trip;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DraftDocumentRepository extends JpaRepository<DraftDocument, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DraftDocument d where d.id = :id")
    Optional<DraftDocument> lockById(@Param("id") String id);
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
            where d.savedTripId is null order by d.updatedAt desc
            """)
    List<DraftMembership> findActiveMemberships();

    @Query("""
            select d.id as id, d.content as content from DraftDocument d
            where d.savedTripId is null and d.id in :ids
            """)
    List<DraftContent> findActiveContents(@Param("ids") List<String> ids);

    long countByOwnerIdAndSavedTripIdIsNull(Long ownerId);
}
