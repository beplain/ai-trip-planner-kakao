# 초안 공동 편집 잠금 적용 및 배포

한 초안에서 한 사람만 수정합니다. 다른 동행자는 계속 읽을 수 있고, 편집자의 이름을 표시하며 수정 버튼을 잠급니다. 메모·이동·삭제·복원·여행 정보 수정·확정에도 서버가 편집 권한을 검사합니다. 편집 상태는 약 2초마다 확인하고, 편집자는 15초마다 권한을 갱신합니다. 저장·취소·화면 이동 시 반납하며, 연결이 끊어지면 마지막 갱신 후 60초에 만료됩니다. 기존 버전 충돌 검사도 유지합니다.

## 변경 파일

- 서버: `backend/src/main/java/com/tripplanner/trip/`의 `DraftEditLease.java`, `DraftEditLeaseRepository.java`, `DraftEditLocks.java`, `DraftEditLockController.java`, `DraftDocumentRepository.java`, `DraftRooms.java`, `TrashMaintenance.java`
- CORS: `backend/src/main/java/com/tripplanner/config/SecurityConfig.java`
- DB: `backend/deploy/draft-edit-leases.sql`
- 앱 서비스: `services/draft-edit-token.ts`, `services/draft-edit.ts`, `services/draft-edit-session.ts`, `services/drafts.ts`, `services/recovery.ts`
- 화면: `src/app/draft/[id]/index.tsx`, `src/app/draft/[id]/edit.tsx`
- UI: `src/hooks/use-draft-edit.ts`, `src/components/draft-edit-notice.tsx`, `src/components/draggable-places.tsx`, `src/components/memo-modal.tsx`, `src/components/deleted-places.tsx`
- 검사: `backend/src/test/java/com/tripplanner/trip/DraftEditLocksTests.java`, `scripts/test-draft-edit.cjs`, `scripts/test-delete-confirmation.cjs`, `scripts/test-edit-save.cjs`

## 1. DB 준비

새 테이블 `draft_edit_leases`가 필요합니다. 기존 여행 데이터 변경은 없습니다. 운영 DB에 DBeaver로 연결하여 `backend/deploy/draft-edit-leases.sql` 파일을 실행하세요. MySQL 프로필의 `DDL_AUTO=update`가 적용되는 환경에서는 서버가 자동 생성하지만, 수동 실행해도 `IF NOT EXISTS`로 중복 생성하지 않습니다. DB 재시작은 필요 없습니다.

## 2. 백엔드 업로드

PC PowerShell에서 실행합니다.

```powershell
cd C:\projects\ai-trip-planner-kakao\backend
.\mvnw.cmd package
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" ".\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar.new
```

Ubuntu SSH에서 실행합니다.

```bash
sudo systemctl stop ai-trip-planner
mv /home/ubuntu/ai-trip-planner/app.jar.new /home/ubuntu/ai-trip-planner/app.jar
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
```

## 3. 웹 업로드

PC PowerShell에서 실행합니다. 이미 빌드된 `dist`를 사용한다면 export 명령은 생략할 수 있습니다.

```powershell
cd C:\projects\ai-trip-planner-kakao\app
npx.cmd expo export --platform web
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu SSH에서 실행합니다.

```bash
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

서버와 웹을 함께 배포한 뒤 열려 있는 앱을 완전히 닫고 다시 여세요. 홈 화면 아이콘은 다시 추가하지 않아도 됩니다. Nginx 설정 변경이나 재시작은 필요 없습니다. 개발 중인 Expo는 화면을 새로고침하면 됩니다. APK는 새 코드를 포함하여 다시 빌드·설치해야 합니다. 이전 앱은 편집 권한 요청을 하지 않아 수정이 차단됩니다.

## 4. 동작 확인

서로 다른 두 계정으로 같은 초안을 여세요. A가 메모를 열면 B에게 A의 이름과 수정 중 안내가 나타나고, B는 내용을 읽되 수정할 수 없어야 합니다. A가 취소 또는 저장하면 B의 수정 기능이 다시 활성화되어야 합니다. A의 인터넷을 끊고 마지막 갱신으로부터 60초가 지나면 B가 편집을 시작할 수 있어야 합니다. 이때 A의 오래된 화면으로 덮어쓰는 요청은 서버에서 거절합니다.
