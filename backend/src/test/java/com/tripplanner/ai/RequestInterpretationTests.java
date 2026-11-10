package com.tripplanner.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.config.AppProperties;
import com.tripplanner.trip.ExtraRequestRules;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RequestInterpretationTests {
    @Test void naturalPhrasesAreSentToConfiguredModelUsingStructuredOutputs() throws Exception {
        var mapper=new ObjectMapper();var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var rule=Map.of("source","카페 추천 중에 하나는 메가커피로 추천해줘","kind","INCLUDE","target","메가커피","value",1,"daily",false,"day",0,"meal","","category","카페","aliases",List.of("메가MGC커피"));
        String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(Map.of("rules",List.of(rule),"unknown",List.of())))))));
        for(String phrase:List.of("카페 추천 중에 하나는 메가커피로 추천해줘","메가커피 최소 1곳 포함")) {
            server.expect(requestTo("https://example.invalid/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("configured-model"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(request -> assertTrue(new String(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsBytes(),java.nio.charset.StandardCharsets.UTF_8).contains(phrase)))
                .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        }
        var props=new AppProperties(null,null,new AppProperties.OpenAi("test","configured-model","https://example.invalid/v1"),false,null,7,null);
        var client=new OpenAiItineraryClient(builder.build(),props,mapper);
        assertTrue(client.interpretRequest("카페 추천 중에 하나는 메가커피로 추천해줘",2).startsWith(ExtraRequestRules.STRUCTURED));
        assertTrue(client.interpretRequest("메가커피 최소 1곳 포함",2).startsWith(ExtraRequestRules.STRUCTURED));
        assertNull(client.interpretRequest(null,2));server.verify();
    }
    @Test void unsupportedDataAndEmptyInterpretationsNeverBecomeSuccess() {
        var mapper=new ObjectMapper();
        assertThrows(com.tripplanner.common.ApiException.class,()->ExtraRequestRules.structured(mapper.valueToTree(Map.of("rules",List.of(),"unknown",List.of()))));
        var error=assertThrows(com.tripplanner.common.ApiException.class,()->ExtraRequestRules.structured(mapper.valueToTree(Map.of("rules",List.of(),"unknown",List.of("실시간 좌석 잔여 수: 예매 API가 없어 확인 불가")))));
        assertTrue(error.getMessage().contains("좌석"));
    }
}
