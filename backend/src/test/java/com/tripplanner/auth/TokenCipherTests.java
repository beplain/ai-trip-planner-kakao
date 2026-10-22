package com.tripplanner.auth;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class TokenCipherTests {
    @Test
    void refreshedCredentialsKeepRefreshTokenWhenKakaoDoesNotReturnReplacement() throws Exception {
        var cipher = new TokenCipher(new byte[32]);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var user = new com.tripplanner.user.User("kakao:test", null, "테스트");
        long now = java.time.Instant.now().getEpochSecond();
        user.setKakaoTokenBundle(cipher.encrypt(mapper.writeValueAsString(
                new KakaoOAuthClient.Tokens("expired", "existing-refresh", now - 1, now + 86400, "talk_calendar"))));
        var repository = (com.tripplanner.user.UserRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.tripplanner.user.UserRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findForCalendar")) return java.util.Optional.of(user);
                    throw new AssertionError(method.getName());
                });
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"access_token\":\"new-access\",\"expires_in\":3600}", org.springframework.http.MediaType.APPLICATION_JSON));
        var props = new com.tripplanner.config.AppProperties(null,
                new com.tripplanner.config.AppProperties.Kakao("client-id", "", java.util.List.of()), null, true, "", 7, "");
        var vault = new KakaoTokenVault(repository, cipher, mapper, builder.build(), props);
        assertEquals("new-access", vault.accessToken(1L));
        var stored = mapper.readValue(cipher.decrypt(user.getKakaoTokenBundle()), KakaoOAuthClient.Tokens.class);
        assertEquals("existing-refresh", stored.refreshToken());
        assertEquals(now + 86400, stored.refreshExpiresAt());
        assertEquals("talk_calendar", stored.scopes());
        assertTrue(user.isKakaoCalendarConnected());
        server.verify();
    }
    @Test
    void tokensAreEncryptedWithIndependentNoncesAndTamperingIsRejected() {
        var cipher = new TokenCipher(new byte[32]);
        String secret = "sample-access-and-refresh-token";
        String first = cipher.encrypt(secret), second = cipher.encrypt(secret);
        assertNotEquals(first, second);
        assertFalse(first.contains(secret));
        assertEquals(secret, cipher.decrypt(first));
        byte[] bytes = Base64.getDecoder().decode(first);
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes)));
    }
    @Test
    void calendarScopeMustMatchExactly() {
        assertTrue(KakaoTokenVault.hasCalendarScope("profile_nickname talk_calendar"));
        assertFalse(KakaoTokenVault.hasCalendarScope("talk_calendar_task talk_message"));
        assertFalse(KakaoTokenVault.hasCalendarScope(null));
    }
}
