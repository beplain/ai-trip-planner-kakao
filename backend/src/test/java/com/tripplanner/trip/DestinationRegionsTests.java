package com.tripplanner.trip;

import com.tripplanner.place.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DestinationRegionsTests {
    @Test void boundariesUseAdministrativeNamesRatherThanRadius() {
        assertTrue(DestinationRegions.contains("경상북도 경주시","경북 경주시 태종로 1"));
        assertFalse(DestinationRegions.contains("경상북도 경주시","경북 포항시 남구"));
        assertFalse(DestinationRegions.contains("서울특별시 강서구","부산 강서구 대저동"));
        assertTrue(DestinationRegions.contains("전남광주통합특별시","광주 북구 용봉동"));
        assertTrue(DestinationRegions.contains("전남광주통합특별시","전남 여수시 중앙동"));
        assertFalse(DestinationRegions.contains("전남광주통합특별시","경기 광주시 경안동"));
        assertFalse(DestinationRegions.contains("부산",null));
    }
    @Test void farAwayPlacesWithinRegionRemainButNearbyPlacesAcrossBorderDoNot() {
        var delegate=new KakaoLocalClient(null,null) {
            @Override public List<PlaceHit> search(String q,Area a,int size) {
                return List.of(new PlaceHit("far","식당","강원 양양군",38.,128.,null,null,"음식점",150000),
                    new PlaceHit("near","식당","강원 속초시",38.,128.,null,null,"음식점",50));
            }
        };
        var result=new GenerationPlaces(delegate,Map.of(),"강원특별자치도 양양군").search("식당",null,15);
        assertEquals(List.of("far"),result.stream().map(KakaoLocalClient.PlaceHit::placeId).toList());
    }
    @Test void mergedRegionSearchUsesBothLegacyProviderNames() {
        var queries=new ArrayList<String>();
        var delegate=new KakaoLocalClient(null,null) {
            @Override public List<PlaceHit> search(String q,Area a,int size) { queries.add(q); return List.of(); }
        };
        new GenerationPlaces(delegate,Map.of(),"전남광주통합특별시").search("전남광주통합특별시 식당",null,15);
        assertEquals(List.of("광주광역시 식당","전라남도 식당"),queries);
    }
}
