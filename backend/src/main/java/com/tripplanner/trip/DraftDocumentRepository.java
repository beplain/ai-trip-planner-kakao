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
    long countByOwnerIdAndSavedTripIdIsNull(Long ownerId);
}
