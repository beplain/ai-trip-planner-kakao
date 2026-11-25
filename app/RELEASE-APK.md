# Android 릴리즈 APK 만들기

PowerShell에서 앱 폴더로 이동합니다.

```powershell
cd C:\projects\ai-trip-planner-kakao\app
npx eas-cli@latest login
npx eas-cli@latest init
```

본인 Expo 계정의 프로젝트를 생성하거나 기존 프로젝트와 연결합니다. 기존 프로젝트가 있다면 같은 프로젝트를 선택합니다. 로그인 정보나 토큰을 채팅에 공유하지 않습니다.

## 지도 키 설정

로컬 .env.local은 클라우드 빌드에 자동 전달되지 않습니다. 카카오 JavaScript 키를 Expo production 환경에 한 번 등록합니다. 아래 값은 .env.local의 EXPO_PUBLIC_KAKAO_JS_KEY 값입니다. REST API 키는 앱에 넣지 않습니다.

```powershell
npx eas-cli@latest env:set --environment production --name EXPO_PUBLIC_KAKAO_JS_KEY --value "카카오 JavaScript 키" --visibility plaintext
```

API 주소와 개발 로그인 비활성화는 eas.json의 release 프로필에 설정돼 있습니다.

## APK 빌드

```powershell
npx eas-cli@latest build --platform android --profile release
```

처음 Android 서명 키를 만들지 묻는다면 새 앱은 Generate new keystore를 선택합니다. 이미 배포한 앱이면 기존 서명 키를 유지해야 업데이트 설치가 가능합니다. 빌드가 끝나면 표시되는 링크에서 APK를 다운로드해 Android 기기에 설치합니다. 이 APK는 Expo Go와 PC의 개발 서버 없이 실행됩니다. 백엔드는 실행 중이어야 합니다. APK는 Android용이며 iPhone에는 설치할 수 없습니다.

## 서버 개발 로그인 API 끄기

Ubuntu SSH에서 서버 환경 파일을 엽니다.

```bash
sudo nano /etc/ai-trip-planner.env
```

기존 APP_DEV_LOGIN_ENABLED 값을 false로 수정하거나, 항목이 없으면 추가합니다.

```dotenv
APP_DEV_LOGIN_ENABLED=false
```

카카오 앱 복귀 주소를 허용하는 KAKAO_ALLOWED_REDIRECTS에는 tripplanner://auth를 포함해야 합니다. 기존 Expo Go 등 다른 허용 주소가 필요하면 유지합니다.

저장 후 서버를 재시작합니다.

```bash
sudo systemctl restart ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
```

JAR를 새로 빌드하거나 DB를 재시작할 필요는 없습니다. 앱은 릴리즈 빌드에서 개발 로그인 버튼과 요청을 차단하며, 개발 실행에서도 명시적으로 EXPO_PUBLIC_DEV_LOGIN=true로 설정해야 활성화됩니다. 이번 로컬 설정은 false입니다. 기존 개발 로그인을 통해 발급된 토큰은 이 설정만으로 폐기되지 않습니다.
