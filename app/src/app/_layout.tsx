import '@/global.css';
import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { Platform, useColorScheme, useWindowDimensions, View } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { AnimatedSplashOverlay } from '@/components/app-splash';
import { AuthProvider } from '@/lib/auth-context';
import { WEB_APP_WIDTH } from '@/lib/app-layout';

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const colorScheme = useColorScheme();
  const { width } = useWindowDimensions();
  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: '#E9ECF3' }}>
      <View style={[{ flex: 1, minWidth: 0 }, Platform.OS === 'web' && { width: Math.min(width, WEB_APP_WIDTH), maxWidth: '100%', alignSelf: 'center' }]}><SafeAreaProvider>
      <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
        <AuthProvider>
          {/* 각 화면이 자체 헤더(ScreenHeader)를 그리므로 기본 헤더는 숨김 */}
          <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#F6F7FB' } }} />
        </AuthProvider>
        <AnimatedSplashOverlay />
      </ThemeProvider>
    </SafeAreaProvider></View></GestureHandlerRootView>
  );
}
