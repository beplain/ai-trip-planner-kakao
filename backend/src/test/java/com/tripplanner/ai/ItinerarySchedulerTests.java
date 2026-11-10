package com.tripplanner.ai;

import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.trip.TravelSchedule;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ItinerarySchedulerTests {
    private static AiPlace p(String name, String time, String category, String... styles) {
        return new AiPlace(name, name, time, category, "", "", List.of(styles));
    }
    private static ItineraryRequest request() {
        return new ItineraryRequest("대구", null, 2, null, List.of("맛집"), null,
                new TravelDetails(MovementMode.PUBLIC_TRANSIT, List.of(new Stay("호텔인터불고 대구", "대구 수성구", 1, 2,
                        "15:00", "11:00", LuggagePlan.HOTEL, LuggagePlan.HOTEL)),
                        new Transport(TransportMode.KTX, "대구역", "10:00", "대구역", "19:00")));
    }
    @Test void removesHotelAndStationRecommendationsAndFitsPlacesAroundFixedTimes() {
        var input = new AiItinerary("대구", List.of(
                new AiDay(1, List.of(p("대구역", "10:00", "관광"), p("대구 막창", "11:00", "식당", "맛집"),
                        p("호텔인터불고 대구", "15:00", "기타"), p("동성로", "15:15", "관광")), List.of()),
                new AiDay(2, List.of(p("호텔인터불고 대구", "10:00", "기타"), p("대구역", "11:00", "관광"),
                        p("택시로 이동하여 대구역 도착", "12:00", "기타")), List.of(p("수성못 식당", "12:00", "식당", "맛집")))));
        var r = request(); var output = ItineraryScheduler.prepare(input, r);
        assertTrue(ItineraryRules.validate(output, r).isEmpty(), ItineraryRules.validate(output, r).toString());
        assertEquals(2, output.days().size());
        assertTrue(output.days().stream().allMatch(d -> d.places().stream().anyMatch(p -> ItineraryRules.stylesFor(p).contains("맛집"))));
        assertTrue(output.days().stream().flatMap(d -> d.places().stream()).noneMatch(p -> p.name().contains("호텔인터불고") || p.name().contains("대구역")));
        assertEquals(output, ItineraryScheduler.prepare(output, r));
    }
    @Test void doesNotIgnoreMissingDaysOrMissingRequiredStyles() {
        var r = request();
        var missing = new AiItinerary("대구", List.of(new AiDay(1, List.of(p("식당", "16:00", "식당", "맛집")), List.of())));
        assertTrue(ItineraryRules.validate(ItineraryScheduler.prepare(missing, r), r).stream().anyMatch(e -> e.contains("일차 수")));
        var noFood = new AiItinerary("대구", List.of(new AiDay(1, List.of(p("공원", "11:00", "관광")), List.of())));
        assertThrows(ItineraryScheduler.SchedulingException.class, () -> ItineraryScheduler.prepare(noFood, r));
    }
    @Test void cannotMoveNightStyleIntoDaytimeToMakeItFit() {
        var r = new ItineraryRequest("대구", null, 1, null, List.of("야경"), null,
                new TravelDetails(MovementMode.CAR, List.of(), new Transport(TransportMode.CAR, "대구역", "10:00", "대구역", "17:00")));
        var input = new AiItinerary("대구", List.of(new AiDay(1, List.of(p("전망대", "10:00", "관광", "야경")), List.of())));
        assertThrows(ItineraryScheduler.SchedulingException.class, () -> ItineraryScheduler.prepare(input, r));
        var late = new ItineraryRequest("대구", null, 1, null, List.of("야경"), null,
                new TravelDetails(MovementMode.CAR, List.of(), new Transport(TransportMode.CAR, "대구역", "10:00", "대구역", "23:00")));
        var result = ItineraryScheduler.prepare(input, late);
        assertTrue(TravelSchedule.minute(result.days().get(0).places().get(0).visitTime()) >= 18 * 60);
        assertTrue(ItineraryRules.validate(result, late).isEmpty());
    }
    @Test void styleIsNotRequiredAgainOnTheSecondDay() {
        var r=new ItineraryRequest("대구",null,2,null,List.of("카페"),null,
            new TravelDetails(MovementMode.CAR,List.of(),null));
        var input=new AiItinerary("여행",List.of(
            new AiDay(1,List.of(p("카페","10:00","카페","카페")),List.of()),
            new AiDay(2,List.of(p("공원","10:00","관광")),List.of())));
        var result=ItineraryScheduler.prepare(input,r);
        assertTrue(ItineraryRules.validate(result,r).isEmpty());
        assertEquals("공원",result.days().get(1).places().get(0).name());
    }
    @Test void legacyRequestsWithoutTravelDetailsKeepOriginalItinerary() {
        var input = new AiItinerary("대구", List.of(new AiDay(1, List.of(p("카페", "10:00", "카페")), List.of())));
        assertSame(input, ItineraryScheduler.prepare(input, new ItineraryRequest("대구", null, 1, null, List.of(), null)));
    }
    @Test void firstDayContinuesAfterCheckInThroughTwentyEvenIfAiOnlyGivesMorningTimes() {
        var r = request();
        var input = new AiItinerary("대구", List.of(
                new AiDay(1, List.of(p("점심 식당", "12:00", "식당", "맛집"), p("공원", "14:00", "관광")),
                        List.of(p("저녁 식당", "19:00", "식당", "맛집"))),
                new AiDay(2, List.of(p("다른 식당", "13:00", "식당", "맛집")), List.of())));
        var result = ItineraryScheduler.prepare(input, r);
        assertTrue(ItineraryRules.validate(result, r).isEmpty(), ItineraryRules.validate(result, r).toString());
        int last = result.days().get(0).places().stream().mapToInt(p -> TravelSchedule.minute(p.visitTime())).max().orElseThrow();
        assertTrue(last >= 19 * 60, "The final one-hour activity must run through at least 20:00");
    }
}
