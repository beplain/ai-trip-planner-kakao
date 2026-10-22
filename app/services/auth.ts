import * as Linking from 'expo-linking';
import * as SecureStore from 'expo-secure-store';
import * as WebBrowser from 'expo-web-browser';
import { Platform } from 'react-native';

import { API_BASE_URL, api } from './api';

export type User = { id: number; email?: string; name: string; calendarConnected?: boolean };
type TokenResponse = { accessToken: string; expiresInSeconds: number; user: User };

const TOKEN_KEY = 'tripplanner.accessToken';
let webMemoryToken: string | null = null; // 웹은 SecureStore 미지원 → 메모리에만 보관

export const DEV_LOGIN_ENABLED = process.env.EXPO_PUBLIC_DEV_LOGIN !== 'false';

export const tokenStore = {
  async get() {
    if (Platform.OS === 'web') return webMemoryToken;
    return SecureStore.getItemAsync(TOKEN_KEY);
  },
  async set(token: string) {
    if (Platform.OS === 'web') webMemoryToken = token;
    else await SecureStore.setItemAsync(TOKEN_KEY, token);
  },
  async clear() {
    if (Platform.OS === 'web') webMemoryToken = null;
    else await SecureStore.deleteItemAsync(TOKEN_KEY);
  },
};

/**
 * 카카오 로그인 (브라우저 방식 · 네이티브 SDK 없음 → Expo Go에서도 동작)
 * 1) 서버 /api/auth/kakao/start 를 브라우저로 열기 → 카카오 로그인/동의
 * 2) 서버가 앱 주소(tripplanner://auth 또는 Expo Go의 exp://.../--/auth)로 1회용 code를 붙여 돌려보냄
 * 3) code를 서버에 보내 앱용 JWT로 교환
 * 사용자가 취소하면 null
 */
export async function signInWithKakao(calendar = false): Promise<TokenResponse | null> {
  if (!API_BASE_URL) throw new Error('EXPO_PUBLIC_API_BASE_URL을 .env.local에 설정해 주세요.');
  const redirect = Linking.createURL('auth');
  const startUrl = `${API_BASE_URL}/api/auth/kakao/start?redirect=${encodeURIComponent(redirect)}${calendar ? '&calendar=true' : ''}`;
  const result = await WebBrowser.openAuthSessionAsync(startUrl, redirect);
  if (result.type !== 'success') return null;

  const { queryParams } = Linking.parse(result.url);
  const error = queryParams?.error;
  const code = queryParams?.code;
  if (error === 'access_denied') return null; // 동의 화면에서 취소
  if (error) throw new Error(`카카오 로그인 실패(${String(error)}). 서버 로그를 확인해 주세요.`);
  if (typeof code !== 'string') throw new Error('로그인 코드를 받지 못했습니다.');
  return (await api.post<TokenResponse>('/api/auth/kakao/exchange', { code })).data;
}

/** 개발용 로그인 (서버 APP_DEV_LOGIN_ENABLED=true 필요) */
export async function signInDev(name: string): Promise<TokenResponse> {
  return (await api.post<TokenResponse>('/api/auth/dev', { name })).data;
}

export async function fetchMe(): Promise<User> {
  return (await api.get<User>('/api/me')).data;
}
