# 입력·공동 편집·장소 선택 개선

## 추가 수정: 추천 문구·로그아웃·초안 이동

- 안내와 요청 대상의 ‘횟집’ 표기는 ‘회’로 통일했습니다. 이전 요청의 별칭과 지도에 등록된 실제 상호는 식별을 위해 지원합니다.
- 확정 일정에도 간략한 숙소·교통 요약을 적용했습니다.
- 메인 화면의 ‘아래로 당겨 새로고침’ 문구를 제거했습니다. 새로고침 기능 자체는 유지합니다.
- ‘새 초안으로 다시 계획하기’는 생성된 초안 페이지로 바로 이동합니다. 확정 일정은 보존합니다.
- 추천 문구는 초기 방식으로 복원했습니다. 초안 생성 시 AI가 장소별 방문 매력을 약 40자 한 문장으로 작성하고 저장된 reason을 표시합니다. 주소·시각·앞뒤 장소를 강제로 넣던 별도 생성 API와 화면 로딩 처리는 제거했습니다. 업종별 음식 예문은 복원하지 않습니다.
- 로그아웃은 웹에서도 보이는 공통 확인창을 사용합니다. 취소와 완료 후 이동, 실패 안내를 검증했습니다.
- 일반 화면 글자의 드래그 선택은 막고 입력창·메모·편집 가능한 영역은 선택과 복사·붙여넣기를 허용합니다. 클릭·스크롤·지도 이벤트는 막지 않습니다. 자동 브라우저 확인은 로컬 연결 실패로 수행하지 못했으며 실제 아이폰에서 추가 확인이 필요합니다.

이 추가 수정도 **백엔드 JAR와 웹 파일을 모두 배포**해야 합니다. 추천 문구는 별도 API 없이 초안 생성 결과에 포함됩니다. 아래 기존 배포 명령을 그대로 사용하며 DB 구조 변경은 없습니다.

## 변경 내용

1. 모든 앱 입력창에 공통 예시 색상(#A4ACBA)을 적용했습니다. 실제 입력값의 색은 유지합니다.
2. 웹 입력 글자는 최소 16px로 렌더링하고 버튼의 빠른 연속 터치 확대를 막았습니다. Safari의 입력창 포커스 확대를 피하기 위한 처리이며 사용자의 수동 확대는 유지합니다.
3. 방문 횟수의 특정 음식 대상은 기타에서 입력합니다. 기타를 선택하면 돼지국밥·회·냉면 등 한 종류씩 입력할 수 있습니다. 서버는 실제 장소명·업종에 일치하는 결과만 횟수에 셉니다. 실제 메뉴 제공 여부를 추측하지 않습니다.
4. 방문 날짜·시간 변경의 날짜를 MM/DD로 표시합니다.
5. 편집 권한을 얻는 중에 토큰 없이 조회한 이전 응답이 권한을 덮어쓰지 않도록 했습니다. 화면 전환 때 이전 해제 요청이 끝난 후 새 권한을 얻습니다. 본인의 편집은 다른 편집자 팝업을 표시하지 않습니다. 유형 선택은 즉시 미리보기하고 연속 선택을 모아 최신 선택부터 순서대로 저장하며, 권한 응답의 버전이 같으면 중복 조회를 생략합니다. 다른 사용자의 실제 동시 수정 방지는 유지합니다.
6. 포함·제외 장소를 직접 입력하거나 지도에서 선택할 수 있습니다. 지도 선택은 장소 ID를 보관하여 같은 이름의 다른 지점과 구분합니다. 직접 입력한 브랜드 제외는 전체 지점에 적용합니다.
7. 초안 상단 숙소·교통 요약에서 호텔 주소, 열차번호, 별도 출발/도착 장소 안내를 없앴습니다. 첫날과 마지막 날은 출발→도착 시각을 각각 한 줄로 표시합니다. 실제 고정 일정은 바꾸지 않습니다.
8. 추천 문구가 비었을 때 음식·카페·관광 예시 문장을 만들어 넣던 코드를 제거했습니다. 교체 장소에 기존 장소의 문구·업종·메모를 가져오지 않습니다. 현재는 위 추가 수정에 따라 확인된 장소 정보와 방문 순서로 추천 문구를 새로 생성하며, 실패하면 재시도를 안내합니다.
9. 확정 일정 수정은 화면이 활성화된 후 자동으로 권한을 얻습니다. 교체 버튼은 장소 카드 오른쪽에 표시하고 DAY 아래에는 장소 추가만 둡니다. 지도 제목은 장소 교체/장소 추가로 표시합니다. 해당 일차의 숙소 좌표를 중심으로 확대하며 숙소 좌표가 없으면 해당 일차의 장소 좌표, 지역 순으로 사용합니다. DAY 색은 초안과 동일합니다.

## 주요 파일

- 공통 입력: src/components/app-text-input.tsx, src/global.css, src/app/_layout.tsx 및 기존 입력창 사용 파일
- 방문 조건: src/components/request-preferences-input.tsx, src/lib/request-preferences.ts, src/app/create.tsx, src/app/draft/[id]/edit.tsx
- 편집 상태: services/draft-edit-session.ts, src/hooks/use-draft-edit.ts, src/app/draft/[id]/index.tsx
- 확정 일정: src/app/trip/[id]/edit.tsx, src/components/place-card.tsx, src/components/itinerary-disclosure.tsx, src/components/draggable-places.tsx
- 지도·날짜: src/components/destination-map-picker.tsx, src/lib/lodging-map.ts, src/lib/visit-map-center.ts, src/components/move-visit-dialog.tsx
- 요약·추천: src/components/travel-details-input.tsx, src/lib/place-blurb.ts
- 서버: ExtraRequestRules 및 장소 후보·방문 수를 처리하는 생성/식사/유형/영업시간 보정 클래스

## 배포

**백엔드 JAR를 먼저 교체하고 웹 파일을 배포해야 합니다.** 새 장소 ID와 기타 횟수 조건을 이전 서버는 처리하지 못합니다. DB 구조 변경은 없습니다.

PowerShell에서:

```powershell
cd C:\projects\ai-trip-planner-kakao\backend
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" ".\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar.new
```

Ubuntu SSH에서:

```bash
sudo systemctl stop ai-trip-planner
mv /home/ubuntu/ai-trip-planner/app.jar.new /home/ubuntu/ai-trip-planner/app.jar
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
```

PowerShell에서:

```powershell
cd C:\projects\ai-trip-planner-kakao\app
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu SSH에서:

```bash
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

Expo 개발 앱은 새 코드를 다시 불러오세요. 웹은 새로고침하고, 아이폰 홈 화면 앱은 완전히 종료한 뒤 다시 여세요. 홈 화면 아이콘은 다시 추가하지 않아도 됩니다. APK는 새 빌드·설치가 필요합니다. Nginx 설정 변경은 필요하지 않습니다. 실제 아이폰의 자동 확대와 두 사용자 간 공동 편집은 배포 후 기기에서 확인해야 합니다.

## 로컬 검증

- 서버 전체 테스트 324개 통과 및 JAR 생성 완료
- 앱 lint, TypeScript 검사 통과
- 본인 권한·이전 응답·화면 전환 해제 순서·자동 편집 시작 테스트 통과
- 연속 유형 선택 즉시 미리보기·요청 합치기·버전 순서 저장 테스트 통과
- 기타 방문 횟수·지도 지점 ID 구분·교체 시 업종 및 추천 문구 초기화 테스트 통과
- 입력창 예시 색·웹 글자 크기·숙소별 지도 중심·날짜 표기·간략 요약 테스트 통과
- 장소 정보·삭제 복원·열차 선택 등 기존 회귀 테스트 통과

로컬 빌드는 운영 서버에 자동으로 배포되지 않습니다.
