import type { Day, Place, Role, Trip, TripDraft } from '../../services/trips';

export const STYLE_OPTIONS = ['맛집', '힐링', '자연', '쇼핑', '문화', '액티비티', '카페', '야경'];

export const ROLE_LABEL: Record<Role, string> = { OWNER: '소유자', EDITOR: '편집 가능', VIEWER: '보기만' };

/** Numeric budgets use won; preserve older free-text budgets without changing their meaning. */
export function formatBudget(value?: string) {
  const text = value?.trim() ?? '';
  if (!text) return '';
  if (!/^[\d,\s]+원?$/.test(text)) return text;
  const digits = text.replace(/\D/g, '').replace(/^0+(?=\d)/, '');
  return digits ? `${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}원` : text;
}

export function clampDateDay(year: number, month: number, day: number) {
  return Math.max(1, Math.min(day, new Date(year, month, 0).getDate()));
}

const WEEK = ['일', '월', '화', '수', '목', '금', '토'];

/** "2026-10-20" → "10/20(화)" */
export function shortDate(iso?: string) {
  if (!iso) return '';
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return iso;
  const day = new Date(y, m - 1, d).getDay();
  return `${m}/${d}(${WEEK[day]})`;
}

export function dateRange(start?: string, end?: string) {
  if (!start || !isValidDate(start)) return '';
  const [sy, sm, sd] = start.split('-').map(Number);
  if (!end || !isValidDate(end) || start === end) return `${sm}월 ${sd}일`;
  const [ey, em, ed] = end.split('-').map(Number);
  if (sy !== ey) return `${sy}년 ${sm}월 ${sd}일 ~ ${ey}년 ${em}월 ${ed}일`;
  return sm === em ? `${sm}월 ${sd}일 ~ ${ed}일` : `${sm}월 ${sd}일 ~ ${em}월 ${ed}일`;
}

/** Inclusive calendar days, independent of timezone and daylight saving. */
export function tripDayCount(start: string, end: string) {
  if (!isValidDate(start) || !isValidDate(end) || end < start) return 0;
  const [sy, sm, sd] = start.split('-').map(Number);
  const [ey, em, ed] = end.split('-').map(Number);
  return Math.round((Date.UTC(ey, em - 1, ed) - Date.UTC(sy, sm - 1, sd)) / 86400000) + 1;
}

export function tripEndDate(start: string, days: number) {
  if (!isValidDate(start) || !Number.isInteger(days) || days < 1) return '';
  const [y, m, d] = start.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days - 1)).toISOString().slice(0, 10);
}

/** YYYY-MM-DD 형식이고 실제 존재하는 날짜인지 */
export function isValidDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d;
}

export function hasCoords(p: Place): p is Place & { latitude: number; longitude: number } {
  return typeof p.latitude === 'number' && typeof p.longitude === 'number';
}

/** Android 공유 시트용 텍스트 */
export function shareText(trip: Trip | TripDraft) {
  const lines: string[] = [trip.title];
  const meta = [trip.destination, dateRange(trip.startDate, trip.endDate), trip.budget ? `예산 ${formatBudget(trip.budget)}` : '']
    .filter(Boolean).join(' · ');
  if (meta) lines.push(meta);
  trip.days.forEach((d: Day) => {
    lines.push('', `[DAY ${d.dayNumber}${d.date ? ` · ${shortDate(d.date)}` : ''}]`);
    if (d.places.length === 0) lines.push('- (장소 없음)');
    d.places.forEach((p, i) => lines.push(`${i + 1}. ${p.visitTime ? `${p.visitTime} ` : ''}${p.name}${p.address ? ` - ${p.address}` : ''}`));
  });
  lines.push('', '※ 방문 시각은 추정치입니다. 영업시간·예약은 방문 전 확인해 주세요.');
  return lines.join('\n');
}

/** 초대 링크/코드에서 토큰만 추출 (tripplanner://invite/XXXX, 코드 직접 입력 모두 허용) */
export function parseInviteToken(input: string) {
  const v = input.trim();
  const m = v.match(/invite\/([A-Za-z0-9_-]+)/);
  if (m) return m[1];
  return /^[A-Za-z0-9_-]{10,}$/.test(v) ? v : null;
}
