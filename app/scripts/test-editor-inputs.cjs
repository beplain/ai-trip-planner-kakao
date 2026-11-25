const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props});
function load(file,mocks={},suffix='') {const ctx={exports:{},require:id=>mocks[id]??new Proxy({},{get:(_,k)=>k})};vm.runInNewContext(ts.transpileModule(fs.readFileSync(file,'utf8')+suffix,{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);return ctx.exports;}
const platform={OS:'web'};
const {AppTextInput}=load('src/components/app-text-input.tsx',{
  react:{forwardRef:fn=>fn},'react/jsx-runtime':{jsx,jsxs:jsx},
  'react-native':{Platform:platform,StyleSheet:{flatten:style=>style},TextInput:'Input'},
});
const input=AppTextInput({style:{fontSize:12},placeholder:'예: 장소',value:'입력값'},null);
assert.equal(input.props.style[1].fontSize,16);assert.equal(input.props.placeholderTextColor,'#A4ACBA');assert.equal(input.props.value,'입력값');
assert.equal(AppTextInput({style:{fontSize:20}},null).props.style[1].fontSize,20);
platform.OS='ios';assert.equal(AppTextInput({style:{fontSize:15}},null).props.style[0].fontSize,15);
const {visitMapCenter}=load('src/lib/visit-map-center.ts');
const details={stays:[{checkInDay:1,checkOutDay:3,latitude:35,longitude:129},{checkInDay:3,checkOutDay:5,latitude:36,longitude:128}]};
assert.equal(visitMapCenter(details,{dayNumber:1}).latitude,35);
assert.equal(visitMapCenter(details,{dayNumber:3}).latitude,36,'Changing hotels centers the new hotel');
assert.equal(visitMapCenter(details,{dayNumber:5}).latitude,36,'Checkout day retains its hotel');
assert.equal(visitMapCenter(undefined,{dayNumber:1,places:[{latitude:37,longitude:127}]}).latitude,37);
assert.equal(visitMapCenter(undefined,undefined),undefined);
const {MoveForm}=load('src/components/move-visit-dialog.tsx',{
 react:{useState:v=>[v,()=>{}]},'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':{View:'View',Text:'Text',Pressable:'Button',ScrollView:'Scroll'},
 './app-modal':{AppModal:'Modal'},'./time-wheel-input':{TimeWheelInput:'Wheel'},'@/lib/styles':{s:{}},
},'\nexport {MoveForm};');
function text(n){return typeof n==='string'?n:Array.isArray(n)?n.map(text).join(''):n?.props?text(n.props.children):'';}
const form=MoveForm({name:'장소',days:[{dayNumber:1,date:'2026-11-23'}],source:0,time:'12:00',onCancel(){},onMove(){}});
assert.match(text(form),/11\/23/);assert.doesNotMatch(text(form),/11-23/);
const {TravelDetailsSummary}=load('src/components/travel-details-input.tsx',{
 'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':{View:'View',Text:'Text'},'@/lib/styles':{s:{}},
});
const summary=TravelDetailsSummary({compact:true,value:{movementMode:'CAR',stays:[{name:'호텔',address:'숨겨야 할 주소',checkInDay:1,checkOutDay:2,checkInTime:'15:00',checkOutTime:'11:00'}],
 transport:{mode:'KTX',train:{trainNo:'KTX00015',departureTime:'08:00'},arrivalTime:'10:26',departureTime:'20:00',returnArrivalTime:'22:30',originLocation:{name:'서울역'},arrivalPlace:'부산역'}}});
assert.match(text(summary),/첫날.*출발.*도착/);assert.match(text(summary),/마지막 날.*출발.*도착/);
assert.doesNotMatch(text(summary),/숨겨야 할 주소|KTX00015|가는 편|오는 편|서울역|부산역/);
console.log('Editor inputs passed: muted examples, Safari input size, per-day hotel centers and slash dates.');
