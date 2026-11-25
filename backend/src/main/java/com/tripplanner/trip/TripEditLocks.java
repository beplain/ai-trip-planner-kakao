package com.tripplanner.trip;
import com.tripplanner.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
@Service @Transactional
public class TripEditLocks {
 private final TripService trips; private final DraftEditLeaseRepository leases;
 public TripEditLocks(TripService trips,DraftEditLeaseRepository leases){this.trips=trips;this.leases=leases;}
 static String key(Long id){return "trip:"+id;}
 private DraftEditLocks.Status status(TripMember m,DraftEditLease l,String token){boolean active=l!=null&&l.active(Instant.now());return new DraftEditLocks.Status(active,active?l.editorId():null,active?l.editorName():null,active?l.expiresAt():null,active&&l.owned(m.getUser().getId(),token),m.getRole().canEdit(),m.getTrip().getVersion());}
 @Transactional(readOnly=true) public DraftEditLocks.Status status(Long id,Long user,String token){return status(trips.membership(id,user),leases.findById(key(id)).orElse(null),token);}
 public DraftEditLocks.Grant acquire(Long id,Long user,String token){
  trips.lock(id);var m=trips.membership(id,user);if(!m.getRole().canEdit())throw ApiException.forbidden("보기 전용입니다.");
  var l=leases.findById(key(id)).orElse(null);var now=Instant.now();if(l!=null&&l.active(now)&&!l.owned(user,token))throw locked(l);
  String issued=l!=null&&l.active(now)?l.token():UUID.randomUUID().toString();String name=m.getUser().getName();if(name==null||name.isBlank())name="동행자";
  if(l==null)l=new DraftEditLease(key(id),user,name,issued,now.plusSeconds(60));else l.claim(user,name,issued,now.plusSeconds(60));
  leases.saveAndFlush(l);return new DraftEditLocks.Grant(issued,status(m,l,issued));
 }
 public DraftEditLocks.Status renew(Long id,Long user,String token){trips.lock(id);var m=trips.membership(id,user);var l=leases.findById(key(id)).orElse(null);if(!m.getRole().canEdit()||l==null||!l.active(Instant.now())||!l.owned(user,token))throw expired();l.renew(Instant.now().plusSeconds(60));leases.flush();return status(m,l,token);}
 public void release(Long id,Long user,String token){trips.lock(id);trips.membership(id,user);var l=leases.findById(key(id)).orElse(null);if(l!=null&&l.owned(user,token)){leases.delete(l);leases.flush();}}
 static void guard(Long id,Long user,DraftEditLeaseRepository leases,boolean required){
  if(leases==null)return;var request=RequestContextHolder.getRequestAttributes();String token=request instanceof ServletRequestAttributes a?a.getRequest().getHeader(DraftEditLocks.HEADER):null;var l=leases.findById(key(id)).orElse(null);
  if(l!=null&&l.active(Instant.now())){if(!l.owned(user,token))throw locked(l);}else if((token!=null&&!token.isBlank())||required)throw expired();
 }
 static ApiException locked(DraftEditLease l){return new ApiException(HttpStatus.LOCKED,"EDIT_LOCKED",l.editorName()+"님이 일정을 수정 중입니다.");}
 static ApiException expired(){return new ApiException(HttpStatus.LOCKED,"EDIT_LOCK_EXPIRED","편집 권한이 만료되었습니다. 최신 일정으로 편집을 다시 시작해 주세요.");}
}
