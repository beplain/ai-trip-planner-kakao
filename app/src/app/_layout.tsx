import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { useColorScheme } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { AnimatedSplashOverlay } from '@/components/animated-icon';
import { AuthProvider } from '@/lib/auth-context';

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();
  return (
    <GestureHandlerRootView style={{ flex: 1 }}><SafeAreaProvider>
      <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
        <AuthProvider>
          {/* 각 화면이 자체 헤더(ScreenHeader)를 그리므로 기본 헤더는 숨김 */}
          <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F6F7FB' } }} />
        </AuthProvider>
        <AnimatedSplashOverlay />
      </ThemeProvider>
    </SafeAreaProvider></GestureHandlerRootView>
  );
}
