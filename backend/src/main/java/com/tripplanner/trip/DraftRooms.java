package com.tripplanner.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    public record RoomSummary(String id, String title, String destination, long companionCount) {}
    public record Update(@NotNull Long version, @NotNull @Valid TripDraft draft) {}
    public record Save(@NotNull Long version) {}
    public record Joined(String draftId, Long savedTripId) {}
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GoogleHoursScheduler editHours;
    private final UserRepository users;
    private final TripService trips;
    private final DraftDocumentRepository rooms;
    private final DraftInvitationRepository invitations;
    private final ObjectMapper json;
    private final DraftEditLeaseRepository editLeases;
    public DraftRooms(UserRepository users, TripService trips, DraftDocumentRepository rooms,
                      DraftInvitationRepository invitations, ObjectMapper json) {
        this(users,trips,rooms,invitations,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DraftRooms(UserRepository users, TripService trips, DraftDocumentRepository rooms,
                      DraftInvitationRepository invitations, ObjectMapper json,DraftEditLeaseRepository editLeases) {
        this.users = users; this.trips = trips; this.rooms = rooms; this.invitations = invitations; this.json = json;
        this.editLeases=editLeases;
    }
    public RoomResponse create(TripDraft draft, Long user) {
        DraftVariants.validate(draft);
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
    public List<RoomSummary> summaries(Long user) {
        Map<String, Long> visible = new LinkedHashMap<>();
        for (var row : rooms.findActiveMemberships()) {
            List<MemberResponse> members = decode(row.getParticipants(), new TypeReference<>() {});
            if (members.stream().anyMatch(member -> user.equals(member.userId()))) {
                visible.put(row.getId(), members.stream().filter(member -> member.role() != MemberRole.OWNER).count());
            }
        }
        if (visible.isEmpty()) return List.of();
        Map<String, RoomSummary> summaries = new HashMap<>();
        for (var row : rooms.findActiveContents(List.copyOf(visible.keySet()))) {
            summaries.put(row.getId(), summarize(row.getId(), row.getContent(), visible.get(row.getId())));
        }
        return visible.keySet().stream().map(summaries::get).filter(Objects::nonNull).toList();
    }

    private RoomSummary summarize(String id, String content, long companionCount) {
        try (var parser = json.getFactory().createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalStateException("초안 형식 오류");
            String title = null;
            String destination = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("title".equals(field)) title = parser.getValueAsString();
                else if ("destination".equals(field)) destination = parser.getValueAsString();
                else parser.skipChildren();
                if (title != null && destination != null) return new RoomSummary(id, title, destination, companionCount);
            }
            throw new IllegalStateException("초안 제목 또는 목적지 누락");
        } catch (IOException e) {
            throw new IllegalStateException("초안 요약 읽기 실패", e);
        }
    }

    @Transactional(readOnly = true)
    public RoomResponse get(String id, Long user) { var r = room(id, false); requireMember(r, user); return response(r, user); }
    public RoomResponse update(String id, Update update, Long user) {
        var r = room(id, true); requireEditor(r, user); check(r, update.version());
        var before = content(r); var after = update.draft();
        if (!Objects.equals(before.title(), after.title()) || !Objects.equals(before.destination(), after.destination())
                || !Objects.equals(before.startDate(), after.startDate()) || !Objects.equals(before.endDate(), after.endDate())
                || !Objects.equals(before.budget(), after.budget()) || !Objects.equals(before.styles(), after.styles())
                || !Objects.equals(before.request(), after.request()) || !Objects.equals(before.travelDetails(), after.travelDetails())
                || !Objects.equals(before.selectedOption(), after.selectedOption()))
            throw ApiException.badRequest("여행 정보는 '여행 정보 수정'에서 변경해 주세요.");
        TravelSchedule.validateDraft(after);
        if(editHours!=null) {
            var session=editHours.session();
            var edited=after;
            var days=edited.days().stream().map(day->new DayDraft(day.dayNumber(),day.date(),day.places().stream().map(p->{
                var previous=before.days().stream().filter(d->Objects.equals(d.date(),day.date()))
                    .flatMap(d->d.places().stream()).filter(old->Objects.equals(old.placeId(),p.placeId())
                        && Objects.equals(old.name(),p.name()) && Objects.equals(old.address(),p.address())
                        && Objects.equals(old.visitTime(),p.visitTime())).findFirst();
                return previous.isPresent()?p.withBusinessHours(previous.get().businessHours()):session.checkEdited(p,
                    day.date()!=null?day.date():edited.startDate()==null?null:edited.startDate().plusDays(day.dayNumber()-1));
            }).toList())).toList();
            after=DraftVariants.withDays(after,days,after.warnings(),after.options(),after.selectedOption());
        }
        var updated = DraftVariants.updateCurrent(before, after.days(), after.warnings());
        for(var day:before.days())for(var place:day.places()) {
            if(place.fixedEventId()!=null)continue;
            boolean remains=updated.days().stream().flatMap(d->d.places().stream()).anyMatch(p->Objects.equals(p.placeId(),place.placeId())
                    && Objects.equals(p.name(),place.name()) && Objects.equals(p.address(),place.address()));
            if(!remains)r.deletedPlaces(RecoveryArchive.add(r.deletedPlaces(),day.dayNumber(),before.selectedOption(),RecoveryArchive.context(before),place));
        }
        r.edit(encode(updated)); rooms.flush(); return response(r, user);
    }
    public RoomResponse rename(String id, RenameDraftRequest request, Long user) {
        var r = room(id, true); requireEditor(r, user); check(r, request.version());
        var before = content(r);
        var renamed = new TripDraft(request.title().trim(), before.destination(), before.startDate(), before.endDate(),
                before.budget(), before.styles(), before.days(), before.source(), before.warnings(), before.request(), before.travelDetails(), before.options(), before.selectedOption(), before.interpretedRequest());
        r.edit(encode(renamed)); rooms.flush(); return response(r, user);
    }
    public RoomResponse switchOption(String id, SwitchDraftOptionRequest request, Long user) {
        var r = room(id,true); requireEditor(r,user); check(r,request.version());
        var switched = DraftVariants.switchTo(content(r),request.optionId());
        r.edit(encode(switched)); rooms.flush(); return response(r,user);
    }
    public void delete(String id, Long version, Long user) {
        var r = room(id, true); requireMember(r, user);
        if (!r.getOwnerId().equals(user)) throw ApiException.forbidden("초안 삭제는 여행을 만든 사람만 할 수 있습니다.");
        DraftEditLocks.guard(r,user,editLeases);
        check(r, version);
        r.deletedAt(Instant.now());
        if(editLeases!=null)editLeases.deleteById(id);
        rooms.flush();
    }
    @Transactional(readOnly=true)
    public List<RecoveryArchive.TrashItem> trash(Long user) {
        return rooms.findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user).stream().filter(r->!RecoveryArchive.expired(r.getDeletedAt()))
                .map(r->{var d=content(r);return new RecoveryArchive.TrashItem(r.getId(),d.title(),d.destination(),r.getDeletedAt(),r.getDeletedAt().plus(RecoveryArchive.RETENTION));}).toList();
    }
    private DraftDocument trashed(String id,Long user) {
        var r=rooms.lockById(id).orElseThrow(()->ApiException.notFound("삭제한 초안"));
        if(!r.getOwnerId().equals(user)||r.getDeletedAt()==null||RecoveryArchive.expired(r.getDeletedAt()))throw ApiException.notFound("삭제한 초안");
        return r;
    }
    public RoomResponse restore(String id,Long user) {
        var r=trashed(id,user);
        if(rooms.countByOwnerIdAndSavedTripIdIsNull(user)>=50)throw ApiException.badRequest("편집 중인 초안은 최대 50개입니다. 다른 초안을 정리한 후 복원해 주세요.");
        r.deletedAt(null);rooms.flush();return response(r,user);
    }
    public void permanentlyDelete(String id,Long user) {
        var r=trashed(id,user);invitations.deleteByDocument_Id(id);invitations.flush();rooms.delete(r);rooms.flush();
    }
    @Transactional(readOnly=true)
    public List<RecoveryArchive.DeletedPlace> deletedPlaces(String id,Long user) {
        var r=room(id,false);requireMember(r,user);return RecoveryArchive.read(r.deletedPlaces());
    }
    public RoomResponse restorePlace(String id,String entryId,RecoveryArchive.RestorePlace req,Long user) {
        var r=room(id,true);requireEditor(r,user);check(r,req.version());var before=content(r);
        var row=RecoveryArchive.find(r.deletedPlaces(),entryId);
        if(!Objects.equals(row.optionId(),before.selectedOption()))throw ApiException.badRequest("삭제했던 초안 유형으로 전환한 후 복원해 주세요.");
        if(!Objects.equals(row.context(),RecoveryArchive.context(before)))throw ApiException.badRequest("여행 조건이 변경되었습니다. 삭제 당시의 여행 조건으로 돌아간 후 복원해 주세요.");
        var day=before.days().stream().filter(d->d.dayNumber()==row.dayNumber()).findFirst().orElseThrow(()->ApiException.badRequest("원래 일차를 찾을 수 없습니다."));
        var place=RecoveryArchive.at(row,req.visitTime());RecoveryArchive.checkSpace(day.places(),place,before.travelDetails());
        if(editHours!=null)place=editHours.session().checkEdited(place,day.date()!=null?day.date():before.startDate()==null?null:before.startDate().plusDays(day.dayNumber()-1));
        var places=new ArrayList<>(day.places());places.add(place);places.sort(Comparator.comparing(PlaceDraft::visitTime));
        var days=before.days().stream().map(d->d.dayNumber()==day.dayNumber()?new DayDraft(d.dayNumber(),d.date(),places):d).toList();
        var restored=DraftVariants.updateCurrent(before,days,before.warnings());
        try {TravelSchedule.validateDraft(restored);}catch(ApiException e){throw ApiException.badRequest("복원할 시간이 고정 일정이나 다른 방문과 겹칩니다. 복원 시각을 조정해 주세요.");}
        r.edit(encode(restored));r.deletedPlaces(RecoveryArchive.remove(r.deletedPlaces(),entryId));rooms.flush();return response(r,user);
    }
    @Transactional(readOnly = true)
    public void checkRegeneration(String id, Long version, Long user) {
        var r = room(id, false); requireEditor(r, user); check(r, version);
    }
    public RoomResponse replaceGenerated(String id, Long version, TripDraft generated, Long user) {
        var r = room(id, true); requireEditor(r, user); check(r, version);
        DraftVariants.validate(generated);
        return replaceAndArchive(r, content(r), generated, history(r), user);
    }

    public Optional<RoomResponse> restoreGenerated(String id, RegenerateDraftRequest request, Long user) {
        var room = room(id, true);
        requireEditor(room, user);
        check(room, request.version());
        var current = content(room);
        var target = conditions(request.input());
        var history = history(room);
        for (var saved : List.copyOf(history)) {
            if (!conditions(saved).equals(target)) continue;
            history.remove(saved);
            var restored = new TripDraft(request.title().trim(), saved.destination(), saved.startDate(), saved.endDate(),
                    saved.budget(), saved.styles(), saved.days(), saved.source(), saved.warnings(), saved.request(),
                    saved.travelDetails(), saved.options(), saved.selectedOption(), saved.interpretedRequest());
            return Optional.of(replaceAndArchive(room, current, restored, history, user));
        }
        return Optional.empty();
    }

    private RoomResponse replaceAndArchive(DraftDocument room, TripDraft previous, TripDraft replacement,
                                           List<TripDraft> history, Long user) {
        var previousConditions = conditions(previous);
        history.removeIf(saved -> conditions(saved).equals(previousConditions));
        history.add(0, previous);
        room.replaceGenerated(encode(replacement), encode(history.stream().limit(10).toList()));
        rooms.flush();
        return response(room, user);
    }

    private List<TripDraft> history(DraftDocument room) {
        return room.regenerationHistory() == null ? new ArrayList<>()
                : new ArrayList<>(decode(room.regenerationHistory(), new TypeReference<List<TripDraft>>() {}));
    }

    private record Conditions(String destination, java.time.LocalDate startDate, int days, String budget,
                              Set<String> styles, String request, JsonNode travelDetails) {}

    private Conditions conditions(TripDraft draft) {
        return conditions(new GenerateRequest(draft.destination(), draft.startDate(), draft.days().size(),
                draft.budget(), draft.styles(), draft.request(), draft.travelDetails()));
    }

    private Conditions conditions(GenerateRequest input) {
        JsonNode details = json.valueToTree(input.travelDetails());
        if (details.path("transport") instanceof ObjectNode transport) {
            // Auto-calculated arrival values can change between drafts with identical user inputs.
            if (transport.path("arrivalAuto").asBoolean()) transport.remove("arrivalTime");
            if (transport.path("returnArrivalAuto").asBoolean(true)) transport.remove("returnArrivalTime");
        }
        return new Conditions(input.destination().trim(), input.startDate(), input.days(),
                Objects.toString(input.budget(), "").trim(), input.styles() == null ? Set.of() : Set.copyOf(input.styles()),
                Objects.toString(input.request(), "").trim(), details);
    }
    public TripResponse save(String id, Long version, Long user) {
        var r = room(id, true); requireEditor(r, user);
        if (r.getSavedTripId() != null) return trips.get(r.getSavedTripId(), user);
        check(r, version); var draft = content(r);
        if (draft.days().stream().allMatch(d -> d.places().isEmpty())) throw ApiException.badRequest("최소 한 곳 이상 남겨 주세요.");
        var saved = trips.createCollaborative(draft, r.getOwnerId(), participants(r), user);
        trips.importDeletedPlaces(saved.id(),RecoveryArchive.read(r.deletedPlaces()).stream()
                .filter(e->Objects.equals(e.optionId(),draft.selectedOption()) && Objects.equals(e.context(),RecoveryArchive.context(draft))).toList());
        // Trip creation and finalization commit atomically under a DB row lock.
        r.finish(saved.id());
        if(editLeases!=null)editLeases.deleteById(id);
        rooms.flush(); return trips.get(saved.id(),user);
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
            DraftEditLocks.guardJoining(r,editLeases);
            if (r.getSavedTripId() != null) trips.addDraftMember(r.getSavedTripId(), r.getOwnerId(), user, inv.getRole());
            people.add(member(user, inv.getRole())); r.participants(encode(people)); rooms.flush();
        }
        return new Joined(r.getId(), r.getSavedTripId());
    }
    private MemberResponse member(Long user, MemberRole role) {
        var u = users.findById(user).orElseThrow(() -> ApiException.notFound("사용자"));
        return new MemberResponse(u.getId(), u.getName(), u.getEmail(), role);
    }
    private DraftInvitation invitation(String token) { return invitations.findById(token).filter(i->i.getDocument().getDeletedAt()==null).orElseThrow(() -> ApiException.notFound("초대")); }
    private void checkExpiry(DraftInvitation inv) {
        if (inv.isExpired()) throw new ApiException(HttpStatus.GONE, "INVITE_EXPIRED", "만료된 초대입니다. 새 링크를 요청해 주세요.");
    }
    private DraftDocument room(String id, boolean lock) {
        return (lock ? rooms.lockById(id) : rooms.findById(id)).filter(r->r.getDeletedAt()==null).orElseThrow(() -> ApiException.notFound("초안"));
    }
    private MemberResponse requireMember(DraftDocument r, Long user) {
        return participants(r).stream().filter(m -> m.userId().equals(user)).findFirst().orElseThrow(() -> ApiException.notFound("초안"));
    }
    private void requireEditor(DraftDocument r, Long user) {
        if (!requireMember(r, user).role().canEdit()) throw ApiException.forbidden("보기 권한만 있어 수정·저장할 수 없습니다.");
        DraftEditLocks.guard(r,user,editLeases);
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
                                p.note(), p.category(), p.verified(), p.placeUrl(), p.phone(), p.fixedEventId()).withBusinessHours(p.businessHours()).withDurationMinutes(p.durationMinutes())).toList())).toList(), null, List.of(), null, t.travelDetails());
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
