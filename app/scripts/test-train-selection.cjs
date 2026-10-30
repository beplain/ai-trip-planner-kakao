const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const ts=require('typescript');
function compile(file,requireFn,globals={}) {
  const ctx={exports:{},require:requireFn,...globals};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
  return ctx.exports;
}
const helper=compile('src/lib/train-selection.ts',()=>({}));
const origin={name:'서울역 경부선',address:'서울특별시',placeId:'1',latitude:37,longitude:127};
const arrival={name:'대구역 경부선',address:'대구광역시',placeId:'2',latitude:35,longitude:129};
const one={id:'one',date:'2027-05-01',trainNo:'1',trainType:'KTX',originStation:'서울',arrivalStation:'대구',departureTime:'08:15',arrivalTime:'10:20'};
const two={...one,id:'two',trainNo:'2',departureTime:'08:30',arrivalTime:'10:45'};
const transport={mode:'KTX',arrivalAuto:true,originLocation:origin,arrivalLocation:arrival,arrivalPlace:'대구역',departurePlace:'대구역',departureTime:'18:00',originTime:'08:00',arrivalTime:'10:00',train:one};
assert.equal(helper.validTrainSelection(transport,'2027-05-01'),true);
assert.equal(helper.trainThreshold('08:00'),'07:50');
assert.equal(helper.trainThreshold('19:00'),'18:50');
assert.equal(helper.trainThreshold('00:05'),'00:00');
assert.equal(helper.validTrainSelection({...transport,originTime:'08:25'},'2027-05-01'),true);
assert.equal(helper.validTrainSelection({...transport,originTime:'08:26'},'2027-05-01'),false);
assert.equal(helper.validTrainSelection(transport,'2027-05-02'),false);
assert.equal(helper.validTrainSelection({...transport,originTime:'09:00'},'2027-05-01'),false);
assert.equal(helper.validTrainSelection({...transport,arrivalLocation:{...arrival,name:'동대구역'}},'2027-05-01'),false);
assert.equal(helper.patchTransport(transport,{originTime:'09:00'}).train,undefined);
assert.equal(helper.patchTransport(transport,{mode:'CAR'}).train,undefined);
assert.equal(helper.patchTransport(transport,{departureTime:'19:00'}).train.id,'one');
let hooks=[],cursor=0,effects=[],date='2027-05-01',current={movementMode:'CAR',stays:[],transport:{...transport,train:undefined}},pending=[],changes=0;
const react={
 useState(initial){const i=cursor++;if(!(i in hooks))hooks[i]=initial;return[hooks[i],(v)=>{hooks[i]=v;}];},
 useRef(value){const i=cursor++;if(!(i in hooks))hooks[i]={current:value};return hooks[i];},
 useEffect(fn,deps){const i=cursor++,previous=hooks[i];if(!previous||deps.some((d,j)=>d!==previous.deps[j])){previous?.cleanup?.();const state=hooks[i]={deps};effects.push(()=>{state.cleanup=fn();});}},
};
const mod=compile('src/components/travel-details-input.tsx',(id)=>{
 if(id==='react')return react;
 if(id==='react/jsx-runtime')return{jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props}),Fragment:'Fragment'};
 if(id==='react-native')return{Keyboard:{dismiss(){}},Pressable:'Pressable',ScrollView:'ScrollView',Text:'Text',View:'View'};
 if(id==='@/components/time-wheel-input')return{TimeWheelInput:'TimeField'};
 if(id==='@/components/destination-map-picker')return{DestinationMapPicker:'MapPicker'};
 if(id==='@/lib/format')return{tripEndDate:(d,n)=>{const dt=new Date(d+'T00:00:00Z');dt.setUTCDate(dt.getUTCDate()+n-1);return dt.toISOString().slice(0,10);},shortDate:(d)=>d};
 if(id==='@/lib/styles')return{s:{}};
 if(id==='@/lib/train-selection')return helper;
 if(id.endsWith('/services/api'))return{apiError:()=> '조회 실패'};
 if(id.endsWith('/services/trips'))return{tripsApi:{trainOptions:()=>new Promise((resolve,reject)=>pending.push({resolve,reject})),transportArrival:(query)=>new Promise((resolve,reject)=>pending.push({resolve,reject,query}))}};
 throw new Error(`Unexpected import ${id}`);
});
function render(){cursor=0;const tree=mod.TravelDetailsInput({value:current,onChange:(d)=>{current=d;changes++;},startDate:date,days:2,destination:'대구광역시'});effects.splice(0).forEach((fn)=>fn());return tree;}
function flatten(tree){if(!tree)return[];if(Array.isArray(tree))return tree.flatMap(flatten);if(typeof tree!=='object')return[];return[tree,...flatten(tree.props?.children)];}
function textOf(node){if(node==null)return '';if(Array.isArray(node))return node.map(textOf).join('');if(typeof node==='object')return textOf(node.props?.children);return String(node);}
function button(tree,text){return flatten(tree).find((n)=>n.type==='Pressable'&&textOf(n.props.children).includes(text));}
(async()=>{
 assert.match(mod.travelDetailsError(current,2,date),/열차 시간표/);
 let task=button(render(),'열차 시간표 조회').props.onPress();
 date='2027-05-02';render();pending[0].resolve([one,two]);await task;
 assert.equal(changes,0,'A stale response must not apply after the trip date changes');
 date='2027-05-01';task=button(render(),'열차 시간표 조회').props.onPress();pending[1].resolve([one,two]);await task;
 assert.equal(current.transport.train,undefined,'Lookup must not confirm a train');
 assert(flatten(render()).find((n)=>n.type==='Pressable'&&textOf(n.props.children)==='선택'));
 button(render(),'KTX 2').props.onPress();
 assert.equal(current.transport.train,undefined,'Tapping a row must only highlight it');
 flatten(render()).find((n)=>n.type==='Pressable'&&textOf(n.props.children)==='선택').props.onPress();
 assert.equal(current.transport.train.id,'two');assert.equal(current.transport.arrivalTime,'10:45');
 assert.equal(current.transport.originTime,'08:00');
 assert.equal(mod.travelDetailsError(current,2,date),undefined);
 assert(!button(render(),'KTX 2'),'Confirmed list must close');
 assert(button(render(),'열차 시간표 조회'),'Lookup button must return after confirmation');
 const texts=flatten(render()).filter((n)=>n.type==='Text').map((n)=>JSON.stringify(n.props.children));
 assert(texts.some((s)=>s.includes('08:30')&&s.includes('10:45')&&s.includes('출발')));
 assert(!texts.some((s)=>s.includes('시간표상 도착 10:20')),'Old default arrival must not remain above a later selected train');
 current={...current,transport:helper.patchTransport(current.transport,{arrivalAuto:false})};
 assert(flatten(render()).some((n)=>n.type==='TimeField'&&n.props.label==='도착 시각'));
 current={...current,transport:{...current.transport,departureLocation:arrival,returnArrivalLocation:origin,departureTime:'19:00'}};
 const home={...one,id:'return',date:'2027-05-02',originStation:'대구',arrivalStation:'서울',departureTime:'18:50',arrivalTime:'20:50'};
 task=flatten(render()).filter((n)=>n.type==='Pressable'&&textOf(n.props.children)==='열차 시간표 조회').at(-1).props.onPress();pending[2].resolve([home]);await task;
 assert.equal(current.transport.returnTrain,undefined);
 button(render(),'KTX 1').props.onPress();
 assert.equal(current.transport.returnTrain,undefined);
 flatten(render()).find((n)=>n.type==='Pressable'&&textOf(n.props.children)==='선택').props.onPress();
 assert.equal(current.transport.returnTrain.id,'return');
 assert(!button(render(),'KTX 1'),'Return list must close after confirmation');
 assert.equal(current.transport.departureTime,'19:00');
 assert.equal(mod.travelDetailsError(current,2,date),undefined);
 assert.equal(helper.patchTransport(current.transport,{departureTime:'20:00'}).returnTrain,undefined);
 assert.equal(helper.patchTransport(current.transport,{returnArrivalLocation:undefined}).returnTrain,undefined);
 task=flatten(render()).filter((n)=>n.type==='Pressable'&&textOf(n.props.children)==='열차 시간표 조회').at(-1).props.onPress();date='2027-05-03';render();pending[3].resolve([home]);await task;
 assert.equal(current.transport.returnTrain.date,'2027-05-02','Stale return response must not update the changed date');
 for (const mode of ['CAR','BUS']) {
  current={...current,transport:helper.patchTransport(current.transport,{mode,departureTime:'19:00'})};
  const count=pending.length;
  task=flatten(render()).filter((n)=>n.type==='Pressable'&&textOf(n.props.children)==='예상 도착 시각 확인').at(-1).props.onPress();
  assert.equal(pending[count].query.originLocation.placeId,arrival.placeId);
  assert.equal(pending[count].query.arrivalLocation.placeId,origin.placeId);
  assert.equal(pending[count].query.originTime,'19:00');
  pending[count].resolve({arrivalTime:'20:30'});await task;
  assert.equal(current.transport.returnArrivalTime,'20:30');
  assert.equal(current.transport.departureTime,'19:00');
  assert.equal(helper.patchTransport(current.transport,{departureTime:'20:00'}).returnArrivalTime,undefined);
 }
 console.log('Train selection checks passed: default and alternate arrivals, stale responses, date/station/time changes, and manual fallback.');
})().catch((error)=>{console.error(error);process.exitCode=1;});
