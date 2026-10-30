package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class TransportArrivalServiceTests {
    private static RestClient noNetwork() {
        var builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }
    private static SelectedPlace selected(String id, double lng) {
        return new SelectedPlace(id,"장소"+id,"서울특별시",37.5,lng,null);
    }
    private static Transport input(TransportMode mode, String time, boolean auto) {
        return new Transport(mode,"도착역","10:00","귀가역","20:00",selected("2",127.1),selected("2",127.1),selected("1",127.0),time,auto);
    }
    @Test void carDurationOverridesStaleArrivalBeforePromptAndFixedEvents() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("apis-navi.kakaomobility.com")))
                .andRespond(withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":5401}}]}",MediaType.APPLICATION_JSON));
        var routes=new RouteTimeClient("test","",3,builder.build());
        try {
            var service=new TransportArrivalService(routes);
            var req=new GenerateRequest("서울특별시",null,2,"100000",List.of("맛집"),null,
                    new TravelDetails(MovementMode.PUBLIC_TRANSIT,List.of(),input(TransportMode.CAR,"08:00",true)));
            var resolved=service.resolve(req);
            assertEquals("09:31",resolved.travelDetails().transport().arrivalTime());
            assertEquals(601,TravelSchedule.start(1,resolved.travelDetails()));
            assertTrue(TravelSchedule.prompt(resolved.travelDetails(),2).contains("09:31 도착"));
            assertEquals("09:31",TravelSchedule.events(resolved.travelDetails(),2).stream().filter(e->e.id().equals("transport:arrival")).findFirst().orElseThrow().place().visitTime());
            assertEquals("10:00",req.travelDetails().transport().arrivalTime());
            server.verify();
        } finally { routes.shutdown(); }
    }
    @Test void busUsesTransitRegardlessOfInTripMovementMode() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/routing/publictraffic")))
                .andRespond(withSuccess("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"type\":\"BUS\",\"totalTime\":2400}}]}",MediaType.APPLICATION_JSON));
        var routes=new RouteTimeClient("","test",3,builder.build());
        try { assertEquals("08:40",new TransportArrivalService(routes).calculate(input(TransportMode.BUS,"08:00",true)).arrivalTime()); server.verify(); }
        finally { routes.shutdown(); }
    }
    @Test void unavailableRouteCannotInventArrival() {
        var routes=new RouteTimeClient("","",3,noNetwork());
        try { assertThrows(ApiException.class,()->new TransportArrivalService(routes).calculate(input(TransportMode.CAR,"08:00",true))); }
        finally { routes.shutdown(); }
    }
    @Test void trainAndManualArrivalsUseConfirmedTimeWithoutRoadLookup() {
        var routes=new RouteTimeClient("","",3,noNetwork());
        try {
            var service=new TransportArrivalService(routes);
            assertThrows(ApiException.class,()->service.calculate(input(TransportMode.KTX,"08:00",true)));
            var req=new GenerateRequest("서울",null,2,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),input(TransportMode.KTX,"08:00",false)));
            assertSame(req,service.resolve(req));
        } finally { routes.shutdown(); }
    }
    @Test void arrivalAfterMidnightIsRejectedInsteadOfWrappingToEarlierDay() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":7200}}]}",MediaType.APPLICATION_JSON));
        var routes=new RouteTimeClient("test","",3,builder.build());
        try { assertThrows(ApiException.class,()->new TransportArrivalService(routes).calculate(input(TransportMode.CAR,"23:00",true))); server.verify(); }
        finally { routes.shutdown(); }
    }
    @Test void manualArrivalMustFollowDepartureAndOldJsonStillLoads() throws Exception {
        var d=new TravelDetails(MovementMode.CAR,List.of(),input(TransportMode.KTX,"11:00",false));
        assertThrows(ApiException.class,()->TravelSchedule.validate(d,2));
        var mapper=new ObjectMapper();
        var old=mapper.readValue("{\"mode\":\"KTX\",\"arrivalPlace\":\"부산역\",\"arrivalTime\":\"10:00\",\"departurePlace\":\"부산역\",\"departureTime\":\"18:00\"}",Transport.class);
        assertFalse(old.arrivalAuto()); assertNull(old.originLocation());
        TravelSchedule.validate(new TravelDetails(MovementMode.CAR,List.of(),old),2);
        var original=input(TransportMode.CAR,"08:00",true);
        assertEquals(original,mapper.readValue(mapper.writeValueAsString(original),Transport.class));
    }

    @Test void returningCarAndBusUseHomewardCoordinatesAndPreserveDestinationInSavedDetails() {
        for(var mode:List.of(TransportMode.CAR,TransportMode.BUS)) {
            var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
            var expectation=server.expect(requestTo(containsString(mode==TransportMode.CAR ? "origin=127.1,37.5" : "start_x=127.1")));
            if(mode==TransportMode.CAR) expectation.andExpect(queryParam("destination","127.2,37.5"))
                .andRespond(withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":5400}}]}",MediaType.APPLICATION_JSON));
            else expectation.andExpect(queryParam("end_x","127.2"))
                .andRespond(withSuccess("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"type\":\"BUS\",\"totalTime\":5400}}]}",MediaType.APPLICATION_JSON));
            var routes=new RouteTimeClient("test","test",3,builder.build());
            try {
                var t=input(mode,"08:00",false); var home=selected("3",127.2);
                var transport=new Transport(t.mode(),t.arrivalPlace(),t.arrivalTime(),t.departurePlace(),"19:00",t.arrivalLocation(),t.departureLocation(),t.originLocation(),t.originTime(),false,null,home,null,"22:00");
                var request=new GenerateRequest("서울",null,2,null,List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),transport));
                var saved=new TransportArrivalService(routes).resolve(request).travelDetails().transport();
                assertEquals("20:30",saved.returnArrivalTime()); assertEquals(home,saved.returnArrivalLocation());
                assertEquals("10:00",saved.arrivalTime()); assertEquals("19:00",saved.departureTime()); server.verify();
            } finally { routes.shutdown(); }
        }
    }
}
