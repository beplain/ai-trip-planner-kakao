package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class TripService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DraftEditLeaseRepository planLeases;
    public record PlanUpdate(@jakarta.validation.constraints.NotNull Long version,
        @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max=7) List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid DayDraft> days) {}

    public TripResponse savePlan(Long id,PlanUpdate request,Long user) {
        var m=requireEditor(id,user);var trip=m.getTrip();
        TripEditLocks.guard(id,user,planLeases,true);checkVersion(trip,request.version());
        if(request.days().size()!=trip.getDays().size())throw ApiException.badRequest("여행 기간 변경은 새 초안으로 다시 계획해 주세요.");
        var seen=new HashSet<Integer>();
        for(var day:request.days()) {
            int previous=-1;
            for(var place:day.places()) {
                int time=TravelSchedule.minute(place.visitTime());
                if(time<=previous)throw ApiException.badRequest("방문 시각은 중복 없이 시간순으로 지정해 주세요.");
                previous=time;
            }
            if(!seen.add(day.dayNumber()))throw ApiException.badRequest("일차가 중복되었습니다.");
            var stored=trip.getDays().stream().filter(d->d.getDayNumber()==day.dayNumber()).findFirst().orElseThrow(()->ApiException.badRequest("일차가 다릅니다."));
            if(!Objects.equals(stored.getDate(),day.date()))throw ApiException.badRequest("여행 날짜를 변경할 수 없습니다.");
            var fixed=stored.getPlaces().stream().filter(p->p.getFixedEventId()!=null).map(RecoveryArchive::snapshot).toList();
            var nextFixed=day.places().stream().filter(p->p.fixedEventId()!=null).toList();
            if(!fixed.equals(nextFixed))throw ApiException.badRequest("숙소·교통 고정 일정은 새 초안에서 변경해 주세요.");
            for(var old:stored.getPlaces()) {
                if(old.getFixedEventId()!=null)continue;
                if(request.days().stream().flatMap(d->d.places().stream()).noneMatch(p->Objects.equals(p.placeId(),old.getPlaceId())&&Objects.equals(p.name(),old.getName())&&Objects.equals(p.address(),old.getAddress())))
                    trip.deletedPlaces(RecoveryArchive.add(trip.deletedPlaces(),day.dayNumber(),null,null,RecoveryArchive.snapshot(old)));
            }
            var fixedEntities=new HashMap<String,ItineraryPlace>();
            var snapshots=stored.getPlaces().stream().map(RecoveryArchive::snapshot).toList();
            stored.getPlaces().stream().filter(p->p.getFixedEventId()!=null).forEach(p->fixedEntities.put(p.getFixedEventId(),p));
            stored.getPlaces().removeIf(p->p.getFixedEventId()==null);
            int index=0;
            for(var p:day.places()) {
                var entity=p.fixedEventId()!=null?fixedEntities.get(p.fixedEventId()):stored.addPlace(toEntity(p));
                entity.setOrderIndex(index++);
                if(p.fixedEventId()==null){
                    var old=snapshots.stream().filter(o->Objects.equals(o.placeId(),p.placeId())&&Objects.equals(o.name(),p.name())&&Objects.equals(o.address(),p.address())&&Objects.equals(o.visitTime(),p.visitTime())).findFirst();
                    if(old.isPresent())entity.setBusinessHours(old.get().businessHours());else recheckHours(entity);
                }
            }
        }
        validateSchedule(trip);trip.touch();trips.flush();return TripMapper.trip(trip,m.getRole());
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GoogleHoursScheduler editHours;
    private final TripRepository trips;
    private final TripMemberRepository members;
    private final TripInviteRepository invites;
    private final UserRepository users;
    private final DraftDocumentRepository draftDocuments;
    private final DraftInvitationRepository draftInvitations;

    public TripService(TripRepository trips, TripMemberRepository members, TripInviteRepository invites,
                       UserRepository users) {
        this(trips,members,invites,users,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TripService(TripRepository trips,TripMemberRepository members,TripInviteRepository invites,UserRepository users,
                       DraftDocumentRepository draftDocuments,DraftInvitationRepository draftInvitations) {
        this.trips = trips;
        this.members = members;
        this.invites = invites;
        this.users = users;
        this.draftDocuments=draftDocuments;
        this.draftInvitations=draftInvitations;
    }

    // ───────── 조회 ─────────

    @Transactional(readOnly = true)
    public List<TripSummary> list(Long userId) {
        return trips.findAllForMember(userId).stream()
                .map(t -> TripMapper.summary(t, roleOf(t, userId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public TripResponse get(Long tripId, Long userId) {
        TripMember m = membership(tripId, userId);
        return TripMapper.trip(m.getTrip(), m.getRole());
    }

    /** 폴링용: 클라이언트가 가진 버전과 다를 때만 전체 일정을 내려줌 */
    @Transactional(readOnly = true)
    public ChangesResponse changes(Long tripId, Long userId, Long since) {
        TripMember m = membership(tripId, userId);
        Trip t = m.getTrip();
        boolean changed = since == null || !Objects.equals(t.getVersion(), since);
        return new ChangesResponse(changed, t.getVersion(), t.getUpdatedAt(),
                changed ? TripMapper.trip(t, m.getRole()) : null);
    }

    // ───────── 생성/수정 ─────────

    /** 사용자가 검토·수정한 AI 초안을 저장 */
    public TripResponse create(TripDraft draft, Long userId) {
        return createCollaborative(draft, userId, List.of(), userId);
    }

    public TripResponse createCollaborative(TripDraft draft, Long userId, List<MemberResponse> companions, Long actor) {
        TravelSchedule.validateDraft(draft);
        ExtraRequestRules.require(draft);
        User owner = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        int dayCount = draft.days().size();
        LocalDate start = draft.startDate();
        LocalDate end = start == null ? null : start.plusDays(dayCount - 1L);

        Trip trip = new Trip(owner, draft.title().trim(), draft.destination().trim(), start, end,
                blankToNull(draft.budget()), cleanStyles(draft.styles()));
        trip.setTravelDetails(draft.travelDetails());
        trip.addMember(owner, MemberRole.OWNER);
        for (MemberResponse companion : companions) {
            if (!companion.userId().equals(userId)) {
                User user = users.findById(companion.userId()).orElseThrow(() -> ApiException.notFound("동행자"));
                trip.addMember(user, companion.role() == MemberRole.OWNER ? MemberRole.VIEWER : companion.role());
            }
        }

        List<DayDraft> sortedDays = new ArrayList<>(draft.days());
        sortedDays.sort(Comparator.comparingInt(DayDraft::dayNumber));
        for (int i = 0; i < sortedDays.size(); i++) {
            int dayNumber = i + 1;
            TripDay day = trip.addDay(dayNumber, start == null ? null : start.plusDays(i));
            for (PlaceDraft p : sortedDays.get(i).places()) {
                day.addPlace(toEntity(p));
            }
        }
        Trip saved = trips.save(trip);
        trips.flush(); // version/id 확정
        return TripMapper.trip(saved, roleOf(saved, actor));
    }

    public void addDraftMember(Long tripId, Long ownerId, Long userId, MemberRole role) {
        lock(tripId);
        TripMember owner = membership(tripId, ownerId);
        if (owner.getRole() != MemberRole.OWNER) throw ApiException.forbidden("초대 권한이 없습니다.");
        if (members.findByTripIdAndUserId(tripId, userId).isEmpty()) {
            User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
            owner.getTrip().addMember(user, role);
            owner.getTrip().touch(); trips.flush();
        }
    }

    public TripResponse updateInfo(Long tripId, UpdateTripRequest req, Long userId) {
        membership(tripId, userId);
        throw ApiException.forbidden("여행 정보는 확정 전 초안에서만 수정하고 AI로 다시 생성할 수 있습니다.");
    }

    public TripResponse reorder(Long tripId, Long dayId, ReorderRequest req, Long userId) {
        TripMember m = requireEditor(tripId, userId);
        Trip t = m.getTrip();
        checkVersion(t, req.version());
        TripDay day = t.getDays().stream().filter(d -> d.getId().equals(dayId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("일차"));

        Map<Long, ItineraryPlace> byId = new HashMap<>();
        day.getPlaces().forEach(p -> byId.put(p.getId(), p));
        if (req.placeIds().size() != byId.size() || !byId.keySet().containsAll(req.placeIds())
                || new HashSet<>(req.placeIds()).size() != req.placeIds().size()) {
            throw ApiException.badRequest("순서 목록이 현재 일정의 장소와 일치하지 않습니다. 새로고침 후 다시 시도해 주세요.");
        }
        // 컬렉션을 비웠다 다시 넣으면 orphanRemoval로 삭제될 수 있어 orderIndex만 갱신
        List<Long> ids = req.placeIds();
        if (t.getTravelDetails() != null) {
            var previous = day.getPlaces().stream().sorted(Comparator.comparingInt(ItineraryPlace::getOrderIndex)).toList();
            var times = previous.stream().map(ItineraryPlace::getVisitTime).toList();
            for (int i = 0; i < ids.size(); i++) {
                var old = previous.get(i); var next = byId.get(ids.get(i));
                if ((old.getFixedEventId() != null || next.getFixedEventId() != null) && !old.getId().equals(next.getId()))
                    throw ApiException.badRequest("고정 일정의 위치를 변경할 수 없습니다.");
                if (next.getFixedEventId() == null) {
                    boolean changed=!Objects.equals(next.getVisitTime(),times.get(i));
                    next.updateMemo(times.get(i), next.getNote());
                    if(changed) recheckHours(next);
                }
            }
        }
        for (int i = 0; i < ids.size(); i++) byId.get(ids.get(i)).setOrderIndex(i);
        validateSchedule(t);
        t.touch();
        trips.flush();
        return TripMapper.trip(t, m.getRole());
    }

    public TripResponse updatePlace(Long tripId, Long placeId, UpdatePlaceRequest req, Long userId) {
        TripMember m = requireEditor(tripId, userId);
        Trip t = m.getTrip();
        checkVersion(t, req.version());
        ItineraryPlace p = findPlace(t, placeId);
        if (p.getFixedEventId() != null) throw ApiException.badRequest("고정 항목은 초안의 여행 정보 수정에서 변경해 주세요.");
        boolean timeChanged=!Objects.equals(p.getVisitTime(),blankToNull(req.visitTime()));
        p.updateMemo(blankToNull(req.visitTime()), blankToNull(req.note()));
        if(timeChanged) recheckHours(p);
        validateSchedule(t);
        t.touch();
        trips.flush();
        return TripMapper.trip(t, m.getRole());
    }

    public TripResponse deletePlace(Long tripId, Long placeId, Long version, Long userId) {
        TripMember m = requireEditor(tripId, userId);
        Trip t = m.getTrip();
        checkVersion(t, version);
        ItineraryPlace p = findPlace(t, placeId);
        if (p.getFixedEventId() != null) throw ApiException.badRequest("고정 항목은 삭제할 수 없습니다. 초안의 여행 정보를 변경해 주세요.");
        TripDay day = p.getTripDay();
        t.deletedPlaces(RecoveryArchive.add(t.deletedPlaces(),day.getDayNumber(),null,null,RecoveryArchive.snapshot(p)));
        day.getPlaces().remove(p);
        day.reindex();
        validateSchedule(t);
        t.touch();
        trips.flush();
        return TripMapper.trip(t, m.getRole());
    }

    public void delete(Long tripId, Long userId) {
        lock(tripId);
        TripMember m = membership(tripId, userId);
        if (m.getRole() != MemberRole.OWNER) {
            throw ApiException.forbidden("여행 삭제는 만든 사람만 할 수 있습니다.");
        }
        TripEditLocks.guard(tripId,userId,planLeases,false);
        m.getTrip().deletedAt(java.time.Instant.now());
        trips.flush();
    }

    @Transactional(readOnly=true)
    public List<RecoveryArchive.TrashItem> trash(Long user) {
        return trips.findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user).stream()
                .filter(t->!RecoveryArchive.expired(t.getDeletedAt()))
                .map(t->new RecoveryArchive.TrashItem(t.getId().toString(),t.getTitle(),t.getDestination(),t.getDeletedAt(),t.getDeletedAt().plus(RecoveryArchive.RETENTION))).toList();
    }
    private Trip trashed(Long id,Long user) {
        var t=trips.lockById(id).orElseThrow(()->ApiException.notFound("삭제한 여행"));
        if(!t.getOwner().getId().equals(user) || t.getDeletedAt()==null || RecoveryArchive.expired(t.getDeletedAt()))throw ApiException.notFound("삭제한 여행");
        return t;
    }
    public TripResponse restore(Long id,Long user) {
        var t=trashed(id,user);t.deletedAt(null);trips.flush();return TripMapper.trip(t,MemberRole.OWNER);
    }
    public void permanentlyDelete(Long id,Long user) {
        if(trips.countOwnedTrash(id,user)==0)throw ApiException.notFound("삭제한 여행");
        if(draftDocuments!=null)TrashMaintenance.lockFinalizedDrafts(draftDocuments,id);
        var t=trashed(id,user);
        if(draftDocuments!=null)TrashMaintenance.removeFinalizedDrafts(draftDocuments,draftInvitations,id);
        invites.deleteByTripId(id);invites.flush();trips.delete(t);trips.flush();
    }
    @Transactional(readOnly=true)
    public List<RecoveryArchive.DeletedPlace> deletedPlaces(Long id,Long user) {
        return RecoveryArchive.read(membership(id,user).getTrip().deletedPlaces());
    }
    public TripResponse restorePlace(Long id,String entryId,RecoveryArchive.RestorePlace req,Long user) {
        var m=requireEditor(id,user);var t=m.getTrip();checkVersion(t,req.version());
        var row=RecoveryArchive.find(t.deletedPlaces(),entryId);
        var day=t.getDays().stream().filter(d->d.getDayNumber()==row.dayNumber()).findFirst().orElseThrow(()->ApiException.badRequest("원래 일차를 찾을 수 없습니다."));
        var restored=RecoveryArchive.at(row,req.visitTime());
        RecoveryArchive.checkSpace(day.getPlaces().stream().map(RecoveryArchive::snapshot).toList(),restored,t.getTravelDetails());
        var entity=day.addPlace(toEntity(restored));
        var sorted=day.getPlaces().stream().sorted(Comparator.comparing(ItineraryPlace::getVisitTime)).toList();
        for(int i=0;i<sorted.size();i++)sorted.get(i).setOrderIndex(i);
        try {validateSchedule(t);}catch(ApiException e){throw ApiException.badRequest("복원할 시간이 고정 일정이나 다른 방문과 겹칩니다. 복원 시각을 조정해 주세요.");}
        recheckHours(entity);
        t.deletedPlaces(RecoveryArchive.remove(t.deletedPlaces(),entryId));trips.flush();
        return TripMapper.trip(t,m.getRole());
    }
    void importDeletedPlaces(Long id,List<RecoveryArchive.DeletedPlace> entries) {
        if(entries.isEmpty())return;
        var t=lock(id);
        t.deletedPlaces(RecoveryArchive.encode(entries.stream().map(e->new RecoveryArchive.DeletedPlace(e.id(),e.deletedAt(),e.dayNumber(),null,null,e.place())).toList()));
        trips.flush();
    }

    // ───────── 권한 ─────────

    /** 구성원이 아니면 존재 여부도 노출하지 않도록 404 */
    TripMember membership(Long tripId, Long userId) {
        return members.findByTripIdAndUserId(tripId, userId).filter(m->m.getTrip().getDeletedAt()==null)
                .orElseThrow(() -> ApiException.notFound("여행"));
    }

    TripMember requireEditor(Long tripId, Long userId) {
        lock(tripId);
        TripMember m = membership(tripId, userId);
        if (!m.getRole().canEdit()) throw ApiException.forbidden("보기 권한만 있어 일정을 수정할 수 없습니다.");
        TripEditLocks.guard(tripId,userId,planLeases,false);
        return m;
    }

    Trip lock(Long tripId) {
        return trips.lockById(tripId).filter(t->t.getDeletedAt()==null).orElseThrow(() -> ApiException.notFound("여행"));
    }

    private static MemberRole roleOf(Trip t, Long userId) {
        return t.getMembers().stream().filter(m -> m.getUser().getId().equals(userId))
                .map(TripMember::getRole).findFirst().orElse(MemberRole.VIEWER);
    }

    private static void checkVersion(Trip t, Long clientVersion) {
        if (clientVersion == null || !clientVersion.equals(t.getVersion())) throw ApiException.conflict();
    }

    private static ItineraryPlace findPlace(Trip t, Long placeId) {
        return t.getDays().stream().flatMap(d -> d.getPlaces().stream())
                .filter(p -> p.getId().equals(placeId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("장소"));
    }

    private static ItineraryPlace toEntity(PlaceDraft p) {
        boolean hasCoords = p.latitude() != null && p.longitude() != null
                && Math.abs(p.latitude()) <= 90 && Math.abs(p.longitude()) <= 180;
        ItineraryPlace entity = new ItineraryPlace(blankToNull(p.placeId()), p.name().trim(), blankToNull(p.address()),
                hasCoords ? p.latitude() : null, hasCoords ? p.longitude() : null,
                blankToNull(p.visitTime()), blankToNull(p.reason()), blankToNull(p.note()),
                blankToNull(p.category()), p.verified() && hasCoords && p.placeId() != null,
                safeUrl(p.placeUrl()), blankToNull(p.phone()));
        entity.setFixedEventId(p.fixedEventId());
        entity.setBusinessHours(p.businessHours());
        entity.setDurationMinutes(p.durationMinutes());
        return entity;
    }

    private void recheckHours(ItineraryPlace p) {
        if(editHours==null)return;
        var place=new PlaceDraft(p.getPlaceId(),p.getName(),p.getAddress(),p.getLatitude(),p.getLongitude(),
            p.getVisitTime(),p.getReason(),p.getNote(),p.getCategory(),p.isVerified(),p.getPlaceUrl(),p.getPhone()).withDurationMinutes(p.getDurationMinutes());
        p.setBusinessHours(editHours.session().checkEdited(place,p.getTripDay().getDate()).businessHours());
    }

    private static void validateSchedule(Trip t) {
        var days = t.getDays().stream().map(d -> new DayDraft(d.getDayNumber(), d.getDate(),
                d.getPlaces().stream().sorted(Comparator.comparingInt(ItineraryPlace::getOrderIndex))
                 .map(p -> new PlaceDraft(p.getPlaceId(), p.getName(), p.getAddress(), p.getLatitude(), p.getLongitude(),
                         p.getVisitTime(), p.getReason(), p.getNote(), p.getCategory(), p.isVerified(), p.getPlaceUrl(), p.getPhone(), p.getFixedEventId()).withBusinessHours(p.getBusinessHours()).withDurationMinutes(p.getDurationMinutes())).toList())).toList();
        TravelSchedule.validateDraft(new TripDraft(t.getTitle(), t.getDestination(), t.getStartDate(), t.getEndDate(),
                t.getBudget(), t.getStyles(), days, null, List.of(), null, t.getTravelDetails()));
    }

    /** 앱에서 열 링크이므로 http(s)만 허용 */
    private static String safeUrl(String url) {
        String u = blankToNull(url);
        return u != null && (u.startsWith("https://") || u.startsWith("http://")) ? u : null;
    }

    private static List<String> cleanStyles(List<String> styles) {
        if (styles == null) return List.of();
        return styles.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
