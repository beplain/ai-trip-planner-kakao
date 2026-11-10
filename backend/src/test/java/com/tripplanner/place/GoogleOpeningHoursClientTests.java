package com.tripplanner.place;
import com.fasterxml.jackson.databind.*;
import com.tripplanner.trip.TripDtos.PlaceDraft;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
class GoogleOpeningHoursClientTests {
    private final ObjectMapper json=new ObjectMapper();
    PlaceDraft place(String name){return new PlaceDraft("1",name,"부산 해운대구 해운대해변로209번가길 13",35.16,129.15,"12:00",null,null,"식당",true,null,null);}
    String response(String name){var row=json.createObjectNode();row.put("id","google-id").put("formattedAddress","대한민국 부산광역시 해운대구 해운대해변로209번가길 13");row.putObject("displayName").put("text",name);row.putObject("location").put("latitude",35.16).put("longitude",129.15);row.putObject("regularOpeningHours").putArray("periods");var root=json.createObjectNode();root.putArray("places").add(row);return root.toString();}
    @Test void postsHeadersAndSharesResponseWithinGenerationOnly() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Goog-Api-Key","secret")).andExpect(header("X-Goog-FieldMask",org.hamcrest.Matchers.containsString("regularOpeningHours")))
            .andExpect(jsonPath("$.pageSize").value(3)).andRespond(withSuccess(response("영남돼지"),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(response("영남돼지"),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {
            var s=client.session();var first=s.lookup(place("영남돼지"));assertSame(first,s.lookup(place("영남돼지")));assertEquals(1,s.calls());
            assertEquals(GoogleOpeningHours.State.CLOSED,first.assess(LocalDate.of(2026,11,23),"12:00",60).state());
            client.session().lookup(place("영남돼지"));server.verify();
        } finally {client.close();}
    }
    @Test void rejectsDifferentBranchAndCapsCalls() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("영남돼지 다른지점"),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",1,Clock.systemUTC());
        try {var s=client.session();assertEquals(GoogleOpeningHours.State.UNKNOWN,s.lookup(place("영남돼지")).assess(LocalDate.now(),"12:00",60).state());s.lookup(place("다른식당"));server.verify();}
        finally {client.close();}
    }
    @Test void authFailureDoesNotSilentlyBecomeOpen() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {var s=client.session();s.lookup(place("영남돼지"));assertThrows(com.tripplanner.common.ApiException.class,s::requireReady);server.verify();}finally {client.close();}
    }
    PlaceDraft mall(){return new PlaceDraft("mall","롯데몰 동부산점","부산 기장군 기장읍 기장해안로 147",35.19,129.21,"08:30",null,null,"쇼핑",true,null,null);}
    String mallResponse(String town,int number) {
        var root=json.createObjectNode();var row=root.putArray("places").addObject();
        row.put("id","mall-google-id").put("formattedAddress","대한민국 부산광역시 기장군 "+town+"기장해안로 "+number);
        row.putObject("displayName").put("text","롯데몰 동부산점");row.putObject("location").put("latitude",35.19).put("longitude",129.21);
        var periods=row.putObject("regularOpeningHours").putArray("periods");
        for(int day=0;day<7;day++) {
            var period=periods.addObject();period.putObject("open").put("day",day).put("hour",10).put("minute",30);
            period.putObject("close").put("day",day).put("hour",21).put("minute",0);
        }
        return root.toString();
    }
    @Test void lotteMallOmittedTownStillMatchesAndEightThirtyIsClosed() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(mallResponse("",147),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {
            var session=client.session();var hours=session.lookup(mall());var date=LocalDate.of(2026,11,24);
            assertEquals(GoogleOpeningHours.State.CLOSED,hours.assess(date,"08:30",60).state());
            assertTrue(hours.assess(date,"10:30",60).open());
            assertEquals(GoogleOpeningHours.State.CLOSED,hours.assess(date,"20:30",60).state());
            assertEquals(1,session.calls());server.verify();
        }finally {client.close();}
    }
    @Test void retriesWithRegionAndNameWhenFullAddressSearchMisses() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(jsonPath("$.textQuery").value("부산 기장군 기장읍 기장해안로 147 롯데몰 동부산점"))
            .andRespond(withSuccess("{\"places\":[]}",MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.textQuery").value("부산 롯데몰 동부산점"))
            .andRespond(withSuccess(mallResponse("",147),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {
            var session=client.session();assertEquals(GoogleOpeningHours.State.CLOSED,session.lookup(mall()).assess(LocalDate.of(2026,11,24),"08:30",60).state());
            assertEquals(2,session.calls());server.verify();
        }finally {client.close();}
    }
    @Test void conflictingAddressesWithDistantCoordinatesAreNotMerged() {
        for(String original:java.util.List.of(mallResponse("장안읍 ",147),mallResponse("",148))) {
            String response=original.replace("35.19","36.19");
            var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
            server.expect(org.springframework.test.web.client.ExpectedCount.times(2),anything()).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
            try {assertEquals(GoogleOpeningHours.State.UNKNOWN,client.session().lookup(mall()).assess(LocalDate.of(2026,11,24),"08:30",60).state());server.verify();}
            finally {client.close();}
        }
    }
    @Test void busanShortAddressAndCafePrefixMatchAtSeventeenTwenty() throws Exception {
        var root=json.readTree(response("On The Hill Cafe, Haeundae 온더힐"));
        var row=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("places").get(0);
        row.put("formattedAddress","대한민국 부산광역시 해운대구 달맞이길117번길 11");
        var period=row.putObject("regularOpeningHours").putArray("periods").addObject();
        period.putObject("open").put("day",1).put("hour",9).put("minute",0);
        period.putObject("close").put("day",2).put("hour",0).put("minute",0);
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(root.toString(),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {assertTrue(client.session().lookup(new PlaceDraft("cafe","카페 온더힐","부산 해운대구 달맞이길117번길 11",35.16,129.15,"17:20",null,null,"카페",true,null,null)).assess(LocalDate.of(2026,11,23),"17:20",60).open());server.verify();}
        finally {client.close();}
    }
    @Test void missingSearchHoursTriggersPlaceDetailsInsteadOfImmediateUnknown() throws Exception {
        var root=json.readTree(response("영남돼지"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("places").get(0)).remove("regularOpeningHours");
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(root.toString(),MediaType.APPLICATION_JSON));
        var details=json.readTree(response("영남돼지")).path("places").get(0).toString();
        server.expect(requestTo("https://places.googleapis.com/v1/places/google-id")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(details,MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {var session=client.session();assertEquals(GoogleOpeningHours.State.CLOSED,session.lookup(place("영남돼지")).assess(LocalDate.of(2026,11,23),"17:20",60).state());assertEquals(2,session.calls());server.verify();}
        finally {client.close();}
    }
    @Test void cafeAliasDoesNotMergeDifferentBuildingEvenWithNearbyCoordinates() throws Exception {
        var root=json.readTree(response("On The Hill Cafe 온더힐"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("places").get(0)).put("formattedAddress","부산광역시 해운대구 달맞이길117번길 12");
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),anything()).andRespond(withSuccess(root.toString(),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {var place=new PlaceDraft("cafe","카페 온더힐","부산 해운대구 달맞이길117번길 11",35.16,129.15,"17:20",null,null,"카페",true,null,null);
            assertEquals(GoogleOpeningHours.State.UNKNOWN,client.session().lookup(place).assess(LocalDate.of(2026,11,23),"17:20",60).state());server.verify();}
        finally {client.close();}
    }
    @Test void identicalAdenNameMatchesNearbyDespiteDifferentRoadAddresses() throws Exception {
        var root=json.readTree(response("아덴 보문호수점"));
        var row=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("places").get(0);
        row.put("formattedAddress","경상북도 경주시 보문로 424-34");
        var period=row.putObject("regularOpeningHours").putArray("periods").addObject();
        period.putObject("open").put("day",1).put("hour",10).put("minute",0);
        period.putObject("close").put("day",1).put("hour",22).put("minute",0);
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(root.toString(),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {var p=new PlaceDraft("aden","아덴 보문호수점","경북 경주시 한국관광1번로 34",35.16,129.15,"12:00",null,null,"카페",true,null,null);
            var h=client.session().lookup(p);assertTrue(h.assess(LocalDate.of(2026,11,23),"12:00",60).open());
            assertEquals(GoogleOpeningHours.State.CLOSED,h.assess(LocalDate.of(2026,11,23),"09:00",60).state());server.verify();
        }finally{client.close();}
    }
    @Test void exactRoadAddressMatchesEnglishNameWithNearbyCoordinates() {
        var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(response("Yeongnam Pork Restaurant"),MediaType.APPLICATION_JSON));
        var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
        try {assertEquals(GoogleOpeningHours.State.CLOSED,client.session().lookup(place("영남돼지")).assess(LocalDate.of(2026,11,23),"12:00",60).state());server.verify();}
        finally{client.close();}
    }

    private PlaceDraft sportsCenter() {
        return new PlaceDraft("1810485368","가상현실스포츠체험센터","부산 금정구 체육공원로399번길 324",
                35.20,129.10,"12:00",null,null,"액티비티",true,null,null);
    }
    private String sportsResponse(String name,String address) {
        var root=json.createObjectNode();var row=root.putArray("places").addObject();
        row.put("id","sports-google").put("formattedAddress",address);
        row.putObject("displayName").put("text",name);
        // Deliberately outside 150m: identity must be supported by address evidence.
        row.putObject("location").put("latitude",35.21).put("longitude",129.10);
        var periods=row.putObject("regularOpeningHours").putArray("periods");
        for(int day=3;day<=6;day++) {
            var period=periods.addObject();
            period.putObject("open").put("day",day).put("hour",10).put("minute",0);
            period.putObject("close").put("day",day).put("hour",18).put("minute",0);
        }
        return root.toString();
    }
    @Test void splitRoadNumbersAndFloorSuffixMatchExactVenueAndTuesdayIsClosed() {
        for(String address:java.util.List.of("부산광역시 금정구 체육공원로 399 324 경륜장 2층",
                "부산광역시 금정구 체육공원로399번길 324 경륜장 2층")) {
            var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
            server.expect(anything()).andRespond(withSuccess(sportsResponse("가상현실스포츠체험센터",address),MediaType.APPLICATION_JSON));
            var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
            try {
                var session=client.session();var hours=session.lookup(sportsCenter());
                assertEquals(GoogleOpeningHours.State.CLOSED,hours.assess(LocalDate.of(2026,11,24),"12:00",60).state());
                assertTrue(hours.assess(LocalDate.of(2026,11,25),"12:00",60).open());
                assertEquals(1,session.calls());server.verify();
            }finally {client.close();}
        }
    }
    @Test void splitRoadMatchDoesNotMergeDifferentNamesRegionsRoadsOrBuildingNumbers() {
        for(String[] candidate:java.util.List.of(
                new String[]{"가상현실스포츠체험센터 다른지점","부산광역시 금정구 체육공원로 399 324 경륜장 2층"},
                new String[]{"가상현실스포츠체험센터","부산광역시 동래구 체육공원로 399 324 경륜장 2층"},
                new String[]{"가상현실스포츠체험센터","부산광역시 금정구 중앙대로 399 324 경륜장 2층"},
                new String[]{"가상현실스포츠체험센터","부산광역시 금정구 체육공원로 398 324 경륜장 2층"},
                new String[]{"가상현실스포츠체험센터","부산광역시 금정구 체육공원로 399 325 경륜장 2층"})) {
            var b=RestClient.builder();var server=MockRestServiceServer.bindTo(b).build();
            server.expect(org.springframework.test.web.client.ExpectedCount.times(2),anything())
                .andRespond(withSuccess(sportsResponse(candidate[0],candidate[1]),MediaType.APPLICATION_JSON));
            var client=new GoogleOpeningHoursClient(b.build(),"secret",5,Clock.systemUTC());
            try {
                assertEquals(GoogleOpeningHours.State.UNKNOWN,client.session().lookup(sportsCenter()).assess(LocalDate.of(2026,11,24),"12:00",60).state());
                server.verify();
            }finally {client.close();}
        }
    }

}
