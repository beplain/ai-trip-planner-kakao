import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { tripsApi, type RequestCheck, type TripDraft } from '../../services/trips';
import { apiError } from '../../services/api';
import { C, s } from '@/lib/styles';

/** Check the displayed option and current edits. A result from another option is never reused. */
export function RequestReflection({ draft }: { draft: TripDraft }) {
  const payload = draft.request?.trim() ? JSON.stringify({ ...draft, options: undefined }) : '';
  const [result, setResult] = useState<{ key: string; checks?: RequestCheck[]; error?: string }>();
  useEffect(() => {
    if (!payload) return;
    let active = true;
    const timer = setTimeout(() => {
      void tripsApi.requestChecks(JSON.parse(payload) as TripDraft).then((checks) => {
        if (active) setResult({ key: payload, checks });
      }).catch((error) => { if (active) setResult({ key: payload, error: apiError(error) }); });
    }, 250);
    return () => { active = false; clearTimeout(timer); };
  }, [payload]);
  if (!payload) return null;
  const current = result?.key === payload ? result : undefined;
  return <View style={s.memberBox}>
    <Text style={s.sectionTitleSmall}>추가 요청 반영 결과</Text>
    {!current && <Text style={s.mutedSmall}>현재 일정에서 반영 여부를 확인하고 있어요…</Text>}
    {current?.error && <Text style={s.warnText}>반영 여부를 확인하지 못했어요. {current.error}</Text>}
    {current?.checks?.map((check, index) => <View key={index} style={{ marginTop: 10, gap: 4 }}>
      <Text style={{ color: check.status === 'APPLIED' ? C.primary : C.danger, fontWeight: '700' }}>
        {check.status === 'APPLIED' ? '✓ 반영 확인' : check.status === 'UNMET' ? '미반영' : '확인 필요'} · {check.request}
      </Text>
      <Text style={s.mutedSmall}>{check.detail}</Text>
    </View>)}
    <Text style={[s.mutedSmall, { marginTop: 10 }]}>현재 장소·시간으로 확인해요. 미반영·확인 필요 항목은 수정한 뒤 일정을 확정할 수 있어요.</Text>
  </View>;
}
