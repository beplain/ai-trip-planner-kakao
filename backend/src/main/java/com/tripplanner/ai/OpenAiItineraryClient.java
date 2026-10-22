package com.tripplanner.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.common.ApiException;
import com.tripplanner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.format.TextStyle;
import java.util.*;

/**
 * OpenAI Chat Completions + Structured Outputs(JSON Schema)로 일정 초안 생성.
 * 키는 서버 환경변수(OPENAI_API_KEY)에만 둔다. 앱에서 직접 호출하지 않는다.
 */
@Component
public class OpenAiItineraryClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiItineraryClient.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final List<String> CATEGORIES = List.of("관광", "식당", "카페", "쇼핑", "자연", "문화", "체험", "기타");

    private final RestClient http;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public OpenAiItineraryClient(RestClient externalRestClient, AppProperties props, ObjectMapper mapper) {
        this.http = externalRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.openai().enabled();
    }

    public AiItinerary generate(ItineraryRequest req) {
        return generate(req, null);
    }

    public AiItinerary generate(ItineraryRequest req, String feedback) {
        String lastError = feedback;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                AiItinerary result = callOnce(req, lastError);
                List<String> problems = ItineraryRules.validate(result, req);
                if (problems.isEmpty()) return result;
                lastError = String.join("; ", problems);
                log.warn("AI 응답 검증 실패(시도 {}): {}", attempt, lastError);
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                log.warn("OpenAI 오류 {}: {}", code, e.getResponseBodyAsString());
                if (code == 401 || code == 403) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_AUTH", "AI 서비스 인증에 실패했습니다. 서버 키를 확인해 주세요.");
                }
                if (code == 429) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_RATE_LIMIT", "AI 사용량 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.");
                }
                lastError = "HTTP " + code;
            } catch (RestClientException e) {
                log.warn("OpenAI 호출 실패: {}", e.getMessage());
                lastError = "network";
            } catch (Exception e) {
                log.warn("AI 응답 파싱 실패: {}", e.getMessage());
                lastError = "응답이 JSON 형식이 아님";
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "AI 일정 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private AiItinerary callOnce(ItineraryRequest req, String previousError) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        String user = userPrompt(req);
        if (previousError != null) {
            user += "\n\n이전 응답에 다음 문제가 있었습니다. 고쳐서 다시 작성하세요: " + previousError;
        }
        messages.add(Map.of("role", "user", "content", user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.openai().model());
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema",
                Map.of("name", "itinerary", "strict", true, "schema", schema())));

        JsonNode res = http.post()
                .uri(props.openai().baseUrl().replaceAll("/$", "") + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + props.openai().apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        JsonNode message = res == null ? null : res.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) throw new IllegalStateException("빈 응답");
        if (message.hasNonNull("refusal")) throw new IllegalStateException("refusal: " + message.get("refusal").asText());
        return mapper.readValue(message.path("content").asText(), AiItinerary.class);
    }

    private static String systemPrompt() {
        return """
                당신은 한국어 여행 일정 플래너입니다. 사용자의 조건으로 날짜별 방문 장소 목록을 만듭니다.
                규칙:
                - 반드시 목적지 지역 안의, 실제로 존재하고 널리 알려진 장소만 추천합니다. 목적지 밖 장소는 금지합니다.
                - 기본 하루 3~5곳. 선택한 모든 스타일을 반영하기 위해 필요한 경우 최대 10곳까지 허용합니다. 점심·저녁 식사 장소를 포함합니다.
                - 선택한 스타일 각각을 첫날부터 마지막 날까지 매일 최소 한 장소에 반영합니다. 특정 날짜에만 몰아서 반영하지 않습니다.
                - 식당은 기본 식사 장소로 포함할 수 있습니다. 선택한 스타일이 있을 때 식사를 제외한 모든 활동은 선택 스타일 중 적어도 하나와 맞아야 합니다. 선택 스타일이 없으면 활동 종류를 제한하지 않습니다.
                - 쇼핑=쇼핑, 맛집=식당, 자연=자연, 문화=문화, 액티비티=체험, 카페=카페 카테고리를 사용합니다.
                - 힐링은 자연·카페·관광 중 조용히 쉬는 장소이며 추천 이유에 설명합니다. 야경은 18시 이후의 야경 감상 장소이며 단순 저녁 식당으로 대체하지 않습니다.
                - matchedStyles에는 해당 장소가 실제로 반영하는 선택 스타일을 넣습니다. 식사만을 위한 식당은 맛집을 선택하지 않았다면 빈 배열로 둡니다.
                - 한 장소가 자연·힐링·야경처럼 여러 스타일에 맞으면 모두 넣을 수 있지만, 무관한 스타일을 임의로 붙이지 않습니다.
                - 같은 날의 장소는 서로 가까운 곳끼리 묶어 이동을 줄이고, 방문 순서대로 나열합니다.
                - visitTime은 "HH:mm" 24시간 형식이며 같은 날 안에서 오름차순입니다. 이동 시간을 감안한 추정치입니다.
                - 좌표, 주소, 전화번호, 영업시간, URL은 절대 만들지 않습니다(서버가 카카오맵 검색으로 확인합니다).
                - searchQuery에는 카카오맵 검색에 쓸 "지역명 + 정확한 장소명"을 넣습니다. 예: "부산 해운대해수욕장", "강릉 안목해변 카페거리".
                - 국내 여행만 다룹니다.
                - 1일차부터 마지막 날까지 전체 여행에서 같은 실제 장소를 반복하지 않습니다. 다른 시간·이름 표기·검색어로도 같은 장소를 다시 넣지 않습니다. 지점은 정확한 지점명을 명시합니다.
                - alternatives에는 같은 스타일과 카테고리로 대체 가능한 실제 장소를 일차별 4~6곳 넣습니다. 다른 날짜의 방문 장소와 중복되지 않게 준비합니다.
                - reason은 추천 이유 한 문장(60자 이내), note는 방문 팁 한 문장(80자 이내).
                - 예산과 취향을 반영하되, 가격을 단정하지 않습니다.
                """;
    }

    private static String userPrompt(ItineraryRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("목적지: ").append(r.destination()).append('\n');
        sb.append("여행 일수: ").append(r.days()).append("일 (days 배열 길이를 정확히 ").append(r.days()).append("로)\n");
        if (r.startDate() != null) {
            sb.append("출발일: ").append(r.startDate()).append(" (")
                    .append(r.startDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN)).append(")\n");
        }
        sb.append("예산: ").append(r.budget() == null || r.budget().isBlank() ? "미정" : r.budget()).append('\n');
        sb.append("여행 스타일: ").append(r.styles() == null || r.styles().isEmpty() ? "제한 없음" : String.join(", ", r.styles())).append('\n');
        if (r.styles() != null && !r.styles().isEmpty()) {
            for (int day = 1; day <= r.days(); day++) sb.append(day).append("일차 필수 스타일: ").append(String.join(", ", r.styles())).append('\n');
        }
        if (r.extraRequest() != null && !r.extraRequest().isBlank()) {
            sb.append("추가 요청: ").append(r.extraRequest()).append('\n');
        }
        sb.append("title은 \"").append(r.destination()).append(" ").append(r.days()).append("일 여행\"처럼 짧게 만드세요.");
        return sb.toString();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> place = obj(Map.of(
                "name", str(),
                "searchQuery", str(),
                "visitTime", str(),
                "category", Map.of("type", "string", "enum", CATEGORIES),
                "matchedStyles", Map.of("type", "array", "items", Map.of("type", "string", "enum", ItineraryRules.STYLES)),
                "reason", str(),
                "note", str()));
        Map<String, Object> day = obj(Map.of(
                "dayNumber", Map.of("type", "integer"),
                "places", Map.of("type", "array", "items", place),
                "alternatives", Map.of("type", "array", "items", place)));
        return obj(Map.of(
                "title", str(),
                "days", Map.of("type", "array", "items", day)));
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    /** strict 모드: 모든 속성 required + additionalProperties=false */
    private static Map<String, Object> obj(Map<String, Object> properties) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", properties);
        o.put("required", new ArrayList<>(properties.keySet()));
        o.put("additionalProperties", false);
        return o;
    }
}
