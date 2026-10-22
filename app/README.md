# AI 여행 플래너 – 앱 (Expo SDK 57 · 카카오)

전체 설명과 카카오 설정 방법은 상위 폴더의 `README.md`를 보세요.

```bash
npm run setup          # 의존성 + expo-secure-store, react-native-webview 설치
cp .env.example .env.local
npx expo start --go    # Expo Go에서 카카오 로그인·지도 모두 동작
npm run typecheck
```

## 화면 구조 (src/app)
- `login.tsx` – 카카오 로그인 / 개발용 로그인
- `auth.tsx` – 카카오 로그인 후 앱 복귀 주소(tripplanner://auth)
- `index.tsx` – 내 여행 목록, 마이(초대 코드 참여·로그아웃)
- `create.tsx` – 조건 입력 → AI 초안 → 검토·수정 → 저장
- `trip/[id]/index.tsx` – 일정 상세(순서 변경·삭제·메모·공유·10초 폴링)
- `trip/[id]/map.tsx` – 카카오맵(WebView, 일차별 번호 마커·방문 순서 점선)
- `trip/[id]/edit.tsx` – 여행 정보 수정
- `trip/[id]/invite.tsx` – 초대 링크 만들기
- `invite/[token].tsx` – 초대 수락 (딥링크 `tripplanner://invite/<token>`)

## 카카오 관련 코드
- `services/auth.ts` – `signInWithKakao()` (expo-web-browser + 서버 코드 교환)
- `src/lib/kakao-map.ts` – 지도 HTML, 길찾기(`kakaomap://route` → 웹 폴백), 장소 상세 링크
