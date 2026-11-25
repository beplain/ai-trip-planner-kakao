const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props});
let value=[];
const ctx={exports:{},require:id=>({
 'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':{Pressable:'Button',Text:'Text',View:'View'},
 '@/lib/format':{STYLE_OPTIONS:['맛집','카페','쇼핑','체험','자연'],styleLabel:v=>v},'@/lib/styles':{C:{}},
}[id])};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/travel-style-input.tsx','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function buttons(){return nodes(ctx.exports.TravelStyleInput({value,onChange:updater=>{value=updater(value);}})).filter(n=>n.type==='Button');}
const initial=buttons();
initial.find(n=>n.props.accessibilityLabel==='맛집').props.onPress();
initial.find(n=>n.props.accessibilityLabel==='체험').props.onPress();
assert.equal(value.length,2);assert.ok(value.includes('맛집')&&value.includes('체험'),'Rapid adjacent selections preserve both choices before rerender');
assert.ok(buttons().filter(n=>['맛집','체험'].includes(n.props.accessibilityLabel)).every(n=>n.props.accessibilityState.checked));
buttons().find(n=>n.props.accessibilityLabel==='체험').props.onPress();assert.equal(value.length,1);assert.equal(value[0],'맛집');
for(const button of buttons()){
 assert.equal(button.props.hitSlop,0);assert.equal(button.props.pressRetentionOffset.bottom,0);assert.equal(button.props.pressRetentionOffset.top,0);
 assert.ok(button.props.style.minHeight>=48);
}
console.log('Travel styles passed: independent rapid selection, targeted deselection and non-overlapping press regions.');
