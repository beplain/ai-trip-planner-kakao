package com.tripplanner.trip;

import com.tripplanner.place.PlaceCategories;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.tripplanner.trip.PlaceCategoryGenerationTests.hit;

class PlaceCategoriesTests {
    @Test void acceptsOnlyPositiveKakaoEvidenceForMealsAndShopping() {
        assertTrue(PlaceCategories.matches("식당",hit("1","식당","음식점","FD6","음식점 > 한식")));
        assertTrue(PlaceCategories.matches("카페",hit("2","카페","카페","CE7","음식점 > 카페 > 커피전문점")));
        assertTrue(PlaceCategories.matches("쇼핑",hit("3","백화점","","","가정,생활 > 백화점")));
        assertTrue(PlaceCategories.matches("쇼핑",hit("4","마트","대형마트","MT1","가정,생활 > 대형마트")));
        assertTrue(PlaceCategories.matches("쇼핑",hit("5","시장","","","가정,생활 > 시장 > 전통시장")));
    }
    @Test void cinemaAndApartmentCannotMasqueradeAsShoppingOrFood() {
        var cinema = hit("1","CGV 쇼핑 맛집","문화시설","CT1","문화,예술 > 영화,영상 > 영화관");
        var apartment = hit("2","백화점 식당아파트","","","부동산 > 주거시설 > 아파트");
        for (String category : new String[]{"쇼핑","식당","카페","자연","체험"}) {
            assertFalse(PlaceCategories.matches(category,cinema));
            assertFalse(PlaceCategories.matches(category,apartment));
        }
        assertTrue(PlaceCategories.matches("문화",cinema));
    }
    @Test void unknownIndustriesAndFoodInShoppingMallsAreRejected() {
        for (String c : new String[]{"쇼핑","식당","카페","자연","문화","체험","관광"})
            assertFalse(PlaceCategories.matches(c,hit("1","유명 맛집 쇼핑센터","","","")));
        assertFalse(PlaceCategories.matches("쇼핑",hit("2","백화점점 식당","음식점","FD6","음식점 > 한식")));
        assertFalse(PlaceCategories.matches("식당",hit("3","카페","카페","CE7","음식점 > 카페")));
        assertFalse(PlaceCategories.matches("쇼핑",hit("4","백화점부동산","중개업소","AG2","부동산 > 부동산서비스")));
    }
    @Test void natureCultureAndActivitiesAlsoRequireMatchingIndustry() {
        assertTrue(PlaceCategories.matches("자연",hit("1","공원","관광명소","AT4","여행 > 관광,명소 > 공원")));
        assertFalse(PlaceCategories.matches("자연",hit("2","박물관","문화시설","CT1","문화,예술 > 문화시설 > 박물관")));
        assertTrue(PlaceCategories.matches("체험",hit("3","체험","","","여행 > 관광,명소 > 테마파크")));
        assertFalse(PlaceCategories.matches("체험",hit("4","체험학원","학원","AC5","교육,학문 > 학원")));
    }
}
