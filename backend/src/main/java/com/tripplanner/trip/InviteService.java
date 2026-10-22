package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import com.tripplanner.trip.TripDtos.InvitePreview;
import com.tripplanner.trip.TripDtos.InviteResponse;
import com.tripplanner.trip.TripDtos.TripResponse;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
@Transactional
public class InviteService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TripService tripService;
    private final TripInviteRepository invites;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final AppProperties props;

    public InviteService(TripService tripService, TripInviteRepository invites, TripMemberRepository members,
                         UserRepository users, AppProperties props) {
        this.tripService = tripService;
        this.invites = invites;
        this.members = members;
        this.users = users;
        this.props = props;
    }

    /** 초대 링크 생성: 소유자만, 역할은 EDITOR 또는 VIEWER */
    public InviteResponse create(Long tripId, MemberRole role, Long userId) {
        TripMember m = tripService.membership(tripId, userId);
        if (m.getRole() != MemberRole.OWNER) throw ApiException.forbidden("초대 링크는 만든 사람만 만들 수 있습니다.");
        if (role == MemberRole.OWNER) throw ApiException.badRequest("초대 권한은 EDITOR 또는 VIEWER만 가능합니다.");

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(Duration.ofDays(props.inviteTtlDays()));
        invites.save(new TripInvite(m.getTrip(), token, role, m.getUser(), expires));
        return new InviteResponse(token, role, props.inviteLinkBase() + token, expires);
    }

    @Transactional(readOnly = true)
    public InvitePreview preview(String token, Long userId) {
        TripInvite inv = find(token);
        Trip t = inv.getTrip();
        boolean already = members.findByTripIdAndUserId(t.getId(), userId).isPresent();
        return new InvitePreview(t.getId(), t.getTitle(), t.getDestination(), inv.getRole(),
                inv.getCreatedBy().getName(), inv.isExpired(), already);
    }

    /** 초대 수락. 이미 구성원이면 기존 권한 유지 */
    public TripResponse join(String token, Long userId) {
        TripInvite inv = find(token);
        if (inv.isExpired()) {
            throw new ApiException(HttpStatus.GONE, "INVITE_EXPIRED", "만료된 초대 링크입니다. 새 링크를 요청해 주세요.");
        }
        Trip t = tripService.lock(inv.getTrip().getId());
        Optional<TripMember> existing = members.findByTripIdAndUserId(t.getId(), userId);
        if (existing.isEmpty()) {
            User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
            t.addMember(user, inv.getRole());
            t.touch();
        }
        members.flush();
        return tripService.get(t.getId(), userId);
    }

    private TripInvite find(String token) {
        return invites.findByToken(token).orElseThrow(() -> ApiException.notFound("초대 링크"));
    }
}
