import { userMessage } from '../../services/user-message';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Pressable, Text, View } from 'react-native';

import { apiError } from '../../services/api';
import { tripsApi, type CalendarResult } from '../../services/trips';
import { useAuth } from '@/lib/auth-context';
import { s } from '@/lib/styles';

export function TalkCalendarConnection() {
  const { connectCalendar, user } = useAuth();
  const [busy, setBusy] = useState(false);
  async function connect() {
    if (busy) return;
    setBusy(true);
    try {
      if (await connectCalendar()) Alert.alert('톡캘린더 연결 완료', '이제 새 여행을 저장하면 내 톡캘린더에도 여행 기간 일정 1개가 등록됩니다.');
    } catch (e) { Alert.alert('톡캘린더 연결 실패', apiError(e)); }
    finally { setBusy(false); }
  }
  return (
    <View style={s.infoBox}>
      <Text style={s.infoTitle}>톡캘린더에도 여행 저장</Text>
      <Text style={s.infoText}>처음 한 번 카카오 계정으로 연결하고 동의하면, 새 여행 저장 시 내 톡캘린더에도 자동 등록해요.</Text>
      <Pressable accessibilityRole="button" disabled={busy} style={[s.outlineButton, busy && s.disabled]} onPress={() => { void connect(); }}><Text style={s.outlineText}>{busy ? '연결 중…' : user?.calendarConnected ? '톡캘린더 연결됨 · 다시 연결' : '톡캘린더 연결'}</Text></Pressable>
    </View>
  );
}

export function TalkCalendarPanel({ tripId, version }: { tripId: number; version: number }) {
  const { connectCalendar } = useAuth();
  const [result, setResult] = useState<CalendarResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    tripsApi.calendarStatus(tripId).then((r) => { if (active) setResult(r); }).catch((e) => { if (active) setError(apiError(e)); });
    return () => { active = false; };
  }, [tripId, version]);
  async function register(reconnect = false) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      if (reconnect && !await connectCalendar()) return;
      setResult(await tripsApi.registerCalendar(tripId));
    } catch (e) { setError(apiError(e)); }
    finally { setBusy(false); }
  }
  const canRegister = result && ['NOT_CONNECTED', 'NOT_REGISTERED', 'FAILED'].includes(result.status);
  return (
    <View style={s.infoBox}>
      <Text style={s.infoTitle}>내 톡캘린더</Text>
      {!result && !error ? <ActivityIndicator /> : <Text accessibilityLiveRegion="polite" style={s.infoText}>{error || userMessage(result?.message)}</Text>}
      {canRegister && <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void register(result.status === 'NOT_CONNECTED'); }} style={[s.outlineButton, busy && s.disabled]}><Text style={s.outlineText}>{busy ? '등록 확인 중…' : result.status === 'NOT_CONNECTED' ? '카카오 연결하고 등록' : '톡캘린더에 등록'}</Text></Pressable>}
      {result?.status === 'FAILED' && <Pressable accessibilityRole="button" disabled={busy} onPress={() => { void register(true); }} style={s.outlineButton}><Text style={s.outlineText}>권한 다시 연결하고 등록</Text></Pressable>}
      {(error || result?.status === 'PENDING' || result?.status === 'UNKNOWN') && <Pressable style={s.outlineButton} disabled={busy} onPress={() => { tripsApi.calendarStatus(tripId).then((r) => { setResult(r); setError(''); }).catch((e) => setError(apiError(e))); }}><Text style={s.outlineText}>상태 다시 확인</Text></Pressable>}
      <Pressable accessibilityRole="button" style={[s.outlineButton, { backgroundColor: '#FEE500', borderColor: '#E0C900', minHeight: 48 }]} onPress={() => { void Linking.openURL('https://calendar.kakao.com').catch(() => Alert.alert('톡캘린더를 열지 못했어요')); }}><Text style={[s.outlineText, { color: '#191919', fontWeight: '700' }]}>톡캘린더 열기 ↗</Text></Pressable>
      <Text style={s.mutedSmall}>처음 저장한 여행 기간을 종일 일정 1개로 등록합니다. 이후 여행 수정·삭제는 톡캘린더에 자동 반영되지 않으니 캘린더에서도 변경해 주세요.</Text>
    </View>
  );
}
