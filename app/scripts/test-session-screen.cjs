const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
let state, retries = 0;
const jsx = (type, props) => ({ type, props });
const mocks = {
  'expo-router': { Redirect: 'Redirect', usePathname: () => '/create' },
  'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'Fragment' },
  'react-native': { ActivityIndicator: 'ActivityIndicator', Pressable: 'Pressable', Text: 'Text', View: 'View' },
  '@/lib/auth-context': { useAuth: () => state },
  '@/lib/styles': { C: { primary: 'blue' }, s: {} },
};
const ctx = { exports: {}, require: (id) => mocks[id] };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('src/components/require-auth.tsx', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText, ctx);
function nodes(node) { return !node || typeof node !== 'object' ? [] : [node, ...[node.props?.children].flat().flatMap(nodes)]; }
state = { user: null, ready: true, restoring: false, sessionError: 'temporary outage', retryRestore: async () => { retries++; } };
const outage = ctx.exports.RequireAuth({ children: 'protected' });
assert.equal(outage.type, 'View'); assert.equal(nodes(outage).some((n) => n.type === 'Redirect'), false);
const button = nodes(outage).find((n) => n.type === 'Pressable');
assert.equal(button.props.disabled, false); button.props.onPress(); assert.equal(retries, 1);
state = { ...state, restoring: true };
assert.equal(nodes(ctx.exports.RequireAuth({ children: 'protected' })).find((n) => n.type === 'Pressable').props.disabled, true);
state = { ...state, restoring: false, sessionError: null };
assert.equal(ctx.exports.RequireAuth({ children: 'protected' }).type, 'Redirect');
state = { ...state, user: { id: 1 } };
assert.equal(ctx.exports.RequireAuth({ children: 'protected' }).props.children, 'protected');
console.log('Session screen checks passed (5 cases).');
