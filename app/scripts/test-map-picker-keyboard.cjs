const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('typescript');
const vm = require('node:vm');
const place = {placeId:'123',name:'서울역',address:'서울 용산구',latitude:37.55,longitude:126.97,placeUrl:'https://place.map.kakao.com/123'};
let cursor=0;
const hooks=[null,[place],true,'서울역'];
const calls=[],effects=[],listeners={};
const react={
  useState(initial) {const i=cursor++;if(!(i in hooks))hooks[i]=initial;return [hooks[i],value=>{hooks[i]=value;}];},
  useRef(initial) {return {current:initial};},
  useMemo(fn) {return fn();}, useEffect(fn) {effects.push(fn);},
};
const jsx=(type,props)=>({type,props});
const context={exports:{},process:{env:{EXPO_PUBLIC_KAKAO_JS_KEY:'test-key'}},setTimeout:()=>1,clearTimeout(){},require(id){
  if(id==='react')return react;
  if(id==='react/jsx-runtime')return {jsx,jsxs:jsx,Fragment:'Fragment'};
  if(id==='react-native')return {ActivityIndicator:'Spinner',Alert:{},FlatList:'List',Keyboard:{dismiss(){calls.push('dismiss');},addListener(event,handler){listeners[event]=handler;return {remove(){delete listeners[event];}};}},Linking:{},Modal:'Modal',Pressable:'Button',Text:'Text',TextInput:'Input',View:'View'};
  if(id==='expo-location')return {};
  if(id==='react-native-safe-area-context')return {SafeAreaProvider:'Provider',SafeAreaView:'SafeArea'};
  if(id==='react-native-webview')return {WebView:'WebView'};
  if(id==='@/lib/styles')return {s:{},C:{primary:'#00f'}};
  if(id==='@/lib/destination-region')return {mapDestinationRegion:v=>v};
  if(id.endsWith('services/user-message'))return {userMessage:v=>v};
  if(id==='@/lib/lodging-map')return {buildLodgingMapHtml:()=>'',parseLodgingPlace:v=>v};
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
assert.equal(hooks[3],'서울역','Selecting must preserve the typed query');
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
