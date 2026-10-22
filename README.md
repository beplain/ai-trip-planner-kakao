# AI 여행 플래너 (국내 여행 · Android · Expo + Spring Boot · 카카오)

> AI가 국내 여행 일정을 제안하고, 사용자가 수정·저장한 뒤 카카오맵에서 확인하고 동행자와 공유하는 앱

```
project/
├─ backend/   Spring Boot 3.5 · Java 17+ · Spring Security(JWT) · JPA · MySQL/H2
└─ app/       Expo SDK 57 · React Native · TypeScript · Expo Router
```

**카카오 버전의 장점**: 로그인(브라우저 방식)과 지도(WebView) 모두 네이티브 SDK가 필요 없어 **Expo Go에서 전체 기능을 테스트**할 수 있습니다. Google Cloud 결제 계정도 필요 없습니다.

## 1. 기획서 대비 구현 현황

| 기획서 MVP 기능 | 구현 | 비고 |
|---|---|---|
| 로그인 + 세션 유지 | **카카오 로그인** → 서버가 자체 JWT 발급, 앱은 SecureStore 보관 | 브라우저 + 서버 코드 교환 방식 |
| 여행 조건 입력 | `create.tsx` | 목적지 입력/지도 선택·캘린더 기간 선택(1~14일)·예산·스타일·추가 요청 |
| AI 일정 생성 | `OpenAiItineraryClient` | Structured Outputs(JSON Schema) + 검증 + 최대 2회 재시도 |
| 장소 검증 | **카카오 로컬 API(키워드 검색)** | 목적지 중심 60km 밖 결과 제외 → AI 대체 장소 → 실패 시 "지도 미확인" 표시 |
| 날짜별 목록 + 지도 마커 | **카카오맵(WebView + JavaScript SDK)** | 일차별 색상 번호 마커, 방문 순서 점선(실제 경로 아님 명시) |
| 장소 삭제·순서 변경 후 저장 | 초안도 DB에 자동 저장, 확정 후 여행 API로 수정 | 같은 날짜의 장소를 길게 눌러 드래그 |
| 저장 일정 조회 + 공유 | Android 공유 시트 | |
| 동행자 초대·공동 편집 | 초대 링크(7일)·편집/보기 권한·10초 폴링·버전 충돌 409 | 지금 동기화 버튼 지원 |
| 영업시간 | **카카오맵 장소 상세 링크** | ⚠️ 카카오 로컬 API는 영업시간을 주지 않아 앱 안 "지금 영업 중" 표시는 없음 |
| 교통 → 외부 지도 앱 | **카카오맵 앱 길찾기** (`kakaomap://route`, 없으면 웹) | |
| 예약 링크 | 카카오맵 상세(예약 정보가 있는 장소는 거기서 연결) + 전화 | 앱 내 예약·결제 없음 |

**MVP에서 뺀 것(기획서대로)**: GPS 주변 추천, 실시간 동시 편집, 앱 내 예약/결제, 실시간 교통 재배치, 경로 최적화, 사진/댓글, 내비게이션. **해외 여행은 지원하지 않습니다**(카카오맵은 국내 전용).

### 개발 편의 기능
- **개발용 로그인** `POST /api/auth/dev` — 카카오 설정 전에도 이름만으로 로그인(여러 계정으로 초대/공동편집 테스트에 유용). 배포 전 `APP_DEV_LOGIN_ENABLED=false`, 앱 `EXPO_PUBLIC_DEV_LOGIN=false`.
- **샘플 일정 모드** — `OPENAI_API_KEY`가 없으면 샘플 일정 + 경고.
- **장소 검증 생략** — `KAKAO_REST_API_KEY`가 없으면 좌표 없이 진행(지도 표시 안 됨).
- **H2 파일 DB** — 기본 `local` 프로필은 MySQL 없이 실행.

### 기획서와 다른 점
- 초대 토큰은 별도 `TripInvite` 테이블(토큰·권한·만료, 한 링크로 여러 명 참여).
- 순서 변경은 같은 날짜의 장소를 길게 눌러 드래그합니다. 초안과 초대도 DB에 보관합니다. 자세한 내용은 `backend/README-DRAFTS.md`를 참고하세요.
- 폴링은 `GET /api/trips/{id}/changes?since={version}` (시간 대신 버전 비교).
- `User.googleSubject` → `authSubject` (`kakao:<회원번호>` / `dev:<이름>`).

## 2. 빠른 시작 (키 없이 로컬에서)

### 백엔드
```powershell
cd backend
.\mvnw.cmd spring-boot:run   # macOS/Linux: sh mvnw spring-boot:run
curl.exe http://localhost:8080/actuator/health    # {"status":"UP"}
```
JDK 17 이상 필요.

### 앱 (Expo Go)
```powershell
cd app
npm.cmd run setup                       # npm install + expo-secure-store, react-native-webview
Copy-Item .env.example .env.local       # EXPO_PUBLIC_API_BASE_URL 수정
npx.cmd expo start --go --clear
```
휴대폰 Expo Go로 QR 스캔 → **개발용 로그인** → 새 여행 만들기.

## 3. 카카오 설정 (developers.kakao.com)

1. **애플리케이션 추가** → [앱 키]에서 두 키 확인
   - **REST API 키** → 서버 `KAKAO_REST_API_KEY` (로그인 토큰 교환 + 장소 검색)
   - **JavaScript 키** → 앱 `EXPO_PUBLIC_KAKAO_JS_KEY` (지도 표시)
2. **카카오맵 API 사용 설정**: 앱 설정에서 카카오맵(로컬/지도) 사용을 켭니다. 무료 쿼터는 계정당 처음 활성화한 앱에만 제공되니 팀에서 **앱 하나를 공유**하세요. (콘솔 메뉴 이름은 개편에 따라 다를 수 있음)
3. **플랫폼 > Web 사이트 도메인**에 `http://localhost` 등록 — 앱의 WebView 지도가 이 주소로 로드됩니다.
4. **카카오 로그인 활성화 ON** → **Redirect URI** 등록:
   `https://<서버 주소>/api/auth/kakao/callback` (예: `https://abcd.ngrok-free.app/api/auth/kakao/callback`)
   - 서버가 실제로 쓰는 주소와 **한 글자도 다르면 안 됩니다**(KOE006). ngrok 무료 계정의 **고정 도메인**을 쓰면 매번 바꿀 필요가 없습니다.
   - 프록시 뒤에서 주소가 다르게 계산되면 서버에 `APP_PUBLIC_BASE_URL=https://abcd.ngrok-free.app` 지정.
5. **동의항목**: 닉네임(필수). 이메일은 선택이며 추가 심사가 필요할 수 있습니다(없어도 동작).
6. (선택) **보안 > Client Secret** 사용 시 서버 `KAKAO_CLIENT_SECRET`.
7. 앱을 공개하기 전까지는 [팀 관리]에 팀원 카카오 계정을 추가해 두면 테스트가 편합니다.

### 로그인 흐름 (참고)
```
앱 ──브라우저──▶ 서버 /api/auth/kakao/start?redirect=<앱주소>
                 └▶ 카카오 로그인·동의 ─▶ 서버 /api/auth/kakao/callback?code&state
                                           (카카오 토큰 교환·회원 확인, 앱용 JWT 생성)
앱주소?code=<1회용> ◀── 302 ──┘
앱 ─▶ POST /api/auth/kakao/exchange {code} ─▶ {accessToken, user}
```
- 앱 주소: 개발 빌드는 `tripplanner://auth`, Expo Go는 `exp://<PC IP>:8081/--/auth`. 서버는 `KAKAO_ALLOWED_REDIRECTS`(기본 `tripplanner://,exp://`)에 맞는 주소만 허용 → **배포 시 `exp://` 제거**.
- JWT를 딥링크 URL에 직접 싣지 않고 2분짜리 1회용 코드로 교환합니다.
- 휴대폰 브라우저가 서버에 접근해야 하므로 **ngrok https 주소**를 쓰세요. ngrok 무료 도메인은 브라우저 첫 방문 시 경고 페이지가 뜰 수 있는데, "Visit Site"를 한 번 누르면 진행됩니다.

## 4. 서버 환경변수

| 변수 | 설명 | 기본값 |
|---|---|---|
| `APP_JWT_SECRET` | 앱 JWT 서명키(32자 이상 랜덤) | 개발용 값 — **운영에서 반드시 변경** |
| `KAKAO_REST_API_KEY` | 카카오 REST API 키 | 비어 있음 → 카카오 로그인·장소 검증 불가 |
| `KAKAO_CLIENT_SECRET` | Client Secret(사용 시) | 비어 있음 |
| `KAKAO_ALLOWED_REDIRECTS` | 로그인 후 돌아갈 앱 주소 접두사 | `tripplanner://,exp://` |
| `APP_PUBLIC_BASE_URL` | 외부 접근 서버 주소(Redirect URI 계산) | 비어 있음 → 요청 주소로 자동 계산 |
| `OPENAI_API_KEY` / `OPENAI_MODEL` | AI 일정 생성 | 비어 있음 → 샘플 일정 / `gpt-4o-mini` |
| `APP_DEV_LOGIN_ENABLED` | 개발용 로그인 허용 | `true` |
| `SPRING_PROFILES_ACTIVE` | `local`(H2) / `mysql` | `local` |
| `DB_URL` `DB_USERNAME` `DB_PASSWORD` | MySQL 접속 | `localhost:3306/tripplanner` |

```powershell
$env:KAKAO_REST_API_KEY="..."
$env:OPENAI_API_KEY="sk-..."
.\mvnw.cmd spring-boot:run
```
IntelliJ는 Run Configuration → Environment variables. **키를 코드/깃에 올리지 마세요.** MySQL: `docker compose up -d` 후 `$env:SPRING_PROFILES_ACTIVE="mysql"`.

## 5. 실행 순서 (실기기 + 카카오)
1. 백엔드 실행(위 환경변수) → `ngrok http 8080` → https 주소 확인
2. 카카오 콘솔 Redirect URI에 `https://<ngrok 주소>/api/auth/kakao/callback` 등록
3. 앱 `.env.local`:
   ```dotenv
   EXPO_PUBLIC_API_BASE_URL=https://<ngrok 주소>
   EXPO_PUBLIC_KAKAO_JS_KEY=<JavaScript 키>
   ```
4. `npx.cmd expo start --go --clear` → Expo Go → **카카오로 시작하기**
5. 배포용 APK가 필요하면 `npx.cmd eas-cli@latest build -p android --profile preview` (EAS 계정 필요)

## 6. API 요약 (`/api/auth/**` 외에는 `Authorization: Bearer <JWT>`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/auth/kakao/start?redirect=` | 카카오 로그인 시작(브라우저) |
| GET | `/api/auth/kakao/callback` | 카카오 Redirect URI |
| POST | `/api/auth/kakao/exchange` | `{code}` → `{accessToken, user}` |
| POST | `/api/auth/dev` | `{name}` 개발용 로그인 |
| GET | `/api/me` | 내 정보 |
| POST | `/api/trips/generate` | 조건 → 초안(저장 안 함) |
| POST | `/api/trips` | 초안 저장 |
| GET | `/api/trips` · `/api/trips/{id}` | 목록 · 상세 |
| PUT | `/api/trips/{id}` | 여행 정보 수정 + `version` |
| DELETE | `/api/trips/{id}` | 삭제(소유자) |
| PUT | `/api/trips/{id}/days/{dayId}/places/order` | `{placeIds[], version}` |
| PUT | `/api/trips/{id}/places/{placeId}` | `{visitTime, note, version}` |
| DELETE | `/api/trips/{id}/places/{placeId}?version=` | 장소 삭제 |
| GET | `/api/trips/{id}/changes?since={version}` | 폴링 |
| POST | `/api/trips/{id}/invites` | `{role: EDITOR\|VIEWER}` → 링크(소유자) |
| GET · POST | `/api/invites/{token}` · `/join` | 초대 미리보기 · 참여 |

장소 응답 필드: `placeId`(카카오 장소 id), `name`, `address`(도로명 우선), `latitude`, `longitude`, `phone`, `placeUrl`(카카오맵 상세), `verified`, `visitTime`, `reason`, `note`, `category`.
오류 응답: `{"code": "...", "message": "..."}` · 버전 불일치 시 **409 VERSION_CONFLICT**.

## 7. 비용·운영
- 카카오맵 API는 계정당 첫 앱에 무료 쿼터, 초과/추가 앱은 비즈월렛 연결 후 과금. 일정 1회 생성 ≈ 장소+대체 장소 수만큼 검색(3일 ≈ 20~25회). 사용량은 콘솔 [통계 > 쿼터]에서 확인.
- OpenAI 호출은 생성 1회당 1~2번.
- 로그인 중간 상태(state·1회용 코드)는 **서버 메모리**에 보관 → 서버 1대 기준. 여러 대로 늘리면 Redis로 교체.
- 배포 시: `APP_DEV_LOGIN_ENABLED=false`, `KAKAO_ALLOWED_REDIRECTS=tripplanner://`, `APP_JWT_SECRET` 교체, `DDL_AUTO=validate`, 카카오 Redirect URI를 배포 도메인으로 추가.

## 8. 검증 상태 (중요)
네트워크가 차단된 환경에서 작성되어 **실제 빌드·실행은 하지 못했습니다.**
- 백엔드: `javac` 구문 검사 통과. Spring 의존성 컴파일/실행 미검증.
- 앱: 라이브러리 타입을 간이 스텁으로 대체한 TypeScript 검사 통과. `npm run typecheck`·Expo 실행 미검증.
- 카카오 로그인/로컬 API/지도, OpenAI 실제 호출, 실기기 테스트 미수행.

처음 실행할 때 나오는 오류 메시지(서버 로그의 KOE 코드 포함)를 그대로 알려주시면 바로 고치겠습니다.

### 직접 확인할 시나리오
1. 개발용 로그인 → 부산/2일 → 초안 생성 → 장소 삭제·순서 변경 → 저장
2. 카카오 로그인 → 동의 → 앱 복귀 → 앱 재시작 후 로그인 유지
3. 지도 보기 → 일차 전환, 번호 마커 탭 → 길찾기(카카오맵 앱)·상세
4. 다른 계정으로 초대 코드 참여 → 한쪽 수정 → 10초 내 반영
5. 두 기기 동시 수정 → 늦은 쪽에 충돌 안내
6. 보기 권한 계정에서 편집 버튼 숨김, 소유자만 삭제/초대 가능
