import { router, useLocalSearchParams, type Href } from 'expo-router';
import { useState } from 'react';
import { Alert, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../services/api';
import { DEV_LOGIN_ENABLED } from '../../services/auth';
import { ScreenHeader } from '@/components/screen-header';
import { useAuth } from '@/lib/auth-context';
import { s } from '@/lib/styles';

export default function LoginScreen() {
  const { next } = useLocalSearchParams<{ next?: string }>();
  const { loginWithKakao, loginDev } = useAuth();
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);

  function goNext() {
    const target = typeof next === 'string' && next.startsWith('/') && next !== '/login' ? next : '/';
    router.replace(target as Href);
  }

  async function run(action: () => Promise<boolean | void>) {
    if (busy) return;
    setBusy(true);
    try {
      const ok = await action();
      if (ok !== false) goNext();
    } catch (e) {
      Alert.alert('로그인 실패', apiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={s.safe}>
      <ScreenHeader title="로그인" back={false} />
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <ViewHero />
        <Pressable disabled={busy} style={[s.primary, { backgroundColor: '#FEE500' }, busy && s.disabled]} onPress={() => run(loginWithKakao)}>
          <Text style={[s.primaryText, { color: 'rgba(0,0,0,0.85)' }]}>{busy ? '로그인 중…' : '💬  카카오로 시작하기'}</Text>
        </Pressable>

        {DEV_LOGIN_ENABLED && (
          <>
            <Text style={s.label}>개발용 로그인 (카카오 설정 전 테스트)</Text>
            <TextInput value={name} onChangeText={setName} placeholder="이름 입력 (예: 팀원A)" style={s.input} maxLength={30} />
            <Pressable
              disabled={busy || !name.trim()}
              style={[s.outlineButton, (busy || !name.trim()) && s.disabled]}
              onPress={() => run(() => loginDev(name.trim()))}>
              <Text style={s.outlineText}>개발용 계정으로 로그인</Text>
            </Pressable>
            <Text style={[s.footerText, { marginTop: 10 }]}>서버의 APP_DEV_LOGIN_ENABLED=true일 때만 동작합니다. 배포 전 꺼 주세요.</Text>
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

function ViewHero() {
  return (
    <View style={s.welcome}>
      <Text style={s.eyebrowLight}>AI 여행 플래너</Text>
      <Text style={s.welcomeTitle}>조건만 알려주면{'\n'}일정을 짜드려요</Text>
      <Text style={s.welcomeSub}>AI가 제안한 일정을 지도에서 확인하고, 동행자와 함께 다듬어 보세요.</Text>
    </View>
  );
}
