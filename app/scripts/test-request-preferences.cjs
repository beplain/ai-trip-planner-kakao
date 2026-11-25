const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
function load(path, mocks = {}) {
  const context = { exports: {}, require: id => mocks[id] };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText, context);
  return context.exports;
}
const prefs = load('src/lib/request-preferences.ts');
let value = prefs.addRequestRule('', prefs.placeRule('INCLUDE', '정동문화사')).value;
value = prefs.addRequestRule(value, prefs.placeRule('EXCLUDE', '다이소')).value;
value = prefs.addRequestRule(value, prefs.countRule('카페', -1, 'LIMIT', 1)).value;
value = prefs.addRequestRule(value, prefs.countRule('횟집', 0, 'EXACT', 2)).value;
assert.equal(prefs.readRequest(value).rules.length, 4);
assert.match(prefs.requestSummary(value), /카페 · 매일 · 최대 1곳/);
assert.match(prefs.requestSummary(value), /회 · 여행 전체 · 정확히 2곳/);
assert.ok(prefs.addRequestRule(value, prefs.placeRule('INCLUDE', '다이소 부산역점')).error);
assert.ok(prefs.addRequestRule(value, prefs.countRule('카페', -1, 'INCLUDE', 2)).error);
assert.ok(prefs.addRequestRule(value, prefs.placeRule('INCLUDE', '정동문화사')).error);
assert.equal(prefs.readRequest('이전 요청').legacy, '이전 요청');
assert.ok(prefs.addRequestRule('이전 요청', prefs.placeRule('EXCLUDE', '다이소')).error);
assert.equal(prefs.countRule('카페', 2, 'EXACT', 1).day, 2);
assert.equal(prefs.countRule('카페', -1, 'LIMIT', 0).value, 0);
let states = [], cursor = 0, changed;
const jsx = (type, props) => ({ type, props });
const { RequestPreferencesInput } = load('src/components/request-preferences-input.tsx', {
  react: { useState(initial) { const i = cursor++; if (!(i in states)) states[i] = initial; return [states[i], v => { states[i] = v; }]; } },
  'react/jsx-runtime': { jsx, jsxs: jsx },
  'react-native': { Pressable: 'Pressable', Text: 'Text', TextInput: 'TextInput', View: 'View', StyleSheet: { create: s => s } },
  '@/lib/styles': { C: {}, s: {} }, '@/lib/request-preferences': prefs,
  './destination-map-picker': {DestinationMapPicker:'DestinationMapPicker'},
  '@/components/app-text-input': {AppTextInput:'TextInput'},
});
function nodes(n) { return !n || typeof n !== 'object' ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(nodes)]; }
function render() { cursor = 0; return nodes(RequestPreferencesInput({ value, onChange: v => { changed = value = v; }, days: 2 })); }
assert.equal(render().filter(n => n.type === 'TextInput').length, 0, 'No free-form request box');
render().find(n => n.props?.accessibilityLabel === '방문 횟수 추가').props.onPress();
const tree = render();
assert.ok(tree.some(n => n.props?.children === '여행 전체'));
assert.ok(!tree.some(n => n.type === 'TextInput'), 'Counts use selections, not prose');
tree.find(n => n.props?.accessibilityLabel === '제외 · 다이소 삭제').props.onPress();
assert.equal(prefs.readRequest(changed).rules.length, 3);
assert.ok(!prefs.VISIT_TARGETS.includes('횟집'));
render().find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children==='기타')).props.onPress();
render().find(n=>n.props?.accessibilityLabel==='방문 횟수 기타 대상').props.onChangeText('냉면');
render().find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children==='조건 추가')).props.onPress();
assert.ok(prefs.readRequest(changed).rules.some(r=>r.target==='냉면'&&r.meal==='COUNT'));
render().find(n=>n.props?.accessibilityLabel==='포함할 장소 추가').props.onPress();
render().find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children==='지도에서 선택')).props.onPress();
render().find(n=>n.type==='DestinationMapPicker').props.onPlaceSelect({placeId:'205',name:'해운대해수욕장',address:'부산 해운대구',category:'자연'});
render().find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children==='조건 추가')).props.onPress();
assert.ok(prefs.readRequest(changed).rules.some(r=>r.placeId==='205'&&r.kind==='INCLUDE'&&r.category==='자연'));
console.log('Selected request preferences passed: scopes, limits, conflicts, legacy preservation, compact UI and removal.');
