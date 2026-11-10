package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.place.KakaoLocalClient.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftOptionsTests {
    @Test void sevenDayOptionsKeepDatesAndNeverRepeatSharedCandidateAcrossDays() {
        var date = java.time.LocalDate.of(2026,11,1);
        List<DayDraft> days = new ArrayList<>(); Map<Integer,List<PlaceDraft>> pool = new HashMap<>();
        for (int day=1;day<=7;day++) {
            var visits = List.of(shop(day*10,"10:00"),shop(day*10+1,"12:00"),shop(day*10+2,"14:00"));
            days.add(new DayDraft(day,date.plusDays(day-1L),visits));
            var candidates = new ArrayList<>(visits); candidates.add(shop(999,"16:00")); pool.put(day,candidates);
        }
        var original = base(new TravelDetails(MovementMode.CAR,List.of(),null),days);
        for (var option : DraftOptionComposer.compose(original,pool)) {
            assertEquals(7,option.days().size());
            assertEquals(days.stream().map(DayDraft::date).toList(),option.days().stream().map(DayDraft::date).toList());
            var visits = option.days().stream().flatMap(d -> d.places().stream()).toList();
            assertEquals(visits.size(),visits.stream().map(PlaceDraft::placeId).distinct().count());
            assertTrue(option.days().stream().allMatch(d -> d.places().stream().anyMatch(p -> "쇼핑".equals(p.category()))));
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(option));
        }
    }
    @Test void sevenDayEightStylesMayBeDistributedAcrossDaysForAllThreeProfiles() {
        String[] categories={"식당","自然","自然","쇼핑","문화","체험","카페","관광"};
        categories[1]="자연";categories[2]="자연";
        var date=java.time.LocalDate.of(2026,11,23);
        var days=new ArrayList<DayDraft>();var pool=new HashMap<Integer,List<PlaceDraft>>();
        int id=0;
        for(int day=1;day<=7;day++) {
            var visits=new ArrayList<PlaceDraft>();
            for(int n=0;n<3;n++) {
                String category=categories[Math.min(day-1,7)];
                if(day==7&&n==1)category="관광";
                var p=new PlaceDraft("p"+(id++),"장소"+id,"부산",35.0,129.0,day==7&&n==1?"19:00":n==0?"10:00":n==1?"12:00":"14:00",null,null,category,true,null,null);
                visits.add(p);
            }
            visits.sort(Comparator.comparing(PlaceDraft::visitTime));
            days.add(new DayDraft(day,date.plusDays(day-1),visits));pool.put(day,visits);
        }
        var original=new TripDraft("여행","부산",date,date.plusDays(6),null,ItineraryRules.STYLES,days,"AI",List.of(),null,new TravelDetails(MovementMode.CAR,List.of(),null));
        var options=DraftOptionComposer.compose(original,pool);
        assertEquals(3,options.size());
        assertEquals(3,options.stream().map(DraftOptionComposer::fingerprint).distinct().count());
        options.forEach(option->{assertDoesNotThrow(()->DraftOptionComposer.requireCoverage(option));assertDoesNotThrow(()->TravelSchedule.validateDraft(option));});
    }
    @Test void restoresShoppingRemovedByWalkingAdjustmentUsingNearbyUnusedCandidate() {
        var date=java.time.LocalDate.of(2026,11,23);
        var food=new PlaceDraft("food","식당","경상북도 경주시",35.84,129.21,"12:00",null,null,"식당",true,null,null);
        var food2=new PlaceDraft("food2","다른식당","경상북도 경주시",35.841,129.21,"12:00",null,null,"식당",true,null,null);
        var nearShop=new PlaceDraft("near","가까운상점","경상북도 경주시",35.842,129.21,"14:00",null,null,"쇼핑",true,null,null);
        var original=new TripDraft("경주","경상북도 경주시",date,date.plusDays(1),null,List.of("맛집","쇼핑"),
            List.of(new DayDraft(1,date,List.of(food)),new DayDraft(2,date.plusDays(1),List.of(food2))),"AI",List.of(),null,new TravelDetails(MovementMode.WALK,List.of(),null));
        var candidates=DraftOptionComposer.recoveryCandidates(original,Map.of(1,List.of(nearShop)));
        assertFalse(candidates.isEmpty());
        var client=new RouteTimeClient("","",0,org.springframework.web.client.RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build());
        try {
            var routed=new RoutedItineraryService(client).adjustOptions(candidates);
            assertTrue(routed.stream().anyMatch(d->{try{DraftOptionComposer.requireCoverage(d);TravelSchedule.validateDraft(d);return true;}catch(IllegalArgumentException e){return false;}}));
            assertTrue(routed.stream().anyMatch(d->d.days().stream().flatMap(day->day.places().stream()).anyMatch(p->p.placeId().equals("near"))));
        }finally{client.shutdown();}
    }
    @Test void twoDayGyeongjuWalkingFoodAndShoppingProducesThreeAfterFarShopIsDropped() {
        var date=java.time.LocalDate.of(2026,11,23);
        var first=new AiDay(1,List.of(
            new AiPlace("식당1","식당1","10:00","식당","","",List.of("맛집")),
            new AiPlace("상점먼곳","상점먼곳","12:00","쇼핑","","",List.of("쇼핑")),
            new AiPlace("식당2","식당2","14:00","식당","","",List.of("맛집"))),
            java.util.stream.IntStream.range(1,5).mapToObj(n->new AiPlace("상점근처"+n,"상점근처"+n,"16:00","쇼핑","","",List.of("쇼핑"))).toList());
        var second=new AiDay(2,List.of(
            new AiPlace("식당3","식당3","10:00","식당","","",List.of("맛집")),
            new AiPlace("식당4","식당4","12:00","식당","","",List.of("맛집"))),List.of());
        var ai=new TripGenerationRulesTests.FakeAi(new AiItinerary("경주 여행",List.of(first,second)));
        var maps=new TripGenerationRulesTests.FakePlaces(){
            @Override public List<PlaceHit> search(String q,Area a,int size){return List.of(new PlaceHit(q,q,"경상북도 경주시",q.contains("먼곳")?36.84:35.84,129.21+Math.floorMod(q.hashCode(),10)*0.0001,null,null,q.startsWith("식당")?"음식점":"대형마트",null));}
        };
        var routes=new RouteTimeClient("","",0,org.springframework.web.client.RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory()).build());
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),maps,new RoutedItineraryService(routes));
        try {
            var result=service.generateOptions(new GenerateRequest("경상북도 경주시",date,2,null,List.of("맛집","쇼핑"),null,new TravelDetails(MovementMode.WALK,List.of(),null)));
            assertEquals(3,result.options().size());
            assertEquals(3,result.options().stream().map(o->DraftOptionComposer.fingerprint(o.draft())).distinct().count());
            for(var option:result.options()) {assertDoesNotThrow(()->DraftOptionComposer.requireCoverage(option.draft()));assertDoesNotThrow(()->TravelSchedule.validateDraft(option.draft()));}
        }finally{service.shutdown();routes.shutdown();}
    }
    static PlaceDraft shop(int i, String time) {
        return new PlaceDraft("shop"+i,"상점"+i,"부산",35.0,129.0+i*0.001,time,"추천","","쇼핑",true,"url",null);
    }
    static TripDraft base(TravelDetails details, List<DayDraft> days) {
        return new TripDraft("부산 여행","부산",null,null,"300000",List.of("쇼핑"),days,"AI",List.of(),null,details);
    }
    @Test void composesDistinctOptionsWithoutLosingStylesOrRepeatingPlaces() {
        var details = new TravelDetails(MovementMode.CAR,List.of(),null);
        var original = base(details,List.of(new DayDraft(1,null,List.of(shop(0,"10:00"),shop(1,"12:00"),shop(2,"14:00")))));
        var candidates = java.util.stream.IntStream.range(0,8).mapToObj(i -> shop(i,"10:00")).toList();
        var result = DraftOptionComposer.compose(original,Map.of(1,candidates));
        assertEquals(3,result.size());
        assertEquals(3,result.stream().map(DraftOptionComposer::fingerprint).distinct().count());
        assertTrue(result.get(1).days().get(0).places().size() < original.days().get(0).places().size());
        for (var option : result) {
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(option));
            assertEquals(original.travelDetails(),option.travelDetails()); assertEquals(original.styles(),option.styles());
            var visits = option.days().stream().flatMap(d -> d.places().stream()).toList();
            assertEquals(visits.size(),visits.stream().map(PlaceDraft::placeId).distinct().count());
            assertTrue(visits.stream().allMatch(PlaceDraft::verified));
        }
        assertEquals("10:00",original.days().get(0).places().get(0).visitTime());
    }
    @Test void everyVariantPreservesAllFixedEventsAndCrossDayUniqueness() {
        var details = TravelScheduleTests.selectedHotel();
        var days = new ArrayList<DayDraft>(); var pool = new HashMap<Integer,List<PlaceDraft>>();
        for (var fixed : TravelScheduleTests.fixedDays(details,2)) {
            var places = new ArrayList<>(fixed.places());
            var visit = shop(fixed.dayNumber(), fixed.dayNumber() == 1 ? "17:00" : "13:00");
            places.add(visit); places.sort(Comparator.comparing(PlaceDraft::visitTime));
            days.add(new DayDraft(fixed.dayNumber(),null,places));
            pool.put(fixed.dayNumber(),List.of(visit,shop(10+fixed.dayNumber(),"19:00")));
        }
        var original = base(details,days);
        for (var result : DraftOptionComposer.compose(original,pool)) {
            assertDoesNotThrow(() -> TravelSchedule.validateDraft(result));
            assertEquals(original.days().stream().flatMap(d -> d.places().stream()).filter(p -> p.fixedEventId()!=null).toList(),
                    result.days().stream().flatMap(d -> d.places().stream()).filter(p -> p.fixedEventId()!=null).toList());
        }
    }
    @Test void generationCallsAiOnceAndOnlyVerifiesAlreadyFetchedAlternatives() {
        var places = new TripGenerationRulesTests.FakePlaces() {
            @Override public List<PlaceHit> search(String q, Area a, int size) {
                return List.of(new PlaceHit(q,q,"부산",35,129,null,"url","대형마트",0,"MT1","가정,생활 > 대형마트"));
            }
        };
        var primary = List.of(new AiPlace("상점0","상점0","10:00","쇼핑","","",List.of("쇼핑")),
                new AiPlace("상점1","상점1","12:00","쇼핑","","",List.of("쇼핑")),
                new AiPlace("상점2","상점2","14:00","쇼핑","","",List.of("쇼핑")));
        var alternatives = java.util.stream.IntStream.range(3,8).mapToObj(i -> new AiPlace("상점"+i,"상점"+i,"16:00","쇼핑","","",List.of("쇼핑"))).toList();
        var ai = new TripGenerationRulesTests.FakeAi(new AiItinerary("부산",List.of(new AiDay(1,primary,alternatives))));
        var service = new TripGenerationService(ai,new SampleItineraryGenerator(),places);
        try {
            var result = service.generateOptions(new GenerateRequest("부산",null,1,null,List.of("쇼핑"),null,new TravelDetails(MovementMode.CAR,List.of(),null)));
            assertEquals(1,ai.calls); assertEquals(3,result.options().size());
            assertEquals(List.of("compact","relaxed","varied"),result.options().stream().map(DraftOption::id).toList());
            assertEquals(3,result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
        } finally { service.shutdown(); }
    }
    @Test void limitedCandidatesStillProduceThreeDistinctProfiles() {
        var ai = new TripGenerationRulesTests.FakeAi(new AiItinerary("부산",List.of(new AiDay(1,List.of(
                TripGenerationRulesTests.shop("상점A"),TripGenerationRulesTests.food("식당A")),List.of()))));
        var service = new TripGenerationService(ai,new SampleItineraryGenerator(),new TripGenerationRulesTests.FakePlaces());
        try {
            var result = service.generateOptions(new GenerateRequest("부산",null,1,null,List.of("쇼핑"),null));
            assertEquals(3,result.options().size());
            assertEquals(result.options().size(),result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
            assertTrue(result.warnings().isEmpty());
        } finally { service.shutdown(); }
    }
}
