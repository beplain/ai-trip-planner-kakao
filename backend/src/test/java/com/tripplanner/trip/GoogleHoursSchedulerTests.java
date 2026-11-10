package com.tripplanner.trip;
import com.tripplanner.place.*;
import com.tripplanner.trip.TripDtos.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
class GoogleHoursSchedulerTests {
    PlaceDraft place(String id,String name,String time){return new PlaceDraft(id,name,"부산 해운대구 해운대해변로209번가길 13",35.16,129.15,time,"식사",null,"식당",true,"https://place.map.kakao.com/"+id,null);}
    TripDraft draft(PlaceDraft...places){var date=LocalDate.of(2026,11,23);return new TripDraft("여행","부산",date,date,null,List.of("맛집"),List.of(new DayDraft(1,date,List.of(places))),"AI",List.of());}
    String response(String name,int open,int close) {
        var json=new ObjectMapper();var root=json.createObjectNode();var row=root.putArray("places").addObject();
        row.put("id","google-only-id").put("formattedAddress","부산광역시 해운대구 해운대해변로209번가길 13");
        row.putObject("displayName").put("text",name);row.putObject("location").put("latitude",35.16).put("longitude",129.15);
        var p=row.putObject("regularOpeningHours").putArray("periods").addObject();
        p.putObject("open").put("day",1).put("hour",open).put("minute",0);p.putObject("close").put("day",1).put("hour",close).put("minute",0);
        return root.toString();
    }
    @Test void editedTimeIsRecheckedAndClosedTimeRejectedWithoutRemovingPlace() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("식당",11,21),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            var date=LocalDate.of(2026,11,23);
            var checked=session.checkEdited(place("1","식당","12:00"),date);
            assertEquals("VERIFIED",checked.businessHours().status());
            assertEquals("12:00",checked.businessHours().checkedVisitTime());
            assertThrows(com.tripplanner.common.ApiException.class,()->session.checkEdited(place("1","식당","08:30"),date));
            server.verify();
        }finally {client.close();}
    }
    @Test void editedTimeWithMissingHoursRemainsUnknown() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),anything()).andRespond(withSuccess("{\"places\":[]}",MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var checked=new GoogleHoursScheduler(client).session().checkEdited(place("1","해변","12:00"),LocalDate.of(2026,11,23));
            assertEquals("UNKNOWN",checked.businessHours().status());
            assertEquals("12:00",checked.businessHours().checkedVisitTime());server.verify();
        }finally {client.close();}
    }
    @Test void substitutesRestaurantThatIsClosedAtNoonAndNeverPersistsRawGoogleData()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("오후식당",15,22),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(response("점심식당",11,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var s=new GoogleHoursScheduler(client).session();var original=place("1","오후식당","12:00");var alt=place("2","점심식당","12:00");
            var result=s.verify(s.repair(draft(original),Map.of(1,List.of(alt))));
            assertEquals("점심식당",result.days().get(0).places().get(0).name());
            assertEquals("12:00",result.days().get(0).places().get(0).visitTime());
            var receipt=result.days().get(0).places().get(0).businessHours();
            assertEquals("VERIFIED",receipt.status());assertEquals("12:00",receipt.checkedVisitTime());
            assertEquals("2026-11-23",receipt.visitDate());assertEquals("REGULAR",receipt.basis());
            assertNotNull(receipt.checkedAt());assertNull(receipt.hoursText());assertNull(receipt.closedText());assertNull(receipt.contentId());
            var entity=new ItineraryPlace();entity.setBusinessHours(receipt);assertEquals(receipt,entity.getBusinessHours());
            assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(result).contains("google-only-id"));server.verify();
        } finally {client.close();}
    }
    @Test void movesOriginalToOpeningTimeWhenNoSameSlotAlternativeExists() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();server.expect(anything()).andRespond(withSuccess(response("오후식당",15,22),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {var s=new GoogleHoursScheduler(client).session();var result=s.verify(s.repair(draft(place("1","오후식당","12:00")),Map.of()));assertEquals("15:00",result.days().get(0).places().get(0).visitTime());server.verify();}finally {client.close();}
    }
    @Test void finalVerificationRejectsClosingTimeAfterRouteDelay() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();server.expect(anything()).andRespond(withSuccess(response("식당",11,14),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {var s=new GoogleHoursScheduler(client).session();var prepared=s.repair(draft(place("1","식당","12:00")),Map.of());assertTrue(s.fits(prepared,prepared.days().get(0).places().get(0),"12:00"));assertFalse(s.fits(prepared,prepared.days().get(0).places().get(0),"13:30"));assertThrows(com.tripplanner.common.ApiException.class,()->s.verify(draft(place("1","식당","13:30"))));server.verify();}finally {client.close();}
    }
    @Test void unknownHoursRemainUnconfirmedAndDoNotChangeFixedEvents() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();server.expect(org.springframework.test.web.client.ExpectedCount.times(2),anything()).andRespond(withSuccess("{\"places\":[]}",MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();var fixed=new PlaceDraft("fixed","체크인","숙소",35.16,129.15,"15:00",null,null,"숙소",true,null,null,"stay:1:in");
            var original=draft(place("1","식당","12:00"),fixed);
            var prepared=session.repair(original,Map.of());assertEquals(original.days(),prepared.days());
            assertTrue(session.fits(prepared,prepared.days().get(0).places().get(0),"12:00"));
            var result=session.verify(prepared);assertEquals(2,result.days().get(0).places().size());
            assertEquals("UNKNOWN",result.days().get(0).places().get(0).businessHours().status());
            assertEquals(fixed,result.days().get(0).places().get(1));server.verify();
        }finally {client.close();}
    }
    @Test void mandatoryBrandIsNotSilentlyReplacedWithAnotherRestaurant() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("필수식당",15,22),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(response("다른식당",11,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var original=place("1","필수식당","12:00");var alt=place("2","다른식당","12:00");
            String interpreted=ExtraRequestRules.STRUCTURED+"{\"rules\":[{\"source\":\"필수식당 한 곳 포함\",\"kind\":\"INCLUDE\",\"target\":\"필수식당\",\"value\":1,\"daily\":false,\"day\":0,\"meal\":null,\"category\":\"\",\"aliases\":[]}],\"unknown\":[]}";
            var d=draft(original).withRequestMetadata("필수식당 한 곳 포함",interpreted);
            var session=new GoogleHoursScheduler(client).session();var result=session.verify(session.repair(d,Map.of(1,List.of(alt))));
            assertEquals("필수식당",result.days().get(0).places().get(0).name());assertEquals("15:00",result.days().get(0).places().get(0).visitTime());
            assertTrue(ExtraRequestRules.violations(result).isEmpty());server.verify();
        } finally {client.close();}
    }

    @Test void emptyVariantDoesNotAbortOtherValidVariants()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        var closed=new ObjectMapper().readTree(response("휴무식당",11,20));
        ((com.fasterxml.jackson.databind.node.ArrayNode)closed.path("places").get(0).path("regularOpeningHours").path("periods")).removeAll();
        server.expect(anything()).andRespond(withSuccess(closed.toString(),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(response("확인식당",11,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            assertNull(session.verifyOption(draft(place("1","휴무식당","12:00"))));
            assertNotNull(session.verifyOption(draft(place("2","확인식당","12:00"))));server.verify();
        } finally {client.close();}
    }

    @Test void beachWithoutOpeningHoursIsRetainedWithoutVerifiedBadge()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        var json=new ObjectMapper().readTree(response("해운대해수욕장",11,20));
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("places").get(0)).remove("regularOpeningHours");
        server.expect(anything()).andRespond(withSuccess(json.toString(),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(json.path("places").get(0).toString(),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var beach=new PlaceDraft("beach","해운대해수욕장","부산 해운대구 해운대해변로209번가길 13",35.16,129.15,"12:00",null,null,"자연",true,null,null);
            var session=new GoogleHoursScheduler(client).session();
            var result=session.verify(session.repair(draft(beach),Map.of()));
            assertEquals("해운대해수욕장",result.days().get(0).places().get(0).name());
            assertEquals("UNKNOWN",result.days().get(0).places().get(0).businessHours().status());server.verify();
        }finally {client.close();}
    }
    @Test void finalCheckRequestsRecoveryInsteadOfSilentlyDroppingKnownClosedVisit() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("마감식당",11,14),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            var original=draft(place("closed","마감식당","13:30"),place("unknown","미확인장소","15:00"));
            assertNull(session.verifyOption(original));
            assertThrows(com.tripplanner.common.ApiException.class,()->session.verify(original));server.verify();
        }finally {client.close();}
    }

    @Test void lotteMallAtEightThirtyIsMovedToConfirmedTenThirtyOpening() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        var json=new ObjectMapper().createObjectNode();var row=json.putArray("places").addObject();
        row.put("id","mall-google-id").put("formattedAddress","대한민국 부산광역시 기장군 기장해안로 147");
        row.putObject("displayName").put("text","롯데몰 동부산점");row.putObject("location").put("latitude",35.19).put("longitude",129.21);
        var periods=row.putObject("regularOpeningHours").putArray("periods");
        for(int day=0;day<7;day++) {
            var p=periods.addObject();p.putObject("open").put("day",day).put("hour",10).put("minute",30);
            p.putObject("close").put("day",day).put("hour",21).put("minute",0);
        }
        server.expect(anything()).andRespond(withSuccess(json.toString(),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {
            var date=LocalDate.of(2026,11,24);
            var mall=new PlaceDraft("mall","롯데몰 동부산점","부산 기장군 기장읍 기장해안로 147",35.19,129.21,"08:30",null,null,"쇼핑",true,null,null);
            var draft=new TripDraft("부산","부산",date,date,null,List.of("쇼핑"),List.of(new DayDraft(1,date,List.of(mall))),"AI",List.of());
            var session=new GoogleHoursScheduler(client).session();var result=session.verify(session.repair(draft,Map.of()));
            var actual=result.days().get(0).places().get(0);
            assertEquals("10:30",actual.visitTime());assertEquals("VERIFIED",actual.businessHours().status());
            assertEquals("10:30",actual.businessHours().checkedVisitTime());server.verify();
        }finally {client.close();}
    }

    private TripDraft twoDays(List<PlaceDraft> first,List<PlaceDraft> second) {
        var start=LocalDate.of(2026,11,24);
        return new TripDraft("여행","부산",start,start.plusDays(1),null,List.of("맛집"),
            List.of(new DayDraft(1,start,first),new DayDraft(2,start.plusDays(1),second)),"AI",List.of());
    }
    private String weekdayResponse(String name,int[] days,int open,int close)throws Exception {
        var root=new ObjectMapper().readTree(response(name,open,close));
        var periods=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("places").get(0).path("regularOpeningHours").path("periods");
        periods.removeAll();
        for(int day:days) {
            var p=periods.addObject();
            p.putObject("open").put("day",day).put("hour",open).put("minute",0);
            p.putObject("close").put("day",day).put("hour",close).put("minute",0);
        }
        return root.toString();
    }
    @Test void tuesdayClosedVisitIsSwappedToWednesdayWithoutDroppingEitherVisitOrExtraCalls()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("휴무식당",new int[]{3},10,20),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("다른식당",new int[]{2,3},10,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            var result=session.verify(session.repair(twoDays(List.of(place("a","휴무식당","12:00")),List.of(place("b","다른식당","12:00"))),Map.of()));
            assertEquals("다른식당",result.days().get(0).places().get(0).name());
            var moved=result.days().get(1).places().get(0);
            assertEquals("휴무식당",moved.name());assertEquals("2026-11-25",moved.businessHours().visitDate());
            assertEquals("VERIFIED",moved.businessHours().status());
            assertEquals(2,result.days().stream().flatMap(d->d.places().stream()).map(PlaceDraft::placeId).distinct().count());
            server.verify(); // Exactly one hours request per venue, reused across dates and final verification.
        }finally {client.close();}
    }
    @Test void insertsClosedVisitIntoFreeOpenDaySlotWhenSwappingWouldBreakDinnerTime()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
        for(String name:List.of("휴무식당","기존식당","저녁식당")) {
            server.expect(jsonPath("$.textQuery",org.hamcrest.Matchers.containsString(name)))
                .andRespond(withSuccess(weekdayResponse(name,name.equals("휴무식당")?new int[]{3}:new int[]{2,3},10,22),MediaType.APPLICATION_JSON));
        }
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var dinner=place("c","저녁식당","19:00");
            var session=new GoogleHoursScheduler(client).session();
            var result=session.verify(session.repair(twoDays(List.of(place("a","휴무식당","12:00"),place("b","기존식당","16:00")),List.of(dinner)),Map.of()));
            assertEquals(List.of("기존식당"),result.days().get(0).places().stream().map(PlaceDraft::name).toList());
            assertEquals(2,result.days().get(1).places().size());
            assertEquals("19:00",result.days().get(1).places().stream().filter(p->p.name().equals("저녁식당")).findFirst().orElseThrow().visitTime());
            assertTrue(result.days().get(1).places().stream().anyMatch(p->p.name().equals("휴무식당")));
            server.verify();
        }finally {client.close();}
    }
    @Test void noOpenDayStillUsesAnAvailableReplacementRatherThanKeepingAClosedVenue()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
        for(String name:List.of("휴무식당","다른식당","대체식당")) {
            server.expect(jsonPath("$.textQuery",org.hamcrest.Matchers.containsString(name)))
                .andRespond(withSuccess(weekdayResponse(name,name.equals("휴무식당")?new int[]{}:new int[]{2,3},10,22),MediaType.APPLICATION_JSON));
        }
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            var result=session.verify(session.repair(twoDays(List.of(place("a","휴무식당","12:00")),List.of(place("b","다른식당","12:00"))),Map.of(1,List.of(place("c","대체식당","12:00")))));
            assertEquals("대체식당",result.days().get(0).places().get(0).name());
            assertEquals(2,result.days().stream().flatMap(d->d.places().stream()).count());server.verify();
        }finally {client.close();}
    }

    @Test void impossibleClosedVisitIsReportedRatherThanSilentlyRemoved()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("휴무식당",new int[]{},10,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var session=new GoogleHoursScheduler(client).session();
            var error=assertThrows(com.tripplanner.common.ApiException.class,()->session.repair(draft(place("a","휴무식당","12:00")),Map.of()));
            assertTrue(error.getMessage().contains("휴무식당"));server.verify();
        }finally {client.close();}
    }

    @Test void dateSpecificMandatoryVisitIsNotMovedToAnotherDayBehindUsersBack()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("필수식당",new int[]{3},10,20),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("다른식당",new int[]{2,3},10,20),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            String interpreted=ExtraRequestRules.STRUCTURED+"{\"rules\":[{\"source\":\"첫날 필수식당 방문\",\"kind\":\"INCLUDE\",\"target\":\"필수식당\",\"value\":1,\"daily\":false,\"day\":1,\"meal\":null,\"category\":\"\",\"aliases\":[]}],\"unknown\":[]}";
            var original=twoDays(List.of(place("a","필수식당","12:00")),List.of(place("b","다른식당","12:00")))
                .withRequestMetadata("첫날 필수식당 방문",interpreted);
            var session=new GoogleHoursScheduler(client).session();
            assertThrows(com.tripplanner.common.ApiException.class,()->session.repair(original,Map.of()));server.verify();
        }finally {client.close();}
    }

    @Test void crossDaySwapPreservesCheckInAndCheckOutEvents()throws Exception {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("휴무식당",new int[]{3},10,22),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(weekdayResponse("다른식당",new int[]{2,3},10,22),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",10,Clock.systemUTC());
        try {
            var details=new TravelDetails(MovementMode.CAR,List.of(new Stay("숙소","부산",1,2,"15:00","11:00",LuggagePlan.CARRY,LuggagePlan.CARRY)),null);
            var events=TravelSchedule.events(details,2);
            var days=new ArrayList<DayDraft>();var date=LocalDate.of(2026,11,24);
            for(int day=1;day<=2;day++) {
                var visits=new ArrayList<PlaceDraft>();
                for(var event:events)if(event.day()==day)visits.add(event.place());
                visits.add(place(day==1?"a":"b",day==1?"휴무식당":"다른식당","19:00"));
                visits.sort(Comparator.comparingInt(p->TravelSchedule.minute(p.visitTime())));
                days.add(new DayDraft(day,date.plusDays(day-1),visits));
            }
            var original=new TripDraft("여행","부산",date,date.plusDays(1),null,List.of("맛집"),days,"AI",List.of(),null,details);
            var session=new GoogleHoursScheduler(client).session();var result=session.verify(session.repair(original,Map.of()));
            assertEquals(events.stream().map(TravelSchedule.Event::place).toList(),result.days().stream().flatMap(d->d.places().stream()).filter(p->p.fixedEventId()!=null).toList());
            assertTrue(result.days().get(1).places().stream().anyMatch(p->p.name().equals("휴무식당")));
            assertDoesNotThrow(()->TravelSchedule.validateDraft(result));server.verify();
        }finally {client.close();}
    }

}
