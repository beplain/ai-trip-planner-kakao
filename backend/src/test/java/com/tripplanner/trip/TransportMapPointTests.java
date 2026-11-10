package com.tripplanner.trip;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportMapPointTests {
    @Test void selectedCoordinateCanBeValidatedAndLinkedWithoutPretendingToBeAKakaoBusiness() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var point=new TripDtos.SelectedPlace("point:37.570123,126.980234","광화문빌딩","서울 종로구",37.5701234,126.9802345,null);
            assertTrue(point.validId());
            assertTrue(factory.getValidator().validate(point).isEmpty());
            assertTrue(point.placeUrl().startsWith("https://map.kakao.com/link/map/"));
            assertTrue(point.placeUrl().endsWith(",37.5701234,126.9802345"));
            var invalid=new TripDtos.SelectedPlace("javascript:alert(1)","장소","서울",37.,127.,null);
            assertFalse(invalid.validId());
            assertFalse(factory.getValidator().validate(invalid).isEmpty());
        }
    }
    @Test void ordinaryKakaoPlacesKeepTheirBusinessLinks() {
        var place=new TripDtos.SelectedPlace("123","서울역","서울",37.55,126.97,null);
        assertTrue(place.validId());
        assertEquals("https://place.map.kakao.com/123",place.placeUrl());
    }
}
