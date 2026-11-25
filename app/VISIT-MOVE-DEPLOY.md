# 방문 날짜·시간 변경

초안과 확정 일정의 편집 화면에서 일반 장소를 펼치면 `방문 날짜·시간 변경` 버튼이 표시됩니다. DAY와 스크롤 시각 선택 후 이동하면 해당 날짜의 시간순 위치에 배치합니다. 기존 길게 눌러 드래그는 두 편집 화면에서 비활성화했습니다.

초안은 이동 시 서버에 반영하며, 실패하면 창과 입력을 유지합니다. 확정 일정은 화면에서 임시 변경하고 마지막 `변경사항 저장`으로 반영합니다. 다른 날로 이동한 장소는 삭제 복원 목록에 추가하지 않습니다. 숙소·교통 고정 일정은 변경 대상에서 제외합니다. 체류시간과 이동 여유의 충돌은 선택 창에서 확인하고, 휴무일·영업시간은 서버 저장 시 다시 검사합니다. 이 이동 여유 검사는 실시간 길찾기 결과가 아닙니다.

## 변경 파일

- `src/components/move-visit-dialog.tsx`: 공통 날짜·시각 선택 창
- `src/lib/move-visit.ts`: 일차 이동·시간순 배치·시간 겹침 검사
- `src/components/place-card.tsx`: 이동 버튼
- `src/components/draggable-places.tsx`: 이동 연결 및 드래그 비활성화 옵션
- `src/app/draft/[id]/index.tsx`: 초안 저장·편집 잠금 처리
- `src/app/trip/[id]/edit.tsx`: 확정 일정의 임시 이동 처리
- `services/trips.ts`: 체류시간 타입
- `backend/src/main/java/com/tripplanner/trip/TripService.java`: 일차를 옮긴 장소의 삭제 이력 제외
- `backend/src/test/java/com/tripplanner/trip/ConfirmedPlanEditTests.java`, `scripts/test-move-visit.cjs`: 일차 이동 회귀 검사

새 JAR와 app/dist를 함께 배포하고 백엔드를 재시작하세요. 명령은 `CONFIRMED-EDIT-DEPLOY.md`의 배포 절차와 같습니다. DB 스키마 변경이나 DB 재시작은 없습니다. 아이폰 홈 화면 웹앱은 완전히 닫고 다시 열고, Expo는 새로고침합니다. APK는 재빌드·설치합니다. 운영 서버에는 아직 배포하지 않았습니다.
