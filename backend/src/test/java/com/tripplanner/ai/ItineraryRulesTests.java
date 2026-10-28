package com.tripplanner.ai;

import com.tripplanner.ai.AiItinerary.*;
import com.tripplanner.trip.TripDtos.GenerateRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ItineraryRulesTests {
    static AiPlace shop(String name) {
        return new AiPlace(name, name, "10:00", "쇼핑", "쇼핑", "", List.of("쇼핑"));
    }
    static AiPlace food(String name) {
        return new AiPlace(name, name, "12:00", "식당", "식사", "", List.of());
    }
    static ItineraryRequest request(int days) {
        return new ItineraryRequest("부산", null, days, null, List.of("쇼핑"), null);
    }
    static AiItinerary sevenDays() {
        return new AiItinerary("여행", IntStream.rangeClosed(1, 7)
                .mapToObj(n -> new AiDay(n, List.of(shop("상점" + n), food("식당" + n)), List.of())).toList());
    }
    @Test void shoppingIsPresentEveryDayOfSevenDays() {
        assertTrue(ItineraryRules.validate(sevenDays(), request(7)).isEmpty());
    }
    @Test void missingStyleOnLastDayIsRejected() {
        var days = new ArrayList<>(sevenDays().days());
        days.set(6, new AiDay(7, List.of(food("새식당"),
                new AiPlace("관광지", "관광지", "14:00", "관광", "", "", List.of())), List.of()));
        var errors = ItineraryRules.validate(new AiItinerary("여행", days), request(7));
        assertTrue(errors.stream().anyMatch(e -> e.contains("7일차에 빠진 스타일")));
    }
    @Test void differentlySpacedDestinationPrefixDoesNotHideDuplicate() {
        var days = new ArrayList<>(sevenDays().days());
        days.set(6, new AiDay(7, List.of(shop("부산 상 점 1"), food("식당7")), List.of()));
        assertTrue(ItineraryRules.validate(new AiItinerary("여행", days), request(7))
                .stream().anyMatch(e -> e.contains("장소 중복")));
    }
    @Test void restaurantCannotClaimShoppingAndDaytimeCannotClaimNightView() {
        var fake = new AiPlace("식당", "", "12:00", "식당", "", "", List.of("쇼핑", "야경"));
        assertTrue(ItineraryRules.stylesFor(fake).isEmpty());
        var night = new AiPlace("전망대", "", "17:00", "관광", "", "", List.of("야경"));
        assertTrue(ItineraryRules.stylesFor(night).isEmpty());
    }
    @Test void additionalActivitiesDoNotRejectDailyStyleCoverage() {
        var days = new ArrayList<>(sevenDays().days());
        var original = days.get(6);
        var places = new ArrayList<>(original.places());
        places.add(new AiPlace("쉬어가는 카페", "카페", "14:00", "카페", "휴식", "", List.of()));
        places.add(new AiPlace("주변 문화시설", "문화시설", "16:00", "문화", "주변 방문", "", List.of()));
        days.set(6, new AiDay(7, places, List.of()));
        assertTrue(ItineraryRules.validate(new AiItinerary("여행", days), request(7)).isEmpty());
    }
    @Test void supportingCafeCannotReplaceMissingShopping() {
        var day = new AiDay(1, List.of(food("식당"),
                new AiPlace("카페", "카페", "14:00", "카페", "휴식", "", List.of())), List.of());
        assertTrue(ItineraryRules.validate(new AiItinerary("여행", List.of(day)), request(1))
                .stream().anyMatch(error -> error.contains("빠진 스타일: 쇼핑")));
    }
    @Test void sevenDaysAcceptedAndEightDaysRejectedAtApiBoundary() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new GenerateRequest("부산", null, 7, null, List.of("쇼핑"), null)).isEmpty());
            assertFalse(validator.validate(new GenerateRequest("부산", null, 8, null, List.of("쇼핑"), null)).isEmpty());
        }
    }
}
