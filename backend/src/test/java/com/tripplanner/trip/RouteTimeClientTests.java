package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class RouteTimeClientTests {
    @Test void independentLegsRunConcurrentlyWithoutDuplicatingOrExceedingBudget() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var arrived = new java.util.concurrent.CountDownLatch(3);
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),anything()).andRespond(request -> {
            arrived.countDown();
            try { assertTrue(arrived.await(3,java.util.concurrent.TimeUnit.SECONDS),"Three lookups must overlap"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}",MediaType.APPLICATION_JSON).createResponse(request);
        });
        var client = new RouteTimeClient("test","",3,builder.build());
        try {
            var s = client.session(MovementMode.CAR);
            var path = List.of(p("a",129,"10:00"),p("b",129.1,"12:00"),p("c",129.2,"14:00"),p("d",129.3,"16:00"));
            s.prefetch(List.of(path,path));
            assertEquals(10,s.minutes(path.get(0),path.get(1)).getAsInt());
            assertTrue(s.minutes(path.get(1),path.get(0)).isEmpty());
            server.verify();
        } finally { client.shutdown(); }
    }
    static PlaceDraft p(String name, double lng, String time) {
        return new PlaceDraft(name,name,"주소",35.0,lng,time,"","","관광",true,"url",null);
    }
    @Test void carUsesDirectionalRoadMinutesAndCachesWithinOneGeneration() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("apis-navi.kakaomobility.com")))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":1201}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("origin=129.1")))
                .andRespond(withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}", MediaType.APPLICATION_JSON));
        var session = new RouteTimeClient("test-key","",3,builder.build()).session(MovementMode.CAR);
        var a = p("a",129,"10:00"); var b = p("b",129.1,"12:00");
        assertEquals(21,session.minutes(a,b).getAsInt()); assertEquals(21,session.minutes(a,b).getAsInt());
        assertEquals(10,session.minutes(b,a).getAsInt()); server.verify();
    }
    @Test void transitReadsCompleteUrbanJourneyAndHonorsCallBudget() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andExpect(queryParam("start_x", "129.0")).andExpect(queryParam("start_y", "35.0"))
                .andExpect(queryParam("end_x", "129.1")).andExpect(queryParam("end_y", "35.0"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(
                "{\"status\":\"OK\",\"routes\":[{\"properties\":{\"type\":\"BUS_AND_SUBWAY\",\"totalTime\":2821}},{\"properties\":{\"type\":\"BUS\",\"totalTime\":3900}}]}", MediaType.APPLICATION_JSON));
        var session = new RouteTimeClient("","test-key",1,builder.build()).session(MovementMode.PUBLIC_TRANSIT);
        assertEquals(48,session.minutes(p("a",129,"10:00"),p("b",129.1,"12:00")).getAsInt());
        assertTrue(session.minutes(p("b",129.1,"12:00"),p("a",129,"10:00")).isEmpty());
        assertTrue(session.incomplete()); server.verify();
    }
    @Test void noResultsAndAuthenticationErrorsAreNotReportedAsSuccessfulRoutes() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"NO_RESULTS\",\"routes\":[{\"properties\":{\"type\":\"BUS\",\"totalTime\":10}}]}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withUnauthorizedRequest());
        var session = new RouteTimeClient("","test",3,builder.build()).session(MovementMode.PUBLIC_TRANSIT);
        assertTrue(session.minutes(p("a",129,"10:00"),p("b",129.1,"12:00")).isEmpty());
        assertTrue(session.minutes(p("b",129.1,"12:00"),p("a",129,"10:00")).isEmpty());
        assertTrue(session.incomplete()); server.verify();
    }
    @Test void missingKeyDoesNotSendRequests() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var session = new RouteTimeClient("","",12,builder.build()).session(MovementMode.CAR);
        assertFalse(session.enabled()); assertTrue(session.minutes(p("a",129,"10:00"),p("b",129.1,"12:00")).isEmpty()); server.verify();
    }
}
