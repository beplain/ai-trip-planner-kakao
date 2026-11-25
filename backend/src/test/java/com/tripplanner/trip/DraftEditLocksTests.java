package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql=false,properties="spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Import({DraftRooms.class,TripService.class,DraftEditLocks.class,DraftEditLocksTests.JsonConfig.class})
@org.springframework.test.context.TestExecutionListeners(listeners={org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
        org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class DraftEditLocksTests {
    @TestConfiguration static class JsonConfig {@Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules();}}
    @Autowired DraftRooms drafts;
    @Autowired DraftEditLocks locks;
    @Autowired DraftEditLeaseRepository leases;
    @Autowired DraftDocumentRepository documents;
    @Autowired UserRepository users;
    private Long user(String name){return users.saveAndFlush(new User("dev:"+UUID.randomUUID(),null,name)).getId();}
    private TripDraft itinerary(){return new TripDraft("여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(
            new PlaceDraft("a","방문 장소","부산",35.1,129.1,"10:00","","","관광",true,null,null)))),"AI",List.of());}
    private void header(String token){var req=new MockHttpServletRequest();if(token!=null)req.addHeader(DraftEditLocks.HEADER,token);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));}
    @AfterEach void cleanRequest(){RequestContextHolder.resetRequestAttributes();}

    @Test void oneEditorWinsAndHeartbeatDoesNotChangeDraftVersion() {
        var owner=user("여행장");var editor=user("동행자");var viewer=user("관람자");var room=drafts.create(itinerary(),owner);
        drafts.join(drafts.invite(room.id(),MemberRole.EDITOR,owner).token(),editor);
        drafts.join(drafts.invite(room.id(),MemberRole.VIEWER,owner).token(),viewer);
        long version=drafts.get(room.id(),owner).version();
        var grant=locks.acquire(room.id(),owner,null);
        assertEquals(version,grant.status().version());assertTrue(grant.status().mine());
        assertEquals("여행장",locks.status(room.id(),viewer,null).editorName());
        assertThrows(ApiException.class,()->locks.acquire(room.id(),editor,null));
        assertThrows(ApiException.class,()->locks.acquire(room.id(),owner,null),"Another tab with the same account must not take over");
        assertThrows(ApiException.class,()->locks.acquire(room.id(),viewer,grant.token()));
        locks.renew(room.id(),owner,grant.token());assertEquals(version,drafts.get(room.id(),owner).version());
        locks.release(room.id(),owner,"incorrect");assertTrue(locks.status(room.id(),owner,grant.token()).active());
        locks.release(room.id(),owner,grant.token());assertFalse(locks.status(room.id(),editor,null).active());
    }
    @Test void serverRejectsBypassAndOnlyHolderMayMutate() {
        var owner=user("여행장");var editor=user("편집자");var room=drafts.create(itinerary(),owner);
        drafts.join(drafts.invite(room.id(),MemberRole.EDITOR,owner).token(),editor);var current=drafts.get(room.id(),owner);
        var grant=locks.acquire(room.id(),owner,null);header(null);
        assertThrows(ApiException.class,()->drafts.rename(room.id(),new RenameDraftRequest(current.version(),"외부 수정"),editor));
        assertEquals("여행",drafts.get(room.id(),owner).draft().title());
        header(grant.token());
        assertThrows(ApiException.class,()->drafts.rename(room.id(),new RenameDraftRequest(current.version(),"탈취 시도"),editor));
        var renamed=drafts.rename(room.id(),new RenameDraftRequest(current.version(),"내 수정"),owner);
        assertEquals("내 수정",renamed.draft().title());
        assertThrows(ApiException.class,()->drafts.rename(room.id(),new RenameDraftRequest(current.version(),"오래된 수정"),owner));
    }
    @Test void expiredHolderCannotRenewReleaseOrOverwriteNewHolder() {
        var owner=user("이전 편집자");var editor=user("새 편집자");var room=drafts.create(itinerary(),owner);
        drafts.join(drafts.invite(room.id(),MemberRole.EDITOR,owner).token(),editor);
        var old=locks.acquire(room.id(),owner,null);
        leases.findById(room.id()).orElseThrow().renew(Instant.now().minusSeconds(1));leases.flush();
        assertFalse(locks.status(room.id(),owner,old.token()).active());
        assertThrows(ApiException.class,()->locks.renew(room.id(),owner,old.token()));
        var replacement=locks.acquire(room.id(),editor,null);assertNotEquals(old.token(),replacement.token());
        locks.release(room.id(),owner,old.token());assertTrue(locks.status(room.id(),editor,replacement.token()).mine());
        header(old.token());long version=drafts.get(room.id(),owner).version();
        assertThrows(ApiException.class,()->drafts.rename(room.id(),new RenameDraftRequest(version,"덮어쓰기"),owner));
    }
    @Test void httpWritesRequireLeaseAndUnknownUsersCannotReadEditorIdentity() {
        var owner=user("여행장");var stranger=user("외부인");var room=drafts.create(itinerary(),owner);
        assertThrows(ApiException.class,()->locks.status(room.id(),stranger,null));
        header(null);assertThrows(ApiException.class,()->drafts.rename(room.id(),new RenameDraftRequest(room.version(),"변경"),owner));
        assertDoesNotThrow(()->drafts.get(room.id(),owner));
    }
    @Test void joiningCannotInvalidateAnEditorsVersion() {
        var owner=user("여행장");var newcomer=user("새 동행자");var room=drafts.create(itinerary(),owner);
        var invite=drafts.invite(room.id(),MemberRole.EDITOR,owner).token();var grant=locks.acquire(room.id(),owner,null);
        assertThrows(ApiException.class,()->drafts.join(invite,newcomer));
        locks.release(room.id(),owner,grant.token());assertDoesNotThrow(()->drafts.join(invite,newcomer));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousAcquisitionAcrossTransactionsHasOneWinner() throws Exception {
        var owner=user("여행장");var editor=user("편집자");var room=drafts.create(itinerary(),owner);
        drafts.join(drafts.invite(room.id(),MemberRole.EDITOR,owner).token(),editor);
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(var user:List.of(owner,editor))futures.add(executor.submit(()->{start.await();try{locks.acquire(room.id(),user,null);return true;}catch(ApiException e){return false;}}));
            start.countDown();int winners=0;for(var future:futures)if(future.get(30,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);
        }finally {
            executor.shutdownNow();leases.deleteById(room.id());
            var latest=drafts.get(room.id(),owner);drafts.delete(room.id(),latest.version(),owner);drafts.permanentlyDelete(room.id(),owner);
            users.deleteById(editor);users.deleteById(owner);
        }
    }
}
