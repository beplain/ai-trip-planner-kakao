const assert=require('node:assert/strict'),fs=require('node:fs'),ts=require('typescript'),vm=require('node:vm');
function load(file,mocks){const ctx={exports:{},require:id=>mocks[id],Date,setInterval:()=>1,clearInterval(){}};vm.runInNewContext(ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,ctx);return ctx.exports;}
const tokens=load('services/draft-edit-token.ts',{});
const {DraftEditSession,initialEditState}=load('services/draft-edit-session.ts',{'./api':{apiError:e=>e.message},'./draft-edit':{draftEditApi:{}},'./draft-edit-token':tokens});
let owner=null,serial=0;
const api={
 async status(id,token){return {active:!!owner,mine:!!owner&&owner===token,canEdit:true,version:1};},
 async acquire(id,token){if(owner&&owner!==token)throw Error('locked');owner=owner??`t${++serial}`;return {token:owner,status:{active:true,mine:true,canEdit:true,version:1}};},
 async release(id,token){await Promise.resolve();if(owner===token)owner=null;},async renew(){},
};
async function settle(){for(let i=0;i<60;i++)await Promise.resolve();}
(async()=>{
 // A screen mounting before its focus lifecycle must acquire automatically once focus opens it.
 let state,session,focus;
 class TestSession extends DraftEditSession{constructor(id,notify){super(id,notify,api);}}
 const {useDraftEdit}=load('src/hooks/use-draft-edit.ts',{
  react:{useState:init=>{state??=init();return[state,next=>{state=next;}];},useMemo:factory=>session??=factory(),useEffect:fn=>fn(),useCallback:fn=>fn},
  'expo-router':{useFocusEffect:fn=>{focus=fn;}},'../../services/draft-edit-session':{DraftEditSession:TestSession,initialEditState},
 });
 const a=new DraftEditSession('trip:1',()=>{},api);
 assert.equal(await a.begin(),false,'No lease before focus');a.open();const pending=a.begin();
 a.close();a.open();const restarted=a.begin();
 await settle();await pending;assert.equal(await restarted,true,'Focus cleanup/reopen must recover without a restart button');
 assert.equal(a.state.mine,true);assert.equal(a.state.error,'');a.close();await settle();assert.equal(owner,null);
 // Rendering exposes an automatic acquire callback rather than starting before focus.
 useDraftEdit('trip:2',undefined,true);assert.equal(state.ready,false);const cleanup=focus();await settle();
 assert.equal(state.mine,true,'Opening a focused editor acquires automatically without pressing restart');
 cleanup();await settle();assert.equal(owner,null);
 console.log('Editor lifecycle passed: focus readiness, automatic acquire configuration, rapid reopen and release ordering.');
})().catch(e=>{console.error(e);process.exitCode=1;});
