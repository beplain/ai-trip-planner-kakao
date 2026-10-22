package com.tripplanner.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 로그인 REST API (인가 코드 방식).
 * 문서: https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api
 */
@Component
public class KakaoOAuthClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);
    private static final String AUTHORIZE = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN = "https://kauth.kakao.com/oauth/token";
    private static final String USER_ME = "https://kapi.kakao.com/v2/user/me";

    private final RestClient http;
    private final AppProperties props;

    public record Tokens(String accessToken, String refreshToken, long expiresAt, long refreshExpiresAt, String scopes) {}
    public record KakaoUser(String id, String nickname, String email, Tokens tokens) {}

    public KakaoOAuthClient(RestClient externalRestClient, AppProperties props) {
        this.http = externalRestClient;
        this.props = props;
    }

    public String authorizeUrl(String redirectUri, String state) {
        return authorizeUrl(redirectUri, state, false);
    }

    public String authorizeUrl(String redirectUri, String state, boolean calendar) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE)
                .queryParam("client_id", props.kakao().restApiKey())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .queryParamIfPresent("scope", calendar ? java.util.Optional.of("talk_calendar") : java.util.Optional.empty())
                .encode()
                .build()
                .toUriString();
    }

    /** 인가 코드 → 카카오 액세스 토큰 → 사용자 정보 */
    public KakaoUser fetchUser(String code, String redirectUri) {
        Tokens tokens = exchangeCode(code, redirectUri);
        String accessToken = tokens.accessToken();
        try {
            JsonNode me = http.get().uri(USER_ME)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            if (me == null || !me.has("id")) throw new IllegalStateException("empty user");
            JsonNode account = me.path("kakao_account");
            String nickname = account.path("profile").path("nickname").asText(null);
            if (nickname == null) nickname = me.path("properties").path("nickname").asText(null);
            String email = account.path("is_email_verified").asBoolean(false) ? account.path("email").asText(null) : null;
            return new KakaoUser(me.path("id").asText(), nickname != null ? nickname : "카카오 사용자", email, tokens);
        } catch (RestClientResponseException e) {
            log.warn("카카오 사용자 정보 조회 실패 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw failed();
        } catch (RestClientException | IllegalStateException e) {
            log.warn("카카오 사용자 정보 조회 실패: {}", e.getMessage());
            throw failed();
        }
    }

    private Tokens exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.kakao().restApiKey());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        String secret = props.kakao().clientSecret();
        if (secret != null && !secret.isBlank()) form.add("client_secret", secret);
        try {
            JsonNode res = http.post().uri(TOKEN)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String token = res == null ? null : res.path("access_token").asText(null);
            if (token == null) throw new IllegalStateException("no access_token");
            long now = java.time.Instant.now().getEpochSecond();
            return new Tokens(token, res.path("refresh_token").asText(""), now + res.path("expires_in").asLong(0),
                    now + res.path("refresh_token_expires_in").asLong(0), res.path("scope").asText(""));
        } catch (RestClientResponseException e) {
            // KOE 오류코드(예: KOE006 Redirect URI 불일치, KOE010 Client Secret 오류)가 본문에 포함됨
            log.warn("카카오 토큰 교환 실패 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw failed();
        } catch (RestClientException | IllegalStateException e) {
            log.warn("카카오 토큰 교환 실패: {}", e.getMessage());
            throw failed();
        }
    }

    private static ApiException failed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "KAKAO_LOGIN_FAILED",
                "카카오 로그인에 실패했습니다. 서버 로그의 KOE 오류코드를 확인해 주세요.");
    }
}
