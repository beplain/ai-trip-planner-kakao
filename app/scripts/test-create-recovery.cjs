const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let states, refs, cursor, refCursor, generated, saved, saveFails, generateFails, navigated;
const jsx = (type, props) => ({ type, props });
const mocks = {
  react: { useState(initial) { const i = cursor++; if (!(i in states)) states[i] = initial; return [states[i], v => { states[i] = typeof v === 'function' ? v(states[i]) : v; }]; },
    useRef(initial) { const i = refCursor++; return refs[i] ??= { current: initial }; }, useCallback: f => f },
  'react/jsx-runtime': { jsx, jsxs: jsx },
  'expo-router': { router: { replace: path => { navigated = path; } }, useFocusEffect() {} },
  'react-native': { Keyboard: { dismiss() {} }, Alert: { alert() { throw Error('Invisible web alert'); } },
    View: 'View', Text: 'Text', Pressable: 'Pressable', ScrollView: 'ScrollView', TextInput: 'TextInput' },
  '../../services/api': { apiError: e => e.message },
  '../../services/trips': { tripsApi: { async generateOptions() { generated++; if (generateFails) throw Error('generation failed'); return {}; } }, combineDraftOptions: () => ({ title: 'draft' }) },
  '../../services/drafts': { draftsApi: { async create() { saved++; if (saveFails) throw Error('save failed'); return { id: 'room' }; } } },
  '@/lib/format': { tripDayCount: () => 2, formatBudget: v => v, STYLE_OPTIONS: [] },
  '@/lib/destination-region': { destinationRegion: v => v },
  '@/components/travel-details-input': { DEFAULT_TRAVEL_DETAILS: {}, travelDetailsError: () => null },
  '@/lib/styles': { C: {}, s: {} },
};
const context = { exports: {}, require: id => mocks[id] ?? new Proxy({}, { get: (_, key) => key }) };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/app/create.tsx', 'utf8') + '\nexport { CreateScreen };', {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText, context);
function render() { cursor = refCursor = 0; return context.exports.CreateScreen(); }
function nodes(n) { return !n || typeof n !== 'object' ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(nodes)]; }
function submit() { return nodes(render()).find(n => n.type === 'Pressable').props.onPress(); }
function reset() { states = [{}, '', '부산', '2026-12-01', '2026-12-02', [], '', null, null, 7]; refs = []; generated = saved = 0; saveFails = generateFails = false; navigated = null; }
(async () => {
  reset(); generateFails = true; await submit();
  assert.equal(states[7], null); assert.equal(states[8].message, 'generation failed');
  assert.ok(nodes(render()).some(n => n.props?.accessibilityRole === 'alert'));
  generateFails = false; await submit(); assert.equal(navigated, '/draft/room');
  reset(); saveFails = true; await submit(); assert.equal(generated, 1); assert.equal(states[8].message, 'save failed');
  saveFails = false; await submit(); assert.equal(generated, 1); assert.equal(saved, 2); assert.equal(navigated, '/draft/room');
  reset(); saveFails = true; await submit(); states[6] = '새 요청'; saveFails = false; await submit();
  assert.equal(generated, 2, 'Changed requests must generate a new draft');
  reset(); const header=nodes(render()).find(n=>n.props?.title==='새 여행 만들기'); assert(header.props.right); header.props.right.props.onPress(); assert.equal(navigated,'/','Home shortcut exits the wizard directly');
  console.log('Create recovery passed: visible web failures, retry, save-only retry, changed-input invalidation and home shortcut.');
})().catch(e => { console.error(e); process.exitCode = 1; });
