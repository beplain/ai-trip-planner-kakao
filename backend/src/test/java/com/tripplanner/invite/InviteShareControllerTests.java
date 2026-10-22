package com.tripplanner.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import com.tripplanner.trip.MemberRole;
import com.tripplanner.trip.TripInvite;
import com.tripplanner.trip.TripInviteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InviteShareControllerTests {
    private final String token = "a".repeat(32);
    private TripInvite invitation;
    private int lookups;
    private InviteShareController controller;

    private InviteShareController controller(String base) {
        var repository = (TripInviteRepository) Proxy.newProxyInstance(
                TripInviteRepository.class.getClassLoader(), new Class<?>[]{TripInviteRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByToken")) {
                        lookups++;
                        return token.equals(args[0]) ? Optional.of(invitation) : Optional.empty();
                    }
                    throw new AssertionError("Unexpected repository operation: " + method.getName());
                });
        var props = new AppProperties(null, null, null, true, "tripplanner://invite/", 7, base);
        return new InviteShareController(repository, props, new ObjectMapper(), null);
    }

    @BeforeEach
    void setup() {
        invitation = new TripInvite(null, token, MemberRole.EDITOR, null, Instant.now().plusSeconds(3600));
        controller = controller("https://example.test");
    }

    @Test
    void validInviteHasPublicLandingAndDoesNotJoinOrChangeData() throws Exception {
        var response = controller.page(token, "b".repeat(32), new MockHttpServletRequest());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("https://example.test/share/invite/" + token));
        assertTrue(response.getBody().contains("tripplanner://invite/" + token));
        assertFalse(response.getBody().contains("__INVITE_CONFIG__"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(1, lookups);
    }

    @Test
    void expiredInviteCannotBeShared() throws Exception {
        invitation = new TripInvite(null, token, MemberRole.EDITOR, null, Instant.now().minusSeconds(1));
        var response = controller.page(token, "", new MockHttpServletRequest());
        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertFalse(response.getBody().contains("Kakao.Share"));
    }

    @Test
    void malformedTokenAndKeyAreRejected() {
        assertThrows(ApiException.class, () -> controller.page("<script>", "", new MockHttpServletRequest()));
        assertEquals(0, lookups);
        assertThrows(ApiException.class, () -> controller.page(token, "</script>", new MockHttpServletRequest()));
    }

    @Test
    void jsonCannotBreakOutOfInlineScript() throws Exception {
        controller = controller("https://example.test/</script>");
        var body = controller.page(token, "", new MockHttpServletRequest()).getBody();
        assertTrue(body.contains("\\u003c/script\\u003e"));
    }
}
