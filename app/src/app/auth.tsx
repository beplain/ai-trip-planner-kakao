import { Redirect, router } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';

import { useAuth } from '@/lib/auth-context';
import { C, s } from '@/lib/styles';

// 카카오 로그인 후 앱으로 돌아오는 주소(tripplanner://auth)가 라우터에 잡혔을 때 보이는 화면.
// 실제 토큰 교환은 로그인 화면(signInWithKakao)에서 처리되므로 여기서는 잠시 기다렸다 이동만 한다.
WebBrowser.maybeCompleteAuthSession();

export default function AuthReturnScreen() {
  const { user } = useAuth();
  if (user) return <Redirect href="/" />;
  return (
    <View style={[s.safe, s.center]}>
      <ActivityIndicator color={C.primary} size="large" />
      <Text style={[s.empty, { marginTop: 14 }]}>로그인 처리 중…</Text>
      <Pressable onPress={() => router.replace('/login')}>
        <Text style={[s.linkText, { marginTop: 18 }]}>로그인 화면으로</Text>
      </Pressable>
    </View>
  );
}
