/** Display-only wording: never rewrite stored travel data or user notes. */
export function userMessage(value: unknown, fallback = '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'): string {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  const text = value.trim();
  if (/OPENAI_API_KEY.*(없|샘플)|샘플 일정입니다.*AI 키/.test(text)) return '맞춤 일정 대신 참고용 일정을 준비했어요. 방문 전 내용을 확인해 주세요.';
  if (/경로 조회 키.*설정/.test(text)) return '이동 시간을 확인하지 못해 대략적인 동선으로 구성했어요. 출발 전에 길찾기로 확인해 주세요.';
  if (/DB의 최신/.test(text)) return '최신 일정으로 업데이트했어요.';
  if (/DB에 저장/.test(text)) return '변경 내용을 저장했어요.';
  if (/카카오.*(키|SDK)|EXPO_PUBLIC_KAKAO|KAKAO_REST_API_KEY/.test(text)) return '지도를 이용하기 어려워요. 잠시 후 다시 시도해 주세요.';
  if (/(?:\bDB\b|데이터베이스|스키마|레코드|트랜잭션|낙관|버전|\bversion\b|\bSQL\b|JDBC|Hibernate|MySQL|PostgreSQL|SQLite|travel_db|constraint|stacktrace|SQLState|SQLexception|ECONN|ENOTFOUND|\b(?:SELECT|INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TABLE)\b|JWT|No static resource|java\.|Exception|\.env|EXPO_PUBLIC|APP_[A-Z_]+|[A-Z_]+_API_KEY|API|토큰|SDK|ngrok|localhost|백엔드|환경변수|서버 로그|개발용|HTTP|<html|<!doctype)/i.test(text)) return fallback;
  if (!/[가-힣]/.test(text)) return fallback;
  return text.replace(/서버/g, '서비스');
}
