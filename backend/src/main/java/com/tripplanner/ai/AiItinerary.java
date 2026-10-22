package com.tripplanner.ai;

import java.util.List;

/** AI가 반환하는 일정 초안 (JSON Schema로 형식 고정). 좌표/주소는 AI가 만들지 않는다. */
public record AiItinerary(String title, List<AiDay> days) {

    public record AiDay(int dayNumber, List<AiPlace> places, List<AiPlace> alternatives) {}

    /**
     * @param searchQuery 카카오맵 키워드 검색어 (예: "부산 해운대해수욕장")
     * @param visitTime   "HH:mm" 권장 방문 시각 (추정치)
     */
    public record AiPlace(String name, String searchQuery, String visitTime, String category, String reason,
                          String note, List<String> matchedStyles) {
        public AiPlace(String name, String searchQuery, String visitTime, String category, String reason, String note) {
            this(name, searchQuery, visitTime, category, reason, note, List.of());
        }
    }
}
