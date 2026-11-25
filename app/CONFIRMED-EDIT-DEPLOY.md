# 확정 일정 수정

확정 여행 화면의 `일정 수정`에서 메모·방문 시각·장소 추가/삭제/교체·순서를 변경합니다. 변경사항은 화면 안에서만 유지하다 `변경사항 저장`으로 서버에 한 번에 반영합니다. 취소하거나 편집 화면을 떠나면 저장 전 변경은 반영되지 않습니다. 저장 실패 시 입력을 유지합니다. 숙소·교통 고정 일정과 날짜는 직접 변경할 수 없습니다.

다른 동행자는 편집 중 팝업을 보고 내용을 읽을 수 있으나 동시에 저장할 수 없습니다. 편집 권한은 15초마다 갱신하고 60초에 만료됩니다. 서버에서 권한·버전·고정 일정·방문 시각과 운영시간을 검사합니다. 삭제하거나 교체한 이전 장소는 기존 삭제 복원 목록에 보관합니다.

`새 초안으로 다시 계획하기`는 별도의 초안을 생성하고 여행 정보 수정 화면으로 이동합니다. 조건을 바꿔 재생성·확정해도 원래 확정 여행과 동행자 정보는 보존됩니다. 새 초안의 소유자는 복사를 시작한 사용자이며 기존 여행의 동행자가 자동으로 새 초안에 초대되지는 않습니다.

## 변경 파일

- `app/src/app/trip/[id]/index.tsx`: 수정 버튼·새 초안 복사·공동 편집 안내
- `app/src/app/trip/[id]/edit.tsx`: 저장/취소 편집 화면
- `app/services/trips.ts`: 확정 일정 일괄 저장 API와 복사 출처
- `app/services/draft-edit.ts`: 확정 여행 편집 잠금 API 연결
- `backend/src/main/java/com/tripplanner/trip/TripService.java`: 원자적 저장·삭제 복원 이력·기존 수정 API 잠금 검사
- `backend/src/main/java/com/tripplanner/trip/TripEditLocks.java`, `TripEditController.java`: 확정 여행 잠금 및 저장 API
- `backend/src/main/java/com/tripplanner/trip/TrashMaintenance.java`: 편집 중 이력 정리로 인한 버전 변경 방지
- `backend/src/test/java/com/tripplanner/trip/ConfirmedPlanEditTests.java`, `app/scripts/test-confirmed-editor.cjs`: 회귀 검사

## 배포

이전 공동 편집 기능의 `draft_edit_leases` 테이블을 그대로 사용합니다. 새 테이블이나 컬럼은 추가하지 않습니다. 해당 테이블을 아직 적용하지 않은 서버라면 먼저 `backend/deploy/draft-edit-leases.sql`을 실행합니다.

PC PowerShell:

```powershell
cd C:\projects\ai-trip-planner-kakao\backend
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" ".\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar.new
cd C:\projects\ai-trip-planner-kakao\app
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu SSH:

```bash
sudo systemctl stop ai-trip-planner
mv /home/ubuntu/ai-trip-planner/app.jar.new /home/ubuntu/ai-trip-planner/app.jar
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

웹/아이폰 홈 화면 앱을 완전히 닫고 다시 엽니다. Expo는 새로고침하고, APK는 재빌드·설치합니다. DB·Nginx 재시작은 필요 없습니다. 서버에는 아직 배포하지 않았습니다.
