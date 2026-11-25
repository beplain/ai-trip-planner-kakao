# 최소 방문형 2곳 구성 보완

`2~2곳`은 식당과 고정 일정을 제외한 방문 수가 하루 정확히 2곳이라는 뜻입니다. 새 오류 문구는 `하루 정확히 2곳`으로 표시합니다.

최소 방문형에서 선택 스타일과 식당·카페 순서를 보존하려다 3곳이 남는 경우, 기존 로직은 다른 날의 한 장소를 기존 시각에 교체하는 방식만 시도했습니다. 보완 로직은 다른 날의 일반 방문 두 곳을 식사 경계 시각에 함께 재배치합니다. 식당·고정 일정·추가 요청·스타일·운영시간·이동 여유 검사를 유지하며, AI 재호출 없이 제한된 후보만 검사합니다. 실제 시간 조건이 불가능하면 실패를 그대로 안내합니다.

변경 파일:
- `backend/src/main/java/com/tripplanner/trip/DraftVisitPlanner.java`
- `backend/src/test/java/com/tripplanner/trip/DraftVisitPlannerTests.java`

새 `backend/target/trip-planner-0.1.0.jar`를 서버의 app.jar로 교체하고 ai-trip-planner 서비스를 재시작합니다. 이번 변경은 서버에만 있으므로 웹 재빌드·업로드, Expo·DB·Nginx 재시작은 필요하지 않습니다. 운영 서버 배포는 아직 진행하지 않았습니다.
