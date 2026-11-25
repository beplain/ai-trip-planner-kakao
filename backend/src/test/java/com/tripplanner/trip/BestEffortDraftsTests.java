package com.tripplanner.trip;

import com.tripplanner.ai.*;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.trip.TripDtos.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;


class BestEffortDraftsTests {
    private static final LocalDate DATE=LocalDate.of(2026,11,23);
    private GenerateRequest request(int days,TravelDetails details) {return new GenerateRequest("강원도",DATE,days,null,List.of("맛집","쇼핑","체험","카페"),null,details);}
    private PlaceDraft place(String id,String time) {return new PlaceDraft(id,"방문 장소 "+id,"강원특별자치도 삼척시",37.45,129.16,time,"방문 매력",null,"체험",true,null,null,null);}
    private TripDraft seed(GenerateRequest req,List<PlaceDraft> visits) {return new TripDraft("강원 여행",req.destination(),DATE,DATE.plusDays(req.days()-1),null,req.styles(),List.of(new DayDraft(1,DATE,visits)),"AI",List.of(),null,req.travelDetails(),null,null);}
    @Test void unmetMealsAndCountsKeepUsableVisitsAndAllSevenDays() {
        var req=request(7,null);
        var draft=BestEffortDrafts.create(req,seed(req,List.of(place("1","12:00"))),ApiException.badRequest("1일차에 필요한 식사 시간대를 채우지 못했습니다."));
        assertEquals(7,draft.days().size());assertEquals("1",draft.days().get(0).places().get(0).placeId());
        assertTrue(draft.warnings().stream().anyMatch(w->w.contains("식사 시간대")));
        assertTrue(draft.warnings().stream().anyMatch(w->w.contains("7일차")));
        assertDoesNotThrow(()->DraftVariants.validate(draft));
    }
    @Test void fallbackKeepsExactTransportAndHotelEventsAndRemovesConflicts() {
        var stay=new Stay("쏠비치 삼척","강원특별자치도 삼척시 수로부인길 453",1,7,"15:00","11:00",LuggagePlan.CARRY,LuggagePlan.CARRY);
        var transport=new Transport(TransportMode.KTX,"원주종합버스터미널","09:26","원주종합버스터미널","20:00");
        var req=request(7,new TravelDetails(MovementMode.CAR,List.of(stay),transport));
        var draft=BestEffortDrafts.create(req,seed(req,List.of(place("1","09:30"),place("2","15:00"),place("3","17:00"))),new RuntimeException("private infrastructure detail"));
        assertEquals(TravelSchedule.events(req.travelDetails(),7).size(),draft.days().stream().flatMap(d->d.places().stream()).filter(p->p.fixedEventId()!=null).count());
        assertTrue(draft.days().get(0).places().stream().anyMatch(p->"3".equals(p.placeId())));
        assertFalse(draft.days().get(0).places().stream().anyMatch(p->"1".equals(p.placeId())||"2".equals(p.placeId())));
        assertTrue(draft.warnings().stream().noneMatch(w->w.contains("private infrastructure")));
        assertDoesNotThrow(()->DraftVariants.validate(draft));
    }
    @Test void externalOutageStillReturnsEditableOptionsWithoutInventingPlaces() {
        var ai=new OpenAiItineraryClient(null,null,new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override public String interpretRequest(String request,int days) {throw new IllegalStateException("outage");}
        };
        var service=new TripGenerationService(ai,new SampleItineraryGenerator(),new KakaoLocalClient(null,null));
        try {
            var result=service.generateOptions(request(7,null));assertEquals(3,result.options().size());
            for(var option:result.options()) {assertEquals(7,option.draft().days().size());assertTrue(option.draft().days().stream().allMatch(d->d.places().isEmpty()));assertFalse(option.draft().warnings().isEmpty());}
            var single=service.generate(request(1,null));assertEquals("BEST_EFFORT",single.source());
        } finally {service.shutdown();}
    }
    @Test void restaurantSearchIncludesArrivalCityAsWellAsHotelCity() {
        var queries=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var maps=new KakaoLocalClient(null,null) {
            @Override public java.util.Optional<Area> resolveArea(String destination) {return java.util.Optional.empty();}
            @Override public List<PlaceHit> search(String query,Area area,int size) {queries.add(query);return List.of();}
        };
        var arrival=new SelectedPlace("123","원주종합버스터미널","강원특별자치도 원주시 서원대로 171",37.34,127.93,null);
        var transport=new Transport(TransportMode.KTX,arrival.name(),"09:26",arrival.name(),"20:00",arrival,arrival);
        var stay=new Stay("쏠비치 삼척","강원특별자치도 삼척시 수로부인길 453",1,7,"15:00","11:00",LuggagePlan.CARRY,LuggagePlan.CARRY,"456",37.47,129.16,null);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {DraftMealPlanner.search("강원도",7,new TravelDetails(MovementMode.CAR,List.of(stay),transport),maps,executor);}
        finally {executor.shutdownNow();}
        assertTrue(queries.stream().anyMatch(q->q.startsWith("강원특별자치도 원주시 ")));
        assertTrue(queries.stream().anyMatch(q->q.startsWith("강원특별자치도 삼척시 ")));
    }
    @Test void excludedAndClosedPlacesAreNotForcedIntoFallback() {
        var req=new GenerateRequest("강원도",DATE,1,null,List.of("쇼핑"),"다이소는 제외해줘",null);
        var excluded=new PlaceDraft("5","다이소 삼척점","삼척",37.45,129.16,"12:00",null,null,"쇼핑",true,null,null,null);
        var closed=place("7","14:00").withBusinessHours(new com.tripplanner.place.BusinessHoursInfo(null,"09:00~18:00","매주 월요일 휴무",null,DATE.toString(),"UNKNOWN",null));
        var result=BestEffortDrafts.create(req,seed(req,List.of(excluded,closed)),ApiException.badRequest("일부 조건 미충족"));
        assertTrue(result.days().get(0).places().isEmpty());
    }
}
