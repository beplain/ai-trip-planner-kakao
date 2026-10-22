package com.tripplanner.auth;

import com.tripplanner.auth.AuthDtos.TokenResponse;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카카오 로그인 중간 상태 보관 (메모리).
 * - state: CSRF 방지 + 로그인 후 돌아갈 앱 주소 (10분)
 * - 1회용 코드: 앱 딥링크 URL에 JWT를 직접 싣지 않기 위해, 짧은 코드를 주고 앱이 POST로 교환 (2분, 1회)
 * 서버를 여러 대로 늘리면 Redis 등 공유 저장소로 바꿔야 한다.
 */
@Component
public class KakaoLoginStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration CODE_TTL = Duration.ofMinutes(2);

    private record Pending(String appRedirect, Instant expiresAt) {}
    private record Issued(TokenResponse token, Instant expiresAt) {}

    private final Map<String, Pending> states = new ConcurrentHashMap<>();
    private final Map<String, Issued> codes = new ConcurrentHashMap<>();

    public String newState(String appRedirect) {
        prune();
        String state = random();
        states.put(state, new Pending(appRedirect, Instant.now().plus(STATE_TTL)));
        return state;
    }

    /** state를 1회 소비하고 앱 주소 반환. 없거나 만료면 null */
    public String consumeState(String state) {
        if (state == null) return null;
        Pending p = states.remove(state);
        return p == null || Instant.now().isAfter(p.expiresAt()) ? null : p.appRedirect();
    }

    public String issueCode(TokenResponse token) {
        prune();
        String code = random();
        codes.put(code, new Issued(token, Instant.now().plus(CODE_TTL)));
        return code;
    }

    public TokenResponse consumeCode(String code) {
        if (code == null) return null;
        Issued i = codes.remove(code);
        return i == null || Instant.now().isAfter(i.expiresAt()) ? null : i.token();
    }

    private void prune() {
        Instant now = Instant.now();
        states.values().removeIf(p -> now.isAfter(p.expiresAt()));
        codes.values().removeIf(i -> now.isAfter(i.expiresAt()));
    }

    private static String random() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
