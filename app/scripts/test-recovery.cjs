const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const ts=require('typescript');
const jsx=(type,props)=>({type,props});
let states=[],refs=[],cursor=0,refCursor=0,effects=[],store=[],restored=[],error=false,timer;
const props={kind:'trips',id:7,version:1,editable:true,onRestored(){props.version++;},onRefresh(){}};
const mocks={
  react:{useState(initial){const i=cursor++;if(!(i in states))states[i]=initial;return[states[i],v=>{states[i]=typeof v==='function'?v(states[i]):v;}];},
    useRef(initial){return refs[refCursor++]??={current:initial};},useCallback:f=>f,useEffect:f=>{effects.push(f);}},
  'react/jsx-runtime':{jsx,jsxs:jsx},
  'react-native':{Pressable:'Pressable',Text:'Text',TextInput:'TextInput',View:'View'},
  '@/components/app-text-input':{AppTextInput:'TextInput'},
  '../../services/api':{apiError:e=>e.message},
  '../../services/recovery':{recoveryApi:{async places(){return [...store];},async restorePlace(kind,id,entry,version,time){
    restored.push({entry,version,time});if(error)throw Error('복원할 시간이 겹칩니다');store=store.filter(r=>r.id!==entry);return{version:version+1};
  }}},
  '@/lib/styles':{C:{},s:{}},
  '@/components/time-wheel-input':{TimeWheelInput:'TimeWheelInput'},
};
const context={exports:{},require:id=>mocks[id],setTimeout:f=>{timer=f;return 1;},clearTimeout(){}};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/deleted-places.tsx','utf8'),{
  compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX},
}).outputText,context);
function render(){cursor=refCursor=0;effects=[];return context.exports.DeletedPlaces(props);}
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function button(label){return nodes(render()).find(n=>n.type==='Pressable'&&nodes(n).some(t=>t.props?.children===label));}
async function settle(){for(let i=0;i<12;i++)await Promise.resolve();}
async function reload(){render();effects[0]();await settle();}
const row=id=>({id,deletedAt:new Date().toISOString(),dayNumber:1,place:{name:id,visitTime:id==='A'?'10:00':'12:00'}});
(async()=>{
  await reload();store=[row('A')];props.version++;await reload();assert.ok(button('실행 취소'));
  store=[row('B'),...store];props.version++;await reload();button('실행 취소').props.onPress();await settle();
  assert.equal(restored[0].entry,'B');button('실행 취소').props.onPress();await settle();assert.equal(restored[1].entry,'A');
  store=[row('B'),row('A')];props.version++;await reload();timer();assert.equal(button('실행 취소'),undefined);
  nodes(render()).find(n=>n.type==='Pressable'&&n.props.accessibilityState)?.props.onPress();
  // Each entry still has an independent restore action after the toast expires.
  const restores=nodes(render()).filter(n=>n.type==='Pressable'&&nodes(n).some(t=>t.props?.children==='이 장소 복원'));
  assert.equal(restores.length,2);error=true;restores[1].props.onPress();await settle();assert.equal(restored.at(-1).entry,'A');
  assert.equal(store.length,2);assert.ok(nodes(render()).some(n=>n.props?.children==='복원할 시간이 겹칩니다'));
  assert.equal(refs[1].current,false,'A failed restore must unlock retry');
  assert.equal(nodes(render()).filter(n=>n.type==='TextInput').length,2,'Conflicts can be fixed by editing restore times');
  let toast=null;props.onUndoChange=value=>{toast=value;};error=false;
  store=[row('C'),...store];props.version++;await reload();render();effects[2]();effects[3]();await settle();
  assert.equal(toast.name,'C');assert.equal(button('실행 취소'),undefined,'A floating notice replaces the inline undo');
  const floatingContext={exports:{},require:id=>mocks[id]};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/undo-notice.tsx','utf8'),{
    compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX},
  }).outputText,floatingContext);
  const floating=floatingContext.exports.UndoNotice({notice:toast,onDismiss(){}});
  assert.equal(floating.props.style.position,'absolute','Undo must remain visible when the itinerary is long');
  nodes(floating).find(n=>n.type==='Pressable').props.onPress();await settle();assert.equal(restored.at(-1).entry,'C');
  props.kind='drafts';props.version++;await reload();
  const timeFields=nodes(render()).filter(n=>n.type==='TimeWheelInput');assert.equal(timeFields.length,2);
  assert.equal(nodes(render()).filter(n=>n.type==='TextInput').length,0,'Draft restore time uses wheels instead of typing');
  assert.equal(timeFields[0].props.minuteInterval,1);timeFields[0].props.onChange('13:37');
  nodes(render()).find(n=>n.type==='Pressable'&&nodes(n).some(t=>t.props?.children==='이 장소 복원')).props.onPress();
  await settle();assert.equal(restored.at(-1).time,'13:37','The selected wheel time must reach the restore API');
  props.editable=false;assert.equal(nodes(render()).find(n=>n.type==='TimeWheelInput').props.disabled,true);
  const timeContext={exports:{}};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/lib/time-wheel.ts','utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,timeContext);
  assert.equal(timeContext.exports.wheelTimeParts('12:26',1).minute,26);
  assert.equal(timeContext.exports.wheelTimeParts('12:26').minute,20,'Existing time fields retain their default interval');
  let savedTime;
  const wheelContext={exports:{},require:id=>id==='@/lib/time-wheel'?timeContext.exports:id==='react'?{...mocks.react,useState:initial=>[typeof initial==='function'?initial():initial,()=>{}]}:mocks[id]??{}};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/time-wheel-input.tsx','utf8')+'\nexport { TimeDialog };',{
    compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX},
  }).outputText,wheelContext);
  const picker=wheelContext.exports.TimeDialog({value:'12:26',label:'복원 시각',minuteInterval:1,onSave:value=>{savedTime=value;},onClose(){}});
  const minutes=nodes(picker).find(n=>n.props?.suffix==='분');assert.equal(minutes.props.values.length,60);assert.equal(minutes.props.value,26);
  nodes(picker).filter(n=>n.type==='Pressable').at(-1).props.onPress();assert.equal(savedTime,'12:26','Opening and confirming preserves the exact original minute');
  console.log('Recovery UI passed: two-level undo, expired toast, independent older restore, conflict retention, retry and time adjustment.');
})().catch(e=>{console.error(e);process.exitCode=1;});
