package com.tripplanner.ai;

import com.tripplanner.ai.AiItinerary.*;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.*;

/** Shared checks before and after place lookup. Tags alone cannot override category/time constraints. */
public final class ItineraryRules {
    private ItineraryRules() {}
    public static final List<String> STYLES = List.of("맛집", "힐링", "자연", "쇼핑", "문화", "액티비티", "카페", "야경");
    public static String nameKey(String name, String destination) {
        String key = normalize(name);
        String area = normalize(destination);
        if (!area.isEmpty() && key.startsWith(area)) key = key.substring(area.length());
        return key;
    }
    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static Set<String> stylesFor(AiPlace place) {
        Set<String> result = new HashSet<>();
        for (String style : place.matchedStyles() == null ? List.<String>of() : place.matchedStyles()) {
            boolean valid = switch (style) {
                case "맛집" -> "식당".equals(place.category());
                case "쇼핑" -> "쇼핑".equals(place.category());
                case "자연" -> "자연".equals(place.category());
                case "문화" -> "문화".equals(place.category());
                case "액티비티" -> "체험".equals(place.category());
                case "카페" -> "카페".equals(place.category());
                case "힐링" -> Set.of("자연", "카페", "관광").contains(Objects.toString(place.category(), ""));
                case "야경" -> !"식당".equals(place.category()) && time(place.visitTime()) >= 18 * 60;
                default -> false;
            };
            if (valid) result.add(style);
        }
        return result;
    }
    public static List<String> validate(AiItinerary itinerary, ItineraryRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.days() < 1 || request.days() > 7) errors.add("여행 기간은 1~7일이어야 함");
        Set<String> requested = new LinkedHashSet<>(request.styles() == null ? List.of() : request.styles());
        if (!STYLES.containsAll(requested)) errors.add("지원하지 않는 여행 스타일");
        if (itinerary == null || itinerary.days() == null) return List.of("days 없음");
        if (itinerary.days().size() != request.days()) errors.add("일차 수는 정확히 " + request.days() + "일이어야 함");
        Set<Integer> dayNumbers = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> covered = new HashSet<>();
        for (AiDay day : itinerary.days()) {
            if (day == null) { errors.add("빈 일차"); continue; }
            int n = day.dayNumber();
            if (n < 1 || n > request.days() || !dayNumbers.add(n)) errors.add("일차 번호 중복/범위 오류: " + n);
            int minimum = request.travelDetails() == null ? 2 : 1;
            if (day.places() == null || day.places().size() < minimum || day.places().size() > 10) {
                errors.add(n + "일차 일반 장소 수는 " + minimum + "~10개여야 함");
                if (day.places() == null) continue;
            }
            int previous = -1;
            for (AiPlace p : day.places()) {
                if (p == null || p.name() == null || p.name().isBlank()) { errors.add(n + "일차 장소 이름 없음"); continue; }
                if (!names.add(nameKey(p.name(), request.destination()))) errors.add("전체 여행 중 장소 중복: " + p.name());
                int visit = time(p.visitTime());
                if (visit < 0 || visit <= previous) errors.add(n + "일차 방문 시간 형식/순서 오류: " + p.name());
                if (request.travelDetails() != null && previous >= 0
                        && visit < previous + 60 + com.tripplanner.trip.TravelSchedule.gap(request.travelDetails()))
                    errors.add(n + "일차 방문·이동 여유 시간 부족: " + p.name());
                previous = visit;
                if (request.travelDetails() != null && visit >= 0
                        && !com.tripplanner.trip.TravelSchedule.allowed(n, request.days(), visit, request.travelDetails(),
                            com.tripplanner.trip.TravelSchedule.events(request.travelDetails(), request.days())))
                    errors.add(n + "일차 숙소·교통 고정 시간과 겹치는 장소: " + p.name());
                Set<String> matching = stylesFor(p);
                covered.addAll(matching);
            }
            if (request.travelDetails() != null) {
                int evening = com.tripplanner.trip.TravelSchedule.eveningStart(n, request.days(), request.travelDetails(),
                        com.tripplanner.trip.TravelSchedule.events(request.travelDetails(), request.days()));
                if (evening >= 0 && day.places().stream().noneMatch(p -> p != null && time(p.visitTime()) >= evening))
                    errors.add("첫날 체크인 후 최소 20시까지 이어지는 저녁 장소 필요");
            }
        }
        Set<String> missing = new LinkedHashSet<>(requested); missing.removeAll(covered);
        if (!missing.isEmpty()) errors.add("여행 전체에 빠진 스타일: " + String.join(", ", missing));
        return errors.stream().limit(20).toList();
    }
    private static int time(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return -1;
        try { var t = LocalTime.parse(value); return t.getHour() * 60 + t.getMinute(); }
        catch (Exception e) { return -1; }
    }
    public static AiPlace atTime(AiPlace place, String time) {
        return new AiPlace(place.name(), place.searchQuery(), time, place.category(), place.reason(), place.note(), place.matchedStyles());
    }
}
