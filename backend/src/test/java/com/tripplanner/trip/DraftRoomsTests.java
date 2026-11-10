package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({DraftRooms.class, TripService.class, DraftRoomsTests.JsonConfig.class})
@org.springframework.test.context.TestExecutionListeners(
        listeners = {org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
                org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class DraftRoomsTests {
    @TestConfiguration static class JsonConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @Autowired DraftRooms drafts;
    @Autowired TripService service;
    @Autowired TripRepository trips;
    @Autowired TripInviteRepository invites;
    @Autowired TripMemberRepository members;
    @Autowired UserRepository users;
    @Autowired DraftDocumentRepository documents;
    @Autowired DraftInvitationRepository draftInvites;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void draftsAndInvitesPersistAcrossServiceRecreationThenFinalizeExactlyOnce() throws Exception {
        Long owner = users.saveAndFlush(new User("kakao:draft-owner", null, "여행장")).getId();
        Long editor = users.saveAndFlush(new User("kakao:draft-editor", null, "동행자")).getId();
        Long viewer = users.saveAndFlush(new User("kakao:draft-viewer", null, "관람자")).getId();
        Long stranger = users.saveAndFlush(new User("kakao:draft-stranger", null, "외부인")).getId();
        var place = new PlaceDraft("123", "해운대", null, 35.1, 129.1, "10:00", null, "처음 메모", null, true, null, null);
        var original = new TripDraft("부산 여행", "부산", LocalDate.of(2026,10,30), LocalDate.of(2026,10,30), "200,000원", List.of(),
                List.of(new DayDraft(1, LocalDate.of(2026,10,30), List.of(place))), "SAMPLE", List.of());
        long baseline = trips.count(); long memberBaseline = members.count(); long inviteBaseline = invites.count();
        long documentBaseline = documents.count();
        var room = drafts.create(original, owner);
        assertEquals(documentBaseline + 1, documents.count());
        assertThrows(ApiException.class, () -> drafts.get(room.id(), stranger));
        String editorToken = drafts.invite(room.id(), MemberRole.EDITOR, owner).token();
        drafts.join(editorToken, editor);
        drafts.join(drafts.invite(room.id(), MemberRole.VIEWER, owner).token(), viewer);
        var expectedSummary = new DraftRooms.RoomSummary(room.id(), "부산 여행", "부산", 2);
        assertEquals(List.of(expectedSummary), drafts.summaries(owner));
        assertEquals(List.of(expectedSummary), drafts.summaries(editor));
        assertEquals(List.of(expectedSummary), drafts.summaries(viewer));
        assertTrue(drafts.summaries(stranger).isEmpty());
        var latest = drafts.get(room.id(), editor);
        var editedPlace = new PlaceDraft(place.placeId(), place.name(), place.address(), place.latitude(), place.longitude(), place.visitTime(),
                place.reason(), "같이 수정한 메모", place.category(), place.verified(), place.placeUrl(), place.phone());
        var edited = new TripDraft(original.title(), original.destination(), original.startDate(), original.endDate(), original.budget(),
                original.styles(), List.of(new DayDraft(1, original.startDate(), List.of(editedPlace))), original.source(), original.warnings());
        assertThrows(ApiException.class, () -> drafts.update(room.id(), new DraftRooms.Update(room.version(), edited), owner));
        assertThrows(ApiException.class, () -> drafts.update(room.id(), new DraftRooms.Update(latest.version(), edited), viewer));
        assertThrows(ApiException.class, () -> drafts.save(room.id(), latest.version(), viewer));
        var updated = drafts.update(room.id(), new DraftRooms.Update(latest.version(), edited), editor);
        assertEquals("같이 수정한 메모", drafts.get(room.id(), owner).draft().days().get(0).places().get(0).note());
        assertEquals(baseline, trips.count());
        assertEquals(memberBaseline, members.count());
        assertEquals(inviteBaseline, invites.count());
        assertTrue(draftInvites.existsById(editorToken));
        assertTrue(new JdbcTemplate(dataSource).queryForObject("select content from draft_rooms where id = ?", String.class, room.id()).contains("같이 수정한 메모"));
        // No state is retained on the new service instance; both the draft and token come from committed DB rows.
        var restarted = new DraftRooms(users, service, documents, draftInvites, mapper);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(edited.title(), restarted.get(room.id(), owner).draft().title());
            assertEquals(room.id(), restarted.preview(editorToken, editor).draftId());
        });
        var pool = Executors.newFixedThreadPool(2);
        TripResponse first;
        try {
            var a = pool.submit(() -> drafts.save(room.id(), updated.version(), editor));
            var b = pool.submit(() -> drafts.save(room.id(), updated.version(), owner));
            first = a.get(20, TimeUnit.SECONDS);
            assertEquals(first.id(), b.get(20, TimeUnit.SECONDS).id());
        } finally { pool.shutdownNow(); }
        assertEquals(baseline + 1, trips.count());
        assertEquals(MemberRole.EDITOR, first.myRole());
        assertEquals(3, first.members().size());
        assertEquals(MemberRole.OWNER, service.get(first.id(), owner).myRole());
        assertEquals(MemberRole.VIEWER, service.get(first.id(), viewer).myRole());
        assertEquals(first.id(), drafts.get(room.id(), owner).savedTripId());
        assertTrue(drafts.list(owner).isEmpty());
        assertTrue(drafts.summaries(owner).isEmpty());
        assertThrows(ApiException.class, () -> drafts.update(room.id(), new DraftRooms.Update(updated.version(), edited), editor));
        assertEquals(first.id(), drafts.join(editorToken, stranger).savedTripId());
        assertEquals(MemberRole.EDITOR, service.get(first.id(), stranger).myRole());
        assertEquals(first.id(), drafts.save(room.id(), updated.version(), owner).id());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalizedConditionsAreReadOnlyButPlaceEditsStillCommitToDatabase() {
        Long user = users.saveAndFlush(new User("kakao:update-test", null, "수정 테스트")).getId();
        var p1 = new PlaceDraft("one", "첫 장소", null, 35.1, 129.1, "10:00", null, null, null, true, null, null);
        var p2 = new PlaceDraft("two", "둘째 장소", null, 35.2, 129.2, "12:00", null, null, null, true, null, null);
        var draft = new TripDraft("수정 전", "부산", LocalDate.of(2026,10,30), null, "200,000원", List.of(),
                List.of(new DayDraft(1, null, List.of(p1, p2))), "SAMPLE", List.of());
        var initial = service.create(draft, user);
        assertThrows(ApiException.class, () -> service.updateInfo(initial.id(), new UpdateTripRequest("수정 후", "부산", LocalDate.of(2026,11,1), 1, "300,000원", List.of("맛집"), initial.version()), user));
        var fresh = service.get(initial.id(), user);
        assertEquals("수정 전", fresh.title());
        assertEquals("200,000원", fresh.budget());
        assertEquals(LocalDate.of(2026,10,30), fresh.startDate());
        assertEquals(initial.version(), fresh.version());
        var updated = fresh;
        Long first = fresh.days().get(0).places().get(0).id();
        Long second = fresh.days().get(0).places().get(1).id();
        var memo = service.updatePlace(fresh.id(), first, new UpdatePlaceRequest("11:30", "DB에 남는 메모", updated.version()), user);
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        assertEquals("DB에 남는 메모", sql.queryForObject("select note from itinerary_places where id = ?", String.class, first));
        assertEquals("11:30", sql.queryForObject("select visit_time from itinerary_places where id = ?", String.class, first));
        assertThrows(ApiException.class, () -> service.updatePlace(fresh.id(), first, new UpdatePlaceRequest("09:00", "덮어쓰기", updated.version()), user));
        var ordered = service.reorder(fresh.id(), fresh.days().get(0).id(), new ReorderRequest(List.of(second, first), memo.version()), user);
        assertEquals(second, service.get(fresh.id(), user).days().get(0).places().get(0).id());
        assertEquals(0, sql.queryForObject("select order_index from itinerary_places where id = ?", Integer.class, second));
        var removed = service.deletePlace(fresh.id(), second, ordered.version(), user);
        assertEquals(0, sql.queryForObject("select count(*) from itinerary_places where id = ?", Integer.class, second));
        assertEquals(1, service.get(fresh.id(), user).days().get(0).places().size());
        assertTrue(service.changes(fresh.id(), user, memo.version()).changed());
        assertFalse(service.changes(fresh.id(), user, removed.version()).changed());
    }
}
