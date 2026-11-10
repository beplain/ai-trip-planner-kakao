const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const axios = require('axios');
const callbacks = {};
const api = { interceptors: {
  request: { use: (fn) => { callbacks.request = fn; } },
  response: { use: (_ok, fn) => { callbacks.reject = fn; } },
} };
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('services/api.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, {
  exports: exportsObject,
  process: { env: { EXPO_PUBLIC_API_BASE_URL: 'https://example.test' } },
  require: (id) => id === 'axios' ? { ...axios, create: () => api } : { userMessage: (text) => text },
});
let loggedOut = 0;
exportsObject.setUnauthorizedHandler(() => { loggedOut++; exportsObject.setAccessToken(null); });
function request(url = '/api/me') { return callbacks.request({ url, baseURL: 'https://example.test', headers: new axios.AxiosHeaders() }); }
async function reject(status, config) {
  const error = new axios.AxiosError('failure', undefined, config, undefined, status ? { status } : undefined);
  await assert.rejects(() => callbacks.reject(error));
}
(async () => {
  exportsObject.setAccessToken('old'); const oldRequest = request();
  exportsObject.setAccessToken('new'); await reject(401, oldRequest); assert.equal(loggedOut, 0);
  const current = request();
  for (const status of [500, 502, 503, undefined]) { await reject(status, current); assert.equal(loggedOut, 0); }
  await reject(401, request('/api/auth/kakao/exchange')); assert.equal(loggedOut, 0);
  await reject(401, { url: '/api/me', headers: {} }); assert.equal(loggedOut, 0);
  await reject(401, current); assert.equal(loggedOut, 1);
  await reject(401, current); assert.equal(loggedOut, 1);
  console.log('Session interceptor checks passed (9 cases).');
})().catch((error) => { console.error(error); process.exitCode = 1; });
