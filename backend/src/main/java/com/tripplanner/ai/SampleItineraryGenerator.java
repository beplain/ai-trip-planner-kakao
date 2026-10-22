package com.tripplanner.ai;

import com.tripplanner.ai.AiItinerary.AiDay;
import com.tripplanner.ai.AiItinerary.AiPlace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OPENAI_API_KEY가 없을 때 쓰는 샘플 생성기(개발/시연용).
 * 검색어 형태로 만들어 두면 카카오 REST 키가 있을 때 실제 장소가 연결된다.
 */
@Component
public class SampleItineraryGenerator {
    private static final String[][] SLOTS = {
            {"10:00", "관광", "관광명소", "대표 명소에서 하루를 시작해요."},
            {"12:30", "식당", "맛집", "현지 식당에서 점심을 먹어요."},
            {"14:30", "카페", "카페", "잠시 쉬어 가요."},
            {"16:00", "자연", "공원", "가볍게 산책해요."},
            {"18:30", "식당", "저녁 맛집", "저녁 식사를 해요."},
    };
    private static final String[] ALT = {"박물관", "시장"};

    public AiItinerary generate(ItineraryRequest r) {
        List<AiDay> days = new ArrayList<>();
        for (int d = 1; d <= r.days(); d++) {
            List<AiPlace> places = new ArrayList<>();
            for (String[] s : SLOTS) {
                String q = r.destination() + " " + s[2]; // 일차별 중복은 검증 단계에서 다음 검색 결과로 대체
                places.add(new AiPlace(r.destination() + " " + s[2], q, s[0], s[1], s[3],
                        "샘플 일정입니다. AI 키를 설정하면 맞춤 일정이 생성됩니다."));
            }
            List<AiPlace> alts = new ArrayList<>();
            for (String a : ALT) {
                alts.add(new AiPlace(r.destination() + " " + a, r.destination() + " " + a, "15:00", "문화",
                        "대체 장소", "샘플 대체 장소"));
            }
            days.add(new AiDay(d, places, alts));
        }
        return new AiItinerary(r.destination() + " " + r.days() + "일 여행", days);
    }
}
