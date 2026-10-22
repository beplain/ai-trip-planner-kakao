package com.tripplanner.trip;

import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(CalendarSyncStore.class)
@org.springframework.test.context.TestExecutionListeners(
        listeners = {org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
                org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},
        mergeMode = org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class CalendarPersistenceTests {
    @Autowired UserRepository users;
    @Autowired CalendarSyncStore store;
    @Test
    void pendingAndCreatedClaimsPersistAndOnlyExplicitFailureCanRetry() {
        var user = users.saveAndFlush(new User("kakao:calendar-test", null, "테스트"));
        assertTrue(store.claim(42L, user.getId()).acquired());
        assertFalse(store.claim(42L, user.getId()).acquired());
        store.finish(42L, user.getId(), "FAILED", null, "rejected");
        assertTrue(store.claim(42L, user.getId()).acquired());
        store.finish(42L, user.getId(), "CREATED", "event", "done");
        assertEquals("event", store.find(42L, user.getId()).orElseThrow().eventId());
        assertFalse(store.claim(42L, user.getId()).acquired());
    }
}
