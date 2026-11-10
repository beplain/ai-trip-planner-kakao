import { Redirect, usePathname } from 'expo-router';
import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { useAuth } from '@/lib/auth-context';
import { C, s } from '@/lib/styles';

/** 로그인 필요 화면 감싸기. 미로그인 시 로그인 후 원래 화면으로 돌아오도록 next 전달 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready, restoring, sessionError, retryRestore } = useAuth();
  const pathname = usePathname();
  if (!ready) {
    return <View style={[s.safe, s.center]}><ActivityIndicator color={C.primary} size="large" /></View>;
  }
  if (!user && sessionError) {
    return <View style={[s.safe, s.center, { padding: 24 }]}>
      <Text style={s.sectionTitle}>연결을 확인하고 있어요</Text>
      <Text style={[s.mutedSmall, { textAlign: 'center', marginBottom: 16 }]}>{sessionError}</Text>
      <Pressable disabled={restoring} style={[s.primary, restoring && s.disabled]} onPress={() => { void retryRestore(); }}>
        <Text style={s.primaryText}>{restoring ? '연결 중…' : '다시 연결'}</Text>
      </Pressable>
    </View>;
  }
  if (!user) return <Redirect href={{ pathname: '/login', params: { next: pathname } }} />;
  return <>{children}</>;
}
