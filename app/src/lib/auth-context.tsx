import { isAxiosError } from 'axios';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { AppState } from 'react-native';

import { setAccessToken, setUnauthorizedHandler } from '../../services/api';
import { fetchMe, signInDev, signInWithKakao, tokenStore } from '../../services/auth';
import { SessionRecovery, type SessionState } from '../../services/session-recovery';

type AuthState = SessionState & {
  loginWithKakao: () => Promise<boolean>;
  loginDev: (name: string) => Promise<void>;
  logout: () => Promise<void>;
  retryRestore: () => Promise<void>;
};
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ user: null, ready: false, restoring: false, sessionError: null });
  const [session] = useState(() => new SessionRecovery({
    store: tokenStore, fetchMe, setToken: setAccessToken,
    isUnauthorized: (error) => isAxiosError(error) && error.response?.status === 401,
    onState: setState,
  }));
  const logout = useCallback(() => session.logout(), [session]);
  const retryRestore = useCallback(() => session.retry(), [session]);
  useEffect(() => {
    setUnauthorizedHandler(() => { void session.logout(); });
    void session.start();
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') void session.resume();
    });
    return () => { subscription.remove(); session.stop(); setUnauthorizedHandler(null); };
  }, [session]);

  const loginWithKakao = useCallback(async () => {
    const version = session.getVersion();
    const result = await signInWithKakao();
    return result ? session.login(result.accessToken, result.user, version) : false;
  }, [session]);
  const loginDev = useCallback(async (name: string) => {
    const version = session.getVersion();
    const result = await signInDev(name);
    await session.login(result.accessToken, result.user, version);
  }, [session]);
  const value = useMemo(() => ({ ...state, loginWithKakao, loginDev, logout, retryRestore }),
    [state, loginWithKakao, loginDev, logout, retryRestore]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('AuthProvider 안에서 사용해 주세요.');
  return ctx;
}
