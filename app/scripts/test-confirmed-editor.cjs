const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props});
let state,refs,index,ri,shown,calls,blocked,failing;
const original={id:7,version:4,title:'여행',destination:'부산',days:[{dayNumber:1,places:[{id:1,name:'공원',visitTime:'10:00',verified:true},{id:2,name:'박물관',visitTime:'14:00',verified:true}]}]};
const mocks={
 react:{useState(v){const i=index++;if(!(i in state))state[i]=v;return[state[i],next=>{state[i]=typeof next==='function'?next(state[i]):next;}];},useRef(v){return refs[ri++]??={current:v};},useCallback:f=>f,useEffect(){}},
 'react/jsx-runtime':{jsx,jsxs:jsx},
 'react-native':new Proxy({},{get:(_,key)=>key}),
 'expo-router':{useLocalSearchParams:()=>({id:'7'}),useFocusEffect(){},router:{replace:p=>calls.push(['navigate',p]),back:()=>calls.push(['back'])}},
 '@/hooks/use-draft-edit':{useDraftEdit:()=>({mine:true,blocked,active:true,begin:async()=>true,release:async()=>{calls.push(['release']);}})},
 '@/hooks/use-cross-day-drag':{useCrossDayDrag:()=>({state:null,bind:()=>()=>{}})},
 '@/hooks/use-drag-scroll':{useDragScroll:()=>({controller:{},dragging:false})},
 '@/components/use-screen-alert':{useScreenAlert:()=>({alert:(title,message,buttons)=>{shown={title,message,buttons};},dialog:null})},
 '../../../../services/trips':{tripsApi:{async savePlan(...args){calls.push(['save',...args]);if(failing)throw Error('failed');return original;}}},
 '../../../../services/api':{apiError:e=>e.message}, '@/lib/styles':{C:{},s:{},DAY_COLORS:['blue','orange']},
 '@/lib/visit-map-center':{visitMapCenter:()=>undefined},
 '@/lib/reorder':{reorderItems:(items,from,to)=>{const result=[...items];result.splice(to,0,...result.splice(from,1));return result;}},
};
const ctx={exports:{},require:id=>mocks[id]??new Proxy({},{get:(_,key)=>key})};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/app/trip/[id]/edit.tsx','utf8')+'\nexport {EditConfirmedTrip};',{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function render(){index=ri=0;return ctx.exports.EditConfirmedTrip();}
function button(label){return nodes(render()).find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children===label));}
function reset(){state=[JSON.parse(JSON.stringify(original)),false,'',null,null];refs=[];shown=null;calls=[];blocked=failing=false;}
async function settle(){for(let i=0;i<16;i++)await Promise.resolve();}
(async()=>{
 reset();nodes(render()).find(n=>n.type==='DraggablePlaces').props.onDelete(original.days[0].places[0],0);shown.buttons[1].onPress();assert.equal(state[0].days[0].places.length,1);assert.deepEqual(calls,[]);
 button('수정 취소').props.onPress();shown.buttons[1].onPress();await settle();assert.deepEqual(calls.map(c=>c[0]),['release','back']);assert.equal(original.days[0].places.length,2);
 reset();nodes(render()).find(n=>n.type==='DraggablePlaces').props.onEditMemo(original.days[0].places[0],0);nodes(render()).find(n=>n.type==='MemoModal').props.onSave('11:00','내 메모');assert.deepEqual(calls,[]);button('변경사항 저장').props.onPress();await settle();assert.equal(calls[0][0],'save');assert.equal(calls[0][2][0].places[0].note,'내 메모');assert.equal(calls[0][3],4);assert.deepEqual(calls.map(c=>c[0]),['save','release','navigate']);
 reset();failing=true;button('변경사항 저장').props.onPress();await settle();assert.equal(shown.message,'failed');assert.equal(state[0].version,4);assert.equal(calls.some(c=>c[0]==='navigate'),false);
 reset();blocked=true;assert.equal(button('변경사항 저장').props.disabled,true);button('변경사항 저장').props.onPress();await settle();assert.deepEqual(calls,[]);
 reset();state[0].days.push({dayNumber:2,places:[]});
 const days=nodes(render()).filter(n=>n.type==='DraggablePlaces');assert.notEqual(days[0].props.color,days[1].props.color);
 days[0].props.onReplace(original.days[0].places[0],0);
 let picker=nodes(render()).find(n=>n.type==='DestinationMapPicker');assert.equal(picker.props.pickerTitle,'장소 교체');assert.equal(picker.props.placeKind,'visit');
 picker.props.onPlaceSelect({placeId:'123',name:'맥도날드',address:'부산',latitude:35,longitude:129,category:'식당',categoryPath:'음식점 > 햄버거'});
 assert.equal(state[0].days[0].places[0].name,'맥도날드');assert.equal(state[0].days[0].places[0].category,'식당');assert.equal(state[0].days[0].places[0].reason,undefined);
 console.log('Confirmed editor passed: local changes, cancel without writes, explicit save, failure retention and lock protection.');
})().catch(e=>{console.error(e);process.exitCode=1;});
