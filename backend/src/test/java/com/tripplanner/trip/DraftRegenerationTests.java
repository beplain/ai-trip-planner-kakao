package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({DraftRooms.class, TripService.class, DraftRegenerationService.class, DraftRegenerationTests.Config.class})
@org.springframework.test.context.TestExecutionListeners(
        listeners = {org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
                org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DraftRegenerationTests {
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean FakeGenerator generator() { return new FakeGenerator(); }
    }
    static class FakeGenerator extends TripGenerationService {
        TripDraft result; Runnable during; boolean fail; int calls; GenerateRequest input;
        FakeGenerator() { super(null, null, null); }
        @Override public TripDraft generate(GenerateRequest request) {
            calls++; input = request;
            if (during != null) during.run();
            if (fail) throw ApiException.badRequest("AI 서비스 오류");
            return result;
        }
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TripService tripService;
    @Autowired DraftRooms drafts;
    @Autowired DraftRegenerationService regeneration;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired FakeGenerator generator;
    @Autowired DraftDocumentRepository documents;
    @Autowired DraftInvitationRepository draftInvites;
    Long owner; Long viewer; DraftRooms.RoomResponse room;
    @BeforeEach void setup() {
        owner = users.saveAndFlush(new User("dev:regen-" + UUID.randomUUID(), null, "만든 사람")).getId();
        viewer = users.saveAndFlush(new User("dev:view-" + UUID.randomUUID(), null, "보기 전용")).getId();
        room = drafts.create(itinerary("부산", "이전 장소", "남겨둔 메모"), owner);
        generator.calls = 0; generator.fail = false; generator.during = null;
        generator.result = itinerary("대전", "새 쇼핑 장소", null);
    }
    private TripDraft itinerary(String destination, String name, String note) {
        var p = new PlaceDraft("123", name, null, 36.3, 127.4, "10:00", "추천 이유", note, "쇼핑", true, null, null);
        return new TripDraft(destination + " 여행", destination, LocalDate.of(2026,11,1), LocalDate.of(2026,11,1),
                "300,000원", List.of("쇼핑"), List.of(new DayDraft(1, LocalDate.of(2026,11,1), List.of(p))), "AI", List.of());
    }
    private RegenerateDraftRequest request(long version) {
        return new RegenerateDraftRequest(version, "바꾼 여행 이름",
                new GenerateRequest("대전", LocalDate.of(2026,11,1), 1, "300,000원", List.of("쇼핑"), "쇼핑 위주로"));
    }
    @Test void regenerateReplacesEntireItineraryAndPreservesParticipantsAndInvitations() {
        String token = drafts.invite(room.id(), MemberRole.VIEWER, owner).token();
        drafts.join(token, viewer);
        var before = drafts.get(room.id(), owner);
        long tripCount = trips.count();
        var result = regeneration.regenerate(room.id(), request(before.version()), owner);
        assertEquals(1, generator.calls);
        assertEquals("대전", generator.input.destination());
        assertEquals(List.of("쇼핑"), generator.input.styles());
        assertEquals("쇼핑 위주로", generator.input.request());
        assertEquals("바꾼 여행 이름", result.draft().title());
        assertEquals("쇼핑 위주로", result.draft().request());
        assertEquals("새 쇼핑 장소", result.draft().days().get(0).places().get(0).name());
        assertNull(result.draft().days().get(0).places().get(0).note());
        assertEquals(before.members(), result.members());
        assertEquals(room.id(), drafts.preview(token, viewer).draftId());
        assertEquals("대전", drafts.get(room.id(), viewer).draft().destination());
        assertEquals(tripCount, trips.count());
    }
    @Test void generationFailureLeavesOriginalDraftUntouched() {
        generator.fail = true;
        assertThrows(ApiException.class, () -> regeneration.regenerate(room.id(), request(room.version()), owner));
        assertEquals(room.draft(), drafts.get(room.id(), owner).draft());
        assertEquals(room.version(), drafts.get(room.id(), owner).version());
    }
    @Test void editDuringGenerationIsNotOverwritten() {
        generator.during = () -> drafts.replaceGenerated(room.id(), room.version(), itinerary("부산", "동행자가 먼저 바꾼 장소", "보존"), owner);
        assertThrows(ApiException.class, () -> regeneration.regenerate(room.id(), request(room.version()), owner));
        assertEquals("동행자가 먼저 바꾼 장소", drafts.get(room.id(), owner).draft().days().get(0).places().get(0).name());
    }
    @Test void staleViewerAndFinalizedRequestsAreRejectedBeforeCallingAi() {
        drafts.join(drafts.invite(room.id(), MemberRole.VIEWER, owner).token(), viewer);
        var current = drafts.get(room.id(), owner);
        assertThrows(ApiException.class, () -> regeneration.regenerate(room.id(), request(current.version()), viewer));
        assertThrows(ApiException.class, () -> regeneration.regenerate(room.id(), request(room.version()), owner));
        drafts.save(room.id(), current.version(), owner);
        assertThrows(ApiException.class, () -> regeneration.regenerate(room.id(), request(current.version()), owner));
        assertEquals(0, generator.calls);
    }
    @Test void regularDraftUpdateCannotBypassRegenerationForTravelConditions() {
        assertThrows(ApiException.class, () -> drafts.update(room.id(), new DraftRooms.Update(room.version(), generator.result), owner));
        assertEquals(room.draft(), drafts.get(room.id(), owner).draft());
    }
    @Test void renameChangesOnlyTitleWithoutGeneratingAndPersistsForCompanions() {
        var token = drafts.invite(room.id(), MemberRole.EDITOR, owner).token();
        drafts.join(token, viewer);
        var before = drafts.get(room.id(), viewer);
        var result = drafts.rename(room.id(), new RenameDraftRequest(before.version(), "  친구들과 부산 여행  "), viewer);
        var d = before.draft();
        assertEquals(new TripDraft("친구들과 부산 여행", d.destination(), d.startDate(), d.endDate(),
                d.budget(), d.styles(), d.days(), d.source(), d.warnings(), d.request()), result.draft());
        assertEquals(before.members(), result.members());
        assertTrue(result.version() > before.version());
        assertEquals(result.draft(), drafts.get(room.id(), owner).draft());
        assertEquals(room.id(), drafts.preview(token, owner).draftId());
        assertEquals(0, generator.calls);
    }
    @Test void staleRenameDoesNotOverwriteConcurrentChanges() {
        var changed = drafts.replaceGenerated(room.id(), room.version(), generator.result, owner);
        assertThrows(ApiException.class, () -> drafts.rename(room.id(), new RenameDraftRequest(room.version(), "오래된 수정"), owner));
        assertEquals(changed.draft(), drafts.get(room.id(), owner).draft());
        assertEquals(0, generator.calls);
    }
    @Test void viewerAndFinalizedDraftCannotBeRenamed() {
        drafts.join(drafts.invite(room.id(), MemberRole.VIEWER, owner).token(), viewer);
        var current = drafts.get(room.id(), owner);
        assertThrows(ApiException.class, () -> drafts.rename(room.id(), new RenameDraftRequest(current.version(), "변경"), viewer));
        drafts.save(room.id(), current.version(), owner);
        assertThrows(ApiException.class, () -> drafts.rename(room.id(), new RenameDraftRequest(current.version(), "변경"), owner));
        assertEquals(0, generator.calls);
    }
    @Test void ownerDeletesDraftAndAllItsInvitationsButKeepsOtherDrafts() {
        String token = drafts.invite(room.id(), MemberRole.EDITOR, owner).token();
        String secondToken = drafts.invite(room.id(), MemberRole.VIEWER, owner).token();
        drafts.join(token, viewer);
        var other = drafts.create(itinerary("서울", "별도 장소", null), owner);
        String otherToken = drafts.invite(other.id(), MemberRole.EDITOR, owner).token();
        var current = drafts.get(room.id(), owner);
        long tripCount = trips.count();
        drafts.delete(room.id(), current.version(), owner);
        assertFalse(documents.existsById(room.id()));
        assertFalse(draftInvites.existsById(token));
        assertFalse(draftInvites.existsById(secondToken));
        assertTrue(documents.existsById(other.id()));
        assertTrue(draftInvites.existsById(otherToken));
        assertThrows(ApiException.class, () -> drafts.get(room.id(), viewer));
        assertThrows(ApiException.class, () -> drafts.join(token, viewer));
        assertTrue(drafts.list(owner).stream().noneMatch(d -> d.id().equals(room.id())));
        assertTrue(drafts.list(viewer).stream().noneMatch(d -> d.id().equals(room.id())));
        assertEquals(tripCount, trips.count());
    }
    @Test void companionsAndOutsidersCannotDeleteDraft() {
        assertThrows(ApiException.class, () -> drafts.delete(room.id(), room.version(), viewer));
        for (MemberRole role : List.of(MemberRole.EDITOR, MemberRole.VIEWER)) {
            Long person = users.saveAndFlush(new User("dev:delete-" + UUID.randomUUID(), null, "동행자")).getId();
            String token = drafts.invite(room.id(), role, owner).token();
            drafts.join(token, person);
            var current = drafts.get(room.id(), owner);
            assertThrows(ApiException.class, () -> drafts.delete(room.id(), current.version(), person));
            assertTrue(draftInvites.existsById(token));
        }
        assertTrue(documents.existsById(room.id()));
    }
    @Test void staleVersionOrFinalizedDraftCannotBeDeleted() {
        String token = drafts.invite(room.id(), MemberRole.VIEWER, owner).token();
        var renamed = drafts.rename(room.id(), new RenameDraftRequest(room.version(), "새 이름"), owner);
        assertThrows(ApiException.class, () -> drafts.delete(room.id(), room.version(), owner));
        assertTrue(documents.existsById(room.id()));
        assertTrue(draftInvites.existsById(token));
        var saved = drafts.save(room.id(), renamed.version(), owner);
        var finalized = drafts.get(room.id(), owner);
        assertThrows(ApiException.class, () -> drafts.delete(room.id(), finalized.version(), owner));
        assertTrue(documents.existsById(room.id()));
        assertTrue(draftInvites.existsById(token));
        assertTrue(trips.existsById(saved.id()));
    }

    @Test void accommodationAndFixedEventsSurviveDraftRenameAndFinalPersistence() {
        var hotelDetails = TravelScheduleTests.selectedHotel();
        var station = new SelectedPlace("222", "부산역", "부산 동구", 35.11, 129.04, null);
        var details = new TravelDetails(hotelDetails.movementMode(), hotelDetails.stays(),
                new Transport(TransportMode.KTX, "부산역", "10:00", "부산역", "19:00", station, station));
        var input = TravelScheduleTests.draft(details, TravelScheduleTests.fixedDays(details, 2));
        var created = drafts.create(input, owner);
        var renamed = drafts.rename(created.id(), new RenameDraftRequest(created.version(), "숙소 여행"), owner);
        assertEquals(details, renamed.draft().travelDetails());
        assertEquals(input.days(), renamed.draft().days());
        var saved = drafts.save(renamed.id(), renamed.version(), owner);
        var reloaded = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> trips.findById(saved.id()).orElseThrow().getTravelDetails());
        assertEquals(details, reloaded);
        assertEquals(details, saved.travelDetails());
        assertEquals(station, saved.travelDetails().transport().arrivalLocation());
        assertTrue(saved.days().stream().flatMap(d -> d.places().stream()).allMatch(p -> p.fixedEventId() != null));
        var hotel = saved.days().stream().flatMap(d -> d.places().stream()).filter(p -> "stay:0:in".equals(p.fixedEventId())).findFirst().orElseThrow();
        assertEquals("123456", hotel.placeId()); assertEquals(35.16, hotel.latitude()); assertTrue(hotel.verified());
        var event = saved.days().get(0).places().get(0);
        assertThrows(ApiException.class, () -> tripService.deletePlace(saved.id(), event.id(), saved.version(), owner));
        assertThrows(ApiException.class, () -> tripService.updatePlace(saved.id(), event.id(), new UpdatePlaceRequest("09:00", "수정", saved.version()), owner));
        assertEquals(details, drafts.get(created.id(), owner).draft().travelDetails());
    }
    @Test void cannotRemoveFixedEventsOrChangeAccommodationViaCollaborativeUpdate() {
        var details = TravelScheduleTests.details();
        var input = TravelScheduleTests.draft(details, TravelScheduleTests.fixedDays(details, 2));
        var created = drafts.create(input, owner);
        var altered = new TripDraft(input.title(), input.destination(), null, null, null, List.of(),
                List.of(new DayDraft(1, null, List.of()), input.days().get(1)), "AI", List.of(), null, details);
        assertThrows(ApiException.class, () -> drafts.update(created.id(), new DraftRooms.Update(created.version(), altered), owner));
        var withoutDetails = new TripDraft(input.title(), input.destination(), null, null, null, List.of(),
                input.days(), "AI", List.of());
        assertThrows(ApiException.class, () -> drafts.update(created.id(), new DraftRooms.Update(created.version(), withoutDetails), owner));
        assertEquals(input, drafts.get(created.id(), owner).draft());
    }
    @Test void savedTravelReorderRetainsFixedAnchorsAndAvailableTimeSlots() {
        var details = TravelScheduleTests.details();
        var days = new java.util.ArrayList<>(TravelScheduleTests.fixedDays(details, 2));
        var places = new java.util.ArrayList<>(days.get(0).places());
        places.add(new PlaceDraft(null, "산책", null, null, null, "17:00", null, null, "관광", false, null, null));
        places.add(new PlaceDraft(null, "저녁", null, null, null, "19:00", null, null, "식당", false, null, null));
        days.set(0, new DayDraft(1, null, places));
        var created = drafts.create(TravelScheduleTests.draft(details, days), owner);
        var saved = drafts.save(created.id(), created.version(), owner);
        var day = saved.days().get(0);
        var ids = new java.util.ArrayList<>(day.places().stream().map(PlaceResponse::id).toList());
        java.util.Collections.swap(ids, ids.size() - 1, ids.size() - 2);
        var changed = tripService.reorder(saved.id(), day.id(), new ReorderRequest(ids, saved.version()), owner);
        assertEquals("저녁", changed.days().get(0).places().get(ids.size() - 2).name());
        assertEquals("17:00", changed.days().get(0).places().get(ids.size() - 2).visitTime());
        java.util.Collections.swap(ids, 0, 1);
        assertThrows(ApiException.class, () -> tripService.reorder(saved.id(), day.id(), new ReorderRequest(ids, changed.version()), owner));
    }
}
