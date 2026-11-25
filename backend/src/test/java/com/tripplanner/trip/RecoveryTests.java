package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql=false,properties="spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Import({DraftRooms.class,TripService.class,TrashMaintenance.class,RecoveryTests.JsonConfig.class})
@org.springframework.test.context.TestExecutionListeners(
        listeners={org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
                org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},
        mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class RecoveryTests {
    @TestConfiguration static class JsonConfig { @Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules();} }
    @Autowired UserRepository users;
    @Autowired TripService trips;
    @Autowired DraftRooms drafts;
    @Autowired TripRepository tripRepository;
    @Autowired DraftDocumentRepository documents;
    @Autowired TripInviteRepository tripInvites;
    @Autowired DraftInvitationRepository draftInvites;
    @Autowired jakarta.persistence.EntityManager entityManager;
    private Long user(){return users.saveAndFlush(new User("dev:"+UUID.randomUUID(),null,"여행자")).getId();}
    private PlaceDraft place(String id,String time){return new PlaceDraft(id,"장소"+id,"부산",35.1,129.1,time,"추천","메모"+id,"관광",true,null,null);}
    private TripDraft itinerary(){return new TripDraft("복원 여행","부산",LocalDate.of(2026,11,23),LocalDate.of(2026,11,23),null,List.of(),
            List.of(new DayDraft(1,LocalDate.of(2026,11,23),List.of(place("A","10:00"),place("B","12:00"),place("C","14:00")))),"AI",List.of());}
    @Test void wholeDraftKeepsHistoryAndMembersButIsHiddenUntilOwnerRestores() {
        var owner=user();var editor=user();var room=drafts.create(itinerary(),owner);
        var token=drafts.invite(room.id(),MemberRole.EDITOR,owner).token();drafts.join(token,editor);
        var current=drafts.get(room.id(),owner);drafts.delete(room.id(),current.version(),owner);
        assertTrue(drafts.list(owner).isEmpty());assertTrue(drafts.summaries(editor).isEmpty());
        assertThrows(ApiException.class,()->drafts.get(room.id(),editor));
        assertThrows(ApiException.class,()->drafts.preview(token,editor));
        assertThrows(ApiException.class,()->drafts.restore(room.id(),editor));
        assertEquals(1,drafts.trash(owner).size());assertTrue(drafts.trash(editor).isEmpty());
        var restored=drafts.restore(room.id(),owner);
        assertEquals(itinerary().days(),restored.draft().days());assertEquals(2,restored.members().size());
        assertEquals(MemberRole.EDITOR,drafts.get(room.id(),editor).myRole());
    }
    @Test void draftDeletionStackSurvivesReloadAndRestoresTwoPlacesInReverseOrder() {
        var owner=user();var initial=drafts.create(itinerary(),owner);
        var one=drafts.update(initial.id(),new DraftRooms.Update(initial.version(),DraftVariants.updateCurrent(initial.draft(),List.of(new DayDraft(1,initial.draft().startDate(),initial.draft().days().get(0).places().subList(1,3))),List.of())),owner);
        var two=drafts.update(one.id(),new DraftRooms.Update(one.version(),DraftVariants.updateCurrent(one.draft(),List.of(new DayDraft(1,one.draft().startDate(),one.draft().days().get(0).places().subList(1,2))),List.of())),owner);
        entityManager.clear();
        var history=drafts.deletedPlaces(two.id(),owner);assertEquals(List.of("B","A"),history.stream().map(e->e.place().placeId()).toList());
        var first=drafts.restorePlace(two.id(),history.get(0).id(),new RecoveryArchive.RestorePlace(two.version(),null),owner);
        var second=drafts.restorePlace(two.id(),history.get(1).id(),new RecoveryArchive.RestorePlace(first.version(),null),owner);
        assertEquals(itinerary().days(),second.draft().days());assertTrue(drafts.deletedPlaces(two.id(),owner).isEmpty());
    }
    @Test void finalizedTripPlacesAndWholeTripCanBeRestoredWithPermissions() {
        var owner=user();var outsider=user();var trip=trips.create(itinerary(),owner);
        var removed=trips.deletePlace(trip.id(),trip.days().get(0).places().get(0).id(),trip.version(),owner);
        var row=trips.deletedPlaces(trip.id(),owner).get(0);
        assertEquals("메모A",row.place().note());
        assertThrows(ApiException.class,()->trips.restorePlace(trip.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),null),outsider));
        var restored=trips.restorePlace(trip.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),null),owner);
        assertEquals(List.of("A","B","C"),restored.days().get(0).places().stream().map(PlaceResponse::placeId).toList());
        trips.delete(trip.id(),owner);assertTrue(trips.list(owner).isEmpty());assertThrows(ApiException.class,()->trips.get(trip.id(),owner));
        assertThrows(ApiException.class,()->trips.restore(trip.id(),outsider));
        assertEquals(1,trips.trash(owner).size());assertEquals(3,trips.restore(trip.id(),owner).days().get(0).places().size());
    }
    @Test void expiryAndPermanentDeletionNeverRestoreAndCleanupRemovesExpiredRows() {
        var owner=user();var draft=drafts.create(itinerary(),owner);drafts.delete(draft.id(),draft.version(),owner);
        documents.findById(draft.id()).orElseThrow().deletedAt(Instant.now().minus(Duration.ofDays(31)));documents.flush();
        assertTrue(drafts.trash(owner).isEmpty());assertThrows(ApiException.class,()->drafts.restore(draft.id(),owner));
        var trip=trips.create(itinerary(),owner);trips.delete(trip.id(),owner);
        trips.permanentlyDelete(trip.id(),owner);assertFalse(tripRepository.existsById(trip.id()));
        var cleaner=new TrashMaintenance(tripRepository,documents,tripInvites,draftInvites);cleaner.purge();
        assertFalse(documents.existsById(draft.id()));
    }
    @Test void conflictingTimesStayArchivedAndCanBeAdjusted() {
        var owner=user();var trip=trips.create(itinerary(),owner);
        var removed=trips.deletePlace(trip.id(),trip.days().get(0).places().get(0).id(),trip.version(),owner);
        var row=trips.deletedPlaces(trip.id(),owner).get(0);
        assertThrows(ApiException.class,()->trips.restorePlace(trip.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),"12:00"),owner));
        assertEquals(1,trips.deletedPlaces(trip.id(),owner).size());
        var restored=trips.restorePlace(trip.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),"09:00"),owner);
        assertEquals("09:00",restored.days().get(0).places().get(0).visitTime());
    }
    @Test void finalizedDraftTransfersCurrentOptionsDeletionHistory() {
        var owner=user();var room=drafts.create(itinerary(),owner);
        var edited=drafts.update(room.id(),new DraftRooms.Update(room.version(),DraftVariants.updateCurrent(room.draft(),
                List.of(new DayDraft(1,room.draft().startDate(),room.draft().days().get(0).places().subList(1,3))),List.of())),owner);
        var saved=drafts.save(room.id(),edited.version(),owner);
        assertEquals(1,trips.deletedPlaces(saved.id(),owner).size());
        trips.delete(saved.id(),owner);trips.permanentlyDelete(saved.id(),owner);
        assertFalse(documents.existsById(room.id()),"Permanent deletion also removes the finalized draft snapshot");
    }
    @Test void viewerCannotRestorePlacesAndStaleVersionCannotRestoreTwice() {
        var owner=user();var viewer=user();var room=drafts.create(itinerary(),owner);
        drafts.join(drafts.invite(room.id(),MemberRole.VIEWER,owner).token(),viewer);
        var current=drafts.get(room.id(),owner);
        var removed=drafts.update(room.id(),new DraftRooms.Update(current.version(),DraftVariants.updateCurrent(current.draft(),
                List.of(new DayDraft(1,current.draft().startDate(),current.draft().days().get(0).places().subList(1,3))),List.of())),owner);
        var row=drafts.deletedPlaces(room.id(),owner).get(0);
        assertThrows(ApiException.class,()->drafts.restorePlace(room.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),null),viewer));
        var restored=drafts.restorePlace(room.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),null),owner);
        assertThrows(ApiException.class,()->drafts.restorePlace(room.id(),row.id(),new RecoveryArchive.RestorePlace(removed.version(),null),owner));
        assertEquals(3,restored.draft().days().get(0).places().size());
    }
    @Test void expiredPlaceRecordsAreNotRestorableAndArePruned() {
        var owner=user();var trip=trips.create(itinerary(),owner);
        trips.deletePlace(trip.id(),trip.days().get(0).places().get(0).id(),trip.version(),owner);
        var entity=tripRepository.findById(trip.id()).orElseThrow();var row=trips.deletedPlaces(trip.id(),owner).get(0);
        entity.deletedPlaces(RecoveryArchive.encode(List.of(new RecoveryArchive.DeletedPlace(row.id(),Instant.now().minus(Duration.ofDays(31)),row.dayNumber(),null,null,row.place()))));
        tripRepository.flush();
        assertTrue(trips.deletedPlaces(trip.id(),owner).isEmpty());
        assertThrows(ApiException.class,()->trips.restorePlace(trip.id(),row.id(),new RecoveryArchive.RestorePlace(entity.getVersion(),null),owner));
        new TrashMaintenance(tripRepository,documents,tripInvites,draftInvites).purge();assertNull(entity.deletedPlaces());
    }
}
