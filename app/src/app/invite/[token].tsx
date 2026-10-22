import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../../services/api';
import { tripsApi, type InvitePreview } from '../../../services/trips';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { ROLE_LABEL } from '@/lib/format';
import { C, s } from '@/lib/styles';

/** 딥링크 tripplanner://invite/{token} 또는 마이 탭의 코드 입력으로 진입 */
export default function JoinInviteRoute() {
  return <RequireAuth><JoinInviteScreen /></RequireAuth>;
}

function JoinInviteScreen() {
  const { token } = useLocalSearchParams<{ token: string }>();
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    tripsApi.invitePreview(String(token)).then(setPreview).catch((e) => setError(apiError(e)));
  }, [token]);

  async function join() {
    if (busy) return;
    setBusy(true);
    try {
      const trip = await tripsApi.joinInvite(String(token));
      if ('draftId' in trip) router.replace(trip.savedTripId ? `/trip/${trip.savedTripId}` : `/draft/${trip.draftId}`);
      else router.replace(`/trip/${trip.id}`);
    } catch (e) {
      Alert.alert('참여 실패', apiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="여행 초대" onBack={() => router.replace('/')} />
      <View style={s.content}>
        {error ? (
          <View style={s.warnBox}><Text style={s.warnTitle}>초대를 확인할 수 없어요</Text><Text style={s.warnText}>{error}</Text></View>
        ) : !preview ? (
          <ActivityIndicator color={C.primary} size="large" style={{ marginTop: 40 }} />
        ) : (
          <>
            <Text style={s.eyebrow}>{preview.invitedBy}님의 초대</Text>
            <Text style={s.title}>{preview.tripTitle}</Text>
            <Text style={s.subtitle}>{preview.destination} · 권한: {ROLE_LABEL[preview.role]}</Text>
            {preview.expired ? (
              <View style={s.warnBox}><Text style={s.warnText}>만료된 초대 링크입니다. 새 링크를 요청해 주세요.</Text></View>
            ) : preview.alreadyMember ? (
              <Pressable style={s.primary} onPress={() => router.replace(preview.draftId ? `/draft/${preview.draftId}` : `/trip/${preview.tripId}`)}>
                <Text style={s.primaryText}>이미 참여 중 · 일정 보기</Text>
              </Pressable>
            ) : (
              <Pressable disabled={busy} style={[s.primary, busy && s.disabled]} onPress={join}>
                <Text style={s.primaryText}>{busy ? '참여 중…' : '여행에 참여하기'}</Text>
              </Pressable>
            )}
          </>
        )}
      </View>
    </SafeAreaView>
  );
}
