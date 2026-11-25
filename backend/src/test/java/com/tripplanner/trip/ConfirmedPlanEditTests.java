package com.tripplanner.trip;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.user.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql=false,properties="spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.ANY)
@Import({TripService.class,TripEditLocks.class})
@org.springframework.test.context.TestExecutionListeners(listeners={org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
 org.springframework.test.context.transaction.TransactionalTestExecutionListener.class},mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class ConfirmedPlanEditTests {
 @Autowired TripService trips; @Autowired TripEditLocks locks; @Autowired UserRepository users;
 @Autowired DraftEditLeaseRepository leases;
 Long user(){return users.saveAndFlush(new User("dev:"+UUID.randomUUID(),null,"편집자")).getId();}
 PlaceDraft place(String name,String time){return new PlaceDraft(name,name,"부산",35.1,129.1,time,"추천","메모","관광",true,null,null);}
 TripResponse trip(Long owner){return trips.create(new TripDraft("확정 여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(place("공원","10:00"),place("전시관","14:00")))),"AI",List.of()),owner);}
 void header(String token){var r=new MockHttpServletRequest();if(token!=null)r.addHeader(DraftEditLocks.HEADER,token);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));}
 @AfterEach void clean(){RequestContextHolder.resetRequestAttributes();}
 @Test void acquiringAndCancellingDoNotChangeConfirmedPlan(){
  var owner=user();var trip=trip(owner);var grant=locks.acquire(trip.id(),owner,null);
  locks.renew(trip.id(),owner,grant.token());assertEquals(trip.version(),trips.get(trip.id(),owner).version());
  assertThrows(ApiException.class,()->locks.acquire(trip.id(),owner,null));
  locks.release(trip.id(),owner,grant.token());assertFalse(locks.status(trip.id(),owner,null).active());
  assertEquals(2,trips.get(trip.id(),owner).days().get(0).places().size());
 }
 @Test void planSaveIsAtomicAndKeepsMembershipAndArchivesRemovedPlaces(){
  var owner=user();var trip=trip(owner);var grant=locks.acquire(trip.id(),owner,null);header(grant.token());
  var saved=trips.savePlan(trip.id(),new TripService.PlanUpdate(trip.version(),List.of(new DayDraft(1,null,List.of(place("공원","11:00"),place("새 장소","16:00"))))),owner);
  assertEquals(trip.id(),saved.id());assertEquals(trip.members(),saved.members());
  assertEquals("11:00",saved.days().get(0).places().get(0).visitTime());assertTrue(saved.version()>trip.version());
  assertEquals("전시관",trips.deletedPlaces(trip.id(),owner).get(0).place().name());
 }
 @Test void competingUserAndStaleTokenCannotWrite(){
  var owner=user();var other=user();var trip=trip(owner);trips.addDraftMember(trip.id(),owner,other,MemberRole.EDITOR);
  var current=trips.get(trip.id(),owner);var grant=locks.acquire(trip.id(),owner,null);
  assertThrows(ApiException.class,()->locks.acquire(trip.id(),other,null));header(null);
  assertThrows(ApiException.class,()->trips.deletePlace(trip.id(),current.days().get(0).places().get(0).id(),current.version(),other));
  locks.release(trip.id(),owner,grant.token());header(grant.token());
  assertThrows(ApiException.class,()->trips.savePlan(trip.id(),new TripService.PlanUpdate(current.version(),List.of(new DayDraft(1,null,List.of(place("변경","10:00"))))),owner));
 }
 @Test void missingLeaseAndViewerAreRejected(){
  var owner=user();var viewer=user();var trip=trip(owner);trips.addDraftMember(trip.id(),owner,viewer,MemberRole.VIEWER);
  assertThrows(ApiException.class,()->locks.acquire(trip.id(),viewer,null));header(null);
  assertThrows(ApiException.class,()->trips.savePlan(trip.id(),new TripService.PlanUpdate(trip.version(),List.of(new DayDraft(1,null,List.of(place("변경","10:00"))))),owner));
 }
 @Test void fixedTransportRowsSurviveAndCannotBeRemoved(){
  var owner=user();var details=new TravelDetails(MovementMode.WALK,List.of(),new Transport(TransportMode.KTX,"부산역","09:00","부산역","20:00"));
  var places=new ArrayList<PlaceDraft>(TravelSchedule.events(details,1).stream().map(TravelSchedule.Event::place).toList());
  places.add(place("공원","12:00"));places.sort(Comparator.comparing(PlaceDraft::visitTime));
  var trip=trips.create(new TripDraft("교통 여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,places)),"AI",List.of(),null,details),owner);
  var grant=locks.acquire(trip.id(),owner,null);header(grant.token());
  var submitted=trips.lock(trip.id()).getDays().get(0).getPlaces().stream().sorted(Comparator.comparingInt(ItineraryPlace::getOrderIndex)).map(RecoveryArchive::snapshot).toList();
  var result=trips.savePlan(trip.id(),new TripService.PlanUpdate(trip.version(),List.of(new DayDraft(1,null,submitted))),owner);
  assertEquals(trip.days().get(0).places().stream().filter(p->p.fixedEventId()!=null).map(PlaceResponse::id).toList(),result.days().get(0).places().stream().filter(p->p.fixedEventId()!=null).map(PlaceResponse::id).toList());
  var missing=submitted.stream().filter(p->!"transport:arrival".equals(p.fixedEventId())).toList();
  assertThrows(ApiException.class,()->trips.savePlan(trip.id(),new TripService.PlanUpdate(result.version(),List.of(new DayDraft(1,null,missing))),owner));
 }
 @Test void movingAcrossDaysDoesNotArchiveTheMovedPlace(){
  var owner=user();var trip=trips.create(new TripDraft("이동 여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(place("해운대","10:00"))),new DayDraft(2,null,List.of(place("공원","15:00")))),"AI",List.of()),owner);
  var grant=locks.acquire(trip.id(),owner,null);header(grant.token());
  var result=trips.savePlan(trip.id(),new TripService.PlanUpdate(trip.version(),List.of(new DayDraft(1,null,List.of()),new DayDraft(2,null,List.of(place("해운대","11:00"),place("공원","15:00"))))),owner);
  assertTrue(result.days().get(0).places().isEmpty());assertEquals(2,result.days().get(1).places().size());
  assertTrue(trips.deletedPlaces(trip.id(),owner).isEmpty());
 }
}
