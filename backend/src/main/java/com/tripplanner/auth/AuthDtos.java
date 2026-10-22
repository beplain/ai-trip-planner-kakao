package com.tripplanner.auth;

import com.tripplanner.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    /** 카카오 로그인 후 앱이 받은 1회용 코드 */
    public record KakaoExchangeRequest(@NotBlank @Size(max = 100) String code) {}

    public record DevLoginRequest(@NotBlank @Size(max = 30) String name) {}

    public record UserResponse(Long id, String email, String name, boolean calendarConnected) {
        public static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.isKakaoCalendarConnected());
        }
    }

    public record TokenResponse(String accessToken, long expiresInSeconds, UserResponse user) {}
}
