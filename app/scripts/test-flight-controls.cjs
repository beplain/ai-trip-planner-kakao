const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props});
let hooks=[],cursor=0,effects=[],pending=[],changes=[],props;
const react={useState(v){const i=cursor++;if(!(i in hooks))hooks[i]=v;return[hooks[i],n=>hooks[i]=typeof n==='function'?n(hooks[i]):n];},useRef(v){const i=cursor++;return hooks[i]??={current:v};},useEffect(fn,deps){const i=cursor++,old=hooks[i];if(!old||deps.some((v,j)=>v!==old.deps[j])){old?.cleanup?.();const h=hooks[i]={deps};effects.push(()=>h.cleanup=fn());}}};
const gmp={placeId:'1',name:'김포국제공항'},cju={placeId:'2',name:'제주국제공항'},home={placeId:'3',name:'집'};
const airports=[{id:'GMP',name:gmp.name,location:gmp},{id:'CJU',name:cju.name,location:cju}];
const rows=[{id:'A',date:'2026-12-01',airline:'항공사',flightNo:'A1',originAirportId:'GMP',arrivalAirportId:'CJU',originAirport:gmp.name,arrivalAirport:cju.name,departureTime:'09:00',arrivalTime:'10:10'}, {id:'B',date:'2026-12-01',airline:'항공사',flightNo:'B1',originAirportId:'GMP',arrivalAirportId:'CJU',originAirport:gmp.name,arrivalAirport:cju.name,departureTime:'10:00',arrivalTime:'11:10'}];
const mocks={react,'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':new Proxy({},{get:(_,k)=>k}),'@/lib/styles':{s:{}},'./single-line-text':{SingleLineText:'SingleLineText'},'./time-wheel-input':{TimeWheelInput:'TimeInput'},'./destination-map-picker':{DestinationMapPicker:'MapPicker'},'../../services/api':{apiError:()=> '조회 실패'},
 '../../services/trips':{tripsApi:{flightOptions:()=>new Promise(resolve=>pending.push(resolve)),airportCandidates:()=>new Promise(resolve=>pending.push(resolve)),airportLocation:async id=>airports.find(a=>a.id===id).location}},
 '@/lib/flight-selection':{selectFlight:(f,back)=>back?{returnFlight:f,departureTime:f.departureTime,returnArrivalTime:f.arrivalTime}:{flight:f,originTime:f.departureTime,arrivalTime:f.arrivalTime},validFlightSelection:()=>true}};
const ctx={exports:{},require:id=>{if(!(id in mocks))throw Error(id);return mocks[id];}};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/flight-transport-input.tsx','utf8')+'\nexport {FlightLeg,AirportChoices};',{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
function render(name='FlightLeg'){cursor=0;const tree=ctx.exports[name](props);effects.splice(0).forEach(fn=>fn());return tree;}
function flat(n){if(Array.isArray(n))return n.flatMap(flat);return n&&typeof n==='object'?[n,...flat(n.props?.children)]:[];}
function text(n){return Array.isArray(n)?n.map(text).join(''):n&&typeof n==='object'?text(n.props?.children):n==null?'':String(n);}
const button=(tree,label)=>flat(tree).find(n=>n.type==='Pressable'&&text(n)===label);
async function tick(){for(let i=0;i<8;i++)await Promise.resolve();}
(async()=>{
 props={returning:false,date:'2026-12-01',airports,transport:{mode:'PLANE',originLocation:home,boardingStation:gmp,arrivalLocation:cju,originTime:'08:00',arrivalAuto:true},onChange:p=>{changes.push(p);props={...props,transport:{...props.transport,...p}};}};
 button(render(),'항공편 조회').props.onPress();pending.shift()(rows);await tick();let tree=render();
 assert(button(tree,'선택'));assert.equal(changes.length,0);
 flat(tree).filter(n=>n.props?.accessibilityRole==='radio')[1].props.onPress();assert.equal(changes.length,0,'Row tap must not commit');
 button(render(),'선택').props.onPress();assert.equal(changes.length,1);assert.equal(changes[0].flight.id,'B');assert(!button(render(),'선택'));
 button(render(),'항공편 조회').props.onPress();props={...props,date:'2026-12-02'};render();pending.shift()(rows);await tick();assert(!button(render(),'선택'),'Ignore stale date response');
 // Opening departure uses current location; arrival uses the selected airport's zoomed center.
 button(render(),'집 ⌄').props.onPress();let map=flat(render()).find(n=>n.type==='MapPicker');assert.equal(map.props.centerOnUser,true);map.props.onClose();
 flat(render()).find(n=>n.props?.accessibilityLabel==='지도에서 도착 장소 선택').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');assert.equal(map.props.centerOnUser,false);assert.equal(map.props.transportFocus,'airport');assert.equal(map.props.initialCenter.placeId,cju.placeId);assert.equal(map.props.pickerTitle,'항공편 도착 장소 선택');
 changes=[];const hotel={placeId:'77',name:'제주 숙소'};map.props.onPlaceSelect(hotel);render();assert.equal(changes[0].arrivalLocation,undefined,'Changing the arrival place resets the airport');
 let arrivalChoices=flat(render()).find(n=>n.type?.name==='AirportChoices'&&n.props.placeLabel==='도착 장소');
 assert(arrivalChoices.props.nearby);assert.equal(arrivalChoices.props.origin.placeId,hotel.placeId);assert.equal(arrivalChoices.props.value,undefined);
 arrivalChoices.props.onChange(airports[1],cju);render();assert.equal(props.transport.arrivalLocation.placeId,cju.placeId);assert.equal(props.transport.returnBoardingStation.name,cju.name);
 assert.equal(flat(render()).find(n=>n.type?.name==='AirportChoices'&&n.props.placeLabel==='도착 장소').props.origin.placeId,hotel.placeId,'Airport choice preserves the destination reference for nearby recommendations');
 flat(render()).find(n=>n.props?.accessibilityLabel==='지도에서 도착 장소 선택').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');assert.equal(map.props.initialCenter.placeId,hotel.placeId);map.props.onClose();
 props={...props,returning:true,transport:{...props.transport,returnArrivalLocation:gmp}};render();
 arrivalChoices=flat(render()).find(n=>n.type?.name==='AirportChoices'&&n.props.placeLabel==='도착 장소');arrivalChoices.props.onChange(airports[1],cju);assert.equal(props.transport.returnArrivalLocation.placeId,cju.placeId,'Return-leg airport selection updates the return arrival');
 props={...props,destination:'제주특별자치도 제주시'};
 flat(render()).find(n=>n.props?.accessibilityLabel==='지도에서 도착 장소 선택').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');
 assert.equal(map.props.centerOnUser,true,'Return arrival opens at the current location just like outbound departure');assert.equal(map.props.initialCenter,undefined);assert.equal(map.props.initialRegion,undefined);assert.equal(map.props.transportFocus,undefined);map.props.onClose();

 button(render(),'제주국제공항 ⌄').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');
 assert.equal(map.props.centerOnUser,false,'Return departure must not jump to the current home location');assert.equal(map.props.initialCenter.placeId,cju.placeId);assert.equal(map.props.transportFocus,'airport');assert.equal(map.props.initialRegion,props.destination);map.props.onClose();
 props={...props,transport:{...props.transport,departureLocation:home,returnBoardingStation:undefined}};
 button(render(),'집 ⌄').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');assert.equal(map.props.initialCenter.placeId,cju.placeId,'Use the outbound arrival airport when no return departure airport is selected');map.props.onClose();
 props={...props,transport:{...props.transport,arrivalLocation:undefined}};
 button(render(),'집 ⌄').props.onPress();map=flat(render()).find(n=>n.type==='MapPicker');assert.equal(map.props.initialCenter,undefined);assert.equal(map.props.initialRegion,props.destination);assert.equal(map.props.transportFocus,'airport');assert.equal(map.props.centerOnUser,false);map.props.onClose();

 hooks=[];effects=[];pending=[];changes=[];
 props={origin:home,nearby:true,airports,onChange:(a,p)=>{changes.push(a);props={...props,value:p};}};
 render('AirportChoices');button(render('AirportChoices'),'직접 선택').props.onPress();let list=render('AirportChoices');
 assert(button(list,gmp.name)&&button(list,cju.name),'Manual choice exposes every airport');
 button(list,cju.name).props.onPress();await tick();assert.equal(changes[0].id,'CJU');
 pending.shift()(airports);await tick();const chosen=render('AirportChoices');assert.equal(text(flat(chosen).find(n=>n.props?.accessibilityLabel==='공항 직접 선택')),cju.name);assert(!text(chosen).includes('공항 1'));assert.equal(changes.length,1,'Late nearby result preserves manual airport');
 console.log('Flight controls passed: nearby/manual choices, two-step confirmation, stale-date rejection and late-response preservation.');
})().catch(e=>{console.error(e);process.exitCode=1;});
