const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const compile = file => ts.transpileModule(fs.readFileSync(file,'utf8'), {compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText;
const storage = new Map();
function loadStore(browser = true) {
  const exports = {};
  const context = {exports};
  if(browser) context.window = {localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)}};
  vm.runInNewContext(compile('services/web-token-store.ts'),context);
  return exports.webTokenStore;
}
function loadSession(store, fetchMe) {
  const exports = {}; let state, token;
  vm.runInNewContext(compile('services/session-recovery.ts'),{exports,setTimeout,clearTimeout});
  const session = new exports.SessionRecovery({store,fetchMe,setToken:value=>{token=value;},isUnauthorized:e=>e.status===401,onState:value=>{state=value;}});
  return {session,getState:()=>state,getToken:()=>token};
}
(async()=>{
  const user = {id:1,name:'웹 테스트'};
  const first = loadSession(loadStore(),async()=>user);
  await first.session.start();
  await first.session.login('test-token-only',user,first.session.getVersion());
  first.session.stop();
  // A fresh module and session represent a full browser reload, without shared in-memory tokens.
  const refreshed = loadSession(loadStore(),async()=>user);
  await refreshed.session.start();
  assert.equal(refreshed.getToken(),'test-token-only');
  assert.equal(refreshed.getState().user.id,1);
  await refreshed.session.logout(); refreshed.session.stop();
  assert.equal(await loadStore().get(),null);
  await loadStore().set('expired-test-token');
  const expired = loadSession(loadStore(),async()=>{throw {status:401};});
  await expired.session.start();
  assert.equal(expired.getState().user,null);
  assert.equal(await loadStore().get(),null);
  expired.session.stop();
  assert.equal(await loadStore(false).get(),null);
  console.log('PASS web session reload, logout, expired-token cleanup, server-rendering checks');
})().catch(error=>{console.error(error);process.exitCode=1;});
