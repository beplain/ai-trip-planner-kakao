package com.tripplanner.invite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import com.tripplanner.trip.TripInviteRepository;
import com.tripplanner.trip.DraftRooms;
import com.tripplanner.trip.MemberRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Public invitation landing page; joining still requires the authenticated API. */
@RestController
public class InviteShareController {
    private final TripInviteRepository invites;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final DraftRooms drafts;

    public InviteShareController(TripInviteRepository invites, AppProperties props, ObjectMapper mapper, DraftRooms drafts) {
        this.invites = invites;
        this.props = props;
        this.mapper = mapper;
        this.drafts = drafts;
    }

    @GetMapping(value = "/share/invite/{token}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String token,
                                      @RequestParam(required = false, defaultValue = "") String key,
                                      HttpServletRequest request) throws IOException {
        if (!token.matches("(?:draft_)?[A-Za-z0-9_-]{32}")) throw ApiException.notFound("초대 링크");
        var invite = token.startsWith("draft_") ? null : invites.findByToken(token).orElseThrow(() -> ApiException.notFound("초대 링크"));
        MemberRole invitationRole = invite == null ? drafts.invitationRole(token) : invite.getRole();
        if (invite != null && invite.isExpired()) {
            return html(HttpStatus.GONE, "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>초대 만료</title><h1>만료된 초대입니다.</h1><p>여행을 만든 사람에게 새 초대 링크를 요청해 주세요.</p></html>");
        }
        if (!key.isEmpty() && !key.matches("[a-fA-F0-9]{32}")) {
            throw ApiException.badRequest("카카오 JavaScript 키를 확인해 주세요.");
        }
        String base = props.publicBaseUrl();
        if (base == null || base.isBlank()) base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        String url = base.replaceAll("/$", "") + "/share/invite/" + token;
        String role = invitationRole == MemberRole.EDITOR ? "함께 편집" : "보기만";
        String config = json(Map.of("key", key, "url", url, "token", token,
                "deepLink", "tripplanner://invite/" + token, "role", role));
        String template = new ClassPathResource("templates/invite-share.html").getContentAsString(StandardCharsets.UTF_8);
        return html(HttpStatus.OK, template.replace("__INVITE_CONFIG__", config));
    }

    private String json(Object value) throws JsonProcessingException {
        return mapper.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "strict-origin-when-cross-origin").body(body);
    }
}
