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
    }    @Test void jsonBodyWithOctetStreamHeaderStillPreservesKoreanAndUsesOneCall() throws Exception {
        var mapper=new ObjectMapper();
        var candidate=new AiItinerary("경주 여행",List.of(new AiItinerary.AiDay(1,List.of(
            new AiItinerary.AiPlace("황리단길 식당","황리단길 식당","12:00","식당","맛집","",List.of("맛집")),
            new AiItinerary.AiPlace("황리단길 상점","황리단길 상점","14:00","쇼핑","쇼핑","",List.of("쇼핑"))),List.of())));
        String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(candidate))))));
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess(response.getBytes(java.nio.charset.StandardCharsets.UTF_8),MediaType.APPLICATION_OCTET_STREAM));
        var props=new AppProperties(null,null,new AppProperties.OpenAi("test","test-model","https://example.invalid/v1"),false,null,7,null);
        var client=new OpenAiItineraryClient(builder.build(),props,mapper);
        assertEquals(candidate,client.generate(new ItineraryRequest("경상북도 경주시",null,1,null,List.of("맛집","쇼핑"),null)));
        server.verify();
    }

    @Test void compactSevenDayResponseReconstructsSearchQueriesAndKeepsConfiguredModel()throws Exception {
        var mapper=new ObjectMapper();var itinerary=mapper.createObjectNode();itinerary.put("title","부산 여행");
        var days=itinerary.putArray("days");
        for(int day=1;day<=7;day++) {
            var d=days.addObject();d.put("dayNumber",day);var visits=d.putArray("places");
            int hour=8;
            for(String category:day==1?List.of("식당","쇼핑","문화","체험","카페"):List.of("쇼핑","문화","체험","카페")) {
                visits.addObject().put("name",category+day).put("visitTime",String.format("%02d:00",hour)).put("category",category);
                hour+=2;
            }
            var alternate=d.putArray("alternatives").addObject();
            alternate.put("name","대체상점"+day).put("visitTime","18:00").put("category","쇼핑");
        }
        String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",itinerary.toString()))),
            "usage",Map.of("prompt_tokens",100,"completion_tokens",200)));
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(jsonPath("$.model").value("configured-model"))
            .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
            .andExpect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.searchQuery").doesNotExist())
            .andExpect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.note").doesNotExist())
            .andExpect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.reason").doesNotExist())
            .andExpect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.matchedStyles").doesNotExist())
            .andExpect(jsonPath("$.messages[0].content",org.hamcrest.Matchers.containsString("2~3곳")))
            .andExpect(jsonPath("$.messages[0].content",org.hamcrest.Matchers.containsString("일반 식당과 식당 대체 후보를 출력하지 마세요")))
            .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var props=new AppProperties(null,null,new AppProperties.OpenAi("test","configured-model","https://example.invalid/v1"),false,null,7,null);
        var client=new OpenAiItineraryClient(builder.build(),props,mapper);
        var result=client.generate(new ItineraryRequest("부산",null,7,null,List.of("맛집","쇼핑","문화","액티비티","카페"),null,null,true));
        assertEquals(7,result.days().size());
        for(var day:result.days()) {
            for(var p:day.places()) { assertEquals("부산 "+p.name(),p.searchQuery());assertEquals("",p.note()); }
            for(var p:day.alternatives()) { assertEquals("부산 "+p.name(),p.searchQuery());assertEquals("",p.note()); }
        }
        var covered=new HashSet<String>();result.days().forEach(day->day.places().forEach(p->covered.addAll(p.matchedStyles())));
        assertTrue(covered.containsAll(List.of("맛집","쇼핑","문화","액티비티","카페")));
        assertEquals(29,result.days().stream().flatMap(day->day.places().stream()).count());
        server.verify();
    }
    @Test void incompleteStructuredOutputIsRejectedWithoutAnotherPaidCall()throws Exception {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}",MediaType.APPLICATION_JSON));
        var props=new AppProperties(null,null,new AppProperties.OpenAi("test","configured-model","https://example.invalid/v1"),false,null,7,null);
        var client=new OpenAiItineraryClient(builder.build(),props,new ObjectMapper());
        assertThrows(com.tripplanner.common.ApiException.class,()->client.generate(new ItineraryRequest("부산",null,7,null,List.of("맛집"),null)));
        server.verify();
    }

    @Test void lunaUsesLowEffortForGenerationAndRequestInterpretationWithoutChangingModel()throws Exception {
        for(String effort:List.of("low","medium")) {
            var mapper=new ObjectMapper();
            var candidate=new AiItinerary("부산",List.of(new AiItinerary.AiDay(1,List.of(
                new AiItinerary.AiPlace("식당","부산 식당","12:00","식당","","",List.of("맛집")),
                new AiItinerary.AiPlace("상점","부산 상점","14:00","쇼핑","","",List.of("쇼핑"))),List.of())));
            String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(candidate))))));
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(jsonPath("$.reasoning_effort").value(effort)).andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.stream").value(false)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            String interpretation=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content","{\"rules\":[{\"source\":\"다이소 제외\",\"kind\":\"EXCLUDE\",\"target\":\"다이소\",\"value\":0,\"daily\":false,\"day\":0,\"meal\":\"\",\"category\":\"\",\"aliases\":[]}],\"unknown\":[]}")))));
            server.expect(jsonPath("$.reasoning_effort").value(effort)).andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andRespond(withSuccess(interpretation,MediaType.APPLICATION_JSON));
            var openai=effort.equals("low")?new AppProperties.OpenAi("test","gpt-6-luna","https://example.invalid/v1"):
                new AppProperties.OpenAi("test","gpt-6-luna","https://example.invalid/v1",effort);
            var props=new AppProperties(null,null,openai,false,null,7,null);
            var client=new OpenAiItineraryClient(builder.build(),props,mapper);
            assertEquals(candidate,client.generate(new ItineraryRequest("부산",null,1,null,List.of("맛집","쇼핑"),null)));
            assertNotNull(client.interpretRequest("다이소 제외",1));server.verify();
        }
    }
    @Test void wrappedReadTimeoutAndNetworkFailuresAreDistinguished() {
        assertEquals("TIMEOUT",OpenAiItineraryClient.failureKind(new org.springframework.web.client.RestClientException("outer",new java.io.IOException(new java.net.SocketTimeoutException("Read timed out")))));
        assertEquals("DNS",OpenAiItineraryClient.failureKind(new org.springframework.web.client.ResourceAccessException("outer",new java.net.UnknownHostException())));
        assertEquals("TLS",OpenAiItineraryClient.failureKind(new org.springframework.web.client.ResourceAccessException("outer",new javax.net.ssl.SSLHandshakeException("TLS"))));
        assertEquals("CONNECTION",OpenAiItineraryClient.failureKind(new org.springframework.web.client.ResourceAccessException("outer",new java.net.ConnectException())));
    }

    @Test void healingAndNightKeepExplicitAiTagsRatherThanGuessingFromCategory()throws Exception {
        var mapper=new ObjectMapper();var itinerary=mapper.createObjectNode();itinerary.put("title","부산");
        var day=itinerary.putArray("days").addObject();day.put("dayNumber",1);day.putArray("alternatives");
        var visits=day.putArray("places");
        visits.addObject().put("name","휴식카페").put("visitTime","14:00").put("category","카페")
            .putArray("matchedStyles").add("힐링").add("카페");
        visits.addObject().put("name","야경공원").put("visitTime","19:00").put("category","자연")
            .putArray("matchedStyles").add("자연").add("야경");
        String response=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",itinerary.toString())))));
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.matchedStyles").exists())
            .andExpect(jsonPath("$.response_format.json_schema.schema.properties.days.items.properties.places.items.properties.reason").doesNotExist())
            .andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var props=new AppProperties(null,null,new AppProperties.OpenAi("test","configured-model","https://example.invalid/v1"),false,null,7,null);
        var req=new ItineraryRequest("부산",null,1,null,List.of("힐링","카페","자연","야경"),null);
        var result=new OpenAiItineraryClient(builder.build(),props,mapper).generate(req);
        assertTrue(ItineraryRules.validate(result,req).isEmpty());
        assertTrue(result.days().get(0).places().stream().anyMatch(p->p.matchedStyles().contains("힐링")));
        assertTrue(result.days().get(0).places().stream().anyMatch(p->p.matchedStyles().contains("야경")&&p.reason().equals("야경 감상")));
        server.verify();
    }

}
