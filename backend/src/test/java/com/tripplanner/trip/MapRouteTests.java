package com.tripplanner.trip;
import com.tripplanner.trip.TripDtos.MovementMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MapRouteTests {
    private final MapRouteController.Pair pair=new MapRouteController.Pair(new MapRouteController.Point(35.0,129.0),new MapRouteController.Point(35.1,129.1));
    @Test void carReturnsRoadVerticesRatherThanEndpointLine() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("summary","false")).andExpect(header("Authorization","KakaoAK car"))
            .andRespond(withSuccess("""
            {"routes":[{"result_code":0,"summary":{"duration":120},"sections":[{"roads":[{"vertexes":[129,35,129.02,35.04,129.1,35.1]}]}]}]}
            """,MediaType.APPLICATION_JSON));
        var result=new RouteTimeClient("car","transit",1,builder.build()).geometry(pair,MovementMode.CAR);
        assertEquals("OK",result.status()); assertEquals(3,result.segments().get(0).points().size());
        assertEquals(35.04,result.segments().get(0).points().get(1).latitude()); server.verify();
    }
    @Test void transitUsesFastestRouteAndKeepsWalkingBusAndSubwayPathsSeparate() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(header("Authorization","KakaoAK transit")).andRespond(withSuccess("""
        {"status":"OK","routes":[{"properties":{"type":"BUS","totalTime":900},"steps":[]},
        {"properties":{"type":"BUS_AND_SUBWAY","totalTime":600},"steps":[
        {"properties":{"type":"WALKING"},"path":{"points":[[129,35],[129.01,35.01]]}},
        {"properties":{"type":"BUS"},"path":{"points":[[129.01,35.01],[129.04,35.04]]}},
        {"properties":{"type":"SUBWAY"},"path":{"points":[[129.04,35.04],[129.1,35.1]]}}]}]}
        """,MediaType.APPLICATION_JSON));
        var result=new RouteTimeClient("car","transit",1,builder.build()).geometry(pair,MovementMode.PUBLIC_TRANSIT);
        assertEquals("OK",result.status()); assertEquals(List.of("WALKING","BUS","SUBWAY"),result.segments().stream().map(MapRouteController.Segment::mode).toList()); server.verify();
    }
    @Test void invalidCoordinatesNeverBecomeInventedStraightLines() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(queryParam("summary","false")).andRespond(withSuccess("""
        {"routes":[{"result_code":0,"summary":{"duration":120},"sections":[{"roads":[{"vertexes":[129,35,999,35.1]}]}]}]}
        """,MediaType.APPLICATION_JSON));
        var result=new RouteTimeClient("car","",1,builder.build()).geometry(pair,MovementMode.CAR);
        assertEquals("UNAVAILABLE",result.status()); assertTrue(result.segments().isEmpty()); server.verify();
    }
    @Test void absentKeyAndLegacyWalkingDoNotMakeHttpCalls() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var client=new RouteTimeClient("","",1,builder.build());
        assertEquals("UNAVAILABLE",client.geometry(pair,MovementMode.CAR).status());
        assertEquals("UNAVAILABLE",client.geometry(pair,MovementMode.WALK).status()); server.verify();
    }
    @Test void failedLegDoesNotPreventOtherLegsAndOrderIsPreserved() {
        var client=new RouteTimeClient("","",0,RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build()) {
            @Override MapRouteController.Leg geometry(MapRouteController.Pair p,MovementMode mode) {
                if(p.from().latitude()==35.0) throw new IllegalStateException();
                return new MapRouteController.Leg("OK",List.of());
            }
        };
        var controller=new MapRouteController(client);
        try {
            var response=controller.routes(new MapRouteController.Request(MovementMode.CAR,List.of(pair,new MapRouteController.Pair(pair.to(),pair.from()))));
            assertEquals(List.of("UNAVAILABLE","OK"),response.legs().stream().map(MapRouteController.Leg::status).toList());
        } finally {controller.shutdown();}
    }
}
