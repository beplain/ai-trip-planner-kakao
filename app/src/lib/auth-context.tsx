import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { setAccessToken, setUnauthorizedHandler } from '../../services/api';
import { fetchMe, signInDev, signInWithKakao, tokenStore, type User } from '../../services/auth';

type AuthState = {
  user: User | null;
  ready: boolean; // 저장된 토큰 확인이 끝났는지
  loginWithKakao: () => Promise<boolean>;
  connectCalendar: () => Promise<boolean>;
  loginDev: (name: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  const logout = useCallback(async () => {
    setAccessToken(null);
    setUser(null);
    await tokenStore.clear().catch(() => undefined);
  }, []);

  // 앱 시작 시 저장된 JWT로 세션 복원
  useEffect(() => {
    setUnauthorizedHandler(() => { void logout(); });
    (async () => {
      try {
        const token = await tokenStore.get();
        if (token) {
          setAccessToken(token);
          setUser(await fetchMe());
        }
      } catch {
        setAccessToken(null);
        await tokenStore.clear().catch(() => undefined);
      } finally {
        setReady(true);
      }
    })();
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  const applyToken = useCallback(async (token: string, u: User) => {
    setAccessToken(token);
    await tokenStore.set(token);
    setUser(u);
  }, []);

  const loginWithKakao = useCallback(async () => {
    const res = await signInWithKakao();
    if (!res) return false;
    await applyToken(res.accessToken, res.user);
    return true;
  }, [applyToken]);

  const loginDev = useCallback(async (name: string) => {
    const res = await signInDev(name);
    await applyToken(res.accessToken, res.user);
  }, [applyToken]);

  const connectCalendar = useCallback(async () => {
    const result = await signInWithKakao(true);
    if (!result) return false;
    if (!user || result.user.id !== user.id) throw new Error('현재 로그인한 카카오 계정과 같은 계정으로 연결해 주세요. 계정이 다르면 로그아웃 후 다시 로그인해 주세요.');
    if (!result.user.calendarConnected) throw new Error('톡캘린더 권한에 동의하지 않았습니다. 연결 화면에서 일정 생성 권한을 허용해 주세요.');
    await applyToken(result.accessToken, result.user);
    return true;
  }, [user, applyToken]);

  const value = useMemo(() => ({ user, ready, loginWithKakao, connectCalendar, loginDev, logout }),
    [user, ready, loginWithKakao, connectCalendar, loginDev, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('AuthProvider 안에서 사용해 주세요.');
  return ctx;
}
