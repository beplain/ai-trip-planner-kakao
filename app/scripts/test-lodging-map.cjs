/* global __dirname */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.join(__dirname, '../src/lib/lodging-map.ts'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const exportsObject = {};
vm.runInNewContext(compiled, { exports: exportsObject });
const { parseLodgingPlace, buildLodgingMapHtml } = exportsObject;
const place = { placeId: '123', name: '숙소', address: '부산', latitude: 35.1, longitude: 129.1 };
assert.equal(parseLodgingPlace(place).placeUrl, 'https://place.map.kakao.com/123');
for (const invalid of [null, {}, { ...place, latitude: NaN }, { ...place, placeId: 'javascript:alert(1)' }, { ...place, address: '' }]) {
  assert.equal(parseLodgingPlace(invalid), null);
}
const messages = [], searches = [];
class Position { constructor(latitude, longitude) { this.latitude = latitude; this.longitude = longitude; } }
class MapStub { setCenter() {} setLevel() {} setBounds() {} panTo() {} addControl() {} getCenter() { return new Position(35, 129); } }
class Marker { setMap() {} }
class InfoWindow { setContent() {} setPosition() {} open() {} close() {} }
const context = { Number, String, document: { getElementById() {}, createElement() { return { style: {} }; } },
  ReactNativeWebView: { postMessage(value) { messages.push(JSON.parse(value)); } },
  kakao: { maps: { load(fn) { fn(); }, Map: MapStub, LatLng: Position, Marker, InfoWindow,
    LatLngBounds: class { extend() {} }, ZoomControl: class {}, ControlPosition: { RIGHT: 1 },
    event: { addListener() {} }, services: { Status: { OK: 'OK' }, SortBy: { DISTANCE: 'distance' },
      Places: class { keywordSearch(query, callback, options) { searches.push({ query, callback, options }); } },
    },
  } },
};
context.window = context;
vm.createContext(context);
const html = buildLodgingMapHtml('public-key');
for (const script of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  if (script[1].trim()) vm.runInContext(script[1], context);
}
assert.equal(messages.at(-1).type, 'ready');
context.searchDestination('부산 호텔');
assert.equal(searches[0].options.category_group_code, 'AD5');
context.searchDestination('대구 호텔');
const hotel = { id: '456', place_name: '대구 호텔', category_group_code: 'AD5', address_name: '대구 수성구', road_address_name: '', x: '128.6', y: '35.8' };
searches[0].callback([hotel], 'OK');
assert.equal(messages.at(-1).type, 'resolving', 'Older search results must be discarded');
searches[1].callback([hotel, { ...hotel, id: '999', category_group_code: 'FD6' }], 'OK');
assert.equal(messages.at(-1).type, 'lodging-results');
assert.equal(messages.at(-1).places.length, 1, 'Only lodging results may be selected');
context.selectLodging('999');
assert.equal(messages.at(-1).type, 'lodging-results');
context.selectLodging('456');
assert.equal(messages.at(-1).type, 'lodging-selected');
assert.equal(parseLodgingPlace(messages.at(-1).place).name, '대구 호텔');
context.searchDestination('없는 숙소');
searches[2].callback([], 'ZERO_RESULT');
assert.equal(messages.at(-1).type, 'selection-error');
const transportHtml = buildLodgingMapHtml('public-key', 'transport');
for (const script of transportHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  if (script[1].trim()) vm.runInContext(script[1], context);
}
context.searchDestination('부산역');
assert.equal(searches.at(-1).options.category_group_code, undefined, 'Transport selection must not be restricted to lodging');
searches.at(-1).callback([{ ...hotel, id: '777', place_name: '부산역', category_group_code: 'SW8' }], 'OK');
context.selectLodging('777');
assert.equal(messages.at(-1).place.name, '부산역');
const timeSource = fs.readFileSync(path.join(__dirname, '../src/lib/time-wheel.ts'), 'utf8');
const timeExports = {};
vm.runInNewContext(ts.transpileModule(timeSource, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: timeExports });
assert.deepEqual(Array.from(timeExports.TIME_HOURS), Array.from({ length: 24 }, (_, i) => i));
assert.deepEqual(Array.from(timeExports.TIME_MINUTES), [0, 10, 20, 30, 40, 50]);
assert.equal(timeExports.formatWheelTime(9, 0), '09:00');
assert.equal(timeExports.wheelTimeParts('23:59').minute, 50);
console.log('Lodging message validation, SDK search filtering, selection and search-race checks passed.');
