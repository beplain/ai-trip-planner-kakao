package com.tripplanner.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.config.AppProperties;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.Arrays;

@Service
public class KakaoTokenVault {
    private final UserRepository users;
    private final TokenCipher cipher;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final AppProperties props;
    public KakaoTokenVault(UserRepository users, TokenCipher cipher, ObjectMapper mapper,
                           @Qualifier("calendarRestClient") RestClient http, AppProperties props) {
        this.users = users; this.cipher = cipher; this.mapper = mapper; this.http = http; this.props = props;
    }
    public void store(User user, KakaoOAuthClient.Tokens tokens) {
        boolean enabled = hasCalendarScope(tokens.scopes());
        user.setKakaoCalendarConnected(enabled);
        if (!enabled) { user.setKakaoTokenBundle(null); return; }
        try { user.setKakaoTokenBundle(cipher.encrypt(mapper.writeValueAsString(tokens))); }
        catch (Exception e) { throw new IllegalStateException("카카오 연결 정보를 보관하지 못했습니다."); }
    }
    private KakaoOAuthClient.Tokens read(User user) {
        try { return mapper.readValue(cipher.decrypt(user.getKakaoTokenBundle()), KakaoOAuthClient.Tokens.class); }
        catch (Exception e) { throw new IllegalStateException("톡캘린더 연결이 필요합니다."); }
    }
    @Transactional(readOnly = true)
    public boolean connected(Long userId) {
        try {
            var user = users.findById(userId).orElseThrow();
            return hasCalendarScope(read(user).scopes());
        } catch (Exception e) { return false; }
    }
    public static boolean hasCalendarScope(String scopes) {
        return scopes != null && Arrays.asList(scopes.split("\\s+")).contains("talk_calendar");
    }
    @Transactional
    public String accessToken(Long userId) {
        var user = users.findForCalendar(userId).orElseThrow();
        var tokens = read(user);
        if (!hasCalendarScope(tokens.scopes())) throw new IllegalStateException("톡캘린더 연결이 필요합니다.");
        long now = Instant.now().getEpochSecond();
        if (tokens.expiresAt() > now + 60) return tokens.accessToken();
        if (tokens.refreshToken().isBlank() || tokens.refreshExpiresAt() <= now) throw new IllegalStateException("카카오에 다시 연결해 주세요.");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token"); form.add("client_id", props.kakao().restApiKey());
        form.add("refresh_token", tokens.refreshToken());
        if (props.kakao().clientSecret() != null && !props.kakao().clientSecret().isBlank()) form.add("client_secret", props.kakao().clientSecret());
        JsonNode res = http.post().uri("https://kauth.kakao.com/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        if (res == null || res.path("access_token").asText("").isBlank()) throw new IllegalStateException("카카오에 다시 연결해 주세요.");
        var refreshed = new KakaoOAuthClient.Tokens(res.get("access_token").asText(), res.path("refresh_token").asText(tokens.refreshToken()),
                now + res.path("expires_in").asLong(0), res.has("refresh_token_expires_in") ? now + res.get("refresh_token_expires_in").asLong() : tokens.refreshExpiresAt(),
                res.path("scope").asText(tokens.scopes()));
        store(user, refreshed);
        return refreshed.accessToken();
    }
}
