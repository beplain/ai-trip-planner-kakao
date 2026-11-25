# 조건 미충족 시 초안 생성 보완

- 식사 시간대, 방문 수, 스타일, 유형 간 차이 등 추천 조건을 충족하지 못해도 가능한 초안을 반환합니다.
- 중간 생성 결과를 보관하고, 실패 시 그 안의 확인된 장소를 재사용합니다. 운영시간과 고정 일정에 겹치는 장소는 제외하고 숙소·교통 고정 일정은 유지합니다.
- AI·지도 등 외부 조회가 실패해 장소를 확보하지 못한 경우에도 모든 날짜와 숙소·교통으로 기본 초안을 반환합니다. 장소를 임의로 만들지 않습니다.
- 미충족 사유와 날짜별 실제 방문 수를 초안의 ‘일정 확인 안내’에 표시합니다. 장소가 전혀 없는 경우 확정 후 일정 수정에서 장소를 추가할 수 있습니다.
- 도착·출발 지점 주변 식당도 검색하며 기존 숙소·목적지 검색을 유지합니다.
- 시간 형식 오류, 잘못된 기간, 모순되는 숙소·교통 입력은 입력 단계에서 수정해야 합니다. 인증·DB 저장·서버 연결 자체의 장애는 추천 로직에서 복구할 수 없습니다.

변경 파일:
- backend/src/main/java/com/tripplanner/trip/TripGenerationService.java
- backend/src/main/java/com/tripplanner/trip/BestEffortDrafts.java
- backend/src/main/java/com/tripplanner/trip/DraftMealPlanner.java
- app/src/app/draft/[id]/index.tsx
- app/services/trips.ts

DB 구조 변경은 없습니다. 기존 방식대로 backend/target/trip-planner-0.1.0.jar를 먼저 교체하고 app/dist 내용을 웹에 배포해야 합니다. 서버 배포는 자동으로 수행하지 않았습니다.
