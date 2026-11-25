# iPhone Safari 웹앱 배포 절차

현재 코드의 웹 출력은 single(SPA)입니다. 로그인은 브라우저 저장소에 저장하고 /auth에서 팝업 복귀를 처리합니다. Safari 실기기 확인과 Ubuntu Nginx 설정 변경은 아직 수행하지 않았습니다.

## 1. Safari 카카오 로그인과 복귀 확인

배포 주소 예시는 https://beplain-trip.duckdns.org 입니다.

- 카카오 Developers에서 사용하는 앱의 웹 플랫폼 사이트 도메인에 https://beplain-trip.duckdns.org 를 추가합니다. PC 개발용 http://localhost:8082 도 유지할 수 있습니다.
- 카카오 로그인 Redirect URI에는 서버 콜백 https://beplain-trip.duckdns.org/api/auth/kakao/callback 이 등록되어 있어야 합니다. 이 주소는 아래의 로그인 후 웹앱 복귀 주소와 다릅니다.
- Ubuntu의 /etc/ai-trip-planner.env 에서 KAKAO_ALLOWED_REDIRECTS의 기존 값 뒤에 https://beplain-trip.duckdns.org/auth 를 쉼표로 추가합니다. 기존 tripplanner:// 및 개발용 주소는 필요하면 유지합니다.

예시 값

```dotenv
KAKAO_ALLOWED_REDIRECTS=tripplanner://,exp://,http://localhost:8082/auth,https://beplain-trip.duckdns.org/auth
APP_DEV_LOGIN_ENABLED=false
```

환경 파일을 수정한 경우 Ubuntu에서 백엔드를 재시작합니다. JAR 재빌드는 필요 없습니다.

```bash
sudo systemctl restart ai-trip-planner
```

Safari에서 HTTPS 배포 주소를 열고 카카오 로그인을 누릅니다. 팝업이 닫힌 뒤 원래 화면에 로그인되어야 하며, 새로고침 후에도 유지되어야 합니다. 홈 화면에 추가한 웹앱에서도 동일하게 확인합니다. 팝업 차단 안내가 있으면 해당 사이트의 로그인 팝업을 허용한 뒤 재시도합니다. 실제 iPhone의 Safari와 홈 화면 실행은 각각 검증해야 합니다.

## 2. 여행 상세 주소 직접 접속과 서버 설정

PC PowerShell에서 웹 파일을 생성합니다. .env.local의 API 주소와 JavaScript 지도 키가 올바른지 확인합니다.

```powershell
cd C:\projects\ai-trip-planner-kakao\app
npx.cmd expo export --platform web
```

Ubuntu에서 업로드 폴더를 먼저 생성합니다.

```bash
mkdir -p /home/ubuntu/ai-trip-planner/web-build
```

PC PowerShell에서 dist 폴더의 내용을 업로드합니다.

```powershell
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" -r ".\dist\*" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/web-build/
```

Ubuntu에서 웹 루트에 복사합니다. 새 파일을 덮어쓰며 기존 백엔드 JAR와 DB를 변경하지 않습니다.

```bash
sudo mkdir -p /var/www/ai-trip-planner
sudo cp -a /home/ubuntu/ai-trip-planner/web-build/. /var/www/ai-trip-planner/
```

현재 활성 Nginx 설정은 sudo nginx -T 로 확인합니다. 실제 HTTPS server 블록에서 기존 location / 를 아래 형태로 교체합니다. 같은 location / 를 중복으로 추가하지 않습니다. SSL 인증서 설정, /api/ 프록시와 /share/invite/ 프록시는 보존해야 합니다.

```nginx
location / {
    root /var/www/ai-trip-planner;
    try_files $uri $uri/ /index.html;
}
```

/api/ 는 기존 백엔드로 전달해야 합니다. API 요청에 index.html을 반환하면 안 됩니다. /share/invite/ 도 서버의 초대 미리보기 페이지이므로 백엔드로 전달해야 합니다. 기존 프록시 설정이 없다면 현재 백엔드 포트와 프록시 헤더를 확인한 후 별도로 구성합니다.

초안 생성은 60초 이상 걸릴 수 있습니다. HTTPS server 블록의 **기존** `/api/` 프록시 안에서 응답 대기시간을 300초로 설정하세요. 중복 location을 추가하지 않고 기존 헤더 설정은 유지합니다.

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

60초 부근에만 실패하고 Nginx 로그에 `upstream timed out`이 있다면 이 설정을 확인하세요. 이는 서버 설정이며 웹 파일이나 JAR를 교체해도 자동 반영되지 않습니다.

설정 파일을 백업한 뒤 변경하고 문법 검사가 성공했을 때만 다시 불러옵니다.

```bash
sudo nginx -t
sudo systemctl reload nginx
```

검증 항목은 /auth, 실제 존재하는 /trip/여행ID, /draft/초안ID 주소의 직접 접속과 새로고침입니다. 로그아웃 상태에서는 로그인 화면으로 안내되어야 하며 다른 사용자의 여행이 공개되어서는 안 됩니다. 지도 프레임 /map-frame.html도 실제 HTML로 제공되어야 합니다.

## 3. 홈 화면 아이콘과 앱 이름

현재 app.json의 이름은 AI 여행 플래너이며 favicon은 설정되어 있습니다. 홈 화면 전용 manifest 및 apple-touch-icon 설정은 아직 추가하지 않았습니다.

public 폴더에 icon-192.png, icon-512.png, apple-touch-icon.png(180x180)를 준비합니다. 기존 앱 아이콘을 크기에 맞게 변환해 사용할 수 있습니다.

public/manifest.json 예시

```json
{
  "id": "/",
  "name": "AI 여행 플래너",
  "short_name": "여행 플래너",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "theme_color": "#208AEF",
  "background_color": "#F6F7FB",
  "icons": [
    {"src": "/icon-192.png", "sizes": "192x192", "type": "image/png"},
    {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png"}
  ]
}
```

single 출력에서 HTML 템플릿을 준비합니다.

```powershell
npx.cmd expo customize public/index.html
```

생성된 public/index.html의 head에 추가합니다. 기존 root와 스크립트 주입 구조는 유지합니다.

```html
<link rel="manifest" href="/manifest.json" />
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
<meta name="apple-mobile-web-app-title" content="AI 여행 플래너" />
<meta name="apple-mobile-web-app-capable" content="yes" />
<meta name="theme-color" content="#208AEF" />
```

웹 파일을 다시 export하고 dist 내용을 다시 배포합니다. iPhone Safari에서 공유 → 홈 화면에 추가를 선택하고, 표시되는 이름을 확인합니다. 기존 홈 화면 아이콘에 예전 이름이나 그림이 남아 있으면 기존 아이콘을 지우고 새로 추가합니다. 일정 생성과 지도 등 서버 기능은 인터넷과 실행 중인 백엔드가 필요합니다.

## 참고

- https://docs.expo.dev/guides/publishing-websites/
- https://docs.expo.dev/guides/progressive-web-apps/
- https://docs.expo.dev/versions/v58.0.0/sdk/webbrowser/
- https://nginx.org/en/docs/http/ngx_http_core_module.html#try_files
