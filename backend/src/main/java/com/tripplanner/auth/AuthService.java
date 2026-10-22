package com.tripplanner.auth;

import com.tripplanner.auth.AuthDtos.TokenResponse;
import com.tripplanner.auth.AuthDtos.UserResponse;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {
    private final KakaoOAuthClient kakao;
    private final UserRepository users;
    private final JwtEncoder encoder;
    private final AppProperties props;
    private final KakaoTokenVault tokenVault;

    public AuthService(KakaoOAuthClient kakao, UserRepository users, JwtEncoder encoder, AppProperties props, KakaoTokenVault tokenVault) {
        this.kakao = kakao;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.tokenVault = tokenVault;
    }

    /** 카카오 인가 코드로 사용자 확인 → 회원 생성/갱신 → 앱용 JWT */
    @Transactional
    public TokenResponse loginWithKakao(String kakaoCode, String redirectUri) {
        KakaoOAuthClient.KakaoUser k = kakao.fetchUser(kakaoCode, redirectUri);
        String subject = "kakao:" + k.id();
        User user = users.findByAuthSubject(subject)
                .map(u -> { u.updateProfile(k.email(), k.nickname()); return u; })
                .orElseGet(() -> users.save(new User(subject, k.email(), k.nickname())));
        tokenVault.store(user, k.tokens());
        return issue(user);
    }

    /** 개발용: 이름만으로 로그인 (app.dev-login-enabled=true일 때만) */
    @Transactional
    public TokenResponse devLogin(String name) {
        if (!props.devLoginEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "개발용 로그인이 비활성화되어 있습니다.");
        }
        String subject = "dev:" + name.trim();
        User user = users.findByAuthSubject(subject)
                .orElseGet(() -> users.save(new User(subject, null, name.trim())));
        return issue(user);
    }

    private TokenResponse issue(User user) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofDays(props.jwt().ttlDays());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("trip-planner")
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("name", user.getName())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, ttl.toSeconds(), UserResponse.of(user));
    }
}
