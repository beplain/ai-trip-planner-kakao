# 실제 이동 시간으로 일정 보정하기

## 동작

- 새 초안 생성·조건 변경 재생성 때만 실행한다. 기존 저장 일정과 사용자의 수동 편집 순서를 자동 변경하지 않는다.
- 자동차: 카카오모빌리티 `/v1/directions`의 방향별 도로 이동 시간(초)을 분으로 올림한다.
- 대중교통: 카카오맵 `/v2/routing/publictraffic`의 `status=OK` 응답에서 버스·지하철·버스+지하철 경로 중 최소 `properties.totalTime`(초)을 분으로 올림한다. API가 반환하는 도보·환승 포함 총 이동 시간을 사용한다.
- 실제 좌표를 확인한 장소에 대해 숙소 및 교통 고정 일정 사이에서 이동 시간 합이 줄어드는 순서를 탐색한다. 점심·저녁·야경의 시간대를 유지한다.
- 방문은 60분, 이동은 조회된 소요 시간에 10분 여유를 더한다. 기존 최소 이동 여유(자동차 30분/대중교통 45분)도 유지한다. 다음 방문을 뒤로 옮기거나 고정 시각 전까지 방문 불가능한 일반 장소를 제외한다. 제외 안내를 표시하며 스타일 일부가 빠질 수 있다.
- 고정 일정끼리 이동 시간이 부족해도 임의 변경하지 않고 입력 시각 조정을 안내한다.
- 키 미설정·API 오류·호출 제한·좌표 누락 때는 기존 직선거리/이동 여유 기준으로 유지하고 미확인 안내를 표시한다. 실패한 구간을 실제 경로로 확인했다고 표시하지 않는다.
- 최적화는 조회 성공한 방향별 경로만 비교한다. 일부 경로가 없으면 무리한 순서 변경을 하지 않는다. 모든 조합을 조회하는 전역 최적해 탐색은 아니다.
- 응답 대기 상한 약 20초(마지막 HTTP 연결/읽기 시간 추가 가능), 요청당 기본 최대 12회. 기존 일정의 독립적인 경로 조회는 최대 4개를 병렬 처리하며, 동일 방향·좌표 쌍은 초안 생성 요청 안에서 재사용한다. 사용자 간 영구 경로 데이터 캐시는 만들지 않는다.
- 여행 날짜·출발 시각별 운행 예측, 실제 예약·막차·실시간 배차 보장, 지도 경로선 표시는 포함하지 않는다.

## 서버 키 설정

1. 자동차는 기존 `KAKAO_REST_API_KEY`를 기본 사용한다. 해당 앱의 카카오모빌리티 길찾기 이용 설정과 서비스 정보/호출 허용 IP를 확인한다. 별도 키를 사용하면 `KAKAO_MOBILITY_API_KEY`를 설정한다.
2. 대중교통도 기존 `KAKAO_REST_API_KEY`를 기본 사용한다. 카카오맵 대중교통 경로 조회 권한과 호출 허용 IP(EC2 탄력적 IP)를 확인한다. 별도 키를 사용하는 경우에만 `KAKAO_MAP_API_KEY`를 설정한다.
3. EC2 SSH에서 `sudo nano /etc/ai-trip-planner.env`로 아래 항목을 추가한다. 실제 키는 채팅·GitHub·앱의 EXPO_PUBLIC 환경변수에 넣지 않는다.

```ini
ROUTING_MAX_CALLS=12
# 기존 KAKAO_REST_API_KEY가 있으면 추가 키 입력 불필요
# 대중교통에 별도 키를 사용하는 경우만 추가
# KAKAO_MAP_API_KEY=별도_REST_API_키
# 자동차에 별도 키를 사용하는 경우만 추가
# KAKAO_MOBILITY_API_KEY=별도_REST_API_키
```

요청당 기본 12회이며 동일 방향 좌표 쌍은 해당 생성 요청 안에서 재사용한다. 카카오 계정의 서비스별 쿼터와 요금을 확인한다. 코드상 0~80 범위이며 0이면 실제 경로 조회를 끈다. 이전에 설정한 ODSAY_API_KEY는 더 이상 읽거나 사용하지 않으므로 서버 환경 파일에서 제거해도 된다.

## EC2 적용

DB ALTER는 필요 없다. 새 JAR에 환경 설정을 적용해야 한다.

EC2 SSH:

```bash
sudo systemctl stop ai-trip-planner
```

PC PowerShell, backend 폴더:

```powershell
scp -i "$env:USERPROFILE\Downloads\ai-trip-planner-key.pem" ".\target\trip-planner-0.1.0.jar" ubuntu@beplain-trip.duckdns.org:/home/ubuntu/ai-trip-planner/app.jar
```

EC2 SSH:

```bash
sudo systemctl start ai-trip-planner
sudo systemctl status ai-trip-planner --no-pager
curl -fsS https://beplain-trip.duckdns.org/actuator/health
```

시작할 때 새 코드와 `/etc/ai-trip-planner.env`를 읽으므로 JAR 교체와 재시작이 필요하다. 키가 없으면 해당 이동 방식은 기존 기준으로 동작한다.

## 문서 및 검증

- https://developers.kakaomobility.com/guide/navi-api/directions
- https://developers.kakaomobility.com/guide/navi-api/start
- https://developers.kakao.com/docs/ko/kakaomap/rest-api

자동 테스트는 가짜 HTTP 응답만 사용한다. 실제 키 권한·과금·서비스 접근 가능 여부는 서버 키 설정 후 새 초안으로 확인해야 한다.
