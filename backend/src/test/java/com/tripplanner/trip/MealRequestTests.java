package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient.*;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MealRequestTests {
    static final String REQUEST="마지막 날 저녁식사는 삼겹살로 추천해줘";
    static PlaceDraft food(String name,String categoryPath,String time) {
        return new PlaceDraft("123",name,"부산",35.,129.,time,"삼겹살 메뉴를 제공하는 맛집이라는 AI 설명","","식당",true,null,null,null,categoryPath);
    }
    static TripDraft draft(PlaceDraft first,PlaceDraft last) {
        return new TripDraft("부산","부산",null,null,null,List.of(),List.of(new DayDraft(1,null,List.of(first)),new DayDraft(2,null,List.of(last))),"AI",List.of(),REQUEST);
    }
    @Test void ordinaryMealPhrasingParsesDateMealAndFoodWithoutDemandingMoreDetail() {
        for(String text:List.of(REQUEST,"마지막 날 저녁은 삼겹살을 먹고 싶어","마지막날에는 저녁 식사를 삼겹살로 추천해줘","2일차 점심은 냉면으로 추천해주세요","첫날 아침은 국밥 포함해줘")) {
            assertDoesNotThrow(()->ExtraRequestRules.validateInput(text,2),text);
        }
        var meals=ExtraRequestRules.meals(REQUEST,2,2);
        assertEquals(1,meals.size()); assertEquals("삼겹살",meals.get(0).menu()); assertEquals("저녁",meals.get(0).meal());
        assertEquals(17*60,meals.get(0).start()); assertEquals(22*60,meals.get(0).end());
        assertTrue(ExtraRequestRules.meals(REQUEST,1,2).isEmpty());
        assertThrows(ApiException.class,()->ExtraRequestRules.validateInput("3일차 점심은 냉면 추천해줘",2));
    }
    @Test void verifiedBusinessEvidenceDateAndDinnerTimeAreAllRequired() {
        var pork=food("숯불집","음식점 > 한식 > 육류,고기 > 삼겹살","18:00");
        var chicken=food("치킨집","음식점 > 치킨","18:00");
        assertEquals("APPLIED",ExtraRequestRules.checks(draft(chicken,pork)).get(0).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft(pork,chicken)).get(0).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft(chicken,TripRouteOptimizer.at(pork,"12:00"))).get(0).status());
        assertEquals("UNMET",ExtraRequestRules.checks(draft(chicken,food("치킨집",null,"18:00"))).get(0).status());
        assertEquals("APPLIED",ExtraRequestRules.checks(draft(chicken,food("부산삼겹살집",null,"18:00"))).get(0).status());
        assertTrue(ExtraRequestRules.checks(draft(chicken,pork)).get(0).detail().contains("카카오맵 장소명·업종"));
    }
    static class Maps extends TripGenerationRulesTests.FakePlaces {
        @Override public Optional<Area> resolveArea(String q){return Optional.of(new Area(35.,129.,"부산"));}
        @Override public List<PlaceHit> search(String q,Area area,int size) {
            if(q.contains("삼겹살")) return List.of(
                new PlaceHit("wrong","치킨집","부산",35.,129.,null,null,"음식점",0,"FD6","음식점 > 치킨"),
                new PlaceHit("pork","연탄집","부산",35.,129.,null,null,"음식점",0,"FD6","음식점 > 한식 > 육류,고기 > 삼겹살"));
            if(q.contains("카페")) return List.of();
            boolean food=q.contains("식당")||q.contains("맛집");
            return List.of(new PlaceHit(q,"부산 "+q,"부산",35.,129.,null,null,food?"음식점":"대형마트",0,food?"FD6":"MT1",food?"음식점 > 한식":"가정,생활 > 대형마트"));
        }
    }
    static AiItinerary ai() {
        return new AiItinerary("부산",List.of(
            new AiDay(1,List.of(TripGenerationRulesTests.shop("상점A"),TripGenerationRulesTests.food("식당A")),List.of()),
            new AiDay(2,List.of(TripGenerationRulesTests.shop("상점B"),TripGenerationRulesTests.food("식당B")),List.of())));
    }
    @Test void generationAndEachVariantFindRealMenuCandidatesEvenWhenAiOmitsMeal() {
        var ai=new TripGenerationRulesTests.FakeAi(ai());
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new Maps());
        try {
            var options=service.generateOptions(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST,new TravelDetails(MovementMode.CAR,List.of(),null)));
            assertFalse(options.options().isEmpty());
            for(var option:options.options()) {
                assertTrue(ExtraRequestRules.violations(option.draft()).isEmpty());
                var pork=option.draft().days().get(1).places().stream().filter(p->p.placeId().equals("pork")).findFirst().orElseThrow();
                assertTrue(TravelSchedule.minute(pork.visitTime())>=17*60);
                assertNotNull(pork.categoryPath());
            }
        } finally {service.shutdown();}
    }
    @Test void restaurantNeededOnLastDayIsNotConsumedByAnEarlierGeneralFoodSearch() {
        var maps=new Maps(){ @Override public List<PlaceHit> search(String q,Area a,int size){
            var result=new ArrayList<>(super.search(q,a,size));
            if(!q.contains("삼겹살") && (q.contains("식당")||q.contains("맛집"))) result.add(0,new PlaceHit("pork","연탄집","부산",35.,129.,null,null,"음식점",0,"FD6","음식점 > 삼겹살"));
            return result;
        }};
        var service=new TripGenerationService(new TripGenerationRulesTests.FakeAi(ai()),new SampleItineraryGenerator(),maps);
        try {
            var d=service.generate(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST,new TravelDetails(MovementMode.CAR,List.of(),null)));
            assertTrue(ExtraRequestRules.violations(d).isEmpty());
            assertTrue(d.days().get(0).places().stream().noneMatch(p->p.placeId().equals("pork")));
        } finally {service.shutdown();}
    }
    @Test void recoveryAlsoSearchesSpecificFoodOnRequestedDayAndRetainsEvidence() {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var d=new ConfirmedItineraryFallback(new Maps(),pool).generate(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST),null,false);
            assertTrue(ExtraRequestRules.violations(d).isEmpty());
            assertTrue(d.days().get(0).places().stream().noneMatch(p->p.placeId().equals("pork")));
        } finally {pool.shutdownNow();}
    }
    @Test void noEvidenceIsReportedAsMenuFailureAndDinnerCannotFitBeforeAnEarlyTrain() {
        var maps=new Maps(){ @Override public List<PlaceHit> search(String q,Area a,int size){ return super.search(q,a,size).stream().filter(h->!h.placeId().equals("pork")).toList(); }};
        var service=new TripGenerationService(new TripGenerationRulesTests.FakeAi(ai()),new SampleItineraryGenerator(),maps);
        try {
            var failure=assertThrows(ApiException.class,()->service.generate(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST,new TravelDetails(MovementMode.CAR,List.of(),null))));
            assertTrue(failure.getMessage().contains("삼겹살"));
            assertFalse(failure.getMessage().contains("구체적으로"));
        } finally {service.shutdown();}
        var details=new TravelDetails(MovementMode.CAR,List.of(),new Transport(TransportMode.KTX,"부산역","10:00","부산역","16:00"));
        var service2=new TripGenerationService(new TripGenerationRulesTests.FakeAi(ai()),new SampleItineraryGenerator(),new Maps());
        try { assertThrows(ApiException.class,()->service2.generate(new GenerateRequest("부산",null,2,null,List.of("쇼핑"),REQUEST,details))); }
        finally {service2.shutdown();}
    }
    @Test void porkRestaurantsRemainCandidatesWithoutLiteralSamgyeopsalInBusinessName() {
        var meal=ExtraRequestRules.meals(REQUEST,2,2).get(0);
        assertTrue(meal.matchesPlace("하남돼지집", "식당", "음식점 > 한식 > 육류,고기요리 > 돼지고기구이"));
        assertFalse(meal.matchesPlace("치킨집", "식당", "음식점 > 치킨"));
        assertFalse(meal.matchesPlace("한우집", "식당", "음식점 > 소고기구이"));
        var report=ExtraRequestRules.checks(draft(food("치킨집","음식점 > 치킨","12:00"),food("하남돼지집","음식점 > 돼지고기구이","18:00"))).get(0);
        assertEquals("APPLIED",report.status());
        assertTrue(report.detail().contains("관련 식당 후보"));
        assertTrue(report.detail().contains("메뉴 확인 필요"));
    }
    @Test void earlyReturnIsReportedAsTimeConflictRatherThanNoRestaurants() {
        var original=draft(food("치킨집","음식점 > 치킨","12:00"),food("치킨집","음식점 > 치킨","12:00"));
        var details=new TravelDetails(MovementMode.CAR,List.of(),new Transport(TransportMode.KTX,"부산역","10:00","부산역","18:00"));
        var d=new TripDraft(original.title(),original.destination(),null,null,null,List.of(),original.days(),"AI",List.of(),REQUEST,details);
        var report=ExtraRequestRules.checks(d).get(0);
        assertEquals("UNMET",report.status()); assertTrue(report.detail().contains("60분을 확보"));
        assertFalse(report.detail().contains("구체적으로"));
    }
    @Test void categoryEvidenceSurvivesJsonAndTimeCopies() throws Exception {
        var original=food("연탄집","음식점 > 삼겹살","18:00");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(original,mapper.readValue(mapper.writeValueAsString(original),PlaceDraft.class));
        assertEquals(original.categoryPath(),TripRouteOptimizer.at(original,"19:00").categoryPath());
        assertNull(mapper.readValue("{\"name\":\"기존 식당\",\"category\":\"식당\",\"verified\":true}",PlaceDraft.class).categoryPath());
    }
}
