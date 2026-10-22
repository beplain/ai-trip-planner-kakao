package com.tripplanner.user;

import com.tripplanner.auth.AuthDtos.UserResponse;
import com.tripplanner.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {
    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        User u = users.findById(CurrentUser.id(jwt)).orElseThrow(() -> ApiException.notFound("사용자"));
        return UserResponse.of(u);
    }
}
