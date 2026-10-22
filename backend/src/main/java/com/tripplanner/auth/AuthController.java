package com.tripplanner.auth;

import com.tripplanner.auth.AuthDtos.DevLoginRequest;
import com.tripplanner.auth.AuthDtos.KakaoExchangeRequest;
import com.tripplanner.auth.AuthDtos.TokenResponse;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 카카오 로그인 흐름 (브라우저 + 서버 코드 교환, 네이티브 SDK 불필요 → Expo Go에서도 동작)
 * 1) 앱: 브라우저로 GET /api/auth/kakao/start?redirect=<앱 주소> 열기
 * 2) 서버: 카카오 로그인 페이지로 이동
 * 3) 카카오 → GET /api/auth/kakao/callback?code&state
 * 4) 서버: 카카오 토큰 교환·사용자 확인 후 <앱 주소>?code=<1회용 코드> 로 이동
 * 5) 앱: POST /api/auth/kakao/exchange {code} → 앱용 JWT
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String CALLBACK_PATH = "/api/auth/kakao/callback";

    private final AuthService auth;
    private final KakaoOAuthClient kakao;
    private final KakaoLoginStore store;
    private final AppProperties props;

    public AuthController(AuthService auth, KakaoOAuthClient kakao, KakaoLoginStore store, AppProperties props) {
        this.auth = auth;
        this.kakao = kakao;
        this.store = store;
        this.props = props;
    }

    @GetMapping("/kakao/start")
    public ResponseEntity<Void> kakaoStart(@RequestParam String redirect, @RequestParam(defaultValue = "false") boolean calendar, HttpServletRequest request) {
        if (!props.kakao().enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_NOT_CONFIGURED",
                    "서버에 KAKAO_REST_API_KEY가 설정되지 않았습니다.");
        }
        if (!isAllowedRedirect(redirect)) {
            throw ApiException.badRequest("허용되지 않은 앱 주소입니다: " + redirect);
        }
        String state = store.newState(redirect);
        return found(kakao.authorizeUrl(callbackUrl(request), state, calendar));
    }

    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error,
                                              HttpServletRequest request) {
        String appRedirect = store.consumeState(state);
        if (appRedirect == null) {
            throw ApiException.badRequest("로그인 요청이 만료되었습니다. 앱에서 다시 시도해 주세요.");
        }
        if (error != null || code == null) {
            // 사용자가 동의 화면에서 취소한 경우 error=access_denied
            return found(withParam(appRedirect, "error", error != null ? error : "no_code"));
        }
        try {
            TokenResponse token = auth.loginWithKakao(code, callbackUrl(request));
            return found(withParam(appRedirect, "code", store.issueCode(token)));
        } catch (ApiException e) {
            log.warn("카카오 로그인 실패: {}", e.getMessage());
            return found(withParam(appRedirect, "error", "login_failed"));
        }
    }

    @PostMapping("/kakao/exchange")
    public TokenResponse kakaoExchange(@Valid @RequestBody KakaoExchangeRequest req) {
        TokenResponse token = store.consumeCode(req.code());
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN_CODE", "로그인 코드가 만료되었습니다. 다시 로그인해 주세요.");
        }
        return token;
    }

    /** 개발/시연용 로그인 (카카오 설정 없이 테스트) */
    @PostMapping("/dev")
    public TokenResponse dev(@Valid @RequestBody DevLoginRequest req) {
        return auth.devLogin(req.name());
    }

    /** 카카오 콘솔에 등록한 Redirect URI와 정확히 같아야 함 */
    private String callbackUrl(HttpServletRequest request) {
        String base = props.publicBaseUrl();
        if (base != null && !base.isBlank()) return base.replaceAll("/$", "") + CALLBACK_PATH;
        return ServletUriComponentsBuilder.fromContextPath(request).path(CALLBACK_PATH).build().toUriString();
    }

    private boolean isAllowedRedirect(String redirect) {
        return props.kakao().allowedRedirects() != null && props.kakao().allowedRedirects().stream()
                .map(String::trim).filter(p -> !p.isEmpty()).anyMatch(redirect::startsWith);
    }

    private static String withParam(String url, String key, String value) {
        return UriComponentsBuilder.fromUriString(url).queryParam(key, value).build().toUriString();
    }

    private static ResponseEntity<Void> found(String location) {
        HttpHeaders h = new HttpHeaders();
        h.setLocation(URI.create(location));
        return new ResponseEntity<>(h, HttpStatus.FOUND);
    }
}
