const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props}),day={dayNumber:1,places:[]};
let server={id:'7',version:3,myRole:'OWNER',members:[],draft:{title:'여행',styles:[],days:[day],selectedOption:'a',options:['a','b','c'].map(id=>({id,label:id,days:[day]}))}};
let states=[false,server,'',null,false,null],refs=[{current:false},{current:server},{current:false},{current:false}],si=0,ri=0;
const pending=[],writes=[];
const mocks={
 react:{useState(init){const i=si++;if(!(i in states))states[i]=init;return[states[i],v=>{states[i]=typeof v==='function'?v(states[i]):v;}];},useRef(v){return refs[ri++]??={current:v};},useCallback:f=>f,useMemo:f=>f()},
 'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':new Proxy({},{get:(_,k)=>k}),
 'expo-router':{router:{},useLocalSearchParams:()=>({id:'7'}),useFocusEffect(){}},
 '@/hooks/use-drag-scroll':{useDragScroll:()=>({controller:{isDragging:()=>false},dragging:false})},
 '@/hooks/use-cross-day-drag':{useCrossDayDrag:()=>({bind:()=>({}),dayLayout:()=>()=>{}})},
 '@/hooks/use-draft-edit':{useDraftEdit:()=>({blocked:false,message:'',begin:async()=>true,release:async()=>{},getStatus:()=>({version:server.version})})},
 '@/components/use-screen-alert':{useScreenAlert:()=>({alert:()=>{},dialog:null})},
 '@/lib/styles':{C:{},s:{},DAY_COLORS:['blue']},'@/lib/format':new Proxy({ROLE_LABEL:{}},{get:(o,k)=>o[k]??(v=>v)}),
 '@/lib/place-blurb':{itineraryRecommendations:()=>new Map()},
 '../../../../services/api':{apiError:e=>e.message,isConflict:()=>false},
 '../../../../services/drafts':{draftsApi:{async get(){return server;},switchOption(id,target,version){writes.push({target,version});return new Promise(resolve=>pending.push(()=>{server={...server,version:server.version+1,draft:{...server.draft,selectedOption:target}};resolve(server);}));}}},
};
const ctx={exports:{},require:id=>mocks[id]??new Proxy({},{get:(_,k)=>k})};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/app/draft/[id]/index.tsx','utf8')+'\nexport {DraftScreen};',{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function tabs(){si=ri=0;return nodes(ctx.exports.DraftScreen()).filter(n=>n.props?.accessibilityRole==='tab');}
async function settle(){for(let i=0;i<40;i++)await Promise.resolve();}
(async()=>{
 si=ri=0; const refresh=nodes(ctx.exports.DraftScreen()).find(n=>n.props?.accessibilityLabel==='초안 새로고침');assert(refresh);assert.equal(refresh.props.style.position,'absolute');
 tabs()[1].props.onPress();assert.equal(tabs()[1].props.accessibilityState.selected,true,'Preview changes before network replies');
 tabs()[2].props.onPress();assert.equal(tabs()[2].props.accessibilityState.selected,true);await settle();assert.equal(writes.length,1);assert.equal(writes[0].target,'c','Pending taps coalesce to the most recent selection');
 assert.equal(tabs()[0].props.disabled,false,'Type tabs remain usable while saving a type');tabs()[0].props.onPress();assert.equal(tabs()[0].props.accessibilityState.selected,true);
 pending.shift()();await settle();assert.equal(writes.length,2);assert.equal(writes[1].target,'a');assert.equal(writes[1].version,4,'Queued writes use the last committed server version');
 pending.shift()();await settle();assert.equal(server.draft.selectedOption,'a');assert.equal(tabs()[0].props.accessibilityState.selected,true);
 console.log('Draft type switching passed: immediate previews, coalesced taps, continued interaction and sequential versioned writes.');
})().catch(e=>{console.error(e);process.exitCode=1;});
