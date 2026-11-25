const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
let active;
let dimensions = { width: 390, fontScale: 1 };
const jsx = (type, props) => ({ type, props });
const modules = {};
function loadModule(relative) {
  const output = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText, { exports: output, require() { throw new Error('unexpected import'); } });
  return output;
}
function load(file) {
  const output = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/components', file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText, { exports: output, require(id) {
    if (id === 'react') return { useState(initial) { const state = active; if (!('value' in state)) state.value = initial; return [state.value, value => { state.value = typeof value === 'function' ? value(state.value) : value; }]; } };
    if (id === 'react/jsx-runtime') return { jsx, jsxs: jsx, Fragment: 'Fragment' };
    if (id === 'react-native') return { View: 'View', Text: 'Text', Pressable: 'Button', Linking: {}, StyleSheet: { flatten: style => style }, useWindowDimensions: () => dimensions };
    if (id === '@/lib/styles') return { C: { primary: '#00f', sub: '#888' }, s: {} };
    if (id.endsWith('business-hours')) return { businessHoursNotice: () => ({ label: '운영시간 확인됨.', verified: true }) };
    if (id.endsWith('user-message')) return { userMessage: value => value };
    if (id === '@/lib/kakao-map') return {};
    if (id === './place-recommendation') return {PlaceRecommendation:'PlaceRecommendation'};
    if (id === '@/lib/place-blurb') return loadModule('../src/lib/place-blurb.ts');
    if (id === './fit-text') return modules.fitText;
    if (id === './itinerary-disclosure') return modules.disclosure;
    throw Error(id);
  } });
  return output;
}
function nodes(tree) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  return [tree, ...nodes(tree.props?.children)];
}
function text(tree) {
  if (typeof tree === 'string' || typeof tree === 'number') return String(tree);
  if (!tree) return '';
  if (Array.isArray(tree)) return tree.map(text).join(' ');
  return text(tree.props?.children);
}
modules.fitText = load('fit-text.tsx');
const { FitText } = modules.fitText;
dimensions = { width: 300, fontScale: 1 };
let fitted = FitText({ fontSize: 16, children: '긴 장소 이름' });
assert.equal(fitted.props.style[1].fontSize, 16 * 0.9);
assert.equal(fitted.props.numberOfLines, 2);
assert.equal(fitted.props.adjustsFontSizeToFit, true);
dimensions = { width: 900, fontScale: 1.5 };
fitted = FitText({ fontSize: 16, children: '긴 장소 이름' });
assert.equal(fitted.props.style[1].fontSize, 16 * 1.1);
assert.equal(fitted.props.numberOfLines, undefined);
assert.equal(fitted.props.adjustsFontSizeToFit, false);
modules.disclosure = load('itinerary-disclosure.tsx');
const { PlaceCard } = load('place-card.tsx');
const place = { placeId: '1', name: '남천리녹차팥빙수', address: '부산 수영구 수영로 394번길 29', visitTime: '12:20', category: '카페', verified: true, reason: '추천 이유', note: '메모' };
let deletes = 0;
const card = PlaceCard({ place, index: 2, isLast: false, editable: true, onDelete: () => deletes++ });
const disclosure = nodes(card).find(node => node.type === modules.disclosure.ItineraryDisclosure);
function instance(props) {
  const state = {};
  return { render() { active = state; return modules.disclosure.ItineraryDisclosure(props); } };
}
const first = instance(disclosure.props), second = instance(disclosure.props);
let tree = first.render();
assert.match(text(tree), /12:20/); assert.match(text(tree), /남천리녹차팥빙수/); assert.match(text(tree), /부산 수영구/);
assert.doesNotMatch(text(tree), /추천 이유|운영시간 확인됨|메모|길찾기|삭제/);
let toggle = nodes(tree).find(node => node.type === 'Button');
assert.equal(toggle.props.accessibilityState.expanded, false); toggle.props.onPress();
tree = first.render(); assert.match(text(tree), /💡\s+추천 이유/); assert.match(text(tree), /운영시간 확인됨/);
assert.doesNotMatch(text(second.render()), /추천 이유/, 'Opening one itinerary leaves the other collapsed');
const buttons = nodes(tree).filter(node => node.type === 'Button');
buttons.find(node => text(node).includes('삭제')).props.onPress(); assert.equal(deletes, 1);
assert.doesNotMatch(text(buttons[0]), /삭제|길찾기/, 'Action buttons are outside the header toggle');
buttons[0].props.onPress(); assert.doesNotMatch(text(first.render()), /추천 이유/);
const transport = load('transport-departure.tsx');
const location = { name: '서울역', address: '서울 중구', placeId: 'station' };
const value = { transport: { mode: 'CAR', originLocation: location, originTime: '08:00', arrivalPlace: '부산역', returnArrivalLocation: location, returnArrivalTime: '21:00' } };
for (const element of [transport.TransportDeparture({ value, dayNumber: 1 }), transport.TransportReturnArrival({ value, dayNumber: 2, dayCount: 2 })]) {
  const node = nodes(element).find(entry => entry.type === modules.disclosure.ItineraryDisclosure);
  const visit = instance(node.props); const closed = visit.render();
  assert.match(text(closed), /서울역/); assert.doesNotMatch(text(closed), /자동차/);
  nodes(closed).find(entry => entry.type === 'Button').props.onPress(); assert.match(text(visit.render()), /자동차/);
}
process.stdout.write('Itinerary collapse, independent toggles, actions and transport checks passed.\n');

const { ItineraryDay } = load('itinerary-day.tsx');
function dayInstance(title) {
  const state = {}, children = { type: 'Visit', props: { children: '일정 내용' } };
  return { render() { active = state; return ItineraryDay({ title, children }); }, children };
}
const day1 = dayInstance('DAY 1'), day2 = dayInstance('DAY 2');
let dayTree = day1.render();
let dayButton = nodes(dayTree).find(node => node.type === 'Button');
assert.equal(dayButton.props.accessibilityState.expanded, true);
dayButton.props.onPress(); dayTree = day1.render();
assert.equal(nodes(dayTree).find(node => node.type === 'Button').props.accessibilityState.expanded, false);
assert.ok(nodes(dayTree).some(node => node.props.style?.display === 'none'));
assert.ok(nodes(dayTree).includes(day1.children), 'Hidden day keeps its existing place components mounted');
assert.equal(nodes(day2.render()).find(node => node.type === 'Button').props.accessibilityState.expanded, true, 'Other days remain unchanged');
nodes(dayTree).find(node => node.type === 'Button').props.onPress();
assert.equal(nodes(day1.render()).find(node => node.type === 'Button').props.accessibilityState.expanded, true);
process.stdout.write('Independent day collapse and place state preservation checks passed.\n');

const legacy = PlaceCard({ place: { ...place, reason: '카카오맵에서 확인한 장소로 이동 가능한 시간대에 배치했습니다.',
  note: '임시휴무는 방문 전 확인해주세요.' }, index: 0, isLast: true, editable: false });
const legacyVisit = instance(nodes(legacy).find(node => node.type === modules.disclosure.ItineraryDisclosure).props);
nodes(legacyVisit.render()).find(node => node.type === 'Button').props.onPress();
const legacyText = text(legacyVisit.render());
assert.match(legacyText, /임시휴무는 방문 전 확인해 주세요\./);
assert.doesNotMatch(legacyText, /💡/, 'Missing recommendations must not be filled with stock text');
assert.match(legacyText, /커피|디저트|카페/);
assert.doesNotMatch(legacyText, /이동 가능한 시간대|둘러보|메뉴를 즐겨|나는 집/);
assert.equal(legacyText.match(/임시휴무/g).length, 1);
const meal = PlaceCard({ place: { ...place, category: '식당', reason: '식사 일정 · 저녁 · 순두부', note: '' }, index: 0, isLast: true, editable: false });
const mealVisit = instance(nodes(meal).find(node => node.type === modules.disclosure.ItineraryDisclosure).props);
nodes(mealVisit.render()).find(node => node.type === 'Button').props.onPress();
const mealText = text(mealVisit.render());
assert.doesNotMatch(mealText, /💡|순두부/, 'A menu marker alone is not a recommendation narrative');
assert.doesNotMatch(mealText, /나는 집|저녁 식사로|메뉴를 즐겨/);
