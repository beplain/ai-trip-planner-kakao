package com.tripplanner.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiRetryBudgetTests {
    @Test void invalidPreferencesReturnCandidateWithoutHiddenSecondPaidCall() throws Exception {
        var mapper = new ObjectMapper();
        var candidate = new AiItinerary("부산", List.of(new AiItinerary.AiDay(1,List.of(
                new AiItinerary.AiPlace("공원","공원","10:00","자연","","",List.of("자연"))),List.of())));
        String response = mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(candidate))))));
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var props = new AppProperties(null,null,new AppProperties.OpenAi("test","gpt-4o-mini","https://example.invalid/v1"),false,null,7,null);
        var client = new OpenAiItineraryClient(builder.build(),props,mapper);
        assertEquals(candidate,client.generate(new ItineraryRequest("부산",null,1,null,List.of("맛집"),null),null));
        server.verify();
    }
}
