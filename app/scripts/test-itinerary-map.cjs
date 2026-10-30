/* global __dirname */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/lib/kakao-map.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, { exports: exportsObject, require: () => ({ Linking: {} }) });
const html = exportsObject.buildKakaoMapHtml('test');
const frames = [], listeners = {}, lines = [], messages = [];
let load, resized, relayouts = 0;
const container = { clientWidth: 0, clientHeight: 0 };
class Overlay { setMap(map) { this.map = map; } }
class Line extends Overlay {
  constructor(options) { super(); this.options = options; this.refreshes = 0; lines.push(this); }
  setPath(points) { this.points = points; this.refreshes++; }
}
const context = {
  Number, String, setTimeout: (fn) => frames.push(fn),
  requestAnimationFrame: (fn) => frames.push(fn), addEventListener: () => {},
  ResizeObserver: class { constructor(callback) { resized = callback; } observe() {} },
  ReactNativeWebView: { postMessage: (message) => messages.push(JSON.parse(message)) },
  document: { getElementById: () => container, createElement: () => ({ style: {}, addEventListener() {} }) },
  kakao: { maps: { load: (fn) => { load = fn; },
    Map: class { relayout() { relayouts++; } setBounds() {} setLevel() {} setCenter() {} },
    LatLng: class { constructor(lat, lng) { this.lat = lat; this.lng = lng; } },
    LatLngBounds: class { extend() {} }, CustomOverlay: Overlay, Polyline: Line,
    event: { addListener: (_map, name, callback) => { listeners[name] = callback; } },
  } },
};
context.window = context;
function flush() { let count = 0; while (frames.length) { assert.ok(count++ < 100, 'No render loop'); frames.shift()(); } }
for (const script of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) vm.runInNewContext(script[1], context);
const groups = [{ color: '#4169E1', places: [{ key: 'a', label: '1', lat: 35, lng: 129 }, { key: 'b', label: '2', lat: 35.1, lng: 129.1 }] }];
context.renderPlaces(groups); // Data can arrive before the SDK or layout.
load(); flush(); assert.equal(lines.length, 0);
assert.equal(messages[0].type, 'ready');
container.clientWidth = 390; container.clientHeight = 700;
resized(); flush();
assert.equal(lines.length, 1); assert.equal(lines[0].options.strokeStyle, 'shortdash');
assert.equal(lines[0].points.length, 2); assert.ok(relayouts > 0);
const before = lines[0].refreshes; listeners.idle(); listeners.tilesloaded();
assert.equal(lines[0].refreshes, before + 2);
context.renderPlaces(groups); context.renderPlaces([{ color: '#000', places: [groups[0].places[0]] }]); flush();
assert.equal(lines[0].map, null); assert.equal(lines.length, 1, 'Latest single-place group removes the old line');
process.stdout.write('Itinerary map loading and route redraw checks passed.\n');
