# 추가 요청 선택형 화면 배포

새 여행 만들기와 초안의 여행 정보 수정에서 서술형 입력칸을 제거했습니다.

- 포함할 장소: 이름·지점명을 한 곳씩 추가합니다.
- 제외할 장소·브랜드: 브랜드명은 전체 지점, 지점명은 해당 지점을 제외합니다.
- 방문 횟수: 카페·회·쇼핑·관광·자연·문화·체험 중 대상, 매일/여행 전체/특정 일차, 정확히/최대/최소, 횟수를 선택합니다.
- 접힌 카드에는 추가한 조건만 요약합니다. 삭제 후 다시 추가하여 변경합니다.
- 기존 서술형 요청은 읽기 전용으로 보존합니다. 명시적으로 삭제한 뒤 새 방식으로 바꿉니다.

선택 조건은 기존 request 필드의 REQUEST_RULES_V1 구조로 전달·저장하며 AI 문장 해석을 거치지 않습니다. 서버는 실제 장소 검증과 횟수 검증을 계속 수행합니다. 조건이 서로 충돌하거나 영업시간·숙소·교통편·유형별 방문 수와 함께 충족되지 않으면 조건을 무시하지 않고 실패 이유를 안내합니다. 장소명·업종으로 판단하며 주관적인 분위기나 메뉴 가격을 판정하지 않습니다.

## 변경 파일

- app/src/components/request-preferences-input.tsx
- app/src/lib/request-preferences.ts
- app/src/app/create.tsx
- app/src/app/draft/[id]/edit.tsx
- backend/src/main/java/com/tripplanner/ai/OpenAiItineraryClient.java
- backend/src/main/java/com/tripplanner/trip/ExtraRequestRules.java
- backend/src/main/java/com/tripplanner/trip/TripDtos.java
- app/scripts/test-request-preferences.cjs
- backend/src/test/java/com/tripplanner/trip/SelectedRequestTests.java

## 배포 순서

**백엔드를 먼저 교체한 뒤 웹 파일을 배포합니다.** 이전 서버는 새 LIMIT 조건과 200자를 넘는 선택 조건을 받지 못합니다. DB 구조 변경은 없습니다.

Windows PowerShell:

```powershell
cd C:\projects\ai-trip-planner-kakao\backend
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" ".\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar.new
```

Ubuntu SSH:

```bash
sudo systemctl stop ai-trip-planner
mv /home/ubuntu/ai-trip-planner/app.jar.new /home/ubuntu/ai-trip-planner/app.jar
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
```

Windows PowerShell:

```powershell
cd C:\projects\ai-trip-planner-kakao\app
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu SSH:

```bash
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

Expo 개발 앱은 새 코드를 다시 불러옵니다. 웹은 새로고침하고, 아이폰 홈 화면 앱은 완전히 종료한 뒤 다시 엽니다. 홈 화면 아이콘을 다시 추가할 필요는 없습니다. APK는 다시 빌드·설치해야 합니다. Nginx 설정 변경·재시작은 필요하지 않습니다.

## 확인할 동작

1. 새 여행 마지막 단계에 세 카드만 보이고 서술형 입력칸이 없는지 확인합니다.
2. 포함 정동문화사, 제외 다이소, 카페 매일 최대 1곳, 회 여행 전체 정확히 2곳을 각각 추가·삭제합니다.
3. 기간을 벗어난 일차나 서로 충돌하는 최소·최대 횟수가 거절되는지 확인합니다.
4. 생성한 초안의 여행 정보 수정에서도 조건이 그대로 표시되는지 확인합니다.
5. 예전 여행의 서술형 요청을 열었을 때 보존되고, 삭제 후 새 조건으로 바꿀 수 있는지 확인합니다.

로컬 빌드 결과는 자동으로 운영 서버에 반영되지 않습니다.

## 로컬 검증 결과

- 앱 lint 및 TypeScript 검사 통과
- 선택 조건, 새 여행 생성 재시도, 여행 정보 저장, 요청 반영 표시 테스트 통과
- 서버 전체 테스트 322개 통과, JAR 생성 완료
- Expo 웹 export 완료: app/dist
- 실제 아이폰·운영 서버 배포 후 동작은 별도 확인 필요
