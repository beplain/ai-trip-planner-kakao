package com.tripplanner.trip;

import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RoutedItineraryServiceTests {
    @Test void optionsShareOneRouteBudgetAndReuseLookupResults() {
        var b = RestClient.builder(); var server = MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}",MediaType.APPLICATION_JSON));
        var details = new TravelDetails(MovementMode.CAR,List.of(),null);
        var input = draft(List.of(RouteTimeClientTests.p("a",129,"10:00"),RouteTimeClientTests.p("b",129.01,"12:00")),details);
        var client = new RouteTimeClient("test","",1,b.build());
        try {
            var result = new RoutedItineraryService(client).adjustOptions(List.of(input,input,input));
            assertEquals(3,result.size()); result.forEach(d -> assertDoesNotThrow(() -> TravelSchedule.validateDraft(d)));
            server.verify();
        } finally { client.shutdown(); }
    }
    private TripDraft draft(List<PlaceDraft> places, TravelDetails details) {
        return new TripDraft("여행","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,places)),"AI",List.of(),null,details);
    }
    @Test void actualRoadTimePushesNextVisitLaterInsteadOfUsingStraightLineGap() {
        var b = RestClient.builder(); var server = MockRestServiceServer.bindTo(b).build();
        server.expect(ExpectedCount.manyTimes(),anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":5400}}]}",MediaType.APPLICATION_JSON));
        var service = new RoutedItineraryService(new RouteTimeClient("test","",12,b.build()));
        var details = new TravelDetails(MovementMode.CAR,List.of(),null);
        var result = service.adjust(draft(List.of(RouteTimeClientTests.p("a",129,"10:00"),RouteTimeClientTests.p("b",129.001,"11:30")),details));
        assertEquals("12:40",result.days().get(0).places().get(1).visitTime());
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(result)); server.verify();
    }
    @Test void unreachableVisitIsRemovedButFixedCommitmentRemainsIdentical() {
        var b = RestClient.builder(); var server = MockRestServiceServer.bindTo(b).build();
        server.expect(ExpectedCount.manyTimes(),anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":14400}}]}",MediaType.APPLICATION_JSON));
        var arrival = new SelectedPlace("1","부산역","부산",35.0,129.0,"url");
        var departure = new SelectedPlace("2","귀가역","부산",35.0,129.02,"url");
        var details = new TravelDetails(MovementMode.CAR,List.of(),new Transport(TransportMode.KTX,"부산역","09:00","귀가역","17:00",arrival,departure));
        var fixed = TravelSchedule.events(details,1).stream().map(TravelSchedule.Event::place).toList();
        var all = new ArrayList<>(fixed); all.add(RouteTimeClientTests.p("too-far",129.01,"11:00")); all.sort(Comparator.comparing(PlaceDraft::visitTime));
        var result = new RoutedItineraryService(new RouteTimeClient("test","",12,b.build())).adjust(draft(all,details));
        assertEquals(fixed,result.days().get(0).places());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("too-far")));
        assertDoesNotThrow(() -> TravelSchedule.validateDraft(result)); server.verify();
    }
    @Test void missingKeysKeepDraftAndExplainUnmeasuredRoutes() {
        var input = draft(List.of(RouteTimeClientTests.p("a",129,"10:00")),null);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var result = new RoutedItineraryService(new RouteTimeClient("","",12,builder.build())).adjust(input);
        server.verify();
        assertEquals(input.days(),result.days()); assertTrue(result.warnings().get(0).contains("키가 설정되지"));
    }
}
