package com.tripplanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Kakao kakao,
        OpenAi openai,
        boolean devLoginEnabled,
        String inviteLinkBase,
        int inviteTtlDays,
        /** 외부에서 접근 가능한 서버 주소 (예: https://xxxx.ngrok-free.app). 카카오 Redirect URI 계산에 사용 */
        String publicBaseUrl
) {
    public record Jwt(String secret, int ttlDays) {}

    /**
     * @param restApiKey       카카오 REST API 키 (로그인 토큰 교환 + 로컬 장소 검색) — 서버 전용
     * @param clientSecret     카카오 로그인 Client Secret (콘솔에서 사용 설정한 경우만)
     * @param allowedRedirects 로그인 후 앱으로 돌아갈 주소로 허용할 접두사 (예: tripplanner://, exp://)
     */
    public record Kakao(String restApiKey, String clientSecret, List<String> allowedRedirects) {
        public boolean enabled() {
            return restApiKey != null && !restApiKey.isBlank();
        }
    }

    public record OpenAi(String apiKey, String model, String baseUrl) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
