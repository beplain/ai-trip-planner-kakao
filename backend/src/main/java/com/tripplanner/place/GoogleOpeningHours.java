package com.tripplanner.place;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;

/** Request-local evidence only. Google response fields are not serialized into trip documents. */
public final class GoogleOpeningHours {
    public enum State { OPEN, CLOSED, UNKNOWN }
    public record Assessment(State state,boolean regularOnly,String reason) {
        public boolean open(){return state==State.OPEN;}
    }
    private final JsonNode place;
    private final LocalDate fetchedDate;
    private final String unavailable;
    public GoogleOpeningHours(JsonNode place,LocalDate fetchedDate){this.place=place;this.fetchedDate=fetchedDate;this.unavailable=null;}
    private GoogleOpeningHours(String reason){place=null;fetchedDate=null;unavailable=reason;}
    public static GoogleOpeningHours unknown(String reason){return new GoogleOpeningHours(reason);}
    public Assessment assess(LocalDate date,String time,int dwell) {
        if(place==null)return unknownResult(unavailable);
        if(date==null)return unknownResult("여행 날짜가 없어 요일별 운영시간을 확인하지 못했습니다.");
        LocalDateTime start;
        try {start=date.atTime(LocalTime.parse(time));}catch(Exception e){return unknownResult("방문 시각을 확인하지 못했습니다.");}
        if(dwell<=0)return unknownResult("방문 소요시간이 올바르지 않습니다.");
        String status=place.path("businessStatus").asText();
        if(status.equals("CLOSED_TEMPORARILY")||status.equals("CLOSED_PERMANENTLY")||status.equals("FUTURE_OPENING"))
            return new Assessment(State.CLOSED,false,"임시휴업·폐업 또는 개점 전 장소입니다.");
        boolean current=!date.isBefore(fetchedDate)&&date.isBefore(fetchedDate.plusDays(7))&&place.path("currentOpeningHours").has("periods");
        JsonNode hours=place.path(current?"currentOpeningHours":"regularOpeningHours");
        if(!hours.path("periods").isArray())return unknownResult("운영시간이 등록되지 않았습니다.");
        JsonNode periods=hours.path("periods");
        if(periods.isEmpty())return new Assessment(State.CLOSED,!current,"방문 날짜·시간에 영업하지 않습니다.");
        LocalDateTime finish=start.plusMinutes(dwell);
        boolean malformed=false;
        for(JsonNode period:periods) {
            try {
                JsonNode open=period.path("open"),close=period.path("close");
                if(!open.has("hour")||!open.has("minute")||!open.has("day")) {malformed=true;continue;}
                if(!period.has("close")) {
                    if(open.path("day").asInt(-1)==0&&open.path("hour").asInt(-1)==0&&open.path("minute").asInt(-1)==0&&!open.has("date")&&!current)
                        return new Assessment(State.OPEN,true,"정규 24시간 영업 기준입니다.");
                    malformed=true;continue;
                }
                if(current) {
                    // Current periods are dated and may be truncated at the seven-day boundary.
                    LocalDateTime a=dated(open),b=dated(close);
                    if(!start.isBefore(a)&&!finish.isAfter(b))return new Assessment(State.OPEN,false,"현재 제공된 날짜별 운영시간 기준입니다.");
                } else {
                    int od=open.path("day").asInt(-1),cd=close.path("day").asInt(-1);
                    if(od<0||od>6||cd<0||cd>6||!close.has("hour")||!close.has("minute")){malformed=true;continue;}
                    LocalDate sunday=date.minusDays(date.getDayOfWeek().getValue()%7);
                    for(int offset:new int[]{-7,0,7}) {
                        LocalDateTime a=sunday.plusDays(od+offset).atTime(open.path("hour").asInt(),open.path("minute").asInt());
                        LocalDateTime b=sunday.plusDays(cd+offset).atTime(close.path("hour").asInt(),close.path("minute").asInt());
                        if(!b.isAfter(a))b=b.plusWeeks(1);
                        if(!start.isBefore(a)&&!finish.isAfter(b))return new Assessment(State.OPEN,true,"정규 요일별 운영시간 기준이며 해당 날짜의 임시휴무는 미확인입니다.");
                    }
                }
            } catch(Exception e){malformed=true;}
        }
        if(malformed)return unknownResult("운영시간 응답이 불완전하여 방문 가능 여부를 확인하지 못했습니다.");
        return new Assessment(State.CLOSED,!current,"휴무·오픈 전·마감 또는 휴게시간과 겹칩니다.");
    }
    private static LocalDateTime dated(JsonNode point) {
        JsonNode d=point.path("date");
        return LocalDate.of(d.path("year").asInt(),d.path("month").asInt(),d.path("day").asInt())
            .atTime(point.path("hour").asInt(),point.path("minute").asInt());
    }
    private static Assessment unknownResult(String reason){return new Assessment(State.UNKNOWN,false,reason);}
}
