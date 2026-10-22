package com.tripplanner.invite;

import com.tripplanner.trip.InviteService;
import com.tripplanner.trip.DraftRooms;
import com.tripplanner.trip.TripDtos.InvitePreview;
import com.tripplanner.user.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invites")
public class InviteController {
    private final InviteService invites;
    private final DraftRooms drafts;

    public InviteController(InviteService invites, DraftRooms drafts) {
        this.invites = invites;
        this.drafts = drafts;
    }

    /** 초대 미리보기 (어떤 여행인지, 만료 여부) */
    @GetMapping("/{token}")
    public InvitePreview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return token.startsWith("draft_") ? drafts.preview(token, CurrentUser.id(jwt)) : invites.preview(token, CurrentUser.id(jwt));
    }

    @PostMapping("/{token}/join")
    public Object join(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return token.startsWith("draft_") ? drafts.join(token, CurrentUser.id(jwt)) : invites.join(token, CurrentUser.id(jwt));
    }
}
