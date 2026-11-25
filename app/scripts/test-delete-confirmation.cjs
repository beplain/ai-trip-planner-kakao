const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const jsx = (type, props) => ({ type, props });
let states, refs, cursor, refCursor, shown, calls, failing;
const place = { id: 11, placeId: 'place', name: '방문 장소', category: '관광' };
const trip = { id: 7, version: 3, title: '여행', destination: '부산', styles: [], myRole: 'OWNER', members: [], days: [{ id: 1, dayNumber: 1, places: [place] }] };
const room = { id: '7', version: 3, myRole: 'OWNER', members: [], draft: trip };
const drag = { isDragging: () => false };
const api = {
  async create(input) { calls.push(['create',input]); return {id:'new-room'}; },
  async get() { return room; },
  async remove(...args) { calls.push(['trip', ...args]); if (failing) throw Error('delete failed'); },
  async deletePlace(...args) { calls.push(['place', ...args]); if (failing) throw Error('delete failed'); return { ...trip, days: [] }; },
  async delete(...args) { calls.push(['draft', ...args]); if (failing) throw Error('delete failed'); },
  async update(...args) { calls.push(['update', ...args]); if (failing) throw Error('delete failed'); return room; },
};
const mocks = {
  react: {
    useState(initial) { const i = cursor++; if (!(i in states)) states[i] = initial; return [states[i], v => { states[i] = typeof v === 'function' ? v(states[i]) : v; }]; },
    useRef(initial) { return refs[refCursor++] ??= { current: initial }; }, useCallback: f => f,
  },
  'react/jsx-runtime': { jsx, jsxs: jsx },
  'react-native': new Proxy({ Alert: { alert() { throw Error('Web Alert is unsupported'); } } }, { get: (o, k) => o[k] ?? k }),
  'expo-router': { router: { replace: path => calls.push(['navigate', path]) }, useLocalSearchParams: () => ({ id: '7' }), useFocusEffect() {} },
  '@/components/use-screen-alert': { useScreenAlert: () => ({ alert: (title, message, buttons) => { shown = { title, message, buttons }; }, dialog: { type: 'Dialog' } }) },
  '@/hooks/use-cross-day-drag':{useCrossDayDrag:()=>({state:null,bind:()=>()=>{}})},
 '@/hooks/use-drag-scroll': { useDragScroll: () => ({ controller: drag, dragging: false }) },
  '@/hooks/use-draft-edit': { useDraftEdit: () => ({ blocked:false, message:'', begin:async()=>true, release:async()=>{}, refresh:async()=>{} }) },
  '@/lib/auth-context': { useAuth: () => ({ user: { id: 1 } }) },
  '@/lib/styles': { C: {}, DAY_COLORS: ['blue'], s: {} },
  '@/lib/format': new Proxy({ ROLE_LABEL: {} }, { get: (o, k) => o[k] ?? (() => '') }),
  '@/lib/place-blurb': { itineraryRecommendations: () => new Map() },
  '../../../../services/api': { apiError: e => e.message, isConflict: () => false },
  '../../../../services/trips': { tripsApi: api }, '../../../../services/drafts': { draftsApi: api },
};
function load(route, name) {
  const context = { exports: {}, require: id => mocks[id] ?? new Proxy({}, { get: (_, key) => key }) };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(`src/app/${route}/[id]/index.tsx`, 'utf8') + `\nexport { ${name} };`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText, context);
  return context.exports[name];
}
const Trip = load('trip', 'TripDetailScreen'), Draft = load('draft', 'DraftScreen');
function nodes(n) { return !n || typeof n !== 'object' ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(nodes)]; }
function reset(kind) {
  states = kind === 'trip' ? [trip, null, false, false, null, null] : [false, room, '', null, false, null];
  refs = kind === 'trip' ? [{ current: false }, { current: false }, { current: null }, { current: false }, { current: 3 }]
    : [{ current: false }, { current: room }, { current: false }, { current: false }];
  calls = []; shown = null; failing = false;
}
function render(Screen) { cursor = refCursor = 0; return Screen(); }
function whole(Screen, label) {
  const tree = render(Screen);
  assert.equal(nodes(tree).filter(n => n.type === 'DeletedPlaces').length, 1, 'Only one recovery panel should be mounted');
  assert.ok(nodes(tree).some(n => n.type === 'Dialog'), 'Dialog must be mounted on the screen');
  const button = nodes(tree).find(n => n.type === 'Pressable' && nodes(n).some(t => t.props?.children === label));
  assert.ok(button, label); button.props.onPress();
}
function individual(Screen) { nodes(render(Screen)).find(n => n.type === 'DraggablePlaces').props.onDelete(place, 0); }
async function confirm() { shown.buttons.find(b => b.text === '삭제').onPress(); for (let i = 0; i < 12; i++) await Promise.resolve(); }
async function settle() { for(let i=0;i<12;i++)await Promise.resolve(); }
(async () => {
  for (const [kind, Screen, label] of [['trip', Trip, '여행 삭제'], ['draft', Draft, '여행 계획 삭제']]) {
    for (const open of [() => whole(Screen, label), () => individual(Screen)]) {
      reset(kind); open(); await settle(); assert.deepEqual(calls, []); assert.ok(shown.message);
      shown.buttons.find(b => b.text === '취소').onPress?.(); assert.deepEqual(calls, []);
      reset(kind); open(); await settle(); const wholeDelete = shown.title !== '장소 삭제'; await confirm();
      assert.deepEqual(calls.map(c => c[0]), wholeDelete ? [kind, 'navigate'] : [kind === 'trip' ? 'place' : 'update']);
      reset(kind); failing = true; open(); await settle(); await confirm(); assert.equal(shown.message, 'delete failed');
      assert.equal(calls.some(c => c[0] === 'navigate'), false);
    }
  }
  reset('trip');
  const confirmed=render(Trip);
  assert.equal(nodes(confirmed).find(n=>n.type==='TravelDetailsSummary').props.compact,true);
  nodes(confirmed).find(n=>n.type==='Pressable'&&nodes(n).some(t=>t.props?.children==='새 초안으로 다시 계획하기')).props.onPress();
  shown.buttons.find(b=>b.text==='새 초안 만들기').onPress();await settle();
  assert.equal(calls[0][0],'create');assert.equal(calls[0][1].source,'CONFIRMED_COPY');
  assert.deepEqual(calls[1],['navigate','/draft/new-room']);
  console.log('Deletion checks passed: draft/trip, individual/whole, confirmation, cancel, errors and no navigation on failure.');
})().catch(e => { console.error(e); process.exitCode = 1; });
