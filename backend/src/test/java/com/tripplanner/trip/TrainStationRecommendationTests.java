package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TrainStationRecommendationTests {
    static final LocalDate DATE=LocalDate.of(2027,5,1);
    static SelectedPlace place(String id,String name,double x) { return new SelectedPlace(id,name,"서울특별시 종로구",37.,x,null); }
    static final SelectedPlace ORIGIN=place("1","광화문",127.),A=place("2","서울역",127.01),B=place("3","용산역",127.02),DEST=place("4","부산역",129.);
    static Transport request() { return new Transport(TransportMode.KTX,"부산역","10:00","부산역","18:00",DEST,DEST,ORIGIN,"08:00",true); }
    static TrainOption train() { return new TrainOption("direct",DATE,"1","KTX","용산","부산","08:30","11:00"); }
    static class Trains extends TrainTimetableClient {
        final List<String> calls=new ArrayList<>();
        Trains() { super("test",null); }
        @Override public List<TrainOption> options(Transport t,LocalDate date) {
            assertEquals(DATE,date); assertEquals(ORIGIN,t.originLocation());
            var station=t.boardingStation(); calls.add(station.placeId());
            if(station.equals(A)) throw ApiException.badRequest("직통열차 없음");
            return List.of(train());
        }
    }
    static TrainStationRecommendationService service(Trains trains) {
        return new TrainStationRecommendationService(trains,"test",null) {
            @Override List<SelectedPlace> nearby(SelectedPlace origin) { assertEquals(ORIGIN,origin); return List.of(A,B); }
        };
    }
    @Test void choosesTheFirstNearbyStationWithAnActualDirectTimetableAndPreservesTheOriginalPlace() {
        var trains=new Trains(); var result=service(trains).recommend(request(),DATE);
        assertEquals(B,result.station()); assertEquals(List.of("2","3"),trains.calls); assertEquals(List.of(train()),result.trains());
        assertTrue(result.distanceMeters()>0); assertEquals("광화문",request().originLocation().name());
    }
    @Test void manualStationIsRespectedAndUnavailableStationIsNotSilentlyReplaced() {
        var trains=new Trains(); var service=service(trains);
        assertEquals(B,service.recommend(request().withStations(B,null),DATE).station());
        assertThrows(ApiException.class,()->service.recommend(request().withStations(A,null),DATE));
        assertEquals(List.of("3","2"),trains.calls);
    }
    @Test void candidateListDoesNotQueryAnyTrainTimetablesAndExcludesTheDestination() {
        var trains=new Trains(); var service=service(trains);
        assertEquals(List.of(A,B),service.candidates(ORIGIN,null));
        assertEquals(List.of(B),service.candidates(ORIGIN,A));
        assertTrue(trains.calls.isEmpty());
    }
    @Test void providerOutagesAreNotMisreportedAsNoTrains() {
        var trains=new Trains() { @Override public List<TrainOption> options(Transport t,LocalDate date) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TRAIN_UNAVAILABLE","조회 실패"); } };
        assertEquals("TRAIN_UNAVAILABLE",assertThrows(ApiException.class,()->service(trains).recommend(request(),DATE)).getCode());
    }
    @Test void kakaoSearchFiltersNonRailPlacesButRetainsStationsBeyondTwentyKilometers() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertFalse(org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().containsKey("radius"))).andExpect(queryParam("sort","distance")).andRespond(withSuccess("""
            {"documents":[
              {"id":"2","place_name":"서울역 경부선","address_name":"서울특별시 용산구","x":"127.01","y":"37","category_name":"교통 > 기차역 > KTX정차역"},
              {"id":"3","place_name":"서울역 다른 건물","address_name":"서울특별시 용산구","x":"127.011","y":"37","category_name":"교통 > 기차역 > KTX정차역"},
              {"id":"5","place_name":"서울역 출구","category_name":"교통 > 기차역 > KTX정차역"},
              {"id":"6","place_name":"광화문역","category_name":"교통 > 지하철역 > KTX정차역"},
              {"id":"7","place_name":"폐역","category_name":"교통 > 기차역 > KTX정차역 > 폐역"},
              {"id":"8","place_name":"먼역","address_name":"부산","x":"129","y":"35","category_name":"교통 > 기차역 > KTX정차역"}]}
            """,MediaType.APPLICATION_JSON));
        server.expect(queryParam("sort","distance")).andRespond(withSuccess("""
            {"documents":[
              {"id":"10","place_name":"광명역 경부고속선","address_name":"경기도 광명시","x":"127.02","y":"37","category_name":"교통 > 기차역 > 경부고속철도선"},
              {"id":"11","place_name":"아산역","address_name":"충남","x":"127.001","y":"37","category_name":"교통 > 기차역 > 장항선"},
              {"id":"12","place_name":"서울역 KTX카페","address_name":"서울","x":"127.001","y":"37","category_name":"음식점 > 카페"}]}
            """,MediaType.APPLICATION_JSON));
        var result=new TrainStationRecommendationService(new Trains(),"SECRET",builder.build()).nearby(ORIGIN);
        assertEquals(List.of("2","10","8"),result.stream().map(SelectedPlace::placeId).toList());
        assertTrue(com.tripplanner.place.Geo.distanceMeters(ORIGIN.latitude(),ORIGIN.longitude(),result.get(2).latitude(),result.get(2).longitude())>20000); server.verify();
    }
    @Test void boardingSelectionsSurviveJsonAndReturnQueriesUseTheReturnBoardingStation() throws Exception {
        var selected=request().withStations(A,B);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertEquals(selected,mapper.readValue(mapper.writeValueAsString(selected),Transport.class));
        assertEquals(ORIGIN,selected.originLocation());
        assertEquals(A,selected.boardingStation()); assertEquals(B,selected.returnBoardingStation());
    }
    @Test void airportCanRecommendSeoulStationOutsideTheFormerRadius() {
        var airport=new SelectedPlace("9","인천국제공항","인천광역시 중구",37.46,126.45,null);
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertFalse(org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().containsKey("radius"))).andExpect(queryParam("sort","distance")).andRespond(withSuccess("""
            {"documents":[{"id":"2","place_name":"서울역","address_name":"서울특별시 용산구","x":"126.97","y":"37.55","category_name":"교통 > 기차역 > KTX정차역"}]}
            """,MediaType.APPLICATION_JSON));
        server.expect(queryParam("sort","distance")).andRespond(withSuccess("{\"documents\":[]}",MediaType.APPLICATION_JSON));
        var trains=new Trains() { @Override public List<TrainOption> options(Transport t,LocalDate date) {
            assertEquals(airport,t.originLocation()); assertEquals("서울역",t.boardingStation().name());
            return List.of(new TrainOption("seoul-busan",date,"1","KTX","서울","부산","08:30","11:00"));
        }};
        var input=new Transport(TransportMode.KTX,"부산역","11:00","부산역","18:00",DEST,DEST,airport,"08:00",true);
        var result=new TrainStationRecommendationService(trains,"test",builder.build()).recommend(input,DATE);
        assertEquals("서울역",result.station().name()); assertTrue(result.distanceMeters()>20000);
        assertEquals("seoul-busan",result.trains().get(0).id()); server.verify();
    }
    @Test void highSpeedCandidatesRequireTheActualPlaceClassificationNotJustAKeyword() {
        assertTrue(TrainStationRecommendationService.highSpeedStation("서울역 경부선","교통 > 기차역 > KTX 정차역"));
        assertTrue(TrainStationRecommendationService.highSpeedStation("광명역 경부고속선","교통 > 기차역 > 고속철도선"));
        assertTrue(TrainStationRecommendationService.highSpeedStation("오송역","교통 > 기차역 > 호남고속선"));
        assertFalse(TrainStationRecommendationService.highSpeedStation("아산역","교통 > 기차역 > 장항선"));
        assertFalse(TrainStationRecommendationService.highSpeedStation("KTX 카페","음식점 > 카페"));
        assertFalse(TrainStationRecommendationService.highSpeedStation("서울역 1번출구","교통 > 기차역 > KTX정차역"));
        assertFalse(TrainStationRecommendationService.highSpeedStation("폐역","교통 > 기차역 > KTX정차역 > 폐역"));
    }

}
