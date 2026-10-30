const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let hooks = [], cursor = 0, effects = [], nextTimer = 0;
const timers = new Map(), calls = [];
const react = {
  useState(initial) {
    const index = cursor++;
    if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial;
    return [hooks[index], (value) => { hooks[index] = value; }];
  },
  useEffect(effect, deps) {
    const index = cursor++, previous = hooks[index];
    if (!previous || deps.some((value, i) => value !== previous.deps[i])) {
      previous?.cleanup?.();
      const state = hooks[index] = { deps };
      effects.push(() => { state.cleanup = effect(); });
    }
  },
};
const context = { exports: {}, setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; }, clearTimeout(id) { timers.delete(id); }, require(id) {
  if (id === 'react') return react;
  if (id === 'react/jsx-runtime') return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }) };
  if (id === 'react-native') return { Text: 'Text', View: 'View' };
  if (id.endsWith('/services/trips')) return { tripsApi: { requestChecks(draft) { return new Promise((resolve, reject) => { calls.push({ draft, resolve, reject }); }); } } };
  if (id.endsWith('/services/api')) return { apiError: () => '연결 오류' };
  if (id === '@/lib/styles') return { C: { primary: 'blue', danger: 'red' }, s: {} };
  throw new Error(`Unexpected import ${id}`);
} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/request-reflection.tsx', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText, context);
const Component = context.exports.RequestReflection;
function render(draft) { cursor = 0; const tree = Component({ draft }); effects.splice(0).forEach((effect) => effect()); return tree; }
function startRequests() { for (const [id, fn] of [...timers]) { timers.delete(id); fn(); } }
const view = (tree) => JSON.stringify(tree);
const a = { title: '여행', destination: '부산', request: '다이소 제외', days: [{ dayNumber: 1, places: [{ name: '백화점' }] }] };
const b = { ...a, days: [{ dayNumber: 1, places: [{ name: '다이소' }] }] };
async function flush() { await Promise.resolve(); await Promise.resolve(); }
(async () => {
  assert.match(view(render(a)), /확인하고 있어요/); startRequests();
  assert.match(view(render(b)), /확인하고 있어요/); startRequests();
  calls[0].resolve([{ request: a.request, status: 'APPLIED', detail: '오래된 성공 결과' }]); await flush();
  assert.doesNotMatch(view(render(b)), /오래된 성공 결과/);
  calls[1].resolve([{ request: b.request, status: 'UNMET', detail: '다이소가 남아 있어요' }]); await flush();
  assert.match(view(render(b)), /미반영/);
  assert.match(view(render(b)), /다이소가 남아 있어요/);
  render(a); startRequests();
  calls[2].resolve([{ request: a.request, status: 'APPLIED', detail: '모든 날짜에서 제외 확인' }]); await flush();
  assert.match(view(render(a)), /반영 확인/);
  const edited = { ...a, request: '편하게' };
  render(edited); startRequests();
  calls[3].resolve([{ request: edited.request, status: 'UNVERIFIABLE', detail: '구체적인 조건이 필요해요' }]); await flush();
  assert.match(view(render(edited)), /확인 필요/);
  const failed = { ...a, request: '카페 제외' };
  render(failed); startRequests(); calls[4].reject(new Error('offline')); await flush();
  assert.match(view(render(failed)), /확인하지 못했어요/);
  assert.doesNotMatch(view(render(failed)), /✓ 반영 확인/);
  assert.equal(render({ ...a, request: '' }), null);
  assert.equal(timers.size, 0);
  console.log('Request reflection checks passed: option changes, stale responses, manual edits, unknown requests and network failures.');
})().catch((error) => { console.error(error); process.exitCode = 1; });
