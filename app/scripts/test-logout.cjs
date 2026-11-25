const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),ts=require('typescript');
const jsx=(type,props)=>({type,props});let states,index,notice,calls,failing;
const mocks={react:{useState(init){const i=index++;if(!(i in states))states[i]=init;return[states[i],v=>{states[i]=v;}];}},
 'react/jsx-runtime':{jsx,jsxs:jsx},'react-native':new Proxy({Alert:{alert(){throw Error('Native Alert must not handle web logout');}}},{get:(o,k)=>o[k]??k}),
 'expo-router':{router:{replace:path=>calls.push(['navigate',path])}},
 '@/lib/auth-context':{useAuth:()=>({user:{name:'사용자'},logout:async()=>{calls.push(['logout']);if(failing)throw Error('연결 오류');}})},
 '@/components/use-screen-alert':{useScreenAlert:()=>({dialog:'Dialog',alert:(title,message,buttons)=>{notice={title,message,buttons};}})},
 '@/lib/styles':{C:{},s:{}},'../../services/api':{apiError:e=>e.message},
};
const ctx={exports:{},require:id=>mocks[id]??new Proxy({},{get:(_,k)=>k})};
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/app/index.tsx','utf8')+'\nexport {MeTab};',{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}}).outputText,ctx);
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function render(){index=0;return ctx.exports.MeTab();}
function reset(){states=[];calls=[];notice=null;failing=false;}
function press(){const tree=render();assert.ok(nodes(tree).some(n=>n.props?.children?.includes?.('Dialog')));nodes(tree).find(n=>n.type==='Pressable'&&nodes(n).some(c=>c.props?.children==='로그아웃')).props.onPress();}
async function settle(){for(let i=0;i<16;i++)await Promise.resolve();}
(async()=>{
 reset();press();assert.equal(notice.title,'로그아웃');assert.equal(notice.buttons.map(b=>b.text).join(','),'로그아웃,취소');assert.equal(calls.length,0);notice.buttons.find(b=>b.text==='취소').onPress?.();assert.equal(calls.length,0);
 reset();press();notice.buttons.find(b=>b.text==='로그아웃').onPress();await settle();assert.deepEqual(calls,[['logout'],['navigate','/']]);
 reset();failing=true;press();notice.buttons.find(b=>b.text==='로그아웃').onPress();await settle();assert.equal(notice.title,'로그아웃하지 못했어요');assert.equal(calls.length,1);
 console.log('Logout passed: visible web confirmation, cancellation, session clearing navigation and visible retryable failures.');
})().catch(e=>{console.error(e);process.exitCode=1;});
