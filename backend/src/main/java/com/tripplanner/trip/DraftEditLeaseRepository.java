package com.tripplanner.trip;

import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DraftEditLeaseRepository extends JpaRepository<DraftEditLease,String> {
    @Modifying @Query("delete from DraftEditLease l where l.expiresAt < :now and not exists (select d.id from DraftDocument d where d.id=l.draftId and d.deletedAt is null and d.savedTripId is null)")
    void deleteExpiredOrphans(@Param("now") Instant now);
}
