package com.tripplanner.ai;

import java.util.*;
import java.util.regex.Pattern;

/** One short Korean sentence about what a place is known for. */
public final class PlaceBlurb {
    private PlaceBlurb() {}

    private static final Map<String, String> DISH = dishMap();
    private static final Map<String, String> INVITE = inviteMap();
    private static final List<String> MENUS = DISH.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    private static final Pattern FISH = Pattern.compile("(?:^|[\\s>])회(?:$|[\\s>])|횟집|회센터");

    private static final List<String> CLOSERS = closerList();
    private static final List<String> PLAIN_RESTAURANT = List.of(
            "한 상 차림이 중심인 식당입니다.",
            "집밥이 차려지는 식당입니다.",
            "국그릇이 상 가운데 놓입니다.",
            "들어오면 밥 냄새가 먼저 납니다.",
            "국과 반찬이 함께 상에 오릅니다.",
            "앉으면 따뜻한 밥이 나옵니다.",
            "밥과 국이 기본으로 놓입니다.",
            "이 집의 상은 집밥으로 채워집니다.");
    private static final List<String> CAFE = List.of(
            "커피와 디저트가 한 상에서 나옵니다.",
            "커피 향이 문 앞에서 먼저 납니다.",
            "디저트를 곁들인 커피를 즐겨 보세요.",
            "커피 한 잔을 오래 두는 자리입니다.",
            "창가에서 커피를 마시게 됩니다.",
            "주문하면 커피가 먼저 따라집니다.",
            "커피 잔 위로 김이 오릅니다.",
            "디저트 접시가 커피 옆에 놓입니다.",
            "앉아서 커피를 마시는 시간이 있습니다.",
            "디저트를 고르는 일이 먼저입니다.",
            "문을 열면 커피 향이 번집니다.",
            "커피 잔을 내려놓기 전에 이야기가 오갑니다.");
    private static final List<String> SIGHT = List.of(
            "그 지역의 풍경이 한눈에 들어옵니다.",
            "걸음을 옮길 때마다 풍경이 바뀝니다.",
            "전망이 열린 자리에 서게 됩니다.",
            "발걸음을 멈추고 보게 되는 풍경입니다.",
            "바람과 풍경이 함께 있는 곳입니다.",
            "이곳에 서면 여행지가 이해됩니다.",
            "천천히 한 바퀴를 돌게 됩니다.",
            "안내보다 풍경이 먼저 보입니다.",
            "사진보다 풍경을 먼저 보게 됩니다.",
            "주변의 결이 남아 있는 관광지입니다.",
            "그 지역의 결을 따라 길이 이어집니다.",
            "멀리 산과 마을이 겹쳐 보입니다.");
    private static final List<String> BEACH = List.of(
            "모래사장과 바다가 이어지는 해변입니다.",
            "파도 소리가 먼저 들립니다.",
            "모래를 밟으며 바다가 보입니다.",
            "수평선이 길게 이어집니다.",
            "바닷바람이 부는 곳에 서게 됩니다.",
            "물가가 발 앞에 다가와 있습니다.",
            "바다를 마주 보고 걷게 됩니다.",
            "해안을 따라 풍경이 열립니다.");
    private static final List<String> NIGHT_BEACH = List.of(
            "밤바다 풍경이 이어지는 해변입니다.",
            "불빛이 물에 길게 비칩니다.",
            "어두운 모래사장 너머로 파도가 들립니다.",
            "해안 불빛 아래 걷게 됩니다.",
            "밤 파도가 발 앞에서 부서집니다.");
    private static final List<String> DAY_PARK = List.of(
            "산책로와 녹지가 있는 공원입니다.",
            "나무 그늘이 공원 길을 따라 이어집니다.",
            "벤치에 앉아 공원의 녹지를 봅니다.",
            "공원을 한 바퀴 돌게 됩니다.",
            "잔디와 길이 공원 안에 나란히 있습니다.");
    private static final List<String> NIGHT_PARK = List.of(
            "밤 풍경이 이어지는 공원입니다.",
            "어두워지면 공원의 윤곽이 남습니다.",
            "불빛 아래 공원의 산책로가 이어집니다.",
            "밤에 걸으면 공원의 나무가 실루엣으로 보입니다.",
            "해가 진 뒤의 공원이 열립니다.");
    private static final List<String> NATURE = List.of(
            "숲과 풍경이 이어지는 자연 명소입니다.",
            "녹음이 우거진 길을 걷게 됩니다.",
            "바람 소리가 먼저 들리는 곳입니다.",
            "하늘이 넓게 열린 자리입니다.",
            "풀숲을 따라 길이 이어집니다.",
            "멀리 산이 겹쳐 보입니다.");

    public static String line(String category, String evidence, List<String> styles) {
        return line(category, evidence, styles, Set.of());
    }

    public static String line(String category, String evidence, List<String> styles, Set<String> used) {
        String text = evidence == null ? "" : evidence;
        boolean night = hasStyle(styles, "야경") || text.contains("야경");
        boolean heal = hasStyle(styles, "힐링") || text.contains("힐링") || text.contains("휴식");
        String cat = category == null ? "" : category.trim();
        String dish = findDish(text);
        if (("식당".equals(cat) || cat.isEmpty()) && dish != null && ("식당".equals(cat) || text.contains("식사") || text.contains("메뉴")))
            return pick(restaurantShapes(dish), shapeKey(cat.isEmpty() ? "식당" : cat, text, dish), used);
        return switch (cat) {
            case "식당" -> pick(PLAIN_RESTAURANT, shapeKey("식당", text, null), used);
            case "카페" -> cafeLine(text, heal, used);
            case "쇼핑" -> shopping(text);
            case "자연" -> nature(text, night, heal, used);
            case "문화" -> culture(text);
            case "체험" -> activity(text);
            case "관광" -> sight(text, night, heal, used);
            default -> "여행지 안에서 들르는 장소입니다.";
        };
    }

    public static boolean stockReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String compact = reason.replace(" ", "").replaceAll("\\.+$", "");
        return compact.contains("나는집") || compact.endsWith("유명합니다") || compact.endsWith("즐겨보세요");
    }

    public static String endingKey(String line) {
        if (line == null || line.isBlank()) return "";
        String compact = line.replace(" ", "").replaceAll("\\.+$", "");
        for (String closer : CLOSERS) if (compact.endsWith(closer)) return closer;
        return compact.length() <= 8 ? compact : compact.substring(compact.length() - 8);
    }

    static String findDish(String evidence) {
        if (evidence == null || evidence.isBlank()) return null;
        String compact = evidence.replace(" ", "").replace("순댓국", "순대국밥").replace("돼지국밥집", "돼지국밥").replace("돈가스", "돈까스").replace("자장면", "짜장면");
        for (String menu : MENUS) if (!menu.equals("회") && compact.contains(menu)) return menu;
        return FISH.matcher(evidence).find() ? "회" : null;
    }

    private static List<String> closerList() {
        List<String> closers = new ArrayList<>(List.of(
                "방식이보입니다", "먼저올라옵니다", "맛보는집입니다", "중심으로냅니다", "그릇이놓입니다",
                "나오는집입니다", "부터나옵니다", "대표로냅니다", "끼를채웁니다", "상에놓입니다", "즐겨보세요", "유명합니다"));
        closers.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(closers);
    }

    private static List<String> restaurantShapes(String menu) {
        String character = DISH.getOrDefault(menu, "정갈하게 내는 " + menu);
        String invite = INVITE.getOrDefault(menu, menu);
        return List.of(
                withIga(menu) + " 나오는 집입니다.",
                withEul(invite) + " 즐겨 보세요.",
                withRo(character) + " 유명합니다.",
                "이 집은 " + withEul(menu) + " 대표로 냅니다.",
                "상 위에 " + withIga(menu) + " 먼저 올라옵니다.",
                "들어서면 " + menu + "부터 나옵니다.",
                "앉자마자 " + withIga(menu) + " 상에 놓입니다.",
                withIga(menu) + " 이 집의 한 끼를 채웁니다.",
                withEul(menu) + " 시키면 이 집의 방식이 보입니다.",
                "자리마다 " + menu + " 한 그릇이 놓입니다.",
                withEul(character) + " 그대로 맛보는 집입니다.",
                "주방에서는 " + withEul(menu) + " 중심으로 냅니다.");
    }

    private static String pick(List<String> shapes, String key, Set<String> used) {
        int start = stableIndex(key == null ? "" : key, shapes.size());
        if (used != null) {
            for (int i = 0; i < shapes.size(); i++) {
                String line = shapes.get(Math.floorMod(start + i, shapes.size()));
                if (!used.contains(endingKey(line))) return line;
            }
        }
        return shapes.get(start);
    }

    private static int jongseong(String phrase) {
        char last = phrase.charAt(phrase.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return 0;
        return (last - 0xAC00) % 28;
    }

    private static String withRo(String phrase) {
        int jong = jongseong(phrase);
        return phrase + (jong == 0 || jong == 8 ? "로" : "으로");
    }

    private static String withEul(String phrase) {
        return phrase + (jongseong(phrase) == 0 ? "를" : "을");
    }

    private static String withIga(String phrase) {
        return phrase + (jongseong(phrase) == 0 ? "가" : "이");
    }

    private static String shapeKey(String category, String evidence, String menu) {
        String stripped = evidence == null ? "" : evidence
                .replaceAll("식사 일정\\s*·\\s*(?:아침|점심|저녁)\\s*·\\s*\\S+", " ")
                .replaceAll("(?:아침|점심|저녁)\\s*식사로\\s*.*?메뉴를\\s*즐겨\\s*보세요\\.?", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return category + "|" + (menu == null ? "" : menu) + "|" + stripped;
    }

    private static int stableIndex(String key, int count) {
        int hash = 0;
        for (int i = 0; i < key.length(); i++) hash = hash * 31 + key.charAt(i);
        return Math.floorMod(hash, count);
    }

    private static String cafeLine(String text, boolean heal, Set<String> used) {
        String quiet = "조용한 분위기에서 커피를 내는 카페입니다.";
        if (heal && (used == null || !used.contains(endingKey(quiet)))) return quiet;
        return pick(CAFE, shapeKey("카페", text, null), used);
    }

    private static boolean hasStyle(List<String> styles, String style) {
        return styles != null && styles.contains(style);
    }

    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static boolean palace(String text) {
        if (has(text, "고궁", "궁궐")) return true;
        for (String token : text.split("[\\s>·,]+"))
            if (token.length() > 1 && token.endsWith("궁") && !token.contains("궁합")) return true;
        return false;
    }

    private static List<String> landform(String text, boolean night) {
        if (has(text, "해수욕", "해변", "바닷가")) return night ? NIGHT_BEACH : BEACH;
        if (has(text, "폭포")) return List.of("물줄기가 떨어지는 폭포입니다.", "떨어지는 물소리가 먼저 들립니다.", "바위 아래로 물이 쏟아집니다.", "물보라가 발끝에 닿습니다.");
        if (has(text, "호수")) return night
                ? List.of("밤 풍경이 비치는 호수입니다.", "불빛이 호수 위에 내려앉습니다.", "어두운 수면이 길게 열립니다.", "호숫가에서 밤 풍경을 마주합니다.")
                : List.of("잔잔한 수면이 펼쳐진 호수입니다.", "물 위에 하늘이 비칩니다.", "호숫가를 따라 걷게 됩니다.", "수면이 잔잔히 열려 있습니다.");
        if (has(text, "수목원", "식물원")) return List.of("나무와 꽃을 모아 둔 정원입니다.", "꽃길이 정원 안을 이어 줍니다.", "그늘 아래 나무들이 모여 있습니다.", "꽃과 잎이 길을 따라 피어 있습니다.");
        if (has(text, "휴양림", "숲")) return night
                ? List.of("밤 숲 풍경이 이어지는 숲입니다.", "어두운 숲길이 앞으로 이어집니다.", "밤 나무 사이로 길이 남습니다.", "숲의 윤곽이 밤에 드러납니다.")
                : List.of("숲길이 이어지는 숲입니다.", "나무 사이로 길이 나 있습니다.", "숲 그늘을 걸어 들어가게 됩니다.", "나뭇잎 아래 길이 이어집니다.");
        if (has(text, "공원", "산책")) return night ? NIGHT_PARK : DAY_PARK;
        return null;
    }

    private static String shopping(String text) {
        if (has(text, "아울렛", "아웃렛")) return "유명 브랜드를 한자리에서 할인된 가격에 만날 수 있습니다.";
        if (has(text, "전통시장", "재래시장", "시장")) return "지역 먹거리와 생활 물품이 모인 시장입니다.";
        if (has(text, "백화점")) return "여러 브랜드를 한 건물에 모은 백화점입니다.";
        if (has(text, "면세")) return "여러 브랜드의 면세 상품을 한자리에서 만날 수 있습니다.";
        if (has(text, "대형마트", "마트")) return "식품과 생필품을 한곳에서 고르는 대형마트입니다.";
        return stableIndex(shapeKey("쇼핑", text, null), 2) == 0
                ? "여러 브랜드의 상품을 한자리에서 만날 수 있습니다."
                : "고르는 재미가 있는 매장이 모여 있습니다.";
    }

    private static String culture(String text) {
        if (has(text, "박물관")) return "유물과 전시를 모은 박물관입니다.";
        if (has(text, "미술관")) return "작품 전시가 이어지는 미술관입니다.";
        if (palace(text)) return "전각과 마당이 남은 고궁입니다.";
        if (has(text, "유적")) return "옛 자취가 남은 유적입니다.";
        if (has(text, "영화관")) return "영화를 상영하는 영화관입니다.";
        if (has(text, "공연장")) return "공연이 오르는 공연장입니다.";
        if (has(text, "전통시장", "재래시장", "시장")) return "지역 먹거리와 생활 물품이 모인 시장입니다.";
        return stableIndex(shapeKey("문화", text, null), 2) == 0
                ? "전시와 지역의 이야기가 담긴 문화 공간입니다."
                : "지역의 이야기가 조용히 남아 있는 곳입니다.";
    }

    private static String activity(String text) {
        if (has(text, "테마파크", "놀이공원")) return "테마 공간과 놀이기구가 있는 놀이공원입니다.";
        if (has(text, "케이블카")) return "산 위를 잇는 케이블카입니다.";
        if (has(text, "클라이밍")) return "벽을 오르는 클라이밍 공간입니다.";
        return "직접 참여하는 프로그램이 있는 체험 장소입니다.";
    }

    private static String sight(String text, boolean night, boolean heal, Set<String> used) {
        String key = shapeKey("관광", text, null);
        if (palace(text)) return pick(List.of("전각과 마당이 남은 고궁입니다.", "궁궐 마당을 천천히 건너게 됩니다.", "전각 처마 아래 그늘이 있습니다.", "담장 너머로 마당이 열립니다."), key, used);
        List<String> form = landform(text, night);
        if (form != null) return pick(form, key, used);
        if (has(text, "전망대", "타워")) return pick(List.of("주변이 내려다보이는 전망대입니다.", "높은 자리에서 마을이 펼쳐집니다.", "전망대에 서면 지평이 열립니다.", "발 아래로 골목이 내려다보입니다."), key, used);
        if (night) return pick(List.of("밤 풍경이 이어지는 관광지입니다.", "불이 켜진 뒤의 풍경이 남습니다.", "어두운 길에 불빛이 이어집니다.", "해가 진 뒤 풍경의 윤곽이 보입니다."), key, used);
        if (heal) return pick(List.of("조용히 머물며 풍경을 보는 관광지입니다.", "발소리를 낮추게 되는 자리입니다.", "앉아서 풍경을 보는 시간이 있습니다.", "사람 소리가 적은 풍경이 있습니다."), key, used);
        return pick(SIGHT, key, used);
    }

    private static String nature(String text, boolean night, boolean heal, Set<String> used) {
        String key = shapeKey("자연", text, null);
        List<String> form = landform(text, night);
        if (form != null) return pick(form, key, used);
        if (night) return pick(List.of("밤 풍경이 이어지는 자연 명소입니다.", "어두워진 뒤에 능선이 남습니다.", "밤하늘 아래 풍경이 열립니다.", "해가 진 산의 윤곽이 보입니다."), key, used);
        if (heal) return pick(List.of("조용히 걷기 좋은 자연 공간입니다.", "발소리를 낮추게 되는 곳입니다.", "쉬어 앉기 좋은 그늘이 있습니다.", "바람만 들리는 길이 이어집니다."), key, used);
        return pick(NATURE, key, used);
    }

    private static Map<String, String> dishMap() {
        Map<String, String> dishes = new LinkedHashMap<>();
        dishes.put("삼겹살", "두툼하게 구운 삼겹살");
        dishes.put("돼지국밥", "진한 국물의 돼지국밥");
        dishes.put("회", "모둠으로 내는 회");
        dishes.put("밀면", "차갑게 말아내는 밀면");
        dishes.put("비빔밥", "나물을 올린 비빔밥");
        dishes.put("순두부", "정성이 담긴 뚝배기 순두부");
        dishes.put("설렁탕", "푹 곤 사골 설렁탕");
        dishes.put("돈까스", "두툼하게 튀긴 돈까스");
        dishes.put("칼국수", "칼처럼 썬 면의 칼국수");
        dishes.put("순대국밥", "순대를 넣은 국밥");
        dishes.put("곰탕", "맑게 곤 곰탕");
        dishes.put("샌드위치", "겹겹이 쌓은 샌드위치");
        dishes.put("콩나물국밥", "콩나물을 올린 국밥");
        dishes.put("해장국", "진하게 끓인 해장국");
        dishes.put("김밥", "속이 찬 김밥");
        dishes.put("갈비찜", "양념에 잰 갈비찜");
        dishes.put("초밥", "한 점씩 쥐어 내는 초밥");
        dishes.put("냉면", "차갑게 말아 낸 냉면");
        dishes.put("쌀국수", "맑은 육수의 쌀국수");
        dishes.put("햄버거", "패티를 겹친 햄버거");
        dishes.put("백반", "반찬을 곁들인 백반");
        dishes.put("추어탕", "걸쭉하게 끓인 추어탕");
        dishes.put("삼계탕", "닭 한 마리를 통째 고은 삼계탕");
        dishes.put("갈비탕", "갈비를 넣어 곤 갈비탕");
        dishes.put("장어구이", "양념을 바른 장어구이");
        dishes.put("닭갈비", "양념에 볶은 닭갈비");
        dishes.put("닭볶음탕", "매콤하게 조린 닭볶음탕");
        dishes.put("갈치조림", "갈치를 양념에 조린 갈치조림");
        dishes.put("족발", "부드럽게 삶은 족발");
        dishes.put("보쌈", "수육과 김치를 곁들인 보쌈");
        dishes.put("떡볶이", "매콤한 양념의 떡볶이");
        dishes.put("파스타", "소스를 버무린 파스타");
        dishes.put("피자", "도우 위에 구운 피자");
        dishes.put("오겹살", "겹겹이 구운 오겹살");
        dishes.put("감자탕", "등뼈를 곤 감자탕");
        dishes.put("불고기", "달콤하게 양념한 불고기");
        dishes.put("막국수", "메밀면을 말아 낸 막국수");
        dishes.put("만두", "속을 채워 빚은 만두");
        dishes.put("수제비", "반죽을 떠 넣은 수제비");
        dishes.put("짜장면", "춘장을 볶아 비빈 짜장면");
        dishes.put("짬뽕", "해물을 넣은 얼큰한 짬뽕");
        dishes.put("라멘", "진한 육수의 라멘");
        dishes.put("오므라이스", "달걀로 감싼 오므라이스");
        dishes.put("스테이크", "두껍게 구운 스테이크");
        dishes.put("치킨", "바삭하게 튀긴 치킨");
        dishes.put("곱창", "양념에 구운 곱창");
        dishes.put("대창", "두툼하게 구운 대창");
        dishes.put("오리구이", "껍질을 구운 오리구이");
        dishes.put("복국", "맑게 끓인 복국");
        return Map.copyOf(dishes);
    }

    private static Map<String, String> inviteMap() {
        Map<String, String> invites = new LinkedHashMap<>();
        invites.put("삼겹살", "두툼한 삼겹살");
        invites.put("돼지국밥", "진한 돼지국밥");
        invites.put("회", "모둠회");
        invites.put("밀면", "시원한 밀면");
        invites.put("비빔밥", "나물 비빔밥");
        invites.put("순두부", "따끈한 순두부");
        invites.put("설렁탕", "따뜻한 설렁탕");
        invites.put("돈까스", "두툼한 돈까스");
        invites.put("칼국수", "뜨끈한 칼국수");
        invites.put("순대국밥", "진한 순대국밥");
        invites.put("곰탕", "맑은 곰탕");
        invites.put("샌드위치", "속을 채운 샌드위치");
        invites.put("콩나물국밥", "시원한 콩나물국밥");
        invites.put("해장국", "뜨끈한 해장국");
        invites.put("김밥", "속이 찬 김밥");
        invites.put("갈비찜", "양념 갈비찜");
        invites.put("초밥", "한 점 초밥");
        invites.put("냉면", "시원한 냉면");
        invites.put("쌀국수", "맑은 쌀국수");
        invites.put("햄버거", "두툼한 햄버거");
        invites.put("백반", "집밥 같은 백반");
        invites.put("추어탕", "걸쭉한 추어탕");
        invites.put("삼계탕", "뜨끈한 삼계탕");
        invites.put("갈비탕", "깊은 갈비탕");
        invites.put("장어구이", "양념 장어구이");
        invites.put("닭갈비", "양념 닭갈비");
        invites.put("닭볶음탕", "매콤한 닭볶음탕");
        invites.put("갈치조림", "양념 갈치조림");
        invites.put("족발", "부드러운 족발");
        invites.put("보쌈", "보쌈과 김치");
        invites.put("떡볶이", "매콤한 떡볶이");
        invites.put("파스타", "소스 파스타");
        invites.put("피자", "구운 피자");
        invites.put("오겹살", "겹겹이 구운 오겹살");
        invites.put("감자탕", "진한 감자탕");
        invites.put("불고기", "양념 불고기");
        invites.put("막국수", "메밀 막국수");
        invites.put("만두", "빚은 만두");
        invites.put("수제비", "쫄깃한 수제비");
        invites.put("짜장면", "볶은 짜장면");
        invites.put("짬뽕", "얼큰한 짬뽕");
        invites.put("라멘", "진한 라멘");
        invites.put("오므라이스", "달걀 오므라이스");
        invites.put("스테이크", "두툼한 스테이크");
        invites.put("치킨", "바삭한 치킨");
        invites.put("곱창", "양념 곱창");
        invites.put("대창", "두툼한 대창");
        invites.put("오리구이", "구운 오리");
        invites.put("복국", "맑은 복국");
        return Map.copyOf(invites);
    }
}
