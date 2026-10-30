package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LastRestaurantRequestTests {
    static final String REQUEST="마지막날 마지막 추천 식당은 삼겹살로 해줘";
    static TripDraft draft(PlaceDraft... last) {
        return new TripDraft("부산","부산",null,null,null,List.of(),List.of(
            new DayDraft(1,null,List.of(MealRequestTests.food("식당","음식점 > 한식","12:00"))),
            new DayDraft(2,null,List.of(last))),"AI",List.of(),REQUEST);
    }
    @Test void exactUserPhrasingAndCommonVariantsHaveNoDinnerHourRestriction() {
        for(String text:List.of(REQUEST,"마지막 날 마지막 식당은 삼겹살로 추천해줘","마지막 날 마지막 추천 식당은 삼겹살로 해줘","2일차 마지막 식당은 삼겹살로 해주세요")) {
            ExtraRequestRules.validateInput(text,2);
            var m=ExtraRequestRules.meals(text,2,2).get(0);
            assertTrue(m.lastRestaurant()); assertEquals("삼겹살",m.menu());
            assertTrue(m.containsTime("12:00")); assertTrue(m.containsTime("15:00"));
            assertTrue(ExtraRequestRules.meals(text,1,2).isEmpty());
        }
    }
    @Test void lastRestaurantMustMatchEvenIfPorkWasAlreadyVisitedEarlier() {
        var pork=MealRequestTests.food("연탄집","음식점 > 돼지고기구이","12:00");
        var chicken=MealRequestTests.food("치킨집","음식점 > 치킨","14:00");
        assertEquals("UNMET",ExtraRequestRules.checks(draft(pork,chicken)).get(0).status());
        assertEquals("APPLIED",ExtraRequestRules.checks(draft(chicken,TripRouteOptimizer.at(pork,"15:00"))).get(0).status());
        assertTrue(ExtraRequestRules.checks(draft(pork,chicken)).get(0).detail().contains("마지막 식당이"));
    }
    @Test void cafesAndTourismMayFollowTheLastRestaurant() {
        var pork=MealRequestTests.food("연탄집","음식점 > 돼지고기구이","13:00");
        var cafe=new PlaceDraft("cafe","커피집","부산",35.,129.,"15:00","","","카페",true,null,null);
        assertEquals("APPLIED",ExtraRequestRules.checks(draft(pork,cafe)).get(0).status());
        assertTrue(ExtraRequestRules.lastRestaurantsSatisfied(REQUEST,2,2,List.of(pork,cafe)));
    }
    @Test void generationAndVariantsPlaceRequestedRestaurantBeforeAnEighteenOClockReturn() {
        var service=new TripGenerationService(new TripGenerationRulesTests.FakeAi(MealRequestTests.ai()),new SampleItineraryGenerator(),new MealRequestTests.Maps());
        var details=new TravelDetails(MovementMode.CAR,List.of(),new Transport(TransportMode.KTX,"부산역","10:00","부산역","18:00"));
        try {
            var options=service.generateOptions(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST,details));
            assertFalse(options.options().isEmpty());
            for(var option:options.options()) {
                var d=option.draft();
                assertTrue(ExtraRequestRules.violations(d).isEmpty());
                var restaurant=d.days().get(1).places().stream().filter(p->"식당".equals(p.category())).reduce((a,b)->b).orElseThrow();
                assertEquals("pork",restaurant.placeId());
                assertTrue(TravelSchedule.minute(restaurant.visitTime())<17*60);
                assertTrue(TravelSchedule.minute(restaurant.visitTime())>=12*60);
                TravelSchedule.validateDraft(d);
            }
        } finally {service.shutdown();}
    }
    @Test void routeOptimizationCannotSwapAnotherRestaurantAfterTheRequestedFinalOne() {
        var chicken=MealRequestTests.food("치킨집","음식점 > 치킨","11:00");
        var pork=MealRequestTests.food("삼겹살집","음식점 > 돼지고기구이","13:00");
        var optimized=TripRouteOptimizer.optimize(draft(chicken,pork),(a,b)->a.name().equals("치킨집")&&b.name().equals("삼겹살집")?100.:1.);
        assertEquals("APPLIED",ExtraRequestRules.checks(optimized).get(0).status());
    }
    @Test void annotationDoesNotLetAnAiDinnerLabelReintroduceTheSeventeenOClockLimit() {
        var original=new PlaceDraft("pork","삼겹살집","부산",35.,129.,"13:00","저녁 삼겹살 식사로 추천","","식당",true,null,null,null,"음식점 > 돼지고기구이");
        var annotated=ExtraRequestRules.annotateMeal(original,REQUEST,2,2);
        assertTrue(TripRouteOptimizer.fits(annotated,"13:00",Map.of(annotated,13*60)));
        assertTrue(ExtraRequestRules.mealPrompt(REQUEST,2).contains("17시 이후라는 제한은 없습니다"));
    }
}
