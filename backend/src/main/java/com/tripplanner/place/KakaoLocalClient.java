package com.tripplanner.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripplanner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 카카오 로컬 API (키워드로 장소 검색 / 주소 검색). REST API 키는 서버에만 둔다.
 * 문서: https://developers.kakao.com/docs/latest/ko/local/dev-guide
 * 응답에는 영업시간이 없다 → 앱에서 place_url(카카오맵 상세)로 연결한다.
 */
@Component
public class KakaoLocalClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);
    private static final String KEYWORD = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final String ADDRESS = "https://dapi.kakao.com/v2/local/search/address.json";

    private final RestClient http;
    private final AppProperties props;

    public KakaoLocalClient(@org.springframework.beans.factory.annotation.Qualifier("placesRestClient") RestClient externalRestClient, AppProperties props) {
        this.http = externalRestClient;
        this.props = props;
    }

    public boolean enabled() {
        return props.kakao().enabled();
    }

    /** 목적지 중심 좌표 */
    public record Area(double lat, double lng, String name) {}

    /**
     * @param distanceMeters 검색 시 넘긴 중심 좌표로부터의 직선거리 (중심을 안 넘겼으면 null)
     */
    public record PlaceHit(String placeId, String name, String address, double lat, double lng, String phone,
                           String placeUrl, String categoryGroup, Integer distanceMeters,
                           String categoryCode, String categoryName) {
        public PlaceHit(String placeId, String name, String address, double lat, double lng, String phone,
                        String placeUrl, String categoryGroup, Integer distanceMeters) {
            this(placeId, name, address, lat, lng, phone, placeUrl, categoryGroup, distanceMeters, null, null);
        }
    }

    /** "부산", "제주", "강릉" 같은 목적지의 중심 좌표. 주소 검색 → 실패 시 키워드 검색 */
    public Optional<Area> resolveArea(String destination) {
        JsonNode docs = get(UriComponentsBuilder.fromUriString(ADDRESS)
                .queryParam("query", destination).queryParam("size", 1));
        if (docs.size() > 0) {
            JsonNode d = docs.get(0);
            return Optional.of(new Area(d.path("y").asDouble(), d.path("x").asDouble(), d.path("address_name").asText(destination)));
        }
        List<PlaceHit> hits = search(destination, null, 1);
        return hits.isEmpty() ? Optional.empty()
                : Optional.of(new Area(hits.get(0).lat(), hits.get(0).lng(), hits.get(0).name()));
    }

    /** 키워드 검색. area를 주면 결과마다 중심까지의 거리(distance)가 함께 온다 */
    public List<PlaceHit> search(String query, Area area, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(KEYWORD)
                .queryParam("query", query)
                .queryParam("size", Math.max(1, Math.min(15, size)))
                .queryParam("sort", "accuracy");
        if (area != null) b.queryParam("x", area.lng()).queryParam("y", area.lat());
        List<PlaceHit> out = new ArrayList<>();
        for (JsonNode d : get(b)) {
            if (!d.hasNonNull("id") || !d.hasNonNull("x") || !d.hasNonNull("y")) continue;
            String road = d.path("road_address_name").asText("");
            String addr = road.isBlank() ? d.path("address_name").asText(null) : road;
            String dist = d.path("distance").asText("");
            out.add(new PlaceHit(
                    d.path("id").asText(),
                    d.path("place_name").asText(),
                    addr,
                    d.path("y").asDouble(),
                    d.path("x").asDouble(),
                    blankToNull(d.path("phone").asText(null)),
                    blankToNull(d.path("place_url").asText(null)),
                    blankToNull(d.path("category_group_name").asText(null)),
                    dist.isBlank() ? null : Integer.valueOf(dist),
                    blankToNull(d.path("category_group_code").asText(null)),
                    blankToNull(d.path("category_name").asText(null))));
        }
        return out;
    }

    private JsonNode get(UriComponentsBuilder b) {
        URI uri = b.encode().build().toUri();
        try {
            JsonNode res = http.get().uri(uri)
                    .header("Authorization", "KakaoAK " + props.kakao().restApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            return res == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : res.path("documents");
        } catch (RestClientResponseException e) {
            // 401: 키 오류, 403: 카카오맵 API 미사용 설정/권한, 429: 쿼터 초과
            log.warn("카카오 로컬 API 실패 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
