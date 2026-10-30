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
    @Test void insufficientCandidatesReturnUsableChoiceInsteadOfFakeDuplicates() {
        var ai = new TripGenerationRulesTests.FakeAi(new AiItinerary("부산",List.of(new AiDay(1,List.of(
                TripGenerationRulesTests.shop("상점A"),TripGenerationRulesTests.food("식당A")),List.of()))));
        var service = new TripGenerationService(ai,new SampleItineraryGenerator(),new TripGenerationRulesTests.FakePlaces());
        try {
            var result = service.generateOptions(new GenerateRequest("부산",null,1,null,List.of("쇼핑"),null));
            assertFalse(result.options().isEmpty()); assertTrue(result.options().size()<=3);
            assertEquals(result.options().size(),result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
            if (result.options().size()<3) assertFalse(result.warnings().isEmpty());
        } finally { service.shutdown(); }
    }
}
