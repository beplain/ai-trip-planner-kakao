package com.tripplanner.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlaceBlurbTests {
    @Test void linesNameThePlaceInsteadOfTheMealSlot() {
        List<String> names = List.of("할머니순두부", "바다순두부", "남천순두부", "원형순두부", "골목순두부", "시장순두부",
                "언덕순두부", "포구순두부", "골목안순두부", "바닷가순두부", "정자순두부", "노을순두부");
        Set<String> used = new HashSet<>();
        List<String> restaurants = new ArrayList<>();
        for (String name : names) {
            String line = PlaceBlurb.line("식당", name + " 식사 일정 · 저녁 · 순두부", List.of(), used);
            restaurants.add(line);
            used.add(PlaceBlurb.endingKey(line));
        }
        assertEquals(restaurants.get(0), PlaceBlurb.line("식당", "할머니순두부 식사 일정 · 저녁 · 순두부", List.of()));
        assertEquals(restaurants.get(0), PlaceBlurb.line("식당", "할머니순두부 식사 일정 · 점심 · 순두부", List.of()));
        assertEquals(names.size(), restaurants.stream().map(PlaceBlurb::endingKey).distinct().count(), restaurants.toString());
        long stock = restaurants.stream().filter(PlaceBlurbTests::stockEnding).count();
        assertTrue(restaurants.size() - stock >= 4, restaurants.toString());
        for (String line : restaurants) {
            assertTrue(line.contains("순두부"), line);
            assertFalse(line.contains("나는 집") || line.contains("저녁 식사로") || line.contains("점심으로")
                    || line.contains("메뉴를 즐겨") || line.contains("둘러보") || line.contains("방문해"), line);
        }
        List<String> bibim = new ArrayList<>();
        List<String> sundubu = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            bibim.add(PlaceBlurb.line("식당", "집" + i + " 식사 일정 · 저녁 · 비빔밥", List.of()));
            sundubu.add(PlaceBlurb.line("식당", "집" + i + " 식사 일정 · 저녁 · 순두부", List.of()));
        }
        assertTrue(bibim.contains("비빔밥이 나오는 집입니다."), bibim.toString());
        assertTrue(sundubu.contains("순두부가 나오는 집입니다."));
        assertTrue(sundubu.contains("따끈한 순두부를 즐겨 보세요."));
        assertTrue(sundubu.contains("정성이 담긴 뚝배기 순두부로 유명합니다."));
        assertTrue(bibim.stream().noneMatch(line -> line.contains("나는 집")));
        assertEquals(PlaceBlurb.endingKey("순두부가 나오는 집입니다."), PlaceBlurb.endingKey("비빔밥이 나오는 집입니다."));
        assertEquals(PlaceBlurb.endingKey("따끈한 순두부를 즐겨 보세요."), PlaceBlurb.endingKey("나물 비빔밥을 즐겨 보세요."));
        assertNotEquals(PlaceBlurb.endingKey("순두부가 나오는 집입니다."), PlaceBlurb.endingKey("따끈한 순두부를 즐겨 보세요."));

        List<String> cafes = assigned("카페", List.of("가", "나", "다", "라", "마", "바", "사", "아"), "카페", "");
        List<String> sights = assigned("관광", List.of("가", "나", "다", "라", "마", "바", "사", "아"), "관광", " 관광 방문");
        assertEquals(8, cafes.stream().map(PlaceBlurb::endingKey).distinct().count(), cafes.toString());
        assertEquals(8, sights.stream().map(PlaceBlurb::endingKey).distinct().count(), sights.toString());
        assertTrue(cafes.stream().noneMatch(line -> line.contains("나는 집")));
        assertTrue(sights.stream().noneMatch(line -> line.contains("나는 집")));

        String shopping = PlaceBlurb.line("쇼핑", "프리미엄아울렛", List.of());
        String culture = PlaceBlurb.line("문화", "문화시설", List.of());
        assertEquals("유명 브랜드를 한자리에서 할인된 가격에 만날 수 있습니다.", shopping);
        assertTrue(culture.endsWith("문화 공간입니다.") || culture.endsWith("남아 있는 곳입니다."), culture);
        assertEquals("조용한 분위기에서 커피를 내는 카페입니다.", PlaceBlurb.line("카페", "휴식카페", List.of("힐링")));
        String night = PlaceBlurb.line("자연", "야경공원", List.of("야경"));
        assertTrue(night.contains("공원") && !night.contains("나는 집"), night);
        assertEquals("유물과 전시를 모은 박물관입니다.", PlaceBlurb.line("문화", "국립박물관", List.of()));
        assertEquals("직접 참여하는 프로그램이 있는 체험 장소입니다.", PlaceBlurb.line("체험", "체험", List.of()));
        assertFalse(PlaceBlurb.stockReason("상 위에 비빔밥이 먼저 올라옵니다."));
        assertTrue(PlaceBlurb.stockReason("나물을 올린 비빔밥이 나는 집입니다."));
        assertTrue(PlaceBlurb.stockReason("정성이 담긴 뚝배기 요리로 유명합니다."));
        assertTrue(PlaceBlurb.stockReason("따끈한 순두부를 즐겨 보세요."));
        assertFalse(PlaceBlurb.stockReason(""));
        assertFalse(PlaceBlurb.stockReason("맛집"));
    }

    private static List<String> assigned(String category, List<String> names, String suffix, String extra) {
        Set<String> used = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (String name : names) {
            String line = PlaceBlurb.line(category, name + suffix + extra, List.of(), used);
            lines.add(line);
            used.add(PlaceBlurb.endingKey(line));
        }
        return lines;
    }

    private static boolean stockEnding(String line) {
        return line.endsWith("유명합니다.") || line.endsWith("즐겨 보세요.") || line.contains("나는 집");
    }
}
