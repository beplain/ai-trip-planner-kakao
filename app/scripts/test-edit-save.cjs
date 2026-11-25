const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const jsx = (type, props) => ({ type, props });
function load(path, mocks, suffix = '') {
  const context = { exports: {}, require: id => mocks[id] ?? new Proxy({}, { get: (_, key) => key }) };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path, 'utf8') + suffix, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText, context);
  return context.exports;
}
function nodes(n) { return !n || typeof n !== 'object' ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(nodes)]; }
const ui = Object.fromEntries(['Modal','View','Text','ScrollView','Pressable'].map(k => [k,k]));
let notice = null, applied = 0;
const { useScreenAlert } = load('src/components/use-screen-alert.tsx', {
  react: { useState: () => [notice, v => { notice = v; }], useCallback: f => f },
  'react/jsx-runtime': { jsx, jsxs: jsx }, 'react-native': ui, '@/lib/styles': { C: {}, s: {} },
});
const buttons = [{ text: '취소', style: 'cancel' }, { text: '조건 적용', onPress: () => { applied++; } }];
function clickDialog(label) {
  const tree = useScreenAlert().dialog;
  const button = nodes(tree).find(n => n.type === 'Pressable' && n.props.accessibilityLabel === label);
  assert.ok(button, `${label} must be visible`); button.props.onPress();
}
useScreenAlert().alert('변경 확인', '조건을 적용합니다.', buttons);
clickDialog('취소'); assert.equal(applied, 0); assert.equal(notice, null);
useScreenAlert().alert('변경 확인', '', buttons);
clickDialog('조건 적용'); assert.equal(applied, 1); assert.equal(notice, null);
useScreenAlert().alert('실패', '실패 내용'); clickDialog('확인'); assert.equal(notice, null);

let state, refs, index, refIndex, shown, calls, restored, failing;
const original = { title: '여행', destination: '부산', startDate: '2026-12-01', endDate: '2026-12-02', days: [{}, {}], styles: [], request: '', travelDetails: undefined };
const mocks = {
  react: { useState(initial) { const i = index++; if (!(i in state)) state[i] = initial; return [state[i], v => { state[i] = typeof v === 'function' ? v(state[i]) : v; }]; },
    useRef(initial) { return refs[refIndex++] ??= { current: initial }; }, useCallback: f => f, useEffect() {} },
  'react/jsx-runtime': { jsx, jsxs: jsx },
  'react-native': { ...ui, Keyboard: { dismiss() {} }, Alert: { alert() { throw Error('Web Alert must not be used'); } } },
  'expo-router': { router: { back() { calls.push('back'); } }, useLocalSearchParams: () => ({ id: 'room' }), useFocusEffect() {} },
  '@/components/use-screen-alert': { useScreenAlert: () => ({ alert: (title, message, buttons) => { shown = { title, message, buttons }; }, dialog: null }) },
  '../../../../services/api': { apiError: e => e.message, isConflict: () => false },
  '../../../../services/drafts': { draftsApi: {
    async rename() { calls.push('rename'); },
    async restoreGenerated() { calls.push('restore'); return restored ? {} : null; },
    async regenerateOptions() { calls.push('generate'); if (failing) throw Error('server failed'); return {}; },
    async selectRegenerated() { calls.push('select'); },
  } },
  '../../../../services/trips': { combineDraftOptions: () => ({}) },
  '@/components/travel-details-input': { travelDetailsError: () => null },
  '@/lib/format': { tripDayCount: () => 2, tripEndDate: () => original.endDate, formatBudget: v => v ?? '', styleLabel: v => v === '액티비티' ? '체험' : v, STYLE_OPTIONS: [] },
  '@/lib/destination-region': { destinationRegion: v => v }, '@/lib/styles': { C: {}, s: {} },
  '@/hooks/use-draft-edit': { useDraftEdit: () => ({ mine:true,blocked:false,message:'',begin:async()=>true,release:async()=>{},refresh:async()=>{} }) },
};
const { EditDraftScreen } = load('src/app/draft/[id]/edit.tsx', mocks, '\nexport { EditDraftScreen };');
function reset() {
  state = [null, { id: 'room', version: 1, draft: original }, undefined, '여행', '부산', original.startDate, original.endDate, [], '변경된 요청', false, false, true];
  refs = []; calls = []; shown = null; restored = failing = false;
}
function render() { index = refIndex = 0; return EditDraftScreen(); }
function save() { const button = nodes(render()).find(n => n.type === 'Pressable' && nodes(n).some(t => t.props?.children === '변경사항 저장')); assert.ok(button); button.props.onPress(); }
const settle = async () => { for (let i=0; i<12; i++) await Promise.resolve(); };
(async () => {
  reset(); save(); assert.equal(shown.title, '여행 조건을 변경할까요?'); assert.deepEqual(calls, []);
  shown.buttons.find(b => b.text === '조건 적용').onPress(); await settle(); assert.deepEqual(calls, ['restore','generate','select','back']);
  reset(); restored = true; save(); shown.buttons[1].onPress(); await settle(); assert.deepEqual(calls, ['restore','back']);
  reset(); failing = true; save(); shown.buttons[1].onPress(); await settle(); assert.equal(shown.message, 'server failed'); assert.equal(state[9], false); assert.equal(refs[0].current, false);
  reset(); state[8] = ''; state[3] = '새 이름'; save(); await settle(); assert.deepEqual(calls, ['rename','back']);
  reset(); state[3] = ''; save(); assert.equal(shown.title, '여행 이름을 입력해 주세요'); assert.deepEqual(calls, []);
  console.log('Edit save checks passed: visible confirmation/cancel, generation, history restore, error/unlock, rename, validation.');
})().catch(e => { console.error(e); process.exitCode = 1; });
