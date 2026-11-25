package com.tripplanner.trip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripplanner.ai.AiItinerary;
import com.tripplanner.ai.AiItinerary.AiDay;
import com.tripplanner.ai.AiItinerary.AiPlace;
import com.tripplanner.ai.ItineraryRules;
import com.tripplanner.ai.SampleItineraryGenerator;
import com.tripplanner.common.ApiException;
import com.tripplanner.place.GoogleOpeningHoursClient;
import com.tripplanner.place.KakaoLocalClient;
import com.tripplanner.place.KakaoLocalClient.Area;
import com.tripplanner.place.KakaoLocalClient.PlaceHit;
import com.tripplanner.trip.TripDtos.GenerateRequest;
import com.tripplanner.trip.TripDtos.LuggagePlan;
import com.tripplanner.trip.TripDtos.MovementMode;
import com.tripplanner.trip.TripDtos.Stay;
import com.tripplanner.trip.TripDtos.TravelDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Production constructor: meals, Kakao verification, Google hours, and routing.
 * A blank extra request on a normal domestic trip must still yield three types.
 */
class DomesticOptionGenerationTests {
    private static final LocalDate START = LocalDate.of(2026, 11, 17);
    private static final String DESTINATION = "부산광역시";

    @Test void blankDomesticTripDoesNotRejectAllThreeTypes() throws Exception {
        var harness = harness(600);
        try {
            assertThreeTypes(harness.service, new TravelDetails(MovementMode.CAR, List.of(), null));
        } finally {
            harness.close();
        }
    }

    @Test void hotelStorageRegenerationStillProducesThreeTypes() throws Exception {
        var stay = new Stay("해운대 호텔", "부산광역시 해운대구 해운대로 100", 1, 2, "15:00", "10:00",
                LuggagePlan.HOTEL, LuggagePlan.HOTEL, "987654321", 35.163, 129.163, null);
        var details = new TravelDetails(MovementMode.CAR, List.of(stay), null);
        var harness = harness(10 * 60);
        try {
            var result = harness.service.generateOptions(new GenerateRequest(DESTINATION, START, 2, null,
                    List.of("맛집", "자연"), null, details));
            assertEquals(3, result.options().size(), "three draft types");
            for (int profile = 0; profile < result.options().size(); profile++) {
                var plan = result.options().get(profile).draft();
                DraftVisitPlanner.require(plan, profile);
                final int type = profile;
                assertTrue(plan.days().stream().flatMap(d -> d.places().stream()).filter(place -> place.fixedEventId() == null)
                        .allMatch(place -> place.stayMinutes() == (type == 2 && List.of("식당", "카페").contains(place.category()) ? 45 : 60)));
            }
            assertEquals(3, result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
            for (var option : result.options()) {
                var checkout = option.draft().days().get(1);
                assertTrue(checkout.places().stream().anyMatch(p -> "stay:0:pickup".equals(p.fixedEventId())));
                assertTrue(checkout.places().stream().anyMatch(p -> DraftMealPlanner.isMeal(p, "저녁")),
                        () -> checkout.places().stream().map(p -> p.visitTime() + " " + p.name()).toList().toString());
            }
        } catch (ApiException e) {
            fail(e.getMessage());
        } finally {
            harness.close();
        }
    }

    @Test void lateCheckoutReturnsPartialProfileWithWarnings() {
        var stay = new Stay("해운대 호텔", "부산광역시 해운대구 해운대로 100", 1, 2, "15:00", "11:00",
                LuggagePlan.HOTEL, LuggagePlan.HOTEL, "987654321", 35.163, 129.163, null);
        var harness = harness(10 * 60);
        try {
            var result = harness.service.generateOptions(new GenerateRequest(DESTINATION, START, 2,
                    null, List.of("맛집", "자연"), null, new TravelDetails(MovementMode.CAR, List.of(stay), null)));
            assertEquals(3,result.options().size());
            assertTrue(result.options().stream().anyMatch(option -> "BEST_EFFORT".equals(option.draft().source())));
            for(var option:result.options()) assertDoesNotThrow(() -> DraftVariants.validate(option.draft()));
        } finally { harness.close(); }
    }

    @Test void infeasibleFortyMinuteLegsStillReturnEditableOptions() {
        var stay = new Stay("해운대 호텔", "부산광역시 해운대구 해운대로 100", 1, 2, "15:00", "11:00",
                LuggagePlan.HOTEL, LuggagePlan.HOTEL, "987654321", 35.163, 129.163, null);
        var harness = harness(40 * 60);
        try {
            var result = harness.service.generateOptions(new GenerateRequest(DESTINATION, START, 2,
                    null, List.of("맛집", "자연"), null, new TravelDetails(MovementMode.CAR, List.of(stay), null)));
            assertEquals(3,result.options().size());
            assertTrue(result.options().stream().anyMatch(option -> "BEST_EFFORT".equals(option.draft().source())));
            for(var option:result.options()) assertDoesNotThrow(() -> DraftVariants.validate(option.draft()));
        } finally { harness.close(); }
    }

    @Test void sevenDayCheckoutAtElevenAndEveningTrainProducesAllProfileCounts() throws Exception {
        var stay = new Stay("롯데호텔 부산", "부산광역시 해운대구 우동 1405", 1, 7, "15:00", "11:00",
                LuggagePlan.CARRY, LuggagePlan.CARRY, "123456789", 35.163, 129.163, null);
        var transport = new TripDtos.Transport(TripDtos.TransportMode.KTX, "부산역", "10:26", "부산역", "19:54");
        var harness = harness(0, weekItinerary(), new StylePlaces(), true);
        try {
            var result = harness.service.generateOptions(new GenerateRequest(DESTINATION, START, 7, null,
                    ItineraryRules.STYLES, null, new TravelDetails(MovementMode.CAR, List.of(stay), transport)));
            assertEquals(3, result.options().size());
            for (int profile = 0; profile < 3; profile++) {
                var draft = result.options().get(profile).draft();
                DraftVisitPlanner.require(draft, profile);
                DraftMealPlanner.require(draft);
                TravelSchedule.validateDraft(draft);
            }
        } finally { harness.close(); }
    }

    @Test void sevenDayBusanHotelStorageWithEveryStyleProducesThreeTypes() throws Exception {
        var stay = new Stay("롯데호텔 부산", "부산광역시 해운대구 우동 1405", 1, 7, "15:00", "10:00",
                LuggagePlan.HOTEL, LuggagePlan.HOTEL, "123456789", 35.163, 129.163, null);
        var harness = harness(0, weekItinerary(), new StylePlaces(), true);
        try {
            var result = harness.service.generateOptions(new GenerateRequest(DESTINATION, START, 7, null,
                    ItineraryRules.STYLES, null, new TravelDetails(MovementMode.CAR, List.of(stay), null)));
            assertEquals(3, result.options().size(), "three draft types");
            assertEquals(3, result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
            assertEquals(7, result.options().get(0).draft().days().size());
            for (var option : result.options()) {
                var days = option.draft().days();
                var breakfast = days.get(1).places().stream().filter(p -> DraftMealPlanner.isMeal(p, "아침")).findFirst().orElseThrow();
                assertTrue(Math.abs(breakfast.latitude() - 35.164) < 0.01 && Math.abs(breakfast.longitude() - 129.164) < 0.01,
                        () -> "day 2 breakfast should stay by the hotel, was " + breakfast.name() + " " + breakfast.latitude());
                assertTrue(breakfast.visitTime().compareTo("09:00") <= 0, breakfast.visitTime());
                assertTrue(days.get(6).places().stream().anyMatch(p -> "stay:0:pickup".equals(p.fixedEventId())));
                assertTrue(days.stream().flatMap(d -> d.places().stream()).anyMatch(p -> "카페".equals(p.category())));
            }
        } catch (ApiException e) {
            fail(e.getMessage());
        } finally {
            harness.close();
        }
    }

    private static AiItinerary weekItinerary() {
        return new AiItinerary("부산 여행", List.of(
                new AiDay(1, List.of(
                        spot("해운대공원", "10:30", "자연", "자연", "힐링"),
                        spot("센텀시티", "12:15", "쇼핑", "쇼핑"),
                        spot("광안대교", "19:30", "관광", "야경")), List.of()),
                new AiDay(2, List.of(
                        spot("부산박물관", "10:00", "문화", "문화"),
                        spot("부산체험관", "13:00", "체험", "체험"),
                        spot("온천카페", "16:00", "카페", "카페")), List.of()),
                new AiDay(3, List.of(spot("태종대공원", "10:00", "자연", "자연"), spot("남포시장", "14:00", "쇼핑", "쇼핑")), List.of()),
                new AiDay(4, List.of(spot("부산미술관", "10:00", "문화", "문화"), spot("부산밀면", "12:00", "식당", "맛집"), spot("오륙도", "14:00", "자연", "자연")), List.of()),
                new AiDay(5, List.of(spot("아쿠아리움", "10:00", "체험", "체험"), spot("바다카페", "14:00", "카페", "카페")), List.of()),
                new AiDay(6, List.of(spot("서면상점", "10:00", "쇼핑", "쇼핑"), spot("이기대", "14:00", "자연", "힐링")), List.of()),
                new AiDay(7, List.of(spot("송도해변", "12:30", "자연", "자연"), spot("영화관", "16:00", "문화", "문화")), List.of())));
    }

    private static AiPlace spot(String name, String time, String category, String... styles) {
        return new AiPlace(name, name, time, category, "부산에서 들를 만한 곳이에요.", "", List.of(styles));
    }

    private record Harness(TripGenerationService service, RouteTimeClient routes, GoogleOpeningHoursClient google) {
        void close() { service.shutdown(); routes.shutdown(); google.close(); }
    }

    private static Harness harness(int routeSeconds) {
        return harness(routeSeconds, new AiItinerary("부산 여행", List.of(
                new AiDay(1, List.of(
                        sight("해운대해수욕장", "10:00"),
                        new AiPlace("해운대삼겹살", "해운대삼겹살", "16:00", "식당", "두툼한 삼겹살을 구워 먹는 점심.", "", List.of("맛집")),
                        sight("광안리해수욕장", "19:30")), List.of()),
                new AiDay(2, List.of(
                        sight("태종대", "10:00"),
                        sight("감천문화마을", "13:00")), List.of()))), new CityPlaces());
    }

    private static Harness harness(int routeSeconds, AiItinerary itinerary, KakaoLocalClient places) {
        return harness(routeSeconds, itinerary, places, false);
    }

    private static Harness harness(int routeSeconds, AiItinerary itinerary, KakaoLocalClient places, boolean distanceRoutes) {
        var ai = new TripGenerationRulesTests.FakeAi(itinerary);
        var googleBuilder = RestClient.builder();
        var googleServer = MockRestServiceServer.bindTo(googleBuilder).ignoreExpectOrder(true).build();
        googleServer.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            String query = body.replaceAll(".*\"textQuery\"\\s*:\\s*\"", "").replaceAll("\".*", "");
            int split = query.lastIndexOf(' ');
            String name = split < 0 ? query : query.substring(split + 1);
            String address = split < 0 ? "부산광역시 해운대구 해운대로 264" : query.substring(0, split);
            boolean meal = query.matches(".*(삼겹살|오겹살|장어|돼지국밥|밀면|비빔밥|순두부|설렁탕|돈까스|칼국수|국밥|곰탕|샌드위치|해장국|김밥|갈비|초밥|냉면|쌀국수|햄버거|백반|탕|찜|족발|보쌈|떡볶이|파스타|피자|불고기|막국수|만두|수제비|짜장면|짬뽕|라멘|오므라이스|스테이크|치킨|곱창|대창|오리|복국|맛집).*");
            int open = meal ? 8 : 9;
            try {
                return withSuccess(hours(name, address, open, 21), MediaType.APPLICATION_JSON).createResponse(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        var routeBuilder = RestClient.builder();
        var routeServer = MockRestServiceServer.bindTo(routeBuilder).ignoreExpectOrder(true).build();
        routeServer.expect(ExpectedCount.manyTimes(), anything()).andRespond(request -> {
            int seconds = distanceRoutes ? drivingSeconds(request.getURI().getRawQuery()) : routeSeconds;
            return withSuccess("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":" + seconds + "}}]}",
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        var google = new GoogleOpeningHoursClient(googleBuilder.build(), "secret", 100,
                Clock.fixed(START.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        var routes = new RouteTimeClient("kakao-key", "", 80, routeBuilder.build());
        var service = new TripGenerationService(ai, new SampleItineraryGenerator(), places,
                new RoutedItineraryService(routes), null, new GoogleHoursScheduler(google));
        return new Harness(service, routes, google);
    }

    private static void assertThreeTypes(TripGenerationService service, TravelDetails details) {
        try {
            var result = service.generateOptions(new GenerateRequest(DESTINATION, START, 2, null,
                    List.of("맛집", "자연"), null, details));
            assertEquals(3, result.options().size(), "three draft types");
            assertEquals(3, result.options().stream().map(o -> DraftOptionComposer.fingerprint(o.draft())).distinct().count());
        } catch (ApiException e) {
            fail(e.getMessage());
        }
    }

    private static AiPlace sight(String name, String time) {
        return new AiPlace(name, name, time, "자연", "바다가 길게 펼쳐져 있어요.", "", List.of("자연"));
    }

    private static String hours(String name, String address, int openHour, int closeHour) throws Exception {
        var json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        var row = root.putArray("places").addObject();
        row.put("id", "g-" + name);
        row.put("formattedAddress", address);
        row.putObject("displayName").put("text", name);
        row.putObject("location").put("latitude", 35.16).put("longitude", 129.16);
        var periods = row.putObject("regularOpeningHours").putArray("periods");
        for (int day = 0; day <= 6; day++) {
            var period = periods.addObject();
            period.putObject("open").put("day", day).put("hour", openHour).put("minute", 0);
            period.putObject("close").put("day", day).put("hour", closeHour).put("minute", 0);
        }
        return root.toString();
    }

    /** One verified hit per query, with a real dish name when the query is a meal search. */
    static class CityPlaces extends KakaoLocalClient {
        private final java.util.Map<String, PlaceHit> hits = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        CityPlaces() { super(null, null); }
        @Override public boolean enabled() { return true; }
        @Override public Optional<Area> resolveArea(String destination) {
            return Optional.of(new Area(35.16, 129.16, destination));
        }
        @Override public List<PlaceHit> search(String query, Area area, int size) {
            String key = query == null ? "" : query;
            if (hits.containsKey(key)) return List.of(hits.get(key));
            PlaceHit hit = hit(key);
            if (hit == null) {
                if (key.matches("부산광역시 (공원|박물관|체험|카페|관광명소|쇼핑)")) {
                    List<PlaceHit> results = new java.util.ArrayList<>();
                    for (int index = 0; index < Math.min(size, 15); index++) {
                        String id = "candidate-" + Math.abs(key.hashCode()) + "-" + index;
                        String type = key.substring(key.lastIndexOf(' ') + 1);
                        String name = "부산 " + type + Math.abs(key.hashCode()) + "-" + index;
                        String code = type.equals("카페") ? "CE7" : type.equals("박물관") ? "CT1" : type.equals("쇼핑") ? "MT1" : "AT4";
                        String group = type.equals("카페") ? "카페" : type.equals("박물관") ? "문화시설" : "관광명소";
                        String category = type.equals("체험") ? "레저,스포츠 > 체험여행" : type.equals("쇼핑") ? "쇼핑 > 백화점" : "여행 > " + type;
                        results.add(new PlaceHit(id, name, "부산광역시 해운대구 해운대로 1", 35.164 + index * 0.0001,
                                129.164, null, null, group, null, code, category));
                    }
                    return results;
                }
                return List.of();
            }
            hits.put(key, hit);
            return List.of(hit);
        }
        private PlaceHit hit(String query) {
            int n = seq.incrementAndGet();
            String menu = menu(query);
            double lat = 35.20 + (n % 7) * 0.01;
            double lng = 129.10;
            String address = "부산광역시 해운대구 해운대로 " + (200 + n);
            if (menu != null) {
                String name = "부산" + menu + n;
                return new PlaceHit("meal-" + n, name, address, lat, lng, null,
                        "https://place.map.kakao.com/meal-" + n, "음식점", null, "FD6", "음식점 > 한식");
            }
            String name = query == null ? "" : query;
            boolean namedSight = name.equals("해운대해수욕장") || name.equals("광안리해수욕장")
                    || name.equals("태종대") || name.equals("감천문화마을");
            if (!namedSight) return null;
            lat = switch (name) {
                case "해운대해수욕장" -> 35.00;
                case "광안리해수욕장" -> 35.02;
                case "태종대" -> 35.0001;
                default -> 35.80;
            };
            return new PlaceHit("sight-" + name, name, address, lat, lng, null,
                    "https://place.map.kakao.com/sight-" + n, "관광명소", null, "AT4", "여행 > 관광,명소 > 해수욕장");
        }
        private static String menu(String query) {
            if (query == null) return null;
            String compact = query.replace(" ", "");
            for (String menu : List.of("삼겹살", "돼지국밥", "밀면", "비빔밥", "순두부", "설렁탕", "돈까스", "칼국수",
                    "순대국밥", "곰탕", "샌드위치", "콩나물국밥", "해장국", "김밥", "갈비찜", "초밥", "냉면", "쌀국수",
                    "햄버거", "백반", "추어탕", "삼계탕", "갈비탕", "장어구이", "닭갈비", "닭볶음탕", "갈치조림", "족발",
                    "보쌈", "떡볶이", "파스타", "피자", "오겹살", "감자탕", "불고기", "막국수", "만두", "수제비", "짜장면",
                    "짬뽕", "라멘", "오므라이스", "스테이크", "치킨", "곱창", "대창", "오리구이", "복국", "횟집")) {
                if (compact.contains(menu)) return "횟집".equals(menu) ? "회" : menu;
            }
            return null;
        }
    }

    /** Driving time grows with distance: 3 minutes per kilometer. A restaurant across the city misses 09:00. */
    private static int drivingSeconds(String rawQuery) {
        String origin = queryParam(rawQuery, "origin");
        String destination = queryParam(rawQuery, "destination");
        String[] from = origin.split(",");
        String[] to = destination.split(",");
        double km = haversine(Double.parseDouble(from[1]), Double.parseDouble(from[0]),
                Double.parseDouble(to[1]), Double.parseDouble(to[0]));
        return Math.max(1, (int) Math.round(km * 3)) * 60;
    }

    private static String queryParam(String raw, String name) {
        if (raw == null) throw new IllegalStateException("경로 요청에 좌표가 없습니다.");
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name))
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("경로 요청에 " + name + " 좌표가 없습니다.");
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(a));
    }

    /** CityPlaces plus a Kakao hit for every style the week-long itinerary asks for. */
    static final class StylePlaces extends CityPlaces {
        private final java.util.Map<String, PlaceHit> extra = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1000);
        @Override public List<PlaceHit> search(String query, Area area, int size) {
            var known = super.search(query, area, size);
            if (!known.isEmpty()) {
                PlaceHit hit = known.get(0);
                if (!"FD6".equals(hit.categoryCode())) return known;
                // Same dish beside the hotel and beside a far museum. Ranking decides which breakfast is reachable.
                return List.of(moved(hit, "far-", 35.052, 128.972), moved(hit, "near-", 35.164, 129.164));
            }
            return List.of(extra.computeIfAbsent(query == null ? "" : query, this::styleHit));
        }
        private static PlaceHit moved(PlaceHit hit, String prefix, double lat, double lng) {
            return new PlaceHit(prefix + hit.placeId(), hit.name(), "부산광역시 해운대구 해운대로 1", lat, lng, hit.phone(),
                    hit.placeUrl(), hit.categoryGroup(), hit.distanceMeters(), hit.categoryCode(), hit.categoryName());
        }
        private PlaceHit styleHit(String query) {
            int n = seq.incrementAndGet();
            String name = query.replace("부산광역시", "").trim();
            if (name.isBlank()) name = "부산장소" + n;
            double lat = 35.163 + (n % 5) * 0.002;
            double lng = 129.163;
            if (name.contains("박물관")) { lat = 35.050; lng = 128.970; }
            String address = "부산광역시 해운대구 해운대로 " + n;
            String code = "AT4";
            String group = "관광명소";
            String path = "여행 > 관광,명소 > 공원";
            if (name.contains("카페")) { code = "CE7"; group = "카페"; path = "음식점 > 카페"; }
            else if (name.contains("박물관") || name.contains("미술관") || name.contains("영화")) { code = "CT1"; group = "문화시설"; path = "문화,예술 > 문화시설 > 박물관"; }
            else if (name.contains("체험") || name.contains("아쿠아")) { code = ""; group = "레저,스포츠"; path = "레저,스포츠 > 체험여행"; }
            else if (name.contains("시티") || name.contains("시장") || name.contains("상점") || name.contains("쇼핑")) { code = "MT1"; group = "대형마트"; path = "쇼핑 > 백화점"; }
            return new PlaceHit("style-" + n, name, address, lat, lng, null,
                    "https://place.map.kakao.com/style-" + n, group, null, code, path);
        }
    }
}
