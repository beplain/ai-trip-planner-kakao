package com.tripplanner.user;

import org.springframework.security.oauth2.jwt.Jwt;

/** 컨트롤러에서 @AuthenticationPrincipal Jwt → 사용자 id 변환 */
public final class CurrentUser {
    private CurrentUser() {}

    public static Long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
