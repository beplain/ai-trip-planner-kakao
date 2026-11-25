const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const ts=require('typescript');
const jsx=(type,props)=>({type,props});
function load(path,mocks,suffix='') {
  const ctx={exports:{},require:id=>mocks[id]};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path,'utf8')+suffix,{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
  return ctx.exports;
}
const tokens=load('services/draft-edit-token.ts',{});
const {DraftEditSession}=load('services/draft-edit-session.ts',{
  './api':{apiError:e=>e.message},'./draft-edit':{draftEditApi:{}},'./draft-edit-token':tokens,
});
let holder=null,now=0,serial=0,offline=false;
function server(user) {
  function status(token){return{active:!!holder&&holder.expires>now,mine:!!holder&&holder.expires>now&&holder.user===user&&holder.token===token,
    editorName:holder?.user,canEdit:true,version:5};}
  return {
    async status(id,token){if(offline)throw Error('offline');return status(token);},
    async acquire(id,token){if(holder&&holder.expires>now&&!status(token).mine)throw Error('다른 사람이 수정 중');
      if(!status(token).mine)holder={user,token:`token-${++serial}`,expires:now+60000};else holder.expires=now+60000;
      return{token:holder.token,status:status(holder.token)};},
    async renew(id,token){if(offline)throw Error('offline');if(!status(token).mine)throw Error('expired');holder.expires=now+60000;return status(token);},
    async release(id,token){if(holder?.user===user&&holder.token===token)holder=null;},
  };
}
async function settle(){for(let i=0;i<16;i++)await Promise.resolve();}
function nodes(n){return!n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
(async()=>{
  const a=new DraftEditSession('room',()=>{},server('A')), b=new DraftEditSession('room',()=>{},server('B'));
  a.open();b.open();await settle();const grants=await Promise.all([a.begin(),b.begin()]);assert.equal(grants.filter(Boolean).length,1);
  assert.equal(a.state.mine,true);assert.equal(b.state.mine,false);await b.poll();assert.equal(b.state.editorName,'A');
  const version=a.state.version;await a.renew();assert.equal(a.state.version,version);
  offline=true;await a.poll();assert.equal(a.state.ready,false,'Network uncertainty must disable edits');
  offline=false;await a.poll();assert.equal(a.state.ready,true);assert.equal(a.state.mine,true);
  now=61000;await b.poll();assert.equal(await b.begin(),true);const newToken=tokens.draftEditHeaders('room')[tokens.EDIT_HEADER];
  await a.poll();assert.equal(a.state.mine,false);assert.equal(tokens.draftEditHeaders('room')[tokens.EDIT_HEADER],newToken,'A stale owner must not erase the newer credential');
  await a.release();assert.equal(holder.user,'B');b.close();await settle();assert.equal(holder,null);
  let resolveGrant,lateRelease=0;
  const pendingApi={...server('C'),acquire:()=>new Promise(resolve=>{resolveGrant=resolve;}),async release(){lateRelease++;}};
  const c=new DraftEditSession('late',()=>{},pendingApi);c.open();await settle();const waiting=c.begin();await settle();c.close();
  resolveGrant({token:'late-token',status:{active:true,mine:true,canEdit:true,version:1}});assert.equal(await waiting,false);assert.equal(lateRelease,1);
  assert.equal(tokens.draftEditHeaders('late')[tokens.EDIT_HEADER],undefined,'Leaving during acquisition must not leak a credential');
  let grant,lookups=0;
  const raceApi={async status(){lookups++;return{active:false,mine:false,canEdit:true,version:1};},acquire:()=>new Promise(resolve=>{grant=resolve;}),async release(){},async renew(){}};
  const owner=new DraftEditSession('owner',()=>{},raceApi);owner.open();await settle();const acquiring=owner.begin();await settle();
  await owner.poll();assert.equal(lookups,1,'Do not poll with a missing token while acquiring a lease');
  grant({token:'own',status:{active:true,mine:true,canEdit:true,version:1}});assert.equal(await acquiring,true);
  assert.equal(owner.state.mine,true);assert.equal(tokens.draftEditHeaders('owner')[tokens.EDIT_HEADER],'own');owner.close();await settle();

  // A finger lifted while server permission is pending must never start a delayed drag.
  let state=[],refs=[],si=0,ri=0,gestures=[],permission,started=0,cancelled=0;
  function pan(){const handlers={};const chain=new Proxy({}, {get:(_,key)=>key.startsWith('on')?fn=>{handlers[key]=fn;return chain;}:()=>chain});gestures.push(handlers);return chain;}
  const {DraggablePlaces}=load('src/components/draggable-places.tsx',{
    react:{useState(initial){const i=si++;if(!(i in state))state[i]=initial;return[state[i],v=>{state[i]=v;}];},useRef(initial){return refs[ri++]??={current:initial};},useEffect(){}},
    'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':{View:'View',Text:'Text'},
    'react-native-gesture-handler':{Gesture:{Pan:pan},GestureDetector:'GestureDetector'},
    '@/lib/reorder':{dropIndex:()=>0},'@/lib/styles':{C:{},s:{}},'@/lib/place-blurb':{placeRecommendations:()=>[]},'./place-card':{PlaceCard:'PlaceCard'},
  });
  const scroll={end(){},isDragging:()=>false,getOffset:()=>0,begin(){started++;},move(){}};
  const tree=DraggablePlaces({places:[{name:'A'},{name:'B'}],editable:true,busy:false,color:'blue',scroll,onReorder(){throw Error('Unexpected reorder');},onDelete(){},onEditMemo(){},
    onEditStart:()=>new Promise(resolve=>{permission=resolve;}),onEditCancel(){cancelled++;}});
  nodes(tree).filter(n=>n.props?.onLayout).forEach((n,i)=>n.props.onLayout({nativeEvent:{layout:{y:i*100,height:100}}}));
  gestures[0].onStart({absoluteY:50});gestures[0].onEnd({},false);permission(true);await settle();
  assert.equal(started,0);assert.equal(cancelled,1);
  console.log('Draft editing passed: exclusive owner, connection loss, expiry takeover, stale release, navigation cleanup, and cancelled pending drag.');
})().catch(e=>{console.error(e);process.exitCode=1;});
