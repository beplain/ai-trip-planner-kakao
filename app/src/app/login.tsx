import { router, useLocalSearchParams, type Href } from 'expo-router';
import { useState } from 'react';
import { Alert, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { apiError } from '../../services/api';
import { ScreenHeader } from '@/components/screen-header';
import { useAuth } from '@/lib/auth-context';
import { s } from '@/lib/styles';

export default function LoginScreen() {
  const { next } = useLocalSearchParams<{ next?: string }>();
  const { loginWithKakao } = useAuth();
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
