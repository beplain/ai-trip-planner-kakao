const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const { AxiosError } = require('axios');
const source = ts.transpileModule(fs.readFileSync('services/session-recovery.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText;
const user = { id: 1, name: '사용자' };
const newer = { id: 2, name: '새 사용자' };
function http(status) { return new AxiosError('request failed', undefined, undefined, undefined, { status }); }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
const flush = () => new Promise((resolve) => setImmediate(resolve));
function harness(responses = [user], options = {}) {
  let saved = options.noToken ? null : 'saved-token';
  let token = null, cleared = 0, fetched = 0, written = 0, latest = null, timerId = 0;
  const timers = new Map();
  const exports = {};
  vm.runInNewContext(source, {
    exports,
    setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
    clearTimeout: (id) => timers.delete(id),
  });
  const session = new exports.SessionRecovery({
    store: {
      get: async () => saved,
      set: async (value) => { written++; if (options.writeGate) await options.writeGate.promise; saved = value; },
      clear: async () => { cleared++; if (options.clearGate) await options.clearGate.promise; saved = null; },
    },
    fetchMe: async () => {
      const result = responses[Math.min(fetched++, responses.length - 1)];
      if (result instanceof Error) throw result;
      if (result?.promise) return result.promise;
      return result;
    },
    setToken: (value) => { token = value; },
    isUnauthorized: (error) => error?.isAxiosError && error.response?.status === 401,
    onState: (state) => { latest = state; },
  });
  return { session, timers, get saved() { return saved; }, get token() { return token; }, get cleared() { return cleared; }, get fetched() { return fetched; }, get written() { return written; }, get state() { return latest; },
    runTimer: async () => { const [id, timer] = timers.entries().next().value; timers.delete(id); timer.fn(); await flush(); },
  };
}
(async () => {
  let cases = 0;
  for (const error of [http(500), http(502), http(503), new AxiosError('timeout', 'ECONNABORTED'), new AxiosError('offline', 'ERR_NETWORK')]) {
    const h = harness([error]); await h.session.start();
    assert.equal(h.saved, 'saved-token'); assert.equal(h.cleared, 0); assert.equal(h.state.ready, true); assert.ok(h.state.sessionError); assert.equal(h.state.user, null);
    h.session.stop(); assert.equal(h.timers.size, 0); cases++;
  }
  {
    const h = harness(); await h.session.start(); assert.equal(h.state.user.id, 1); assert.equal(h.cleared, 0); assert.equal(h.timers.size, 0); h.session.stop(); cases++;
  }
  {
    const h = harness([http(401)]); await h.session.start(); assert.equal(h.saved, null); assert.equal(h.cleared, 1); assert.equal(h.state.sessionError, null); h.session.stop(); cases++;
  }
  {
    const h = harness([user], { noToken: true }); await h.session.start(); assert.equal(h.fetched, 0); assert.equal(h.state.user, null); assert.equal(h.state.sessionError, null); h.session.stop(); cases++;
  }
  for (const mode of ['timer', 'foreground', 'manual']) {
    const h = harness([http(503), user]); await h.session.start();
    if (mode === 'timer') await h.runTimer();
    if (mode === 'foreground') await h.session.resume();
    if (mode === 'manual') { const a = h.session.retry(), b = h.session.retry(); assert.equal(a, b); await a; }
    assert.equal(h.state.user.id, 1); assert.equal(h.state.sessionError, null); assert.equal(h.saved, 'saved-token'); assert.equal(h.fetched, 2); assert.equal(h.timers.size, 0);
    h.session.stop(); cases++;
  }
  {
    const request = deferred(), h = harness([request]); const pending = h.session.start(); await flush();
    await h.session.logout(); request.resolve(user); await pending;
    assert.equal(h.saved, null); assert.equal(h.state.user, null); h.session.stop(); cases++;
  }
  for (const failure of [http(500), http(401)]) {
    const request = deferred(), h = harness([request]); const pending = h.session.start(); await flush();
    assert.equal(await h.session.login('new-token', newer, h.session.getVersion()), true);
    request.reject(failure); await pending;
    assert.equal(h.saved, 'new-token'); assert.equal(h.token, 'new-token'); assert.equal(h.state.user.id, 2); assert.equal(h.cleared, 0); h.session.stop(); cases++;
  }
  {
    const clearGate = deferred(), h = harness([user], { clearGate }); await h.session.start();
    const out = h.session.logout(); await flush(); const login = h.session.login('new-token', newer, h.session.getVersion()); await flush();
    assert.equal(h.written, 0); clearGate.resolve(); await out; await login;
    assert.equal(h.saved, 'new-token'); assert.equal(h.state.user.id, 2); h.session.stop(); cases++;
  }
  {
    const writeGate = deferred(), h = harness([user], { writeGate }); await h.session.start();
    const login = h.session.login('new-token', newer, h.session.getVersion()); await flush(); const out = h.session.logout(); writeGate.resolve();
    assert.equal(await login, false); await out; assert.equal(h.saved, null); assert.equal(h.state.user, null); h.session.stop(); cases++;
  }
  {
    const h = harness(); await h.session.start(); const oldVersion = h.session.getVersion(); await h.session.logout();
    assert.equal(await h.session.login('cancelled-token', newer, oldVersion), false); assert.equal(h.saved, null); h.session.stop(); cases++;
  }
  {
    const h = harness([http(503)]); await h.session.start();
    assert.equal([...h.timers.values()][0].delay, 3000); await h.runTimer();
    assert.equal([...h.timers.values()][0].delay, 10000); await h.runTimer();
    assert.equal([...h.timers.values()][0].delay, 30000); await h.runTimer();
    assert.equal(h.timers.size, 0); assert.equal(h.fetched, 4); assert.equal(h.saved, 'saved-token'); h.session.stop(); cases++;
  }
  console.log(`Session recovery checks passed (${cases} cases).`);
})().catch((error) => { console.error(error); process.exitCode = 1; });
