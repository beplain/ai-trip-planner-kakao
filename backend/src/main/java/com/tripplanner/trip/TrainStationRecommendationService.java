package com.tripplanner.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import com.tripplanner.place.Geo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.*;
import java.util.*;

/** A small, bounded station search. No feeder routes or transit diagrams are calculated. */
@Service
public class TrainStationRecommendationService {
    private final TrainTimetableClient trains;
    private final String key;
    private final RestClient http;
    // KORAIL official timetable effective 2026-11-01: 88 unique stations, including KTX-Eum.
    // https://www.korail.com/file/cubedata/COMMON/jfile/202610/13/202610131a130b1ac0b200.xlsx
    private static final List<String> CATALOG=List.of("가남","감곡장호원","강릉","경산","경주","계룡","곡성","공주","광명","광주송정","구례구","구포","기장","김제","김천구미","나주","남원","남창","논산","단양","대전","덕소","동대구","동탄","동해","둔내","마산","만종","목포","묵호","문경","물금","밀양","부발","부산","부전","북울산","살미","삼척","상봉","서대구","서대전","서울","서원주","센텀","수서","수안보온천","수원","순천","신해운대","안동","앙성온천","양평","여수엑스포","여천","연풍","영덕","영등포","영주","영천","오송","용산","울산","울진","원주","의성","익산","장성","전주","정동진","정읍","제천","진부(오대산)","진영","진주","창원","창원중앙","천안아산","청량리","충주","태화강","판교(경기)","평창","평택지제","포항","풍기","행신","횡성");
    private final Map<String,SelectedPlace> catalogLocations=new java.util.concurrent.ConcurrentHashMap<>();
    public List<String> stationCatalog() { return CATALOG; }
    public SelectedPlace catalogLocation(String name) {
        if(!CATALOG.contains(name)) throw ApiException.badRequest("KTX역 목록에서 역을 선택해 주세요.");
        var existing=catalogLocations.get(name); if(existing!=null)return existing;
        if(key==null || key.isBlank()) throw ApiException.badRequest("역 위치 조회를 사용할 수 없어요. 잠시 후 다시 선택해 주세요.");
        try {
            String query=name.equals("판교(경기)")?"경기 판교역":catalogKey(name)+"역";
            var uri=UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json").queryParam("query",query).queryParam("size",15).build().encode().toUri();
            var data=http.get().uri(uri).header("Authorization","KakaoAK "+key).retrieve().body(JsonNode.class);
            if(data!=null) for(var row:data.path("documents")) {
                String label=row.path("place_name").asText(),category=row.path("category_name").asText();
                if(!catalogKey(label).equals(catalogKey(name)) || name.equals("판교(경기)") && !row.path("address_name").asText().startsWith("경기")
                    || label.contains("출구") || category.contains("지하철") || category.contains("폐역")
                    || !(highSpeedStation(label,category) || category.contains("기차역"))) continue;
                var place=new SelectedPlace(row.path("id").asText(),label,row.path("address_name").asText(),Double.valueOf(row.path("y").asText()),Double.valueOf(row.path("x").asText()),null);
                check(place); catalogLocations.put(name,place); return place;
            }
        } catch(RuntimeException ignored) { /* Hide credential-bearing upstream errors. */ }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STATION_LOCATION_UNAVAILABLE","선택한 KTX역 위치를 확인하지 못했어요. 잠시 후 다시 선택해 주세요.");
    }
    static String catalogKey(String name) { return name.trim().split("\\s+")[0].replace("김천(구미)","김천구미").replaceAll("\\([^)]*\\)","").replaceAll("역$",""); }
    @org.springframework.beans.factory.annotation.Autowired
    public TrainStationRecommendationService(TrainTimetableClient trains,@Value("${app.routing.kakao-map-key:}") String key) {
        this(trains,key,client());
    }
    TrainStationRecommendationService(TrainTimetableClient trains,String key,RestClient http) { this.trains=trains; this.key=key; this.http=http; }
    private static RestClient client() {
        var f=new SimpleClientHttpRequestFactory(); f.setConnectTimeout(Duration.ofSeconds(2)); f.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(f).build();
    }
    public StationRecommendation recommend(Transport transport,LocalDate date) {
        if(transport==null || transport.mode()!=TransportMode.KTX || date==null) throw ApiException.badRequest("여행 날짜와 기차 교통편을 선택해 주세요.");
        var origin=transport.originLocation(); check(origin); check(transport.arrivalLocation()); if(transport.alightingStation()!=null)check(transport.alightingStation()); TravelSchedule.minute(transport.originTime());
        // A user override must never be silently replaced by another station.
        if(transport.boardingStation()!=null) {
            check(transport.boardingStation());
            return result(origin,transport.boardingStation(),trains.options(transport,date));
        }
        if(origin.name().trim().split("\\s+")[0].endsWith("역")) {
            try { return result(origin,origin,trains.options(transport,date)); }
            catch(ApiException e) { if(!e.getStatus().is4xxClientError()) throw e; }
        }
        for(var station:nearby(origin)) {
            if(station.placeId().equals(transport.arrivalLocation().placeId())) continue;
            try { return result(origin,station,trains.options(transport.withStations(station,transport.returnBoardingStation()),date)); }
            catch(ApiException e) { if(!e.getStatus().is4xxClientError()) throw e; }
        }
        throw ApiException.badRequest("가까운 기차역 후보 3곳에서 해당 날짜·시각의 직통열차를 찾지 못했어요. 출발역을 직접 변경하거나 날짜·열차 출발 희망 시각을 조정해 주세요.");
    }
    private static StationRecommendation result(SelectedPlace origin,SelectedPlace station,List<TrainOption> trains) {
        return new StationRecommendation(station,trains,(int)Math.round(Geo.distanceMeters(origin.latitude(),origin.longitude(),station.latitude(),station.longitude())));
    }
    public List<SelectedPlace> candidates(SelectedPlace origin,SelectedPlace destination) {
        check(origin); if(destination!=null) check(destination);
        return nearby(origin).stream().filter(s->destination==null || !s.placeId().equals(destination.placeId())).limit(3).toList();
    }
    List<SelectedPlace> nearby(SelectedPlace origin) {
        if(key==null || key.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STATION_NOT_CONFIGURED","주변 기차역 조회가 준비되지 않았어요. 출발역을 직접 선택해 주세요.");
        try {
            var stations=new LinkedHashMap<String,SelectedPlace>();
            String regional = regionalStationName(origin.address());
            List<String> keywords = new ArrayList<>(List.of("KTX 정차역", "고속철도선"));
            if (regional != null) keywords.add(regional);
            for(String keyword:keywords) {
                if (keyword.equals(regional) && stations.values().stream().anyMatch(p -> stationName(p.name()).equals(regional))) continue;
                var uri=UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                    .queryParam("query",keyword).queryParam("x",origin.longitude()).queryParam("y",origin.latitude()).queryParam("sort","distance").queryParam("size",15);
                var data=http.get().uri(uri.build().encode().toUri()).header("Authorization","KakaoAK "+key).retrieve().body(JsonNode.class);
                if(data==null || !data.path("documents").isArray()) throw new IllegalStateException();
                for(var row:data.path("documents")) {
                    try {
                        String name=row.path("place_name").asText(),category=row.path("category_name").asText();
                        boolean regionalMatch = keyword.equals(regional) && stationName(name).equals(regional)
                                && category.contains("기차역") && !category.matches(".*(폐역|지하철).*") && !name.contains("출구");
                        if(!regionalMatch && !highSpeedStation(name,category)) continue;
                        var station=new SelectedPlace(row.path("id").asText(),name,row.path("address_name").asText(),Double.valueOf(row.path("y").asText()),Double.valueOf(row.path("x").asText()),null);
                        check(station);
                        String identity=name.trim().split("\\s+")[0].replaceAll("\\([^)]*\\)","")+"|"+station.address().split("\\s+")[0];
                        stations.merge(identity,station,(a,b)->distance(origin,a)<=distance(origin,b)?a:b);
                    } catch(RuntimeException ignored) { /* Unverified places cannot become high-speed station candidates. */ }
                }
            }
            var ordered = stations.values().stream().sorted(Comparator.comparingDouble(s -> distance(origin, s))).toList();
            var result = new ArrayList<>(ordered.stream().limit(3).toList());
            var representative = ordered.stream().filter(s -> stationName(s.name()).equals(regional)).findFirst();
            if (representative.isPresent() && !result.contains(representative.get())) {
                result.set(result.size() - 1, representative.get());
                result.sort(Comparator.comparingDouble(s -> distance(origin, s)));
            }
            return List.copyOf(result);
        } catch(RuntimeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"STATION_UNAVAILABLE","고속열차 정차역 후보를 확인하지 못했어요. 잠시 후 다시 조회하거나 출발역을 직접 선택해 주세요."); }
    }
    static String regionalStationName(String address) {
        String[] parts = address.trim().split("\\s+");
        String first = parts[0];
        if (Set.of("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종").contains(first)) return first + "역";
        String region = Arrays.stream(parts).filter(p -> p.matches(".*(특별시|광역시|특별자치시)$"))
                .findFirst().orElseGet(() -> Arrays.stream(parts).filter(p -> p.endsWith("시") || p.endsWith("군")).findFirst().orElse(null));
        if (region == null) return null;
        return region.replaceFirst("(특별자치시|특별시|광역시|시|군)$", "") + "역";
    }
    private static String stationName(String name) {
        return name.trim().split("\\s+")[0].replaceAll("\\([^)]*\\)", "");
    }
    static boolean highSpeedStation(String name,String category) {
        String classification=category.replaceAll("\\s","").toUpperCase(Locale.ROOT);
        if(classification.contains("폐역") || classification.contains("지하철") || name.contains("출구")) return false;
        boolean supported=classification.contains("KTX정차역") || classification.contains("고속철도선") || classification.contains("고속선");
        return supported && name.trim().split("\\s+")[0].replaceAll("\\([^)]*\\)","").endsWith("역");
    }
    private static double distance(SelectedPlace a,SelectedPlace b) { return Geo.distanceMeters(a.latitude(),a.longitude(),b.latitude(),b.longitude()); }
    private static void check(SelectedPlace place) {
        if(place==null || place.name()==null || place.name().isBlank() || place.address()==null || place.address().isBlank() || place.placeId()==null || !place.validId()
            || place.latitude()==null || place.longitude()==null || !Double.isFinite(place.latitude()) || !Double.isFinite(place.longitude()) || Math.abs(place.latitude())>90 || Math.abs(place.longitude())>180)
            throw ApiException.badRequest("출발 장소와 도착 기차역을 지도에서 선택해 주세요.");
    }
}
