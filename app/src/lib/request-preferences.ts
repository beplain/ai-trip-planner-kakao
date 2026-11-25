export const REQUEST_PREFIX = 'REQUEST_RULES_V1\n';
export type RequestRule = {
  source: string; kind: string; target: string; value: number; daily: boolean; day: number;
  meal: string; category: string; aliases: string[]; placeId?: string;
};
export const VISIT_TARGETS = ['카페', '쇼핑', '관광', '자연', '문화', '체험', '기타'] as const;
export function readRequest(value?: string): { rules: RequestRule[]; legacy: string } {
  if (!value?.trim()) return { rules: [], legacy: '' };
  if (value.startsWith(REQUEST_PREFIX)) {
    try {
      const plan = JSON.parse(value.slice(REQUEST_PREFIX.length));
      if (Array.isArray(plan.rules) && Array.isArray(plan.unknown) && !plan.unknown.length)
        return { rules: plan.rules.map((r:RequestRule) => r.target==='횟집'&&!r.placeId&&r.meal!=='PLACE' ? {...r,target:'회',source:r.source.replace(/횟집/g,'회')} : r), legacy: '' };
    } catch { /* Keep earlier requests readable until the user explicitly removes them. */ }
    return { rules: [], legacy: '이전 추가 요청의 형식을 확인할 수 없어요.' };
  }
  return { rules: [], legacy: value };
}
export function writeRequest(rules: RequestRule[]): string {
  return rules.length ? REQUEST_PREFIX + JSON.stringify({ rules, unknown: [] }) : '';
}
export function requestSummary(value?: string): string {
  const parsed = readRequest(value);
  return parsed.legacy || parsed.rules.map(r => r.source).join('\n') || '선택한 조건 없음';
}
export function placeRule(kind: 'INCLUDE' | 'EXCLUDE', name: string, placeId?: string, category = ''): RequestRule {
  return { source: `${kind === 'INCLUDE' ? '포함' : '제외'} · ${name.trim()}`, kind, target: name.trim(),
    value: kind === 'INCLUDE' ? 1 : 0, daily: false, day: 0, meal: placeId?'PLACE':'', category: placeId?category:'', aliases: [], ...(placeId?{placeId}:{}) };
}
export function countRule(target: string, scope: number, mode: 'EXACT' | 'INCLUDE' | 'LIMIT', value: number): RequestRule {
  if(target==='횟집')target='회';
  const label = mode === 'EXACT' ? '정확히' : mode === 'INCLUDE' ? '최소' : '최대';
  return { source: `${target} · ${scope === -1 ? '매일' : scope === 0 ? '여행 전체' : `${scope}일차`} · ${label} ${value}곳`,
    kind: mode, target, value, daily: scope === -1, day: scope > 0 ? scope : 0, meal: 'COUNT',
    category: target === '회' ? '식당' : ['카페','쇼핑','관광','자연','문화','체험'].includes(target) ? target : '', aliases: [] };
}
export function addRequestRule(value: string, rule: RequestRule): { value: string; error?: string } {
  const parsed = readRequest(value);
  if (parsed.legacy) return { value, error: '이전 요청을 삭제한 뒤 새 조건을 추가해 주세요.' };
  if (parsed.rules.length >= 20) return { value, error: '조건은 최대 20개까지 추가할 수 있어요.' };
  if (parsed.rules.some(r => r.target === rule.target && r.placeId === rule.placeId && r.kind === rule.kind && r.daily === rule.daily && r.day === rule.day))
    return { value, error: '이미 추가한 조건이에요. 기존 조건을 삭제한 뒤 변경해 주세요.' };
  const rules = [...parsed.rules, rule];
  const key = (s: string) => s.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  if (rules.some(a => a.kind === 'EXCLUDE' && rules.some(b => ['INCLUDE', 'EXACT'].includes(b.kind) && (a.placeId && b.placeId ? a.placeId===b.placeId : key(b.target).includes(key(a.target))))))
    return { value, error: '같은 대상을 포함하면서 제외할 수는 없어요.' };
  for (const a of rules) for (const b of rules) {
    if (a.target !== b.target || a.daily !== b.daily || a.day !== b.day) continue;
    const lower = a.kind === 'EXACT' || a.kind === 'INCLUDE' ? a.value : 0;
    const upper = b.kind === 'EXACT' || b.kind === 'LIMIT' ? b.value : Infinity;
    if (lower > upper) return { value, error: `${a.target}의 최소·최대 방문 횟수가 서로 맞지 않아요.` };
  }
  return { value: writeRequest(rules) };
}
