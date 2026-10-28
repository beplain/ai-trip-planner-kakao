package com.tripplanner.trip;

import com.tripplanner.config.AppProperties;
import com.tripplanner.place.KakaoLocalClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoCategoryParsingTests {
    @Test void readsDetailedCategoryEvenWhenMajorGroupIsEmpty() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("""
                {"documents":[{"id":"123","place_name":"백화점","x":"129.0","y":"35.0",
                "category_group_name":"","category_group_code":"","category_name":"가정,생활 > 백화점"}]}
                """, MediaType.APPLICATION_JSON));
        var props = new AppProperties(null,new AppProperties.Kakao("test",null,List.of()),null,false,null,7,null);
        var found = new KakaoLocalClient(builder.build(),props).search("백화점",null,15).get(0);
        assertNull(found.categoryGroup()); assertNull(found.categoryCode());
        assertEquals("가정,생활 > 백화점",found.categoryName());
        assertTrue(com.tripplanner.place.PlaceCategories.matches("쇼핑",found)); server.verify();
    }
}
