package com.tripplanner.trip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.MemberResponse;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service @Transactional
public class DraftEditLocks {
    public static final String HEADER="X-Draft-Edit-Token";
    static final Duration TTL=Duration.ofSeconds(60);
    public record Status(boolean active,Long editorId,String editorName,Instant expiresAt,boolean mine,boolean canEdit,long version) {}
    public record Grant(String token,Status status) {}
    private final DraftDocumentRepository drafts;
    private final DraftEditLeaseRepository leases;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public DraftEditLocks(DraftDocumentRepository drafts,DraftEditLeaseRepository leases,ObjectMapper json){this(drafts,leases,json,Clock.systemUTC());}
    DraftEditLocks(DraftDocumentRepository drafts,DraftEditLeaseRepository leases,ObjectMapper json,Clock clock){this.drafts=drafts;this.leases=leases;this.json=json;this.clock=clock;}
    @Transactional(readOnly=true)
    public Status status(String id,Long user,String token) {
        var room=drafts.editMembership(id).filter(r->r.getDeletedAt()==null).orElseThrow(()->ApiException.notFound("초안"));
        var member=member(room.getParticipants(),user);
        return status(leases.findById(id).orElse(null),user,token,member.role().canEdit()&&room.getSavedTripId()==null,room.getVersion());
    }
    public Grant acquire(String id,Long user,String token) {
        var room=room(id);var member=member(room.getParticipants(),user);
        if(!member.role().canEdit())throw ApiException.forbidden("보기 권한만 있어 수정할 수 없습니다.");
        var now=clock.instant();var lease=leases.findById(id).orElse(null);
        if(lease!=null&&lease.active(now)&&!lease.owned(user,token))throw locked(lease);
        String issued=lease!=null&&lease.active(now)?lease.token():UUID.randomUUID().toString();
        String name=member.name()==null||member.name().isBlank()?"동행자":member.name();
        if(lease==null)lease=new DraftEditLease(id,user,name,issued,now.plus(TTL));
        else lease.claim(user,name,issued,now.plus(TTL));
        leases.saveAndFlush(lease);
        return new Grant(issued,status(lease,user,issued,true,room.getVersion()));
    }
    public Status renew(String id,Long user,String token) {
        var room=room(id);var member=member(room.getParticipants(),user);
        if(!member.role().canEdit())throw ApiException.forbidden("수정 권한이 없습니다.");
        var lease=leases.findById(id).orElse(null);
        if(lease==null||!lease.active(clock.instant())||!lease.owned(user,token))throw expired();
        lease.renew(clock.instant().plus(TTL));leases.flush();
        return status(lease,user,token,true,room.getVersion());
    }
    public void release(String id,Long user,String token) {
        var room=drafts.lockById(id).orElseThrow(()->ApiException.notFound("초안"));member(room.getParticipants(),user);
        var lease=leases.findById(id).orElse(null);
        if(lease!=null&&lease.owned(user,token)){leases.delete(lease);leases.flush();}
    }
    private DraftDocument room(String id) {
        var r=drafts.lockById(id).filter(d->d.getDeletedAt()==null&&d.getSavedTripId()==null).orElseThrow(()->ApiException.notFound("편집 가능한 초안"));
        return r;
    }
    private MemberResponse member(String people,Long user) {
        try {List<MemberResponse> list=json.readValue(people,new TypeReference<>(){});
            return list.stream().filter(m->m.userId().equals(user)).findFirst().orElseThrow(()->ApiException.notFound("초안"));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private Status status(DraftEditLease lease,Long user,String token,boolean canEdit,Long version) {
        boolean active=lease!=null&&lease.active(clock.instant());
        return new Status(active,active?lease.editorId():null,active?lease.editorName():null,active?lease.expiresAt():null,
                active&&lease.owned(user,token),canEdit,version==null?0:version);
    }
    /** Called under the existing draft row lock, in the same transaction as the mutation. */
    static void guard(DraftDocument room,Long user,DraftEditLeaseRepository leases) {
        if(leases==null)return; // Compatibility for trusted, directly constructed service fixtures.
        var request=RequestContextHolder.getRequestAttributes();
        String token=request instanceof ServletRequestAttributes attrs?attrs.getRequest().getHeader(HEADER):null;
        var lease=leases.findById(room.getId()).orElse(null);
        if(lease!=null&&lease.active(Instant.now())) {
            if(!lease.owned(user,token))throw locked(lease);
        } else if(token!=null&&!token.isBlank())throw expired();
        else if(request instanceof ServletRequestAttributes)
            throw new ApiException(HttpStatus.LOCKED,"EDIT_LOCK_REQUIRED","편집 권한을 먼저 확인한 후 다시 시도해 주세요.");
    }
    static void guardJoining(DraftDocument room,DraftEditLeaseRepository leases) {
        if(leases==null)return;
        var lease=leases.findById(room.getId()).orElse(null);
        if(lease!=null&&lease.active(Instant.now()))throw locked(lease);
    }
    private static ApiException locked(DraftEditLease lease){return new ApiException(HttpStatus.LOCKED,"EDIT_LOCKED",lease.editorName()+"님이 일정을 수정 중입니다. 완료된 후 다시 시도해 주세요.");}
    private static ApiException expired(){return new ApiException(HttpStatus.LOCKED,"EDIT_LOCK_EXPIRED","편집 권한이 만료되었습니다. 최신 일정을 확인하고 편집을 다시 시작해 주세요.");}
    @Scheduled(fixedDelayString="PT6H",initialDelayString="PT1M")
    public void purgeExpiredOrphans(){leases.deleteExpiredOrphans(clock.instant());}
}
