# 휴지통·삭제한 장소 복원 배포

## 동작

- 메인 화면의 **휴지통 · 삭제한 여행 복원**에서 만든 사람만 여행·초안을 복원하거나 영구 삭제합니다.
- 일반 삭제는 30일 보관으로 바뀝니다. 보관 중 동행자·초대 링크·직접 주소로 접근할 수 없습니다.
- 장소 삭제 기록도 DB에 보관합니다. 10초간 표시하는 실행 취소는 최신 삭제부터 되돌립니다. 안내가 사라져도 **삭제한 장소** 목록에서 각각 복원할 수 있습니다.
- 원래 방문 시간과 겹치면 기록을 유지하고 복원 시각을 직접 조정할 수 있습니다. 고정 일정과 운영시간도 검사합니다.
- 초안은 삭제 당시의 유형·여행 조건에서 복원합니다. 선택한 초안을 확정할 때 해당 유형의 삭제 기록도 확정 여행으로 옮깁니다.
- 30일이 지난 기록은 복원할 수 없습니다. 서버 시작 1분 뒤, 이후 6시간마다 만료 자료를 실제 삭제합니다. 보관 기한 이후 정리 작업까지 최대 6시간 정도 DB에 남을 수 있습니다.
- 기능 배포 전에 이미 완전히 삭제한 자료는 이 기능으로 복원할 수 없습니다.

## 1. DB 컬럼 추가

현재 MySQL 프로필의 기본값은 `DDL_AUTO=update`여서 새 JAR 시작 시 컬럼을 추가합니다. 환경에서 `validate`나 `none`을 사용하거나 직접 적용하려면 DBeaver에서 **앱이 사용하는 DB**를 선택한 뒤 아래 파일을 SQL 스크립트 전체 실행합니다.

`C:\projects\ai-trip-planner-kakao\backend\deploy\recovery-columns.sql`

두 테이블 `trips`, `draft_rooms`에 각각 nullable 컬럼 `deleted_at`, `deleted_places`를 추가합니다. 기존 행은 유지됩니다. 스크립트는 이미 있는 컬럼을 건너뛰므로 다시 실행해도 됩니다. DB 재시작은 필요 없습니다.

## 2. 백엔드 교체

PC PowerShell:

```powershell
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" "C:\projects\ai-trip-planner-kakao\backend\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar.new
```

업로드 성공 후 Ubuntu SSH:

```bash
sudo systemctl stop ai-trip-planner
mv /home/ubuntu/ai-trip-planner/app.jar.new /home/ubuntu/ai-trip-planner/app.jar
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
```

## 3. 웹 파일 배포

PC PowerShell:

```powershell
cd C:\projects\ai-trip-planner-kakao\app
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu SSH:

```bash
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

홈 화면 웹앱을 완전히 종료했다가 다시 실행합니다. Expo·Nginx 재시작이나 아이콘 재추가는 필요 없습니다. Android 설치 APK에 적용하려면 APK 재빌드·재설치가 필요합니다.

## 주요 변경 위치

백엔드 `C:\projects\ai-trip-planner-kakao\backend\src\main\java\com\tripplanner\trip\`:

- `RecoveryArchive.java`: 삭제 기록·30일 보관·복원 충돌 검사
- `TrashMaintenance.java`: 만료 자료 정리
- `Trip.java`, `DraftDocument.java`: 보관 시각·삭제 장소 기록 필드
- `TripRepository.java`, `DraftDocumentRepository.java`: 활성 목록·휴지통 조회
- `TripService.java`, `DraftRooms.java`: 삭제·복원·권한·유형 처리
- `TripController.java`, `DraftController.java`: 휴지통과 복원 API
- `InviteService.java`: 보관 중 초대 차단
- `..\invite\InviteShareController.java`: 공유 페이지 접근 차단

프론트엔드 `C:\projects\ai-trip-planner-kakao\app\`:

- `services/recovery.ts`: 복원 API 호출
- `src/app/trash.tsx`: 휴지통 화면
- `src/components/deleted-places.tsx`: 실행 취소·삭제 목록·복원 시각 입력
- `src/components/undo-notice.tsx`: 화면 하단에 떠 있는 실행 취소 안내
- `src/app/index.tsx`: 휴지통 진입
- `src/app/trip/[id]/index.tsx`, `src/app/draft/[id]/index.tsx`: 복원 UI 연결과 삭제 안내 변경

검증:

- `backend/src/test/java/com/tripplanner/trip/RecoveryTests.java`
- `backend/src/test/java/com/tripplanner/trip/DraftRegenerationTests.java` (새 삭제 정책에 맞게 기존 검증 변경)
- `backend/src/test/java/com/tripplanner/invite/InviteShareControllerTests.java` (휴지통 상태의 공유 차단 검증)
- `app/scripts/test-recovery.cjs`
- `app/scripts/test-delete-confirmation.cjs` (기존 삭제 확인과 복원 목록 연결 검증)
