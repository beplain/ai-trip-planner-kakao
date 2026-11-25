const TOKEN_KEY = 'tripplanner.accessToken';

/** Shared by page reloads on this origin. Access is deferred for server rendering. */
export const webTokenStore = {
  async get(): Promise<string | null> {
    if (typeof window === 'undefined') return null;
    return window.localStorage.getItem(TOKEN_KEY);
  },
  async set(token: string): Promise<void> {
    if (typeof window === 'undefined') return;
    window.localStorage.setItem(TOKEN_KEY, token);
  },
  async clear(): Promise<void> {
    if (typeof window === 'undefined') return;
    window.localStorage.removeItem(TOKEN_KEY);
  },
};
