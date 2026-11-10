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
    // TripGenerationService owns the two-attempt budget; do not multiply it with nested retries.
    private static final int MAX_ATTEMPTS = 1;
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

    public String interpretRequest(String request,int days) {
        if(request==null || request.isBlank()) return request;
        // Legacy/sample-mode callers do not have an AI interpreter. Never create an undocumented network call in tests.
        if(props==null || !enabled()) return request;
        try {
            Map<String,Object> body=Map.of("model",props.openai().model(),"messages",List.of(
                Map.of("role","system","content",RequestInterpretation.prompt()),
                Map.of("role","user","content",mapper.writeValueAsString(Map.of("request",request,"days",days)))),
                "response_format",Map.of("type","json_schema","json_schema",Map.of("name","travel_request","strict",true,"schema",RequestInterpretation.schema())));
            byte[] responseBody=postJson(body,"추가요청해석");
            JsonNode response=mapper.readTree(responseBody);
            JsonNode message=response==null?null:response.path("choices").path(0).path("message");
            if(message==null || !message.path("refusal").isMissingNode() && !message.path("refusal").isNull() || message.path("content").asText().isBlank())
                throw new IllegalStateException("empty interpretation");
            String result=com.tripplanner.trip.ExtraRequestRules.structured(mapper.readTree(message.path("content").asText()));
            com.tripplanner.trip.ExtraRequestRules.validateInput(result,days);
            return result;
        } catch(ApiException e) { throw e; }
        catch(RestClientResponseException e) {
            String code=e.getStatusCode().value()==429?"AI_RATE_LIMIT":"REQUEST_INTERPRETATION";
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,code,"추가 요청을 해석하는 AI 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch(Exception e) {
            log.warn("추가 요청 해석 실패: {}",e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY,"REQUEST_INTERPRETATION","추가 요청의 해석 결과를 받지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    public AiItinerary generate(ItineraryRequest req) {
        return generate(req, null);
    }

    public AiItinerary generate(ItineraryRequest req, String feedback) {
        String lastError = feedback;
        AiItinerary lastCandidate = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                lastCandidate = callOnce(req, lastError);
                AiItinerary result = ItineraryScheduler.prepare(lastCandidate, req);
                List<String> problems = ItineraryRules.validate(result, req);
                if (problems.isEmpty()) return result;
                lastError = String.join("; ", problems);
                log.warn("AI 응답 검증 실패(시도 {}): {}", attempt, lastError);
            } catch (ItineraryScheduler.SchedulingException e) {
                lastError = e.getMessage();
                log.warn("AI 추천 장소 시간 배치 실패(시도 {}): {}", attempt, lastError);
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
        // Preserve usable recommendations for the verified-place repair stage instead of discarding the whole draft.
        if (lastCandidate != null && lastCandidate.days() != null) return lastCandidate;
        if (lastError != null && !lastError.equals("network") && !lastError.startsWith("HTTP ") && !lastError.equals("응답이 JSON 형식이 아님"))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_CONSTRAINTS",
                    "입력 조건을 충족하는 일정을 만들지 못했습니다. " + lastError.substring(0, Math.min(300, lastError.length())));
        throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "AI 일정 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private AiItinerary callOnce(ItineraryRequest req, String previousError) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(req)));
        String user = userPrompt(req);
        if (previousError != null) {
            user += "\n\n이전 응답에 다음 문제가 있었습니다. 고쳐서 다시 작성하세요: " + previousError;
        }
        messages.add(Map.of("role", "user", "content", user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.openai().model());
        body.put("messages", messages);
        body.put("response_format", Map.of("type", "json_schema", "json_schema",
                Map.of("name", "itinerary", "strict", true, "schema", schema(req))));

        byte[] responseBody = postJson(body,"일정생성");
        JsonNode res = mapper.readTree(responseBody);
        JsonNode choice=res==null?null:res.path("choices").path(0);
        if(res!=null)log.info("AI 생성 응답: 모델={} 종료={} 입력토큰={} 출력토큰={}",props.openai().model(),
                choice.path("finish_reason").asText("미제공"),res.path("usage").path("prompt_tokens").asInt(-1),res.path("usage").path("completion_tokens").asInt(-1));
        if(res!=null)log.info("AI 추론 사용량: 모델={} 추론토큰={}",props.openai().model(),res.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt(-1));
        if(choice!=null&&"length".equals(choice.path("finish_reason").asText()))throw new IllegalStateException("AI 응답이 출력 한도로 중단됨");
        if(res!=null) {
            int total=res.path("usage").path("completion_tokens").asInt(-1);
            int reasoning=res.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt(-1);
            log.info("AI 일정 출력량: 응답문자={} 일반출력토큰={}",choice.path("message").path("content").asText().length(),total>=0&&reasoning>=0?total-reasoning:-1);
        }
        JsonNode message = choice == null ? null : choice.path("message");
        if (message == null || message.isMissingNode()) throw new IllegalStateException("빈 응답");
        if (message.hasNonNull("refusal")) throw new IllegalStateException("refusal: " + message.get("refusal").asText());
        return completeSearchFields(mapper.readValue(message.path("content").asText(), AiItinerary.class),req);
    }

    private byte[] postJson(Map<String,Object> original,String operation) {
        var body=new LinkedHashMap<>(original);
        body.put("stream",false);
        String effort=effectiveEffort();
        if(effort!=null)body.put("reasoning_effort",effort);
        String host;
        try {host=Objects.toString(java.net.URI.create(props.openai().baseUrl()).getHost(),"미확인");}
        catch(IllegalArgumentException e){host="주소형식오류";}
        long started=System.nanoTime();
        log.info("OpenAI 요청 시작: 작업={} 모델={} 추론={} 서버={}",operation,props.openai().model(),effort==null?"모델기본값":effort,host);
        try {
            var response=http.post().uri(props.openai().baseUrl().replaceAll("/$","")+"/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer "+props.openai().apiKey())
                .body(body).retrieve().toEntity(byte[].class);
            log.info("OpenAI 응답 수신: 작업={} HTTP상태={} 형식={} 응답바이트={}",operation,response.getStatusCode().value(),
                response.getHeaders().getContentType(),response.getBody()==null?0:response.getBody().length);
            return response.getBody();
        } catch(RestClientException e) {
            Throwable cause=deepestCause(e);
            log.warn("OpenAI 요청 실패: 작업={} 모델={} 분류={} 내부원인={} HTTP상태={}",operation,props.openai().model(),failureKind(e),
                cause.getClass().getSimpleName(),e instanceof RestClientResponseException status?status.getStatusCode().value():"미수신");
            throw e;
        } finally {
            log.info("OpenAI 요청 종료: 작업={} 소요={}ms",operation,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        }
    }
    private String effectiveEffort() {
        String model=Objects.toString(props.openai().model(),"");
        // Only send this option to the model family whose support has been verified.
        if(!model.equals("gpt-6-luna")&&!model.startsWith("gpt-6-luna-"))return null;
        String value=Objects.toString(props.openai().reasoningEffort(),"low").trim().toLowerCase(Locale.ROOT);
        if(value.isEmpty())return null;
        if(!Set.of("low","medium","high","xhigh","max").contains(value))
            throw new IllegalArgumentException("OPENAI_REASONING_EFFORT는 low, medium, high, xhigh, max 중 하나여야 합니다.");
        return value;
    }
    private static Throwable deepestCause(Throwable error) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(error.getCause()!=null&&seen.add(error.getCause()))error=error.getCause();
        return error;
    }
    static String failureKind(Throwable error) {
        Throwable cause=deepestCause(error);
        if(cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException)return "TIMEOUT";
        if(cause instanceof java.net.UnknownHostException)return "DNS";
        if(cause instanceof javax.net.ssl.SSLException)return "TLS";
        if(cause instanceof java.net.ConnectException)return "CONNECTION";
        if(error instanceof RestClientResponseException)return "HTTP";
        return "RESPONSE_READ";
    }

    // Generate only venue/time/category. Reconstruct deterministic metadata without an additional AI call.
    private static AiItinerary completeSearchFields(AiItinerary itinerary,ItineraryRequest request) {
        if(itinerary==null||itinerary.days()==null)return itinerary;
        return new AiItinerary(itinerary.title(),itinerary.days().stream().map(day -> day==null?null:
                new AiItinerary.AiDay(day.dayNumber(),completePlaces(day.places(),request),completePlaces(day.alternatives(),request))).toList());
    }
    private static List<AiItinerary.AiPlace> completePlaces(List<AiItinerary.AiPlace> places,ItineraryRequest request) {
        if(places==null)return null;
        return places.stream().map(p -> {
            if(p==null)return (AiItinerary.AiPlace)null;
            List<String> styles=p.matchedStyles();
            if(styles==null) {
                // Category is sufficient for these styles. Healing/night still require explicit AI evidence.
                var requested=(request.styles()==null?List.<String>of():request.styles()).stream()
                    .filter(style->!Set.of("힐링","야경").contains(style)).toList();
                var probe=new AiItinerary.AiPlace(p.name(),p.searchQuery(),p.visitTime(),p.category(),"","",requested);
                var valid=ItineraryRules.stylesFor(probe);
                styles=requested.stream().filter(valid::contains).toList();
            }
            String reason=p.reason();
            if(reason==null)reason=styles.contains("야경")?"야경 감상":styles.contains("힐링")?"휴식을 위한 방문":Objects.toString(p.category(),"장소")+" 방문";
            return new AiItinerary.AiPlace(p.name(),p.searchQuery()==null||p.searchQuery().isBlank()
                    ?(request.destination()+" "+Objects.toString(p.name(),"")).trim():p.searchQuery(),
                    p.visitTime(),p.category(),reason,Objects.toString(p.note(),""),styles);
        }).toList();
    }

    private static String systemPrompt(ItineraryRequest request) {
        String mealInstructions = request.serverSchedulesMeals() ? """
                - 서버가 영업시간과 메뉴 중복을 검증해 아침·점심·저녁 식당을 따로 배치합니다. 일반 식당과 식당 대체 후보를 출력하지 마세요.
                - 하루 2~3곳의 관광·체험·쇼핑 등 비식사 방문을 중심으로 계획하세요. 선택 스타일과 고정 일정에 따라 방문 수를 조정하고 식사 사이에 방문할 만한 장소를 남기세요.
                - 맛집 스타일을 선택했다면 여행 전체에서 식당 한 곳만 대표 후보로 포함하세요. 추가 요청이 특정 식당·메뉴·끼니·식당 방문 수를 지정하면 해당 요청에 필요한 후보도 포함하세요.
                """ : """
                - 기본 하루 3~5곳. 선택한 모든 스타일을 반영하기 위해 필요한 경우 최대 10곳까지 허용합니다. 식사는 여행지 체류 시간 안에 가능한 경우에 포함합니다.
                - 식당은 가능한 아침 08:00~09:00, 점심 11:00~13:00, 저녁 17:00~19:00에 시작하며 각 60분 체류합니다. 추가 요청과 도착·출발에 맞지 않는 끼니는 생략하세요. 같은 구체적인 메뉴는 여행 중 반복하지 말고 식당 연속 또는 식당-카페-식당 순서를 피하세요.
                """;
        return """
                당신은 한국어 여행 일정 플래너입니다. 사용자의 조건으로 날짜별 방문 장소 목록을 만듭니다.
                규칙:
                - 반드시 목적지 지역 안의, 실제로 존재하고 널리 알려진 장소만 추천합니다. 목적지 밖 장소는 금지합니다.
                """ + mealInstructions + """
                - 숙소·교통 조건이 있으면 예약 숙소, 체크인·체크아웃, 짐 보관, 역·공항 도착·출발, 단순 이동은 절대 places나 alternatives에 넣지 않습니다. 서버가 별도 추가합니다. 모든 여행 일차는 빠짐없이 반환하세요.
                - 선택한 스타일 각각을 여행 전체에서 최소 한 장소에 반영합니다. 매일 모든 스타일을 넣을 필요는 없으며, 날짜별 여유와 동선에 맞춰 나누어 배치합니다.
                - 추가 요청의 제외 장소·브랜드·카테고리는 places와 alternatives 모두에서 제외하세요. 필수 방문·방문 시작/종료 시각·일일 방문 수 요청을 모든 날짜에서 지키세요. 선택 스타일과 요청이 충돌하면 임의로 무시하지 마세요.
                - 휴식·주변 관광을 보조 활동으로 추가할 수 있습니다. 보조 활동은 선택 스타일을 충족하는 필수 장소를 대신할 수 없습니다. 사용자가 추가 요청에서 특정 활동을 제외했다면 그 요청을 따릅니다.
                - 쇼핑=쇼핑, 맛집=식당, 자연=자연, 문화=문화, 액티비티=체험, 카페=카페 카테고리를 사용합니다.
                - 힐링은 자연·카페·관광 중 조용히 쉬는 장소입니다. 야경은 18시 이후의 야경 감상 장소이며 단순 저녁 식당으로 대체하지 않습니다.
                - 힐링이나 야경을 선택해 스키마에 matchedStyles가 있을 때만, 해당 장소가 실제로 반영하는 선택 스타일을 넣습니다. 선택 스타일에 해당하지 않는 보조 활동은 빈 배열로 둡니다. 검증을 피하려고 무관한 스타일을 붙이지 않습니다.
                - matchedStyles가 없는 스키마에서는 장소의 실제 카테고리만 정확히 작성하세요. 스타일 분류는 서버가 처리합니다.
                - 추천 장소는 반드시 입력한 목적지 행정구역 안에서 선택하세요. 고정 km 반경은 적용하지 말고 매일 숙소를 중심으로 동선을 묶으세요.
                - 같은 날의 장소는 서로 가까운 곳끼리 묶어 이동을 줄이고, 방문 순서대로 나열합니다. 숙소와 도착·출발 장소를 기준으로 먼 지역을 왕복하거나 같은 구역을 반복 방문하는 동선을 피하세요.
                - visitTime은 "HH:mm" 24시간 형식이며 같은 날 안에서 오름차순입니다. 이동 시간을 감안한 추정치입니다.
                - 좌표, 주소, 전화번호, 영업시간, URL은 절대 만들지 않습니다(서버가 카카오맵 검색으로 확인합니다).
                - name은 실제 장소의 정확한 지점명만 작성하세요. searchQuery, reason, note는 서버가 처리하므로 출력하지 않습니다.
                - 국내 여행만 다룹니다.
                - 1일차부터 마지막 날까지 전체 여행에서 같은 실제 장소를 반복하지 않습니다. 다른 시간·이름 표기·검색어로도 같은 장소를 다시 넣지 않습니다. 지점은 정확한 지점명을 명시합니다.
                - alternatives에는 같은 스타일과 카테고리로 대체 가능한 실제 장소를 일차별 2~3곳만 넣습니다. 다른 날짜의 방문 장소와 중복되지 않게 준비합니다.
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
            sb.append("여행 전체에서 각각 최소 1회 포함할 스타일: ").append(String.join(", ", r.styles())).append('\n');
        }
        if (r.extraRequest() != null && !r.extraRequest().isBlank()) {
            sb.append("추가 요청: ").append(r.extraRequest()).append('\n');
        }
        sb.append(com.tripplanner.trip.ExtraRequestRules.includePrompt(r.extraRequest(),r.days()));
        sb.append(com.tripplanner.trip.ExtraRequestRules.mealPrompt(r.extraRequest(),r.days()));
        sb.append(com.tripplanner.trip.TravelSchedule.prompt(r.travelDetails(), r.days()));
        sb.append("title은 \"").append(r.destination()).append(" ").append(r.days()).append("일 여행\"처럼 짧게 만드세요.");
        return sb.toString();
    }

    private static Map<String, Object> schema(ItineraryRequest request) {
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("name",str());properties.put("visitTime",str());
        properties.put("category",Map.of("type","string","enum",CATEGORIES));
        if(request.styles()!=null&&request.styles().stream().anyMatch(style->Set.of("힐링","야경").contains(style)))
            properties.put("matchedStyles",Map.of("type","array","items",Map.of("type","string","enum",ItineraryRules.STYLES)));
        Map<String,Object> place=obj(properties);
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
