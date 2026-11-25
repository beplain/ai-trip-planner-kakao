const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let hooks = [], cursor = 0, effects = [], pending = [], changes = [];
const origin = { placeId: '1', name: '인천국제공항', address: '인천', latitude: 37, longitude: 126 };
const stations = ['서울역', '광명역', '용산역'].map((name, index) => ({ ...origin, placeId: String(index + 2), name }));
let props = { origin, onChange: station => { changes.push(station); props = { ...props, value: station }; } };
const react = {
  useState(initial) { const i = cursor++; if (!(i in hooks)) hooks[i] = initial; return [hooks[i], value => { hooks[i] = typeof value === 'function' ? value(hooks[i]) : value; }]; },
  useRef(value) { const i = cursor++; if (!(i in hooks)) hooks[i] = { current: value }; return hooks[i]; },
  useEffect(fn, deps) { const i = cursor++, previous = hooks[i]; if (!previous || deps.some((v, j) => v !== previous.deps[j])) { previous?.cleanup?.(); const state = hooks[i] = { deps }; effects.push(() => { state.cleanup = fn(); }); } },
};
const context = { exports: {}, require(id) {
  if (id === 'react') return react;
  if (id === 'react/jsx-runtime') return { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: 'Fragment' };
  if (id === '@/components/destination-map-picker') return { DestinationMapPicker: 'MapPicker' };
  if (id === 'react-native') return { Keyboard: { dismiss() {} }, View: 'View', Text: 'Text', Pressable: 'Pressable', ActivityIndicator: 'Spinner' };
  if (id === '@/lib/styles') return { C: { primary: '#00f' }, s: {} };
  if (id.endsWith('/services/trips')) return { tripsApi: { stationCatalog: async () => ['서울', '청량리'], stationLocation: async name => ({ ...origin, name: name + '역', placeId: '99' }), stationCandidates: (a, b) => new Promise((resolve, reject) => pending.push({ origin: a, destination: b, resolve, reject })) } };
  if (id.endsWith('/services/api')) return { apiError: () => '조회 실패' };
  throw new Error(id);
} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/train-station-choices.tsx', 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText, context);
function render() { cursor = 0; const tree = context.exports.TrainStationChoices(props); effects.splice(0).forEach(fn => fn()); return tree; }
function flat(node) { if (Array.isArray(node)) return node.flatMap(flat); return node && typeof node === 'object' ? [node, ...flat(node.props?.children)] : []; }
function text(node) { if (Array.isArray(node)) return node.map(text).join(''); return node && typeof node === 'object' ? text(node.props?.children) : node == null ? '' : String(node); }
const tick = async () => { await Promise.resolve(); await Promise.resolve(); };
(async () => {
  assert(flat(render()).some(node => node.type === 'Spinner')); assert.equal(pending.length, 1);
  pending[0].resolve(stations); await tick();
  const radios = flat(render()).filter(node => node.props?.accessibilityRole === 'radio');
  assert.equal(radios.length, 3); assert.equal(changes.length, 1, 'The first candidate is automatically selected'); assert.equal(props.value.name,'서울역');
  radios[1].props.onPress();
  assert.equal(props.value.name, '광명역');
  assert(flat(render()).filter(node => node.props?.accessibilityRole === 'radio')[1].props.accessibilityState.checked);
  assert.equal(pending.length, 1, 'Selecting a station must not fetch candidates again');
  props = { ...props, origin: { ...origin, placeId: '8' } }; render();
  props = { ...props, origin: { ...origin, placeId: '9' } }; render();
  pending[1].resolve([stations[2]]); await tick();
  assert(!flat(render()).some(node => node.props?.accessibilityRole === 'radio'), 'Ignore stale candidates');
  pending[2].resolve([stations[0]]); await tick(); render();
  assert.equal(props.value.name, '광명역', 'Candidate refresh must not silently discard an existing or manual station');
  props = { ...props, origin: { ...origin, placeId: '10' } }; render(); pending[3].reject(new Error('offline')); await tick();
  assert(text(render()).includes('조회 실패'));
  flat(render()).find(node => node.type === 'Pressable' && text(node).includes('다시 조회')).props.onPress(); render();
  assert.equal(pending.length, 5); pending[4].resolve([]); await tick();
  assert(text(render()).includes('찾지 못했어요'));
  props = { ...props, origin: undefined }; render(); assert.equal(pending.length, 5);
  props = { ...props, origin: { ...origin, placeId: '20' }, value: undefined }; render();
  flat(render()).find(node => node.type === 'Pressable' && node.props.accessibilityLabel === '목록에서 출발역 직접 선택').props.onPress();
  render(); await tick();
  assert(!flat(render()).some(node => node.type === 'MapPicker'), 'Manual selection must show a list, not a map');
  flat(render()).find(node => node.type === 'Pressable' && text(node) === '청량리역').props.onPress(); await tick();
  assert.equal(props.value.name, '청량리역');assert.equal(text(flat(render()).find(n=>n.props?.accessibilityLabel==='목록에서 출발역 직접 선택')),'청량리역');
  pending[5].resolve(stations); await tick(); render();
  assert.equal(props.value.name, '청량리역', 'Late candidate responses must preserve manual stations outside the three candidates');
  flat(render()).find(node => node.type === 'Pressable' && node.props.accessibilityLabel === '목록에서 출발역 직접 선택').props.onPress(); render(); await tick();
  flat(render()).find(node => node.type === 'Pressable' && node.props.accessibilityLabel === '목록에서 출발역 직접 선택').props.onPress();
  assert.equal(props.value.name, '청량리역', 'Closing the list must preserve the selected station');
  assert.equal(flat(render()).filter(node => node.props?.accessibilityRole === 'radio').length, 3);
  assert(!text(render()).includes('고속열차 출발역 선택'));
  assert(!text(render()).includes('선택한 역:'));
  props = { ...props, kind: '도착역' };
  assert(flat(render()).some(node => node.props?.accessibilityLabel === '목록에서 도착역 직접 선택'));
  console.log('Station choices passed: nearby candidates, stale replies, retry, full catalog selection, closing and pending-response preservation.');
})().catch(error => { console.error(error); process.exitCode = 1; });
