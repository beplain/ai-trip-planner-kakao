package com.tripplanner.trip;

import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @EnableScheduling
public class TrashMaintenance {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DraftEditLeaseRepository editLeases;
    private final TripRepository trips;
    private final DraftDocumentRepository drafts;
    private final TripInviteRepository tripInvites;
    private final DraftInvitationRepository draftInvites;
    public TrashMaintenance(TripRepository trips,DraftDocumentRepository drafts,TripInviteRepository tripInvites,DraftInvitationRepository draftInvites) {
        this.trips=trips;this.drafts=drafts;this.tripInvites=tripInvites;this.draftInvites=draftInvites;
    }
    /** Expiry is enforced on every restore; physical cleanup also runs after restart. */
    @Scheduled(fixedDelayString="PT6H",initialDelayString="PT1M") @Transactional
    public void purge() {
        var cutoff=Instant.now().minus(RecoveryArchive.RETENTION);
        for(var id:trips.expiredTrashIds(cutoff)) {
          lockFinalizedDrafts(drafts,id);
          trips.lockById(id).ifPresent(t->{
            if(t.getDeletedAt()!=null && !t.getDeletedAt().isAfter(cutoff)) {
                removeFinalizedDrafts(drafts,draftInvites,id);
                tripInvites.deleteByTripId(id);tripInvites.flush();trips.delete(t);
            }
          });
        }
        for(var id:drafts.expiredTrashIds(cutoff))drafts.lockById(id).ifPresent(d->{
            if(d.getDeletedAt()!=null && !d.getDeletedAt().isAfter(cutoff)) {
                draftInvites.deleteByDocument_Id(id);draftInvites.flush();drafts.delete(d);
            }
        });
        trips.flush();drafts.flush();
        for(var id:trips.archivedPlaceIds())trips.lockById(id).ifPresent(t->{
            if(editLeases!=null&&editLeases.findById("trip:"+id).filter(l->l.active(Instant.now())).isPresent())return;
            var trimmed=RecoveryArchive.encode(RecoveryArchive.read(t.deletedPlaces()));
            if(!Objects.equals(trimmed,t.deletedPlaces()))t.deletedPlaces(trimmed);
        });
        for(var id:drafts.archivedPlaceIds())drafts.lockById(id).ifPresent(d->{
            if(editLeases!=null&&editLeases.findById(id).filter(l->l.active(Instant.now())).isPresent())return;
            var trimmed=RecoveryArchive.encode(RecoveryArchive.read(d.deletedPlaces()));
            if(!Objects.equals(trimmed,d.deletedPlaces()))d.deletedPlaces(trimmed);
        });
    }
    static void removeFinalizedDrafts(DraftDocumentRepository drafts,DraftInvitationRepository invitations,Long tripId) {
        for(var draft:drafts.findBySavedTripId(tripId)) {
            invitations.deleteByDocument_Id(draft.getId());invitations.flush();drafts.delete(draft);
        }
        drafts.flush();
    }
    // Match invitation acceptance's draft -> trip lock order.
    static void lockFinalizedDrafts(DraftDocumentRepository drafts,Long tripId) {
        drafts.findBySavedTripId(tripId).stream().map(DraftDocument::getId).sorted().forEach(drafts::lockById);
    }
}
