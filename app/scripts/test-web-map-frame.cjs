const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('public/map-frame.html','utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
let receive; const posted=[],writes=[],operations=[];
const origin='https://example.test';
const parent={postMessage:(value,target)=>posted.push({value,target})};
vm.runInNewContext(script,{location:{hash:'#map%2Fone',origin},parent,
 window:{addEventListener:(event,handler)=>{assert.equal(event,'message');receive=handler;}},
 document:{open:()=>operations.push('open'),write:value=>writes.push(value),close:()=>operations.push('close')}});
assert.equal(posted[0].target,origin);
assert.equal(posted[0].value.channel,'map/one');
assert.equal(posted[0].value.type,'map-frame-ready');
const data={channel:'map/one',html:'<html>trusted map</html>'};
receive({source:{},origin,data});
receive({source:parent,origin:'https://other.test',data});
receive({source:parent,origin,data:{...data,channel:'other'}});
receive({source:parent,origin,data:{...data,html:42}});
assert.equal(writes.length,0);
receive({source:parent,origin,data});
assert.deepEqual(writes,[data.html]);
assert.deepEqual(operations,['open','close']);
console.log('PASS web map frame parent/origin/channel checks and document handoff');
