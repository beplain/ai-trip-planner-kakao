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
            for(String keyword:List.of("KTX 정차역","고속철도선")) {
                var uri=UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                    .queryParam("query",keyword).queryParam("x",origin.longitude()).queryParam("y",origin.latitude()).queryParam("sort","distance").queryParam("size",15);
                var data=http.get().uri(uri.build().encode().toUri()).header("Authorization","KakaoAK "+key).retrieve().body(JsonNode.class);
                if(data==null || !data.path("documents").isArray()) throw new IllegalStateException();
                for(var row:data.path("documents")) {
                    try {
                        String name=row.path("place_name").asText(),category=row.path("category_name").asText();
                        if(!highSpeedStation(name,category)) continue;
                        var station=new SelectedPlace(row.path("id").asText(),name,row.path("address_name").asText(),Double.valueOf(row.path("y").asText()),Double.valueOf(row.path("x").asText()),null);
                        check(station);
                        String identity=name.trim().split("\\s+")[0].replaceAll("\\([^)]*\\)","")+"|"+station.address().split("\\s+")[0];
                        stations.merge(identity,station,(a,b)->distance(origin,a)<=distance(origin,b)?a:b);
                    } catch(RuntimeException ignored) { /* Unverified places cannot become high-speed station candidates. */ }
                }
            }
            return stations.values().stream().sorted(Comparator.comparingDouble(s->Geo.distanceMeters(origin.latitude(),origin.longitude(),s.latitude(),s.longitude()))).limit(3).toList();
        } catch(RuntimeException e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"STATION_UNAVAILABLE","고속열차 정차역 후보를 확인하지 못했어요. 잠시 후 다시 조회하거나 출발역을 직접 선택해 주세요."); }
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
