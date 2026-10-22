package com.tripplanner.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Drafts, membership and invitations survive process restarts in the database. */
@Service @Transactional
public class DraftRooms {
    public record RoomResponse(String id, long version, TripDraft draft, List<MemberResponse> members,
                               MemberRole myRole, Long savedTripId, Instant expiresAt) {}
    public record Update(@NotNull Long version, @NotNull @Valid TripDraft draft) {}
    public record Save(@NotNull Long version) {}
    public record Joined(String draftId, Long savedTripId) {}
    private final UserRepository users;
    private final TripService trips;
    private final DraftDocumentRepository rooms;
    private final DraftInvitationRepository invitations;
    private final ObjectMapper json;
    public DraftRooms(UserRepository users, TripService trips, DraftDocumentRepository rooms,
                      DraftInvitationRepository invitations, ObjectMapper json) {
        this.users = users; this.trips = trips; this.rooms = rooms; this.invitations = invitations; this.json = json;
    }
    public RoomResponse create(TripDraft draft, Long user) {
        if (rooms.countByOwnerIdAndSavedTripIdIsNull(user) >= 50) throw ApiException.badRequest("편집 중인 초안은 최대 50개까지 만들 수 있습니다.");
        var r = rooms.saveAndFlush(new DraftDocument(user, encode(draft), encode(List.of(member(user, MemberRole.OWNER)))));
        return response(r, user);
    }
    @Transactional(readOnly = true)
    public List<RoomResponse> list(Long user) {
        return rooms.findBySavedTripIdIsNullOrderByUpdatedAtDesc().stream()
                .filter(r -> participants(r).stream().anyMatch(m -> m.userId().equals(user)))
                .map(r -> response(r, user)).toList();
    }
    @Transactional(readOnly = true)
    public RoomResponse get(String id, Long user) { var r = room(id, false); requireMember(r, user); return response(r, user); }
    public RoomResponse update(String id, Update update, Long user) {
        var r = room(id, true); requireEditor(r, user); check(r, update.version());
        var before = content(r); var after = update.draft();
        if (!Objects.equals(before.title(), after.title()) || !Objects.equals(before.destination(), after.destination())
                || !Objects.equals(before.startDate(), after.startDate()) || !Objects.equals(before.endDate(), after.endDate())
                || !Objects.equals(before.budget(), after.budget()) || !Objects.equals(before.styles(), after.styles())
                || !Objects.equals(before.request(), after.request()))
            throw ApiException.badRequest("여행 정보는 '여행 정보 수정 · AI 다시 생성'에서 변경해 주세요.");
        r.edit(encode(update.draft())); rooms.flush(); return response(r, user);
    }
    @Transactional(readOnly = true)
    public void checkRegeneration(String id, Long version, Long user) {
        var r = room(id, false); requireEditor(r, user); check(r, version);
    }
    public RoomResponse replaceGenerated(String id, Long version, TripDraft generated, Long user) {
        var r = room(id, true); requireEditor(r, user); check(r, version);
        r.edit(encode(generated)); rooms.flush(); return response(r, user);
    }
    public TripResponse save(String id, Long version, Long user) {
        var r = room(id, true); requireEditor(r, user);
        if (r.getSavedTripId() != null) return trips.get(r.getSavedTripId(), user);
        check(r, version); var draft = content(r);
        if (draft.days().stream().allMatch(d -> d.places().isEmpty())) throw ApiException.badRequest("최소 한 곳 이상 남겨 주세요.");
        var saved = trips.createCollaborative(draft, r.getOwnerId(), participants(r), user);
        // Trip creation and finalization commit atomically under a DB row lock.
        r.finish(saved.id()); rooms.flush(); return saved;
    }
    public InviteResponse invite(String id, MemberRole role, Long user) {
        var r = room(id, true); requireMember(r, user);
        if (!r.getOwnerId().equals(user)) throw ApiException.forbidden("초대는 여행을 만든 사람만 할 수 있습니다.");
        if (role == null || role == MemberRole.OWNER) throw ApiException.badRequest("초대 권한을 확인해 주세요.");
        if (r.getSavedTripId() != null) throw ApiException.badRequest("확정된 여행 화면에서 새 초대를 만들어 주세요.");
        String token = "draft_" + UUID.randomUUID().toString().replace("-", "");
        Instant expires = Instant.now().plus(Duration.ofDays(7));
        invitations.save(new DraftInvitation(token, r, role, expires));
        return new InviteResponse(token, role, "tripplanner://invite/" + token, expires);
    }
    @Transactional(readOnly = true)
    public MemberRole invitationRole(String token) { var inv = invitation(token); checkExpiry(inv); return inv.getRole(); }
    @Transactional(readOnly = true)
    public InvitePreview preview(String token, Long user) {
        var inv = invitation(token); var r = inv.getDocument(); var people = participants(r);
        String ownerName = people.stream().filter(m -> m.userId().equals(r.getOwnerId())).findFirst().orElseThrow().name();
        if (r.getSavedTripId() != null) {
            var trip = trips.get(r.getSavedTripId(), r.getOwnerId());
            return new InvitePreview(trip.id(), trip.title(), trip.destination(), inv.getRole(), ownerName,
                    inv.isExpired(), trip.members().stream().anyMatch(m -> m.userId().equals(user)), null);
        }
        var draft = content(r);
        return new InvitePreview(0L, draft.title(), draft.destination(), inv.getRole(), ownerName,
                inv.isExpired(), people.stream().anyMatch(m -> m.userId().equals(user)), r.getId());
    }
    public Joined join(String token, Long user) {
        var inv = invitation(token); checkExpiry(inv);
        var r = room(inv.getDocument().getId(), true);
        var people = new ArrayList<>(participants(r));
        if (people.stream().noneMatch(m -> m.userId().equals(user))) {
            if (r.getSavedTripId() != null) trips.addDraftMember(r.getSavedTripId(), r.getOwnerId(), user, inv.getRole());
            people.add(member(user, inv.getRole())); r.participants(encode(people)); rooms.flush();
        }
        return new Joined(r.getId(), r.getSavedTripId());
    }
    private MemberResponse member(Long user, MemberRole role) {
        var u = users.findById(user).orElseThrow(() -> ApiException.notFound("사용자"));
        return new MemberResponse(u.getId(), u.getName(), u.getEmail(), role);
    }
    private DraftInvitation invitation(String token) { return invitations.findById(token).orElseThrow(() -> ApiException.notFound("초대")); }
    private void checkExpiry(DraftInvitation inv) {
        if (inv.isExpired()) throw new ApiException(HttpStatus.GONE, "INVITE_EXPIRED", "만료된 초대입니다. 새 링크를 요청해 주세요.");
    }
    private DraftDocument room(String id, boolean lock) {
        return (lock ? rooms.lockById(id) : rooms.findById(id)).orElseThrow(() -> ApiException.notFound("초안"));
    }
    private MemberResponse requireMember(DraftDocument r, Long user) {
        return participants(r).stream().filter(m -> m.userId().equals(user)).findFirst().orElseThrow(() -> ApiException.notFound("초안"));
    }
    private void requireEditor(DraftDocument r, Long user) {
        if (!requireMember(r, user).role().canEdit()) throw ApiException.forbidden("보기 권한만 있어 수정·저장할 수 없습니다.");
    }
    private void check(DraftDocument r, Long version) {
        if (r.getSavedTripId() != null) throw ApiException.badRequest("확정된 여행입니다. 여행 화면에서 수정해 주세요.");
        if (!Objects.equals(r.getVersion(), version)) throw ApiException.conflict();
    }
    private RoomResponse response(DraftDocument r, Long user) {
        var role = requireMember(r, user).role();
        if (r.getSavedTripId() != null) {
            var t = trips.get(r.getSavedTripId(), user);
            return new RoomResponse(r.getId(), r.getVersion(), asDraft(t), t.members(), t.myRole(), t.id(), null);
        }
        return new RoomResponse(r.getId(), r.getVersion(), content(r), participants(r), role, null, null);
    }
    private TripDraft asDraft(TripResponse t) {
        return new TripDraft(t.title(), t.destination(), t.startDate(), t.endDate(), t.budget(), t.styles(),
                t.days().stream().map(d -> new DayDraft(d.dayNumber(), d.date(), d.places().stream().map(p ->
                        new PlaceDraft(p.placeId(), p.name(), p.address(), p.latitude(), p.longitude(), p.visitTime(), p.reason(),
                                p.note(), p.category(), p.verified(), p.placeUrl(), p.phone())).toList())).toList(), null, List.of());
    }
    private TripDraft content(DraftDocument r) { return decode(r.getContent(), new TypeReference<TripDraft>() {}); }
    private List<MemberResponse> participants(DraftDocument r) { return decode(r.getParticipants(), new TypeReference<List<MemberResponse>>() {}); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("초안 저장 실패", e); }
    }
    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("초안 읽기 실패", e); }
    }
}
