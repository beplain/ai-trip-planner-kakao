package com.tripplanner.place;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.springframework.http.MediaType;
class BusinessHoursTests {
    BusinessHoursInfo info(String hours,String rest){return new BusinessHoursInfo("1",hours,rest,null,null,"UNKNOWN",null);}
    String state(String hours,String rest,String date,String time){return BusinessHoursRules.evaluate(info(hours,rest),LocalDate.parse(date),time,60).status();}
    @Test void fullVisitMustFitOpeningAndBreak() {
        assertEquals("CLOSED",state("10:00~20:00; 브레이크 15:00~17:00","연중무휴","2026-11-04","14:30"));
        assertEquals("OPEN",state("10:00~20:00; 브레이크 15:00~17:00","연중무휴","2026-11-04","17:00"));
        assertEquals("CLOSED",state("10:00~20:00","연중무휴","2026-11-04","19:30"));
    }
    @Test void regularHolidaysAndUnknownExceptions() {
        assertEquals("CLOSED",state("10:00~20:00","매주 월요일","2026-11-02","12:00"));
        assertEquals("OPEN",state("10:00~20:00","매주 월요일","2026-11-03","12:00"));
        assertEquals("CLOSED",state("10:00~20:00","매월 둘째 월요일","2026-11-09","12:00"));
        assertEquals("UNKNOWN",state("동절기 10:00~18:00","연중무휴","2026-11-04","12:00"));
        assertEquals("UNKNOWN",state("10:00~20:00","공휴일 휴무","2026-11-04","12:00"));
        assertEquals("UNKNOWN",state("10:00~20:00","","2026-11-04","12:00"));
    }
    @Test void overnightAndWeekdayHours() {
        assertEquals("OPEN",state("18:00~02:00","매주 월요일","2026-11-02","00:30"));
        assertEquals("CLOSED",state("평일 09:00~18:00; 주말 11:00~20:00","연중무휴","2026-11-07","10:00"));
    }
    @Test void providerMatchingAndCache() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("searchKeyword2")))
            .andExpect(requestTo(org.hamcrest.Matchers.containsString("serviceKey=key%2B%3D%3D")))
            .andRespond(withSuccess(wrap("[{\"contentid\":\"42\",\"contenttypeid\":\"39\",\"title\":\"식당 본점\",\"mapx\":\"127.0\",\"mapy\":\"37.0\"}]"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("detailIntro2"))).andRespond(withSuccess(wrap("{\"opentimefood\":\"10:00~20:00\",\"restdatefood\":\"월요일\"}"),MediaType.APPLICATION_JSON));
        var client=new TourApiClient(builder.build(),"key+==","https://example.test");var budget=new TourApiClient.Budget(2);
        var found=client.lookup("123","식당 본점",37,127,budget);
        assertEquals("42",found.contentId());assertEquals("월요일",found.closedText());
        assertEquals(found,client.lookup("123","식당 본점",37,127,budget));server.verify();
    }
    @Test void differentBranchNeverBorrowsHours() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(wrap("[{\"contentid\":\"42\",\"contenttypeid\":\"39\",\"title\":\"식당 다른지점\",\"mapx\":\"127\",\"mapy\":\"37\"}]"),MediaType.APPLICATION_JSON));
        assertNull(new TourApiClient(builder.build(),"key","https://example.test").lookup("123","식당 본점",37,127,new TourApiClient.Budget(2)).contentId());server.verify();
    }
    @Test void metadataPersists() {
        var entity=new com.tripplanner.trip.ItineraryPlace() {};var hours=info("10:00~20:00","월요일");
        entity.setBusinessHours(hours);assertEquals(hours,entity.getBusinessHours());
    }
    @Test void encodedKeyIsDecodedOnceAndStrictlyEncoded() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("serviceKey=key%2B%2F%3D%3D")))
            .andRespond(withSuccess(wrap("[]"),MediaType.APPLICATION_JSON));
        var found=new TourApiClient(builder.build(),"key%2B%2F%3D%3D","https://example.test")
            .lookup("123","카페",37,127,new TourApiClient.Budget(1));
        assertTrue(found.detail().contains("같은 지점"));server.verify();
    }
    @Test void failureReasonSurvivesAssessment() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"30\"}}}",MediaType.APPLICATION_JSON));
        var found=new TourApiClient(builder.build(),"key","https://example.test")
            .lookup("123","카페",37,127,new TourApiClient.Budget(1));
        assertTrue(found.detail().contains("거절"));
        assertEquals(found.detail(),BusinessHoursRules.evaluate(found,LocalDate.of(2026,11,4),"12:00",60).detail());
        server.verify();
    }
    @Test void sameRoadAddressConnectsYeongnamDespitePinDifference() {
        var found=addressLookup("영남돼지","부산 해운대구 해운대해변로209번가길 13",
            "영남돼지","부산광역시 해운대구 해운대해변로209번가길 13 (우동)",true);
        assertEquals("2875744",found.contentId());
        assertEquals("11:30~22:30",found.hoursText());
    }
    @Test void sameNameWithDifferentBuildingNumberDoesNotConnect() {
        assertNull(addressLookup("영남돼지","부산 해운대구 해운대해변로209번가길 13",
            "영남돼지","부산광역시 해운대구 해운대해변로209번가길 130",false).contentId());
    }
    @Test void partialAddressCannotOverridePinDifference() {
        assertNull(addressLookup("영남돼지","부산 해운대구",
            "영남돼지","부산광역시 해운대구",false).contentId());
    }
    @Test void matchingAddressCannotOverrideDifferentBranchName() {
        assertNull(addressLookup("영남돼지 본점","부산 해운대구 해운대해변로209번가길 13",
            "영남돼지 다른지점","부산광역시 해운대구 해운대해변로209번가길 13",false).contentId());
    }
    @Test void nearbyPinCannotOverrideConflictingRoadAddress() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var row=mapper.createObjectNode().put("contentid","42").put("contenttypeid","39").put("title","식당")
            .put("mapx",127).put("mapy",37).put("addr1","서울특별시 종로구 사직로 130");
        server.expect(anything()).andRespond(withSuccess(wrap("["+row+"]"),MediaType.APPLICATION_JSON));
        var found=new TourApiClient(builder.build(),"key","https://example.test")
            .lookup("123","식당","서울 종로구 사직로 13",37,127,new TourApiClient.Budget(2));
        assertNull(found.contentId());server.verify();
    }
    private BusinessHoursInfo addressLookup(String name,String address,String title,String sourceAddress,boolean matches) {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var row=mapper.createObjectNode().put("contentid","2875744").put("contenttypeid","39").put("title",title)
            .put("mapx",129.1544153095).put("mapy",35.160053936).put("addr1",sourceAddress);
        server.expect(anything()).andRespond(withSuccess(wrap("["+row+"]"),MediaType.APPLICATION_JSON));
        if(matches)server.expect(anything()).andRespond(withSuccess(wrap("{\"opentimefood\":\"11:30~22:30\",\"restdatefood\":\"연중무휴\"}"),MediaType.APPLICATION_JSON));
        var found=new TourApiClient(builder.build(),"key","https://example.test")
            .lookup("123",name,address,35.163053936,129.1544153095,new TourApiClient.Budget(2));
        server.verify();return found;
    }
    private String wrap(String item){return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":"+item+"}}}}";}
}
