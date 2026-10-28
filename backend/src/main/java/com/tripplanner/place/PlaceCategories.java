package com.tripplanner.place;

import com.tripplanner.place.KakaoLocalClient.PlaceHit;
import java.util.*;

/** Positive evidence from Kakao taxonomy only; AI labels, query text and place names are not evidence. */
public final class PlaceCategories {
    private PlaceCategories() {}
    private static final Set<String> SHOP = Set.of("쇼핑", "백화점", "대형마트", "쇼핑센터", "쇼핑몰", "복합쇼핑몰",
            "아울렛", "아웃렛", "전통시장", "재래시장", "시장", "의류판매", "의류매장", "잡화점", "생활용품점", "서점", "면세점");
    private static final Set<String> NATURE = Set.of("자연", "자연명소", "공원", "도시근린공원", "수목원", "식물원", "해수욕장", "해변", "산", "숲", "휴양림", "자연휴양림", "호수", "폭포", "생태공원", "산책로");
    private static final Set<String> CULTURE = Set.of("문화시설", "영화관", "공연장", "박물관", "미술관", "전시관", "기념관", "문화유적", "문화재", "고궁,궁", "고궁", "유적지");
    private static final Set<String> ACTIVITY = Set.of("레저,스포츠", "스포츠,레저", "체험여행", "체험마을", "체험관", "테마파크", "놀이공원", "수상레저", "레포츠", "볼링장", "스케이트장", "클라이밍", "짚라인", "케이블카");
    private static final Set<String> BLOCKED_CODES = Set.of("AD5", "AG2", "PS3", "SC4", "AC5", "PK6", "OL7", "SW8", "BK9", "PO3", "HP8", "PM9");
    private static final Set<String> BLOCKED_ROOTS = Set.of("부동산", "주거시설", "교육,학문", "의료,건강", "금융,보험", "교통,수송", "공공기관", "숙박");

    public static boolean matches(String requested, PlaceHit hit) {
        if (hit == null || requested == null) return false;
        String code = clean(hit.categoryCode()), group = clean(hit.categoryGroup());
        List<String> path = Arrays.stream(Objects.toString(hit.categoryName(), "").split(">"))
                .map(PlaceCategories::clean).filter(s -> !s.isEmpty()).toList();
        if (BLOCKED_CODES.contains(code) || (!path.isEmpty() && BLOCKED_ROOTS.contains(path.get(0)))
                || path.stream().anyMatch(p -> Set.of("아파트", "오피스텔", "주택", "숙박", "호텔", "모텔", "펜션", "주차장", "화장실", "고객센터", "관리사무소", "현금인출기", "ATM").contains(p))) return false;
        boolean cafe = code.equals("CE7") || group.equals("카페") || path.contains("카페");
        boolean food = code.equals("FD6") || group.equals("음식점") || (!path.isEmpty() && path.get(0).equals("음식점"));
        if (requested.equals("식당")) return (path.isEmpty() || path.get(0).equals("음식점")) && food && !cafe && (code.isEmpty() || code.equals("FD6"));
        if (requested.equals("카페")) return (path.isEmpty() || path.get(0).equals("음식점") || path.get(0).equals("카페")) && cafe && (code.isEmpty() || code.equals("CE7"));
        if (food || cafe) return false;
        return switch (requested) {
            case "쇼핑" -> (code.isEmpty() || code.equals("MT1") || code.equals("CS2"))
                    && (code.equals("MT1") || group.equals("대형마트") || intersects(path, SHOP));
            case "자연" -> intersects(path, NATURE);
            case "문화" -> code.equals("CT1") || group.equals("문화시설") || intersects(path, CULTURE);
            case "체험" -> intersects(path, ACTIVITY);
            case "관광" -> code.equals("AT4") || group.equals("관광명소") || intersects(path, NATURE)
                    || intersects(path, Set.of("박물관", "미술관", "문화유적", "문화재", "고궁,궁", "고궁", "유적지", "관광,명소", "관광명소", "전망대", "테마파크"));
            default -> false;
        };
    }
    private static boolean intersects(List<String> path, Set<String> allowed) {
        return path.stream().anyMatch(allowed::contains);
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
