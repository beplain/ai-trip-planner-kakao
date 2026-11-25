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
    @Test void nearbyVisitFollowsCheckInWithoutWaitingUntilTheOriginalEveningTime() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}", MediaType.APPLICATION_JSON));
        var stay = new Stay("숙소", "부산", 1, 2, "15:00", "11:00", LuggagePlan.CARRY,
                LuggagePlan.CARRY, "12345", 35.0, 129.0, null);
        var details = new TravelDetails(MovementMode.CAR, List.of(stay), null);
        var first = new ArrayList<>(TravelSchedule.events(details, 2).stream()
                .filter(e -> e.day() == 1).map(TravelSchedule.Event::place).toList());
        var cafe = new PlaceDraft("cafe", "숙소 근처 카페", "부산", 35.0, 129.001, "19:25", "", "", "카페", true, null, null);
        first.add(cafe);
        first.add(RouteTimeClientTests.p("저녁 산책", 129.002, "20:55"));
        var second = TravelSchedule.events(details, 2).stream().filter(e -> e.day() == 2).map(TravelSchedule.Event::place).toList();
        var input = new TripDraft("여행", "부산", null, null, null, List.of(), List.of(
                new DayDraft(1, null, first), new DayDraft(2, null, second)), "AI", List.of(), null, details);
        var client = new RouteTimeClient("test", "", 40, builder.build());
        try {
            var service = new RoutedItineraryService(client);
            var result = service.adjust(input, (place, time) -> !place.placeId().equals("cafe") || time.compareTo("16:00") >= 0);
            assertEquals("16:00", result.days().get(0).places().stream().filter(p -> p.placeId().equals("cafe")).findFirst().orElseThrow().visitTime());
            assertEquals("15:00", result.days().get(0).places().get(0).visitTime());
            assertTrue(result.days().get(0).places().stream().anyMatch(p -> p.fixedEventId() == null && p.visitTime().compareTo("19:00") >= 0));
            TravelSchedule.validateDraft(result);
            server.verify();
        } finally { client.shutdown(); }
    }

    @Test void closingIdleGapsStillHonorsTheRequestedStartTime() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(), anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}", MediaType.APPLICATION_JSON));
        var details = new TravelDetails(MovementMode.CAR, List.of(), null);
        var base = draft(List.of(RouteTimeClientTests.p("a", 129, "10:00"), RouteTimeClientTests.p("b", 129.01, "15:00")), details);
        var input = base.withRequestMetadata("모든 일정을 13시 이후로 생성해줘", null);
        var client = new RouteTimeClient("test", "", 12, builder.build());
        try {
            var result = new RoutedItineraryService(client).adjust(input);
            assertTrue(result.days().get(0).places().stream().allMatch(p -> p.visitTime().compareTo("13:00") >= 0));
            ExtraRequestRules.require(result);
        } finally { client.shutdown(); }
    }

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
    @Test void aLaterAdjustmentReusesLegsAlreadyFetchedForThisRequest() {
        var b = RestClient.builder(); var server = MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(
                "{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":600}}]}",MediaType.APPLICATION_JSON));
        var details = new TravelDetails(MovementMode.CAR,List.of(),null);
        var input = draft(List.of(RouteTimeClientTests.p("a",129,"10:00"),RouteTimeClientTests.p("b",129.01,"12:00")),details);
        var client = new RouteTimeClient("test","",1,b.build());
        try {
            var routes = new RoutedItineraryService(client);
            var session = routes.prefetch(List.of(input));
            routes.adjustOptions(List.of(input,input), d -> (p, time) -> true, session);
            routes.adjustOptions(List.of(input), d -> (p, time) -> true, session);
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
