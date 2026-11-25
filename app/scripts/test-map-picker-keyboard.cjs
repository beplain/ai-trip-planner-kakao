const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('typescript');
const vm = require('node:vm');
const place = {placeId:'123',name:'서울역',address:'서울 용산구',latitude:37.55,longitude:126.97,placeUrl:'https://place.map.kakao.com/123'};
const lifecycleExports={};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/lib/map-load-state.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,{exports:lifecycleExports});
let cursor=0;
const hooks=[null,[place],true,true,'서울역'];
const calls=[],effects=[],listeners={},mapTimeouts=[];
const react={
  useState(initial) {const i=cursor++;if(!(i in hooks))hooks[i]=initial;return [hooks[i],value=>{hooks[i]=typeof value==='function'?value(hooks[i]):value;}];},
  useRef(initial) {return {current:initial};},
  useCallback(fn) {return fn;}, useMemo(fn) {return fn();}, useEffect(fn) {effects.push(fn);},
};
const jsx=(type,props)=>({type,props});
const context={exports:{},process:{env:{EXPO_PUBLIC_KAKAO_JS_KEY:'test-key'}},setTimeout:(fn,delay)=>{if(delay===20000)mapTimeouts.push(fn);return 1;},clearTimeout(){},require(id){
  if(id==='react')return react;
  if(id==='@/components/app-text-input')return {AppTextInput:'Input'};
  if(id==='./app-modal')return {AppModal:'Modal'};
  if(id==='react/jsx-runtime')return {jsx,jsxs:jsx,Fragment:'Fragment'};
  if(id==='react-native')return {Platform:{OS:'ios'},ActivityIndicator:'Spinner',Alert:{},FlatList:'List',Keyboard:{dismiss(){calls.push('dismiss');},addListener(event,handler){listeners[event]=handler;return {remove(){delete listeners[event];}};}},Linking:{},Modal:'Modal',Pressable:'Button',Text:'Text',TextInput:'Input',View:'View'};
  if(id==='expo-location')return {Accuracy:{Balanced:3},hasServicesEnabledAsync:async()=>true,requestForegroundPermissionsAsync:async()=>({granted:true}),getCurrentPositionAsync:async()=>({timestamp:1,coords:{latitude:37.55,longitude:126.97,accuracy:10}})};
  if(id==='react-native-safe-area-context')return {SafeAreaProvider:'Provider',SafeAreaView:'SafeArea'};
  if(id==='react-native-webview'||id==='@/components/map-webview')return {WebView:'WebView'};
  if(id==='@/lib/map-load-state')return lifecycleExports;
  if(id==='@/lib/styles')return {s:{},C:{primary:'#00f'}};
  if(id==='@/lib/destination-region')return {mapDestinationRegion:v=>v};
  if(id.endsWith('services/user-message'))return {userMessage:v=>v};
  if(id==='@/lib/lodging-map')return {lodgingSearchQuery:region=>region==='부산광역시'?'부산 숙소':'숙소',buildLodgingMapHtml:()=>'',parseLodgingPlace:v=>v};
  if(id==='@/lib/destination-map')return {buildDestinationMapHtml:()=>''};
  throw new Error(id);
}};
const source=fs.readFileSync('src/components/destination-map-picker.tsx','utf8').replace('function PickerContent(', 'export function PickerContent(');
vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,context);
function flatten(node) {if(Array.isArray(node))return node.flatMap(flatten);return node&&typeof node==='object'?[node,...flatten(node.props?.children)]:[];}
const tree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'transport'});
const nodes=flatten(tree),input=nodes.find(n=>n.type==='Input'),web=nodes.find(n=>n.type==='WebView'),list=nodes.find(n=>n.type==='List');
input.props.ref.current={blur(){calls.push('blur');}};
web.props.ref.current={injectJavaScript(script){calls.push(script);}};
input.props.onSubmitEditing();
assert.deepEqual(calls.slice(0,2),['blur','dismiss'],'Submitting search must release input focus before updating the map');
assert.equal(list.props.keyboardShouldPersistTaps,'always');
const row=list.props.renderItem({item:place});
calls.length=0;row.props.onPressIn();
assert.deepEqual(calls,['blur','dismiss'],'Touching a result releases focus immediately');
calls.length=0;row.props.onPress();
assert.deepEqual(calls.slice(0,2),['blur','dismiss']);
assert.match(calls[2],/selectLodging/);
assert.equal(hooks[0],place,'The same tap still selects the place');
assert.equal(hooks[4],'서울역','Selecting must preserve the typed query');
calls.length=0;web.props.onMessage({nativeEvent:{data:JSON.stringify({type:'lodging-selected',place})}});
assert.deepEqual(calls,['blur','dismiss'],'A map marker selection also releases the search input');
console.log('Map search, result touch and marker selection release keyboard focus while preserving selection.');

// Lodging uses the same guards, including delayed iOS keyboard restoration.
cursor=0;calls.length=0;
const lodgingTree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'lodging'});
const lodgingNodes=flatten(lodgingTree),lodgingInput=lodgingNodes.find(n=>n.type==='Input'),lodgingWeb=lodgingNodes.find(n=>n.type==='WebView');
lodgingInput.props.ref.current={blur(){calls.push('blur');},focus(){calls.push('focus');}};
lodgingWeb.props.ref.current={injectJavaScript(){}};
effects.splice(0).forEach(fn=>fn());
assert.equal(lodgingWeb.props.keyboardDisplayRequiresUserAction,true);
lodgingInput.props.onTouchStart();lodgingInput.props.onFocus();listeners.keyboardDidShow();
assert.equal(calls.length,0,'An intentional search input tap must still allow typing');
lodgingInput.props.onSubmitEditing();calls.length=0;
lodgingInput.props.onFocus();
assert.deepEqual(calls,['blur','dismiss'],'Reject restored search focus after searching for lodging');
calls.length=0;listeners.keyboardDidShow();
assert.deepEqual(calls,['blur','dismiss'],'Dismiss a delayed keyboard show after selection');
calls.length=0;lodgingInput.props.onTouchStart();lodgingInput.props.onFocus();listeners.keyboardDidShow();
assert.equal(calls.length,0,'The user can tap the input and edit again');
lodgingWeb.props.onTouchStart();calls.length=0;listeners.keyboardDidShow();
assert.deepEqual(calls,['blur','dismiss'],'Touching the lodging map locks keyboard restoration too');
console.log('Lodging map blocks unsolicited keyboard restoration and permits deliberate search edits.');

cursor=0;hooks[0]=place;hooks[1]=[place];hooks[2]=true;
let foldTree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'transport'});
let toggle=flatten(foldTree).find(n=>n.props?.accessibilityLabel==='장소 목록 접기');
assert(toggle);toggle.props.onPress();cursor=0;
foldTree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'transport'});
assert(!flatten(foldTree).some(n=>n.type==='List'));
flatten(foldTree).find(n=>n.props?.accessibilityLabel==='장소 목록 펼치기').props.onPress();cursor=0;
foldTree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'transport'});
assert(flatten(foldTree).some(n=>n.type==='List'));
assert.equal(hooks[0],place);
console.log('Map result lists fold and unfold while preserving the selected place.');

cursor=0;hooks[3]=false;hooks[8]=false;
const delayedTree=context.exports.PickerContent({onSelect(){},onClose(){},placeKind:'transport'});
const delayedWeb=flatten(delayedTree).find(n=>n.type==='WebView');
effects.splice(0).forEach(fn=>fn());
mapTimeouts.at(-1)();
assert.equal(hooks[8],true,'Deadline shows a retry state');
delayedWeb.props.onMessage({nativeEvent:{data:JSON.stringify({type:'ready'})}});
assert.equal(hooks[3],true,'Late SDK readiness enables the map');
assert.equal(hooks[8],false,'Late SDK readiness clears the blocking flag');
assert.equal(hooks[7],null,'The obsolete timeout message is cleared');
console.log('A map ready after the deadline clears the timeout UI and becomes usable.');

(async()=>{
 cursor=0;calls.length=0;hooks[3]=false;
 const gpsTree=context.exports.PickerContent({onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'transport',centerOnUser:true});
 const gpsWeb=flatten(gpsTree).find(n=>n.type==='WebView');
 gpsWeb.props.ref.current={injectJavaScript(script){calls.push(script);}};
 gpsWeb.props.onMessage({nativeEvent:{data:JSON.stringify({type:'ready'})}});
 for(let i=0;i<12;i++)await Promise.resolve();
 const injected=calls.find(call=>call.includes('showDestinationUserLocation'));
 assert(injected);assert.match(injected,/, false\)/);
 console.log('Opening the current-location map triggers the same nearby-place lookup as the location button.');
})().catch(error=>{console.error(error);process.exitCode=1;});

cursor=0;calls.length=0;
const autoLodgingTree=context.exports.PickerContent({initialDestination:'부산광역시 숙소',initialRegion:'부산광역시',onSelect(){},onClose(){},onPlaceSelect(){},placeKind:'lodging'});
const autoLodgingWeb=flatten(autoLodgingTree).find(n=>n.type==='WebView');
autoLodgingWeb.props.ref.current={injectJavaScript(script){calls.push(script);}};
autoLodgingWeb.props.onMessage({nativeEvent:{data:JSON.stringify({type:'ready'})}});
assert(calls.some(script=>script.includes('searchDestination("부산 숙소")')));
console.log('Opening the lodging map automatically searches the destination lodging query.');
