package com.tripplanner.place;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Conservative interpretation of public-source text. Unrecognized wording is UNKNOWN, never assumed open. */
public final class BusinessHoursRules {
    private BusinessHoursRules() {}
    public record Result(String status,String detail) {public boolean closed(){return status.equals("CLOSED");}}
    private record Window(Set<Integer> days,int open,int close,boolean pause) {}
    private record Schedule(List<Window> windows,Integer lastOrder,boolean uncertain) {}
    private static final String WEEK="월화수목금토일";
    private static final Pattern RANGE=Pattern.compile("(?<![0-9])([0-2]?[0-9]):([0-5][0-9])\\s*[~〜～–—-]\\s*([0-2]?[0-9]):([0-5][0-9])");
    private static final Pattern LAST=Pattern.compile("(?:라스트\\s*오더|마지막\\s*주문|last\\s*order)\\s*[:：]?\\s*([0-2]?[0-9]):([0-5][0-9])",Pattern.CASE_INSENSITIVE);
    public static String plain(String value) {
        if(value==null)return "";
        return value.replaceAll("(?i)<br\\s*/?>","; ").replaceAll("<[^>]*>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("[ \\t]+"," ").trim();
    }
    public static Result evaluate(BusinessHoursInfo info,LocalDate date,String time,int duration) {
        if(info==null)return unknown("운영시간 미확인");
        if(info.contentId()==null && info.hoursText()==null && info.closedText()==null)
            return unknown(info.detail()==null?"운영시간 미확인":info.detail());
        if(date==null)return unknown("여행 날짜가 없어 운영시간·휴무일을 확인하지 못했어요.");
        int start;
        try {start=LocalTime.parse(time).getHour()*60+LocalTime.parse(time).getMinute();}
        catch(Exception e){return unknown("방문 시각을 확인해 주세요.");}
        Schedule schedule=parseHours(plain(info.hoursText()));
        int rest=rest(plain(info.closedText()),date);
        // A Sunday overnight opening can extend into a Monday closure. Interpret by the opening date.
        boolean possible=false,uncertain=schedule.uncertain();
        for(int offset=0;offset<=1;offset++) {
            LocalDate openingDate=date.minusDays(offset);int holiday=rest(plain(info.closedText()),openingDate);
            if(holiday<0)continue;
            int minute=start+offset*1440;
            for(Window w:schedule.windows()) {
                if(w.pause() || !w.days().contains(openingDate.getDayOfWeek().getValue()) || offset==1 && w.close()<=1440)continue;
                if(minute<w.open() || minute+duration>w.close())continue;
                if(schedule.lastOrder()!=null) {
                    int last=schedule.lastOrder();if(last<w.open() && w.close()>1440)last+=1440;
                    if(minute>last)continue;
                }
                boolean paused=schedule.windows().stream().anyMatch(p->p.pause() && p.days().contains(openingDate.getDayOfWeek().getValue()) && minute<p.close() && minute+duration>p.open());
                if(paused)continue;
                possible=true;uncertain|=holiday>0;
            }
        }
        if(possible)return uncertain?unknown("정규 운영시간은 맞지만 휴무·예외 조건은 추가 확인이 필요해요."):new Result("OPEN","공공데이터의 정규 운영시간·휴무일 기준으로 배치했어요.");
        if(rest<0)return new Result("CLOSED","방문 날짜가 공공데이터의 정기휴무일이에요.");
        if(schedule.windows().isEmpty() || schedule.uncertain())return unknown("운영시간을 자동으로 해석하지 못했어요. 원문을 확인해 주세요.");
        return new Result("CLOSED","오픈·마감·휴게시간 안에 방문을 마칠 수 없어요.");
    }
    public static boolean allowed(BusinessHoursInfo info,LocalDate date,String time) {return !evaluate(info,date,time,60).closed();}
    public static boolean allowedAtStoredDate(BusinessHoursInfo info,String time) {
        LocalDate date=null;try{if(info!=null && info.visitDate()!=null)date=LocalDate.parse(info.visitDate());}catch(Exception ignored){}
        return allowed(info,date,time);
    }
    private static Result unknown(String reason){return new Result("UNKNOWN",reason);}
    private static Schedule parseHours(String text) {
        boolean uncertain=text.matches("(?s).*(계절|동절기|하절기|변동|상이|문의|사정|예약제|홈페이지|인스타|공휴일|명절|설날|추석).*");
        Integer last=null;Matcher order=LAST.matcher(text);
        if(order.find()){last=Integer.parseInt(order.group(1))*60+Integer.parseInt(order.group(2));if(last>1440)uncertain=true;text=order.replaceAll("");}
        List<Window> windows=new ArrayList<>();
        if(text.contains("24시간") || text.toLowerCase(Locale.ROOT).contains("24 hours"))windows.add(new Window(all(),0,1440,false));
        Set<Integer> active=all();
        for(String line:text.split("[;\\n]+")) {
            Matcher matcher=RANGE.matcher(line);int previous=0;
            while(matcher.find()) {
                String label=line.substring(previous,matcher.start());Set<Integer> scoped=weekdays(label);
                if(label.contains("평일"))scoped=Set.of(1,2,3,4,5);if(label.contains("주말"))scoped=Set.of(6,7);if(label.contains("매일"))scoped=all();
                if(!scoped.isEmpty())active=scoped;
                int open=Integer.parseInt(matcher.group(1))*60+Integer.parseInt(matcher.group(2));int close=Integer.parseInt(matcher.group(3))*60+Integer.parseInt(matcher.group(4));
                if(open>=1440 || close>1440){uncertain=true;continue;}if(close<=open)close+=1440;
                boolean pause=label.toLowerCase(Locale.ROOT).matches("(?s).*(브레이크|break|휴게|쉬는시간).*");
                windows.add(new Window(Set.copyOf(active),open,close,pause));previous=matcher.end();
            }
        }
        return new Schedule(windows,last,uncertain);
    }
    private static Set<Integer> all(){return Set.of(1,2,3,4,5,6,7);}
    private static Set<Integer> weekdays(String text) {
        Set<Integer> days=new HashSet<>();
        Matcher range=Pattern.compile("([월화수목금토일])(?:요일)?\\s*[~〜～-]\\s*([월화수목금토일])(?:요일)?").matcher(text);
        while(range.find()){int first=WEEK.indexOf(range.group(1))+1,last=WEEK.indexOf(range.group(2))+1;for(int n=first;;n=n%7+1){days.add(n);if(n==last)break;}}
        Matcher names=Pattern.compile("([월화수목금토일])요일").matcher(text);while(names.find())days.add(WEEK.indexOf(names.group(1))+1);
        Matcher list=Pattern.compile("[월화수목금토일](?:\\s*[,/·]\\s*[월화수목금토일])+").matcher(text);
        while(list.find())for(char c:list.group().toCharArray())if(WEEK.indexOf(c)>=0)days.add(WEEK.indexOf(c)+1);
        return days;
    }
    private static int rest(String text,LocalDate date) {
        if(text.isBlank())return 1;
        String compact=text.replaceAll("\\s+","");
        if(compact.contains("연중무휴") || compact.equals("없음") || compact.equals("무휴"))return 0;
        boolean exception=compact.matches("(?s).*(경우|익일|정상영업|다음날|대체).*");
        Set<Integer> days=weekdays(text);
        if(compact.matches("(?s).*[1-5][,/·][1-5].*"))return 1;
        boolean monthly=compact.contains("매월") || compact.matches("(?s).*(첫째|둘째|셋째|넷째|다섯째|마지막).*");
        boolean matched=days.contains(date.getDayOfWeek().getValue());
        if(monthly) {
            int ordinal=(date.getDayOfMonth()-1)/7+1;boolean selected=false;
            String[] words={"첫째","둘째","셋째","넷째","다섯째"};
            for(int n=1;n<=5;n++)if(compact.contains(words[n-1]) || compact.contains(n+"주"))selected|=ordinal==n;
            if(compact.contains("마지막"))selected|=date.plusWeeks(1).getMonth()!=date.getMonth();
            matched&=selected;
        }
        if(matched && !exception)return -1;
        Matcher annual=Pattern.compile("([0-1]?[0-9])월([0-3]?[0-9])일").matcher(compact);boolean hasDate=false;
        while(annual.find()){hasDate=true;if(date.getMonthValue()==Integer.parseInt(annual.group(1)) && date.getDayOfMonth()==Integer.parseInt(annual.group(2)))return -1;}
        if(exception || compact.matches("(?s).*(공휴일|명절|설날|추석|임시|비정기|유동|문의|수시|인스타|홈페이지).*") || days.isEmpty() && !hasDate)return 1;
        return 0;
    }
}
