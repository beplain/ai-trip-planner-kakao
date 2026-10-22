import { useLocalSearchParams } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useState } from 'react';
import { Alert, Pressable, ScrollView, Share, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { API_BASE_URL, apiError } from '../../../../services/api';
import { draftsApi } from '../../../../services/drafts';
import { tripsApi, type Invite } from '../../../../services/trips';
import { RequireAuth } from '@/components/require-auth';
import { ScreenHeader } from '@/components/screen-header';
import { ROLE_LABEL } from '@/lib/format';
import { s } from '@/lib/styles';

export default function InviteRoute() {
  return <RequireAuth><InviteScreen /></RequireAuth>;
}

function InviteScreen() {
  const { id, draftId } = useLocalSearchParams<{ id: string; draftId?: string }>();
  const [role, setRole] = useState<'EDITOR' | 'VIEWER'>('EDITOR');
  const [invite, setInvite] = useState<Invite | null>(null);
  const [busy, setBusy] = useState(false);
  const [sharing, setSharing] = useState(false);

  async function create() {
    if (busy) return;
    setBusy(true);
    try {
      setInvite(draftId ? await draftsApi.invite(draftId, role) : await tripsApi.createInvite(Number(id), role));
    } catch (e) {
      Alert.alert('초대 링크 생성 실패', apiError(e));
    } finally {
      setBusy(false);
    }
  }

  async function shareOther() {
    if (!invite) return;
    const message = `여행 일정에 초대합니다! (${ROLE_LABEL[invite.role]})\n\n앱에서 링크 열기: ${invite.link}\n\n링크가 열리지 않으면 앱의 [마이 > 초대 링크로 참여]에 아래 코드를 붙여 넣어 주세요.\n${invite.token}`;
    await Share.share({ message }).catch(() => undefined);
  }

  async function shareKakao() {
    if (!invite || sharing) return;
    const jsKey = process.env.EXPO_PUBLIC_KAKAO_JS_KEY?.trim();
    if (!jsKey || !API_BASE_URL) {
      Alert.alert('카카오톡 공유 설정이 필요해요', '앱의 카카오 JavaScript 키와 서버 주소를 설정해 주세요. 다른 방법으로 공유하기도 사용할 수 있어요.');
      return;
    }
    setSharing(true);
    try {
      const url = `${API_BASE_URL}/share/invite/${encodeURIComponent(invite.token)}?key=${encodeURIComponent(jsKey)}`;
      await WebBrowser.openBrowserAsync(url);
    } catch (e) {
      Alert.alert('카카오톡 공유 실패', apiError(e));
    } finally {
      setSharing(false);
    }
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="동행자 초대" />
      <ScrollView contentContainerStyle={s.content}>
        <Text style={s.eyebrow}>함께 계획하기</Text>
        <Text style={s.title}>동행자를 초대해요</Text>
        <Text style={s.subtitle}>링크를 받은 사람은 로그인 후 이 여행에 참여합니다.</Text>

        <Text style={s.label}>초대 권한</Text>
        <View style={s.chips}>
          {(['EDITOR', 'VIEWER'] as const).map((r) => (
            <Pressable key={r} onPress={() => { setRole(r); setInvite(null); }} style={[s.chip, role === r && s.chipActive]}>
              <Text style={[s.chipText, role === r && s.chipTextActive]}>{r === 'EDITOR' ? '함께 편집' : '보기만'}</Text>
            </Pressable>
          ))}
        </View>

        <View style={s.infoBox}>
          <Text style={s.infoTitle}>공동 편집 안내</Text>
          <Text style={s.infoText}>{draftId ? '초안 수정은 DB에 자동 저장되며 약 10초마다 동행자에게 반영돼요. 지금 동기화 버튼으로 바로 확인할 수도 있어요. 초안은 백엔드를 재시작해도 유지됩니다.' : '편집 내용은 저장 후 다른 사람 화면에 10초 이내로 반영돼요.'} 두 사람이 동시에 고치면 나중에 저장한 쪽에 “새로고침 후 다시 시도” 안내가 표시됩니다. 링크는 7일 후 만료됩니다.</Text>
        </View>

        {invite ? (
          <>
            <View style={s.memberBox}>
              <Text style={s.sectionTitleSmall}>초대 링크 ({ROLE_LABEL[invite.role]})</Text>
              <Text selectable style={s.memberNames}>{invite.link}</Text>
              <Text style={s.mutedSmall}>만료: {new Date(invite.expiresAt).toLocaleString('ko-KR')}</Text>
            </View>
            <Pressable disabled={sharing} style={[s.primary, { marginTop: 16, backgroundColor: '#FEE500' }, sharing && s.disabled]} onPress={shareKakao}>
              <Text style={[s.primaryText, { color: '#191919' }]}>{sharing ? '카카오톡 공유 여는 중…' : '카카오톡으로 링크 공유하기'}</Text>
            </Pressable>
            <Text style={[s.mutedSmall, { marginTop: 8 }]}>공유 페이지에서 카카오톡 버튼을 누른 뒤 친구나 대화방을 선택해 주세요.</Text>
            <Pressable style={s.outlineButton} onPress={shareOther}><Text style={s.outlineText}>다른 방법으로 공유하기</Text></Pressable>
          </>
        ) : (
          <Pressable disabled={busy} style={[s.primary, busy && s.disabled]} onPress={create}>
            <Text style={s.primaryText}>{busy ? '만드는 중…' : '초대 링크 만들기'}</Text>
          </Pressable>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
