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
    @Autowired DraftRooms drafts;
    @Autowired DraftRegenerationService regeneration;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired FakeGenerator generator;
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
}
