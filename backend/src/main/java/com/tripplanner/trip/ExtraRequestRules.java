package com.tripplanner.trip;

import com.tripplanner.common.ApiException;
import com.tripplanner.trip.TripDtos.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Fail closed for requirements we cannot independently check. Never treat an AI claim as proof. */
public final class ExtraRequestRules {
    private ExtraRequestRules() {}
    public record Check(String request, String status, String detail) {}
    private record Rule(String source, String kind, String target, int value, boolean daily, int day, String meal, String category, List<String> aliases) {
        Rule(String source,String kind,String target,int value,boolean daily) { this(source,kind,target,value,daily,0,null,null,List.of()); }
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    public static final String STRUCTURED = "REQUEST_RULES_V1\n";
    public static String structured(com.fasterxml.jackson.databind.JsonNode response) {
        try {
            String encoded=STRUCTURED+JSON.writeValueAsString(response);
            if(encoded.length()>16000) throw new IllegalArgumentException();
            Plan plan=parse(encoded);
            if(plan.rules().isEmpty() && plan.unknown().isEmpty()) throw new IllegalArgumentException("조건이 누락되었습니다");
            validateInput(encoded);
            return encoded;
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw ApiException.badRequest("추가 요청 해석 결과를 확인하지 못했어요. 잠시 후 다시 시도해 주세요."); }
    }
    public record Meal(String source,String menu,String meal,int day,int start,int end) {
        public boolean lastRestaurant() { return meal.equals("마지막 식당"); }
        public boolean matchesPlace(String name,String category,String categoryPath) {
            return "식당".equals(category) && (exact(name,categoryPath)
                || (key(menu).equals("삼겹살") && key(categoryPath).contains("돼지고기구이")));
        }
        public boolean exact(String name,String categoryPath) {
            return key(name).contains(key(menu)) || key(categoryPath).contains(key(menu));
        }
        public boolean claims(String name,String category,String description) {
            return "식당".equals(category) && key(name+" "+Objects.toString(description,"")).contains(key(menu));
        }
        public boolean containsTime(String time) { int m=ExtraRequestRules.time(time); return m>=start && m+60<=end; }
        public String query(String destination) { return destination+" "+menu; }
        public List<String> queries(String destination) {
            return key(menu).equals("삼겹살") ? List.of(query(destination),destination+" 삼겹살 맛집",destination+" 돼지고기구이") : List.of(query(destination));
        }
    }
    private record Plan(List<Rule> rules, List<String> unknown) {}
    private static final Map<String,String> CATEGORIES = Map.ofEntries(
        Map.entry("식당","식당"),Map.entry("맛집","식당"),Map.entry("식사","식당"),
        Map.entry("카페","카페"),Map.entry("쇼핑","쇼핑"),Map.entry("관광","관광"),
        Map.entry("자연","자연"),Map.entry("문화","문화"),Map.entry("체험","체험"),Map.entry("액티비티","체험"));
    private static final String POLITE = "(?:해줘|해주세요|해주십시오|해요|줘|주세요|요|해)?";
    private static final Pattern EXCLUDE = Pattern.compile("^(.+?)(?:추천하지말아(?:줘|주세요)|추천하지마(?:세요|요)?|방문하지말아(?:줘|주세요)|방문하지마(?:세요|요)?|추천금지|방문금지|제외"+POLITE+"|빼(?:줘|주세요)|빼기|제외하기|안가(?:게|고싶어|고싶어요|고싶어요)?|가지않게)$");
    private static final Pattern INCLUDE = Pattern.compile("^(.+?)(?:방문"+POLITE+"|포함"+POLITE+"|넣어(?:줘|주세요)|가고싶(?:어|어요|습니다)|방문하기)$");
    private static final Pattern COUNT = Pattern.compile("^(?:매일|하루(?:에)?|일일)(?:방문)?(?:장소|방문지)?(?:는|를|을)?(?:최대)?([1-9]|[12][0-9])(?:곳|개)(?:이하|이내|까지만|까지)(?:로)?(?:추천|방문|제한)?"+POLITE+"$");
    private static final Pattern ALL_AFTER = Pattern.compile("^모든일정을([01]?\\d|2[0-3])(?:시|:)([0-5]\\d)?(?:분)?이후(?:로|에)?(?:생성|만들)(?:해)?"+POLITE+"$");
    private static final Pattern TIME = Pattern.compile("^(?:매일|하루)?([01]?\\d|2[0-3])(?:시|:)([0-5]\\d)?(?:분)?(?:이후|부터|후)(?:에)?(?:일정|관광|방문)?(?:을|은)?시작"+POLITE+"$");
    private static final Pattern END = Pattern.compile("^(?:매일|하루)?([01]?\\d|2[0-3])(?:시|:)([0-5]\\d)?(?:분)?(?:이전|전에|전|까지)(?:일정|관광|방문)?(?:을|은)?(?:종료|마무리|끝내)"+POLITE+"$");
    private static final Pattern MEAL_TIME = Pattern.compile("^(아침|점심|저녁)(?:식사)?(?:는|은|를|을)?([01]?\\d|2[0-3])(?:시|:)([0-5]\\d)?(?:분)?(?:에|으로|로)?(?:배치|추천|시작)?"+POLITE+"$");
    private static final Pattern MEAL = Pattern.compile("^(?:(마지막날|마지막일차|첫날|첫째날|둘째날|셋째날|넷째날|다섯째날|여섯째날|일곱째날|[1-7]일차|[1-7]일째|매일|하루마다)(?:에는|은|는|에)?)?(아침|점심|저녁)(?:식사)?(?:에는|은|는|에|을|를)?(.+?)(?:으로|로|을|를)?(?:추천"+POLITE+"|먹고싶(?:어|어요|습니다)|먹게"+POLITE+"|먹을래|먹을래요|먹고싶어해|포함"+POLITE+"|넣어(?:줘|주세요))$");
    private static final Pattern LAST_RESTAURANT = Pattern.compile("^(?:(마지막날|마지막일차|첫날|첫째날|둘째날|셋째날|넷째날|다섯째날|여섯째날|일곱째날|[1-7]일차|[1-7]일째)(?:에는|은|는|에)?)?마지막(?:으로)?(?:추천)?(?:식당|식사|음식점)(?:에는|은|는|에|을|를)?(.+?)(?:으로|로|을|를)?(?:추천"+POLITE+"|해줘|해주세요|부탁해|부탁해요|넣어(?:줘|주세요)|포함"+POLITE+")$");
    private static int dayNumber(String day) {
        if(day==null || day.equals("매일") || day.equals("하루마다")) return 0;
        if(day.startsWith("마지막")) return -1;
        if(day.equals("첫날")) return 1;
        List<String> words=List.of("첫째날","둘째날","셋째날","넷째날","다섯째날","여섯째날","일곱째날");
        int i=words.indexOf(day); return i>=0?i+1:Integer.parseInt(day.substring(0,1));
    }
    public static List<Meal> meals(String request,int day,int days) {
        var overrides = mealTimes(request, day, days);
        return parse(request).rules().stream().filter(r->r.kind().equals("MEAL"))
            .filter(r->r.day()==0 || (r.day()==-1?days:r.day())==day)
            .map(r->new Meal(r.source(),r.target(),r.meal(),day,
                overrides.getOrDefault(r.meal(),r.meal().equals("마지막 식당")?0:r.meal().equals("아침")?7*60:r.meal().equals("점심")?11*60:17*60),
                overrides.containsKey(r.meal()) ? overrides.get(r.meal())+60 : r.meal().equals("마지막 식당")?24*60:r.meal().equals("아침")?11*60:r.meal().equals("점심")?15*60:22*60)).toList();
    }
    public static Map<String,List<String>> mealSearches(String request,String destination,int days) {
        Map<String,List<String>> searches=new LinkedHashMap<>();
        for(int day=1;day<=days;day++) for(Meal meal:meals(request,day,days)) searches.put(meal.query(destination),meal.queries(destination));
        for(Rule rule:parse(request).rules()) if(Set.of("INCLUDE","EXACT").contains(rule.kind())) {
            List<String> names=new ArrayList<>();names.add(rule.target());if(rule.aliases()!=null) names.addAll(rule.aliases());
            searches.put(destination+" "+rule.target(),names.stream().distinct().map(name->destination+" "+name).toList());
        }
        return searches;
    }
    public static String includePrompt(String request,int days) {
        StringBuilder out=new StringBuilder();
        for(Rule rule:parse(request).rules()) if(Set.of("INCLUDE","EXACT").contains(rule.kind())) {
            String scope=rule.daily()?"매일":rule.day()==0?"여행 전체 "+days+"일 합계":(rule.day()==-1?days:rule.day())+"일차";
            out.append(scope).append(": ").append(rule.target()).append(rule.kind().equals("EXACT")?" 정확히 ":" 최소 ")
                .append(Math.max(1,rule.value())).append("곳을 포함하세요. ");
            if(rule.kind().equals("EXACT")) out.append("다른 지점과 별칭도 같은 대상으로 합산하며 이 수를 넘기지 마세요. 나머지 ")
                .append(rule.category()==null || rule.category().isBlank()?"방문 장소":rule.category()).append("는 다른 장소로 추천하고 기존 방문 수와 스타일을 유지하세요. ");
            else out.append("최소 수를 채운 뒤에는 다른 장소도 골고루 추천하세요. ");
            if(rule.aliases()!=null && !rule.aliases().isEmpty())out.append("동일 대상 표기: ").append(String.join(", ",rule.aliases())).append(". ");
            out.append('\n');
        }
        return out.toString();
    }
    public static String mealPrompt(String request,int days) {
        StringBuilder out=new StringBuilder();
        for(int day=1;day<=days;day++) for(Meal meal:meals(request,day,days)) {
            if(meal.lastRestaurant()) {
                out.append(day).append("일차 마지막으로 방문하는 식당은 ").append(meal.menu()).append(" 관련 식당이어야 합니다. 17시 이후라는 제한은 없습니다. 귀가 출발 장소로 이동·탑승할 시간을 남겨 배치하고, 그 뒤에 다른 식당을 추가하지 마세요. 카페·관광은 그 뒤에도 가능합니다.\n");
                continue;
            }
            out.append(day).append("일차 필수 식사: ")
            .append(meal.meal()).append(" ").append(TravelSchedule.clock(meal.start())).append("~").append(TravelSchedule.clock(meal.end()))
            .append(" 안에 ").append(meal.menu()).append(" 식당 방문 60분을 배치하세요. 메뉴 검색어와 실제 식당 대체 후보를 포함하고, 다른 날짜·끼니로 대신하지 마세요.\n");
        }
        return out.toString();
    }
    public static List<com.tripplanner.ai.AiItinerary.AiPlace> mealSeeds(String request,String destination,int day,int days) {
        return meals(request,day,days).stream().map(m->new com.tripplanner.ai.AiItinerary.AiPlace(
            m.menu()+" 식당",m.query(destination),TravelSchedule.clock(m.lastRestaurant()?10*60:m.start()),"식당",
            m.meal()+" 식사 요청: "+m.menu(),"카카오맵 장소명·업종으로 확인하고 실제 메뉴는 방문 전 확인하세요.",List.of("맛집"))).toList();
    }
    public static com.tripplanner.ai.AiItinerary addMealCandidates(com.tripplanner.ai.AiItinerary input,com.tripplanner.ai.ItineraryRequest request) {
        if(input==null || input.days()==null) return input;
        return new com.tripplanner.ai.AiItinerary(input.title(),input.days().stream().map(d->{
            if(d==null) return d;
            var alternatives=new ArrayList<com.tripplanner.ai.AiItinerary.AiPlace>(mealSeeds(request.extraRequest(),request.destination(),d.dayNumber(),request.days()));
            alternatives.addAll(includeSeeds(request.extraRequest(),request.destination(),d.dayNumber(),request.days()));
            if(d.alternatives()!=null) alternatives.addAll(d.alternatives());
            return new com.tripplanner.ai.AiItinerary.AiDay(d.dayNumber(),d.places(),alternatives);
        }).toList());
    }
    static boolean allowsMealHit(String request,int day,int days,String name,String category,String description,String hitName,String categoryPath) {
        return meals(request,day,days).stream().filter(m->m.claims(name,category,description)).allMatch(m->m.matchesPlace(hitName,category,categoryPath));
    }
    static Map<String,Integer> reserveMeals(String request,int days,java.util.function.Function<Meal,List<com.tripplanner.place.KakaoLocalClient.PlaceHit>> lookup) {
        Map<String,Integer> reserved=new HashMap<>();
        for(int day=1;day<=days;day++) for(Meal meal:meals(request,day,days)) {
            for(var hit:lookup.apply(meal)) {
                if(hit==null || hit.placeId()==null || reserved.containsKey(hit.placeId()) || !Double.isFinite(hit.lat()) || !Double.isFinite(hit.lng())) continue;
                if(!com.tripplanner.place.PlaceCategories.matches("식당",hit) || !meal.matchesPlace(hit.name(),"식당",hit.categoryName()) || !allows(request,hit.name(),"식당")) continue;
                reserved.put(hit.placeId(),day); break;
            }
        }
        return reserved;
    }
    public static boolean lastRestaurantsSatisfied(String request,int day,int days,List<PlaceDraft> visits) {
        var wanted=meals(request,day,days).stream().filter(Meal::lastRestaurant).toList();
        if(wanted.isEmpty()) return true;
        var last=visits.stream().filter(p->p.fixedEventId()==null && "식당".equals(p.category())).max(Comparator.comparingInt(p->time(p.visitTime()))).orElse(null);
        return last!=null && wanted.stream().allMatch(m->last.verified() && m.matchesPlace(last.name(),last.category(),last.categoryPath()));
    }
    static Map<String, Integer> mealTimes(String request, int day, int days) {
        Map<String, Integer> times = new HashMap<>();
        for (Rule rule : parse(request).rules()) {
            if (!rule.kind().equals("MEAL_TIME") || rule.day() != 0 && (rule.day() == -1 ? days : rule.day()) != day) continue;
            Integer previous = times.put(rule.meal(), rule.value());
            if (previous != null && previous != rule.value())
                throw ApiException.badRequest(day + "일차 " + rule.meal() + "에 서로 다른 식사 시각이 요청됐습니다.");
        }
        return times;
    }

    static boolean mealTimeAllowed(PlaceDraft p,String time,String request,int day,int days) {
        if (DraftMealPlanner.scheduledMeal(p) && !DraftMealPlanner.withinWindow(p, time, mealTimes(request, day, days))) return false;
        var matched=meals(request,day,days).stream().filter(m->m.matchesPlace(p.name(),p.category(),p.categoryPath())).toList();
        return matched.isEmpty() || matched.stream().anyMatch(m->m.containsTime(time));
    }
    static PlaceDraft annotateMeal(PlaceDraft p,String request,int day,int days) {
        var matched=meals(request,day,days).stream().filter(m->m.matchesPlace(p.name(),p.category(),p.categoryPath())).toList();
        if(matched.isEmpty()) return p;
        String reason=matched.stream().map(m->m.meal()+" 식사 요청: "+m.menu()).distinct().reduce((a,b)->a+", "+b).orElse("")+" · "+Objects.toString(p.reason(),"");
        if(reason.length()>500) reason=reason.substring(0,500);
        return new PlaceDraft(p.placeId(),p.name(),p.address(),p.latitude(),p.longitude(),p.visitTime(),reason,p.note(),p.category(),p.verified(),p.placeUrl(),p.phone(),p.fixedEventId(),p.categoryPath()).withBusinessHours(p.businessHours());
    }
    private static String compact(String s) { return s == null ? "" : Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+",""); }
    private static String key(String s) { return compact(s).replaceAll("[^\\p{L}\\p{N}]",""); }
    private static String target(String s) {
        return s.replaceAll("^(?:저는|나는|저희는|꼭|반드시)","")
            .replaceAll("(?:일정에서|일정에|장소는|장소를|매장은|지점은)$","")
            .replaceAll("[은는을를]$","").replaceAll("(?:매장|지점|브랜드)$","").replaceAll("(?:꼭|반드시)$","");
    }
    public record Visit(int day,String name,String category) {}
    private static boolean inScope(Rule rule,int day,int days) {
        return rule.day()==0 || (rule.day()==-1?days:rule.day())==day;
    }
    public static boolean allowsAdditional(String request,String name,String category,int day,int days,List<Visit> previous) {
        return parse(request).rules().stream().filter(r->r.kind().equals("EXACT") && inScope(r,day,days) && matches(r,name,category))
            .allMatch(r->previous.stream().filter(v->inScope(r,v.day(),days) && (!r.daily() || v.day()==day) && matches(r,v.name(),v.category())).count()<r.value());
    }
    public static List<com.tripplanner.ai.AiItinerary.AiPlace> includeSeeds(String request,String destination,int day,int days) {
        return parse(request).rules().stream().filter(r->Set.of("INCLUDE","EXACT").contains(r.kind()) && (r.day()==0 || r.day()==day || r.day()==-1 && day==days))
            .map(r->new com.tripplanner.ai.AiItinerary.AiPlace(r.target(),destination+" "+r.target(),"10:00",
                r.category()==null || r.category().isBlank()?CATEGORIES.getOrDefault(r.target(),"관광"):r.category(),
                "추가 요청 필수 방문: "+r.target(),"",List.of())).toList();
    }
    public static boolean includesSatisfied(String request,int day,int days,List<com.tripplanner.ai.AiItinerary.AiPlace> places) {
        return parse(request).rules().stream().filter(r->Set.of("INCLUDE","EXACT").contains(r.kind()) && (r.daily() || r.day()==day || r.day()==-1 && day==days || r.day()==0 && day==1))
            .allMatch(r->places.stream().filter(p->matches(r,p.name(),p.category())).count()>=Math.max(1,r.value()));
    }
    private static Plan normalizeDefaultMenus(Plan plan) {
        return new Plan(plan.rules().stream().map(r->{
            if(r.source()==null||r.target()==null)return r;
            String source=compact(r.source());
            boolean explicitScope=source.matches("(?s).*(매일|하루마다|아침|점심|저녁|조식|중식|석식|마지막식당).*");
            boolean quantity=source.matches("(?s).*(?:[0-9]+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*(?:곳|개|번|끼).*");
            boolean food="식당".equals(r.category())||"MEAL".equals(r.kind());
            if(!food||explicitScope||quantity||r.value()>1||CATEGORIES.containsKey(r.target())
                ||!Set.of("INCLUDE","EXACT","MEAL").contains(r.kind()))return r;
            // Generic menu requests are one visit, never an inferred daily meal rule.
            return new Rule(r.source(),"EXACT",r.target(),1,false,r.day(),"","식당",r.aliases());
        }).toList(),plan.unknown());
    }
    private static Plan parse(String request) {
        if(request!=null && request.startsWith(STRUCTURED)) {
            try {
                Plan plan=JSON.readValue(request.substring(STRUCTURED.length()),Plan.class);
                if(plan.rules()!=null)plan=normalizeDefaultMenus(plan);
                if(plan.rules()==null || plan.unknown()==null || plan.rules().size()>20) throw new IllegalArgumentException();
                for(Rule rule:plan.rules()) {
                    if(rule.source()==null || rule.source().isBlank() || rule.source().length()>200 || rule.target()==null || rule.target().length()>100
                        || !Set.of("INCLUDE","EXACT","EXCLUDE","MEAL","MEAL_TIME","MAX","START","END").contains(rule.kind()) || rule.day() < -1 || rule.day()>7
                        || rule.value()<0 || rule.value()>1440 || rule.aliases()==null || rule.aliases().size()>8
                        || rule.aliases().stream().anyMatch(a->a==null || a.isBlank() || a.length()>100)
                        || rule.category()==null || !Set.of("","관광","식당","카페","쇼핑","자연","문화","체험","기타").contains(rule.category())
                        || Set.of("INCLUDE","EXACT","EXCLUDE","MEAL").contains(rule.kind()) && rule.target().isBlank()
                        || Set.of("INCLUDE","EXACT").contains(rule.kind()) && (rule.value()<1 || rule.value()>20)
                        || rule.kind().equals("MEAL_TIME") && (rule.value() > 1379
                            || !Set.of("아침","점심","저녁").contains(rule.meal()))
                        || rule.kind().equals("MEAL") && !Set.of("아침","점심","저녁","마지막 식당").contains(rule.meal())) throw new IllegalArgumentException();
                }
                return plan;
            } catch(Exception e) { throw ApiException.badRequest("추가 요청의 해석 결과가 올바르지 않아요. 다시 생성해 주세요."); }
        }
        List<Rule> rules = new ArrayList<>(); List<String> unknown = new ArrayList<>();
        if (request == null || request.isBlank()) return new Plan(rules,unknown);
        for (String source : request.split("[\\n;.!?。]+|\\s+그리고\\s+|[,，]")) {
            if (source.isBlank()) continue;
            String text=compact(source).replaceAll("[\"'‘’“”]","");
            Matcher mealTime=MEAL_TIME.matcher(text);
            if (mealTime.matches()) {
                int minute = Integer.parseInt(mealTime.group(2)) * 60
                        + (mealTime.group(3) == null ? 0 : Integer.parseInt(mealTime.group(3)));
                rules.add(new Rule(source.trim(),"MEAL_TIME","",minute,true,0,mealTime.group(1),"",List.of()));
                continue;
            }
            Matcher last=LAST_RESTAURANT.matcher(text);
            if(last.matches()) {
                String menu=target(last.group(2)).replaceAll("(?:식당|맛집)$","");
                if(!menu.isBlank() && menu.length()<=30 && !menu.matches(".*(?:하지만|대신|말고|제외|그리고|경우|알레르기|이나|또는|혹은).*")) {
                    rules.add(new Rule(source.trim(),"MEAL",menu,0,false,last.group(1)==null?-1:dayNumber(last.group(1)),"마지막 식당",null,List.of())); continue;
                }
            }
            Matcher food=MEAL.matcher(text);
            if(food.matches()) {
                String menu=target(food.group(3)).replaceAll("(?:식당|맛집)$","");
                if(!menu.isBlank() && menu.length()<=30 && !menu.matches(".*(?:하지만|대신|말고|제외|그리고|경우|많이|적게|저렴|가까운|같은|알레르기|없|이나|또는|혹은).*")) {
                    String day=food.group(1);
                    rules.add(new Rule(source.trim(),"MEAL",menu,0,day==null || day.equals("매일") || day.equals("하루마다"),dayNumber(day),food.group(2),null,List.of())); continue;
                }
            }
            Matcher m=COUNT.matcher(text);
            if (m.matches()) { rules.add(new Rule(source.trim(),"MAX","",Integer.parseInt(m.group(1)),true)); continue; }
            m=TIME.matcher(text);
            if (!m.matches()) m=ALL_AFTER.matcher(text);
            if (m.matches()) { rules.add(new Rule(source.trim(),"START","",60*Integer.parseInt(m.group(1))+(m.group(2)==null?0:Integer.parseInt(m.group(2))),true)); continue; }
            m=END.matcher(text);
            if (m.matches()) { rules.add(new Rule(source.trim(),"END","",60*Integer.parseInt(m.group(1))+(m.group(2)==null?0:Integer.parseInt(m.group(2))),true)); continue; }
            boolean daily=text.startsWith("매일") || text.startsWith("하루마다");
            String body=text.replaceFirst("^(매일|하루마다)","");
            m=EXCLUDE.matcher(body); String kind="EXCLUDE";
            if (!m.matches()) { m=INCLUDE.matcher(body); kind="INCLUDE"; }
            if (m.matches()) {
                String t=target(m.group(1));
                // Compound or conditional statements need clarification, never partial acceptance.
                if (!t.isBlank() && !t.matches(".*(?:하지만|대신|말고|않|제외|그리고|추천|방문|함께라|위주|때문|포함|같은|이외|외에|대신|라면|으면|경우|비오면|많이|적게|저렴|가까운).*")) {
                    rules.add(new Rule(source.trim(),kind,t,0,daily)); continue;
                }
            }
            if (body.endsWith("위주로") && CATEGORIES.containsKey(body.substring(0,body.length()-3))) {
                rules.add(new Rule(source.trim(),"INCLUDE",body.substring(0,body.length()-3),0,true)); continue;
            }
            unknown.add(source.trim());
        }
        if (rules.isEmpty() && unknown.isEmpty()) unknown.add(request.trim());
        return new Plan(rules,unknown);
    }
    public static int visitStart(String request, int fallback) {
        return parse(request).rules().stream().filter(r->r.kind().equals("START")).mapToInt(Rule::value).max().stream().map(v->Math.max(v,fallback)).findFirst().orElse(fallback);
    }
    public static int visitEnd(String request, int fallback) {
        return parse(request).rules().stream().filter(r->r.kind().equals("END")).mapToInt(Rule::value).min().stream().map(v->Math.min(v,fallback)).findFirst().orElse(fallback);
    }
    public static int visitLimit(String request, int fallback) {
        return parse(request).rules().stream().filter(r->r.kind().equals("MAX")).mapToInt(Rule::value).min().stream().map(v->Math.min(v,fallback)).findFirst().orElse(fallback);
    }
    public static boolean visitAllowed(String request, int minute) {
        return minute >= visitStart(request,0) && minute+60 <= visitEnd(request,1440);
    }
    public static int requiredPriority(String request,String name,String category) {
        return parse(request).rules().stream().anyMatch(r->Set.of("INCLUDE","EXACT").contains(r.kind()) && matches(r,name,category)) ? 10000 : 0;
    }
    public static void validateInput(String request,int days) {
        validateInput(request);
        for(Rule r:parse(request).rules()) if(r.day()>days)
            throw ApiException.badRequest("추가 요청의 "+r.day()+"일차가 여행 기간("+days+"일)을 벗어납니다. 날짜를 확인해 주세요.");
    }
    public static void validateInput(String request) {
        Plan p=parse(request);
        if (!p.unknown().isEmpty() && request!=null && request.startsWith(STRUCTURED)) throw ApiException.badRequest("추가 요청 중 현재 데이터로 확인할 수 없는 조건이 있어요: "+String.join(" / ",p.unknown()));
        if (!p.unknown().isEmpty()) throw ApiException.badRequest("아직 자동으로 해석하거나 확인할 수 없는 요청이에요: "+String.join(" / ",p.unknown())+". 예: 다이소 제외; 해운대해수욕장 꼭 포함; 매일 10시 이후 시작; 하루 최대 3곳 이하");
        for (Rule a:p.rules()) for (Rule b:p.rules()) if (a.kind().equals("EXCLUDE") && Set.of("INCLUDE","EXACT").contains(b.kind())
            && (key(a.target()).equals(key(b.target())) || key(b.target()).contains(key(a.target()))))
            throw ApiException.badRequest("추가 요청에서 같은 장소를 포함하면서 제외할 수는 없어요: "+b.target());
    }
    private static boolean matches(Rule r,String name,String category) {
        if(r.category()!=null && !r.category().isBlank() && !r.category().equals(category)) return false;
        String categoryTarget=CATEGORIES.get(r.target());
        if (categoryTarget!=null) return categoryTarget.equals(category);
        String n=key(name),t=key(r.target());
        return n.contains(t) || r.aliases()!=null && r.aliases().stream().anyMatch(a->n.contains(key(a))) || (t.equals("다이소") && n.contains("daiso")) || (t.equals("daiso") && n.contains("다이소"));
    }
    /** Used on actual map results before primary/alternative/fallback candidates are selected. */
    static boolean allows(String request,String name,String category) {
        return parse(request).rules().stream().filter(r->r.kind().equals("EXCLUDE")).noneMatch(r->matches(r,name,category));
    }
    public static List<Check> checks(TripDraft draft) {
        Plan p=parse(draft.effectiveRequest()); List<Check> result=new ArrayList<>();
        for (Rule r:p.rules()) {
            if(r.kind().equals("MEAL")) { result.add(checkMeal(r,draft)); continue; }
            if(r.kind().equals("MEAL_TIME")) {
                if(draft.days().stream().flatMap(d -> d.places().stream()).anyMatch(DraftMealPlanner::scheduledMeal)) {
                    var missing = draft.days().stream().filter(d -> inScope(r, d.dayNumber(), draft.days().size()))
                            .filter(d -> d.places().stream().noneMatch(v -> DraftMealPlanner.isMeal(v, r.meal())
                                    && time(v.visitTime()) == r.value())).map(d -> d.dayNumber()+"일차").toList();
                    String label = r.meal()+" 시작 시각 "+TravelSchedule.clock(r.value());
                    result.add(new Check(r.source(), missing.isEmpty() ? "APPLIED" : "UNMET",
                            missing.isEmpty() ? label : String.join(", ", missing)+"에 "+label+"을 배치하지 못했어요."));
                }
                continue;
            }
            List<String> failures=new ArrayList<>();
            List<DayDraft> days=draft.days()==null?List.of():draft.days();
            long includeCount=0;
            for (DayDraft day:days) {
                if(r.day()!=0 && day.dayNumber()!=(r.day()==-1?days.size():r.day())) continue;
                List<PlaceDraft> all=day.places()==null?List.of():day.places();
                List<PlaceDraft> visits=all.stream().filter(v->v.fixedEventId()==null).toList();
                long count=visits.stream().filter(v->v.verified() && matches(r,v.name(),v.category())).map(v->Objects.toString(v.placeId(),v.name())).distinct().count();
                includeCount+=count;
                boolean failed=switch(r.kind()) {
                    case "EXCLUDE" -> all.stream().anyMatch(v->matches(r,v.name(),v.category()));
                    case "INCLUDE" -> r.daily() && count<Math.max(1,r.value());
                    case "EXACT" -> r.daily() && count!=r.value();
                    case "MAX" -> visits.size()>r.value();
                    case "START" -> visits.isEmpty() || visits.stream().anyMatch(v->time(v.visitTime())<r.value());
                    case "END" -> visits.isEmpty() || visits.stream().anyMatch(v->time(v.visitTime())<0 || time(v.visitTime())+60>r.value());
                    default -> true;
                };
                if(failed) failures.add(day.dayNumber()+"일차");
            }
            if (days.isEmpty() || (Set.of("INCLUDE","EXACT").contains(r.kind()) && includeCount<Math.max(1,r.value()))) failures.add("필수 장소 "+r.target()+" 최소 "+Math.max(1,r.value())+"곳을 배치하지 못했어요. 검색 후보·동선·방문 가능 시간을 확인해 주세요.");
            if(r.kind().equals("EXACT") && !r.daily() && includeCount>r.value())
                failures.add(r.target()+"는 정확히 "+r.value()+"곳이어야 하지만 "+includeCount+"곳이 배치됐어요. 나머지는 다른 장소로 추천해야 해요.");
            result.add(new Check(r.source(),failures.isEmpty()?"APPLIED":"UNMET",failures.isEmpty()?evidence(r,days):String.join(", ",failures)+": 조건을 충족하지 못했어요."));
        }
        for (String unknown:p.unknown()) result.add(new Check(unknown,"UNVERIFIABLE","현재 기능으로 이 요청을 해석하거나 검증할 수 없어요. 문장이 덜 구체적이어서 발생한 오류는 아닙니다."));
        return List.copyOf(result);
    }
    private static Check checkMeal(Rule rule,TripDraft draft) {
        var days=draft.days()==null?List.<DayDraft>of():draft.days();
        List<Integer> wanted=rule.day()==0?days.stream().map(DayDraft::dayNumber).toList():List.of(rule.day()==-1?days.size():rule.day());
        List<String> evidence=new ArrayList<>(),failures=new ArrayList<>();
        for(int day:wanted) {
            Meal meal=meals(draft.effectiveRequest(),day,days.size()).stream().filter(m->m.source().equals(rule.source())).findFirst().orElse(null);
            var visits=days.stream().filter(d->d.dayNumber()==day).flatMap(d->d.places().stream()).filter(p->p.fixedEventId()==null).toList();
            if(meal!=null && meal.lastRestaurant()) visits=visits.stream().filter(v->"식당".equals(v.category()))
                .max(Comparator.comparingInt(v->time(v.visitTime()))).map(List::of).orElse(List.of());
            var found=meal==null?List.<PlaceDraft>of():visits.stream().filter(p->p.verified() && meal.matchesPlace(p.name(),p.category(),p.categoryPath()) && meal.containsTime(p.visitTime())).toList();
            if(!found.isEmpty()) evidence.add(day+"일차 "+rule.meal()+" · "+found.get(0).visitTime()+" "+found.get(0).name()+
                (meal.exact(found.get(0).name(),found.get(0).categoryPath())?" (카카오맵 장소명·업종 기준)":" (카카오맵 돼지고기구이 업종의 관련 식당 후보 · 삼겹살 메뉴 확인 필요)"));
            else if(meal!=null && meal.lastRestaurant() && !visits.isEmpty())
                failures.add(day+"일차 마지막 식당이 '"+visits.get(0).name()+"'입니다. 마지막 식당을 '"+rule.target()+"' 관련 식당으로 변경해야 해요.");
            else if(meal!=null && Math.min(meal.end(),TravelSchedule.end(day,days.size(),draft.travelDetails()))-Math.max(meal.start(),TravelSchedule.start(day,draft.travelDetails()))<60)
                failures.add(day+"일차 "+rule.meal()+": 도착·귀가 시각 때문에 식사 시간대에 60분을 확보할 수 없어요. 교통편 시각을 확인해 주세요.");
            else failures.add(day+"일차 "+rule.meal()+": 검색한 관련 식당 후보를 현재 동선·시간에 배치하지 못했어요. 검색어: "+String.join(" / ",meal==null?List.of(rule.target()):meal.queries(draft.destination())));
        }
        if(wanted.isEmpty()) failures.add("여행 날짜를 확인할 수 없어요.");
        return new Check(rule.source(),failures.isEmpty()?"APPLIED":"UNMET",failures.isEmpty()?String.join(" / ",evidence)+". 실제 메뉴·영업시간은 방문 전 확인해 주세요.":String.join(" / ",failures)+" 해당 지역에 식당이 없다는 뜻은 아닙니다.");
    }
    private static String evidence(Rule r,List<DayDraft> days) {
        return switch(r.kind()) {
            case "EXCLUDE" -> "제외 조건: "+r.target()+" · 전체 "+days.size()+"일의 장소명·카테고리에서 확인했어요.";
            case "INCLUDE", "EXACT" -> "포함 조건: "+r.target()+(r.kind().equals("EXACT")?" 정확히 ":" 최소 ")+Math.max(1,r.value())+"곳 · 방문 장소: "+days.stream().flatMap(d->d.places().stream()).filter(v->v.verified()&&matches(r,v.name(),v.category())).map(PlaceDraft::name).distinct().reduce((a,b)->a+", "+b).orElse("");
            case "MAX" -> "매일 일반 방문 장소가 "+r.value()+"곳 이하예요. 숙소·교통 고정 항목은 제외해요.";
            case "START" -> "모든 일반 방문이 "+TravelSchedule.clock(r.value())+" 이후에 시작해요.";
            case "END" -> "일반 방문을 60분으로 계산해 "+TravelSchedule.clock(r.value())+"까지 마쳐요.";
            default -> "확인했어요.";
        };
    }
    private static int time(String s) { try { return TravelSchedule.minute(s); } catch(RuntimeException e) { return -1; } }
    static List<String> violations(TripDraft draft) {
        return checks(draft).stream().filter(c->!c.status().equals("APPLIED")).map(c->c.request()+": "+c.detail()).toList();
    }
    static TripDraft require(TripDraft draft) {
        List<String> issues=violations(draft);
        if(!issues.isEmpty()) throw ApiException.badRequest("추가 요청을 충족하지 못한 항목이 있어요. 일정이나 추가 요청을 수정해 주세요. "+String.join(" / ",issues));
        return draft;
    }
}
