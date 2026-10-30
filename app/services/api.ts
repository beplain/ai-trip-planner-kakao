import { userMessage } from './user-message';
import { create, isAxiosError } from 'axios';

/**
 * 백엔드 주소: .env.local 의 EXPO_PUBLIC_API_BASE_URL (예: https://xxxx.ngrok-free.dev)
 * EXPO_PUBLIC_ 값은 앱에 그대로 포함되므로 비밀키를 넣지 말 것.
 */
export const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL?.replace(/\/$/, '');

let accessToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

export const api = create({
  baseURL: API_BASE_URL,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true' },
});

api.interceptors.request.use((config) => {
  if (!config.baseURL) throw new Error('지금은 서비스를 이용하기 어려워요. 잠시 후 다시 시도해 주세요.');
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (error) => {
    // 로그인 API 자체의 401은 로그인 화면에서 처리
    const url: string = error?.config?.url ?? '';
    if (isAxiosError(error) && error.response?.status === 401 && !url.startsWith('/api/auth/')) {
      onUnauthorized?.();
    }
    return Promise.reject(error);
  },
);

export function isConflict(error: unknown) {
  return isAxiosError(error) && error.response?.status === 409;
}

/** 서버의 {code, message} 응답을 사용자용 문장으로 */
export function apiError(error: unknown): string {
  if (isAxiosError(error)) {
    const data = error.response?.data as { message?: string } | string | undefined;
    if (data && typeof data === 'object' && data.message) return userMessage(data.message);
    if (!error.response) {
      return error.code === 'ECONNABORTED'
        ? '처리가 늦어지고 있어요. 잠시 후 목록을 새로고침해 결과를 확인해 주세요.'
        : '연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.';
    }
    if (error.response.status === 401) return '로그인이 만료되었습니다. 다시 로그인해 주세요.';
    if (typeof data === 'string' && data.trim().startsWith('<')) {
      return '지금은 서비스를 이용하기 어려워요. 잠시 후 다시 시도해 주세요.';
    }
    return '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
  return userMessage(error instanceof Error ? error.message : error);
}
