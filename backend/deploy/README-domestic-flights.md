# 국내선 항공 시간표

기존 카카오맵과 여행지 내 자동차·대중교통 이동 방식을 유지합니다.
국토교통부 TAGO 국내항공운항정보(15098526) 활용승인이 필요하며 기존 `TAGO_SERVICE_KEY`를 사용합니다.

## 변경

- 교통편의 항공에서 가는 편·오는 편 공항과 항공편을 각각 선택합니다.
- 출발 희망 시각 이후 항공편을 표시합니다. 결과 없음과 조회 실패를 구분합니다.
- 예약한 시각 직접 선택을 왕복 각각 지원합니다. 직접 선택한 시각은 API 검증 결과로 표시하지 않습니다.
- 국내선 당일 도착편만 지원합니다. 예매·잔여 좌석·실제 판매 운임 조회 기능은 포함하지 않습니다.
- 공항 지도 위치는 카카오 장소 검색으로 확인합니다. 국내선 청사를 우선하고 국제선·주차장은 선택하지 않습니다.
- 생성 시 선택한 날짜·편명·시각·출도착 공항을 시간표와 다시 대조합니다.
- 기존 항공 기본 여유 시간(도착 후 60분, 귀가 출발 전 120분)을 적용합니다. 렌터카 전용 인수·반납 일정은 자동으로 만들지 않습니다.

## API

- GET `/api/trips/transport/airports`
- GET `/api/trips/transport/airports/{id}/location`
- POST `/api/trips/transport/flights`: `{date, originAirportId, arrivalAirportId}`

기존 travelDetails JSON에 flight/returnFlight를 저장하므로 DB 스키마 변경 SQL은 없습니다.
새 백엔드 JAR와 app/dist를 함께 배포해야 합니다. 서버에는 먼저 JAR를 적용하고 웹을 배포합니다.
APK에도 반영하려면 새 APK를 빌드합니다. 운영 서버에는 이 구현 작업에서 자동 배포하지 않습니다.

## 검증

- 백엔드 `FlightTimetableClientTests`: 시간표 파싱, 중복·빈 응답·오류, 국내선 위치, 왕복 재확인, 고정 시각, JSON 저장, 날짜 변경·직접 입력.
- 앱 `node scripts/test-flight-selection.cjs` 및 기존 `node scripts/test-train-selection.cjs`.
- 실서비스 적용 후 로그인 계정으로 항공 왕복 선택 → 초안 생성 → 재접속 → 여행 정보 수정 확인이 필요합니다.
