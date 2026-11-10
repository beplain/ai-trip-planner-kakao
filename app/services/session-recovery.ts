import type { User } from './auth';

export type SessionState = { user: User | null; ready: boolean; restoring: boolean; sessionError: string | null };
type Dependencies = {
  store: { get(): Promise<string | null>; set(token: string): Promise<void>; clear(): Promise<void> };
  fetchMe(): Promise<User>;
  setToken(token: string | null): void;
  isUnauthorized(error: unknown): boolean;
  onState(state: SessionState): void;
};

/** Serializes credential writes and ignores work started before a logout or a newer login. */
export class SessionRecovery {
  private state: SessionState = { user: null, ready: false, restoring: false, sessionError: null };
  private version = 0;
  private active = false;
  private writes: Promise<void> = Promise.resolve();
  private flight: { version: number; promise: Promise<void> } | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private retries = 0;
  private needsRecovery = false;
  constructor(private readonly deps: Dependencies) {}
  getVersion() { return this.version; }
  start() { this.active = true; return this.restore(); }
  stop() { this.active = false; this.version++; this.cancelRetry(); }
  private publish(patch: Partial<SessionState>) {
    this.state = { ...this.state, ...patch };
    if (this.active) this.deps.onState(this.state);
  }
  private current(version: number) { return this.active && version === this.version; }
  private persist(operation: () => Promise<void>) {
    const result = this.writes.then(operation, operation);
    this.writes = result.catch(() => undefined);
    return result;
  }
  private cancelRetry() { if (this.retryTimer !== null) clearTimeout(this.retryTimer); this.retryTimer = null; }
  private scheduleRetry(version: number) {
    const delays = [3000, 10000, 30000];
    if (!this.current(version) || this.retryTimer !== null || this.retries >= delays.length) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.current(version) && this.needsRecovery) void this.restore();
    }, delays[this.retries++]);
  }
  retry() { this.retries = 0; this.cancelRetry(); return this.restore(); }
  resume() { if (this.needsRecovery) return this.retry(); return Promise.resolve(); }
  async logout() {
    this.version++; this.needsRecovery = false; this.cancelRetry();
    this.deps.setToken(null);
    this.publish({ user: null, ready: true, restoring: false, sessionError: null });
    await this.persist(() => this.deps.store.clear()).catch(() => undefined);
  }
  async login(token: string, user: User, expectedVersion: number) {
    if (!this.current(expectedVersion)) return false;
    const version = ++this.version;
    this.needsRecovery = false; this.cancelRetry();
    // Switch the request credential immediately, so old-request 401s cannot reject the new credential.
    this.deps.setToken(token);
    try { await this.persist(() => this.deps.store.set(token)); }
    catch (error) { if (this.current(version)) this.deps.setToken(null); throw error; }
    if (!this.current(version)) return false;
    this.publish({ user, ready: true, restoring: false, sessionError: null });
    return true;
  }
  restore(): Promise<void> {
    if (!this.active) return Promise.resolve();
    if (this.flight?.version === this.version) return this.flight.promise;
    const version = this.version;
    this.publish({ restoring: true });
    const flight = { version, promise: Promise.resolve() };
    flight.promise = Promise.resolve().then(async () => {
      try {
        // Wait for a preceding logout/login write before reading the stored credential.
        await this.writes;
        if (!this.current(version)) return;
        const token = await this.deps.store.get();
        if (!this.current(version)) return;
        if (!token) { this.needsRecovery = false; this.publish({ user: null, sessionError: null }); return; }
        this.deps.setToken(token);
        const user = await this.deps.fetchMe();
        if (!this.current(version)) return;
        this.needsRecovery = false; this.retries = 0; this.cancelRetry();
        this.publish({ user, sessionError: null });
      } catch (error) {
        if (!this.current(version)) return;
        if (this.deps.isUnauthorized(error)) { await this.logout(); return; }
        this.deps.setToken(null);
        this.needsRecovery = true;
        this.publish({ sessionError: '로그인 정보를 확인하지 못했어요. 서버나 인터넷 연결이 복구되면 다시 연결할게요.' });
        this.scheduleRetry(version);
      } finally {
        if (this.flight === flight) this.flight = null;
        if (this.current(version)) this.publish({ ready: true, restoring: false });
      }
    });
    this.flight = flight;
    return flight.promise;
  }
}
