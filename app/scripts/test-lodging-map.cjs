const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.join(__dirname, '../src/lib/lodging-map.ts'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const exportsObject = {};
vm.runInNewContext(compiled, { exports: exportsObject });
const { parseLodgingPlace, buildLodgingMapHtml, lodgingSearchQuery } = exportsObject;
assert.equal(lodgingSearchQuery('부산광역시'),'부산 숙소');
assert.equal(lodgingSearchQuery('경상북도 경주시'),'경주 숙소');
const place = { placeId: '123', name: '숙소', address: '부산', latitude: 35.1, longitude: 129.1 };
assert.equal(parseLodgingPlace(place).placeUrl, 'https://place.map.kakao.com/123');
for (const invalid of [null, {}, { ...place, latitude: NaN }, { ...place, placeId: 'javascript:alert(1)' }, { ...place, address: '' }]) {
  assert.equal(parseLodgingPlace(invalid), null);
}
const messages = [], searches = [], geocodes = [], categories = [], mapClicks = [];
class Position { constructor(latitude, longitude) { this.latitude = latitude; this.longitude = longitude; } getLat() { return this.latitude; } getLng() { return this.longitude; } }
class MapStub { constructor(_element, options) { this.center = options.center; this.level = options.level; } getLevel() { return this.level ?? 5; } setCenter(center) { this.center = center; } setLevel(level) { this.level = level; } setBounds() {} panTo(position) { this.center=position; } relayout() {} addControl() {} getCenter() { return this.center ?? new Position(35, 129); } }
class Marker { setMap() {} }
class InfoWindow { setContent() {} setPosition() {} open() {} close() {} }
const context = { Number, String, setTimeout, clearTimeout, document: { getElementById() {}, createElement() { return { style: {} }; } },
  ReactNativeWebView: { postMessage(value) { messages.push(JSON.parse(value)); } },
  kakao: { maps: { load(fn) { fn(); }, Map: MapStub, LatLng: Position, Marker, InfoWindow,
    LatLngBounds: class { extend() {} }, ZoomControl: class {}, ControlPosition: { RIGHT: 1 },
    event: { addListener(target, event, handler) { if(target instanceof MapStub && event === 'click')mapClicks.push(handler); } }, services: { Status: { OK: 'OK' }, SortBy: { DISTANCE: 'distance' },
      Places: class { keywordSearch(query, callback, options) { searches.push({ query, callback, options }); } categorySearch(code, callback, options) { categories.push({code,callback,options}); } },
      Geocoder: class { addressSearch(query, callback) { geocodes.push({ query, callback }); } coord2Address(lng,lat,callback) { geocodes.push({lng,lat,callback}); } },
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
assert.equal(context.map.getLevel(),3);
assert.equal(context.map.getCenter().getLat(),35.8);
context.searchDestination('없는 숙소');
searches[2].callback([], 'ZERO_RESULT');
assert.equal(messages.at(-1).type, 'selection-error');
assert.equal(mapClicks.length,0,'Lodging selection must keep its category-based behavior');
const transportHtml = buildLodgingMapHtml('public-key', 'transport');
for (const script of transportHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  if (script[1].trim()) vm.runInContext(script[1], context);
}
context.searchDestination('부산역');
assert.equal(searches.at(-1).options.category_group_code, undefined, 'Transport selection must not be restricted to lodging');
searches.at(-1).callback([{ ...hotel, id: '777', place_name: '부산역', category_group_code: 'SW8' }], 'OK');
assert.equal(messages.at(-1).type,'lodging-selected');
assert.equal(messages.at(-1).place.placeId,'777');
context.selectLodging('777');
assert.equal(messages.at(-1).place.name, '부산역');
// Restore actual place candidates within 50m; never create an address-only map point.
assert.equal(mapClicks.length,1);
mapClicks[0]({latLng:new Position(37.57,126.98)});
geocodes.at(-1).callback([{road_address:{building_name:'광화문빌딩',address_name:'서울 종로구 세종대로'}}],'OK');
assert.equal(searches.at(-1).query,'광화문빌딩');
assert.equal(searches.at(-1).options.radius,50);
const building={...hotel,id:'888',place_name:'광화문빌딩',address_name:'서울 종로구 세종대로',category_group_code:'',x:'126.98',y:'37.57'};
searches.at(-1).callback([building,{...building,id:'889',place_name:'60m 밖 건물',y:'37.5706'},{...building,id:'890',place_name:'가까운 가게',y:'37.5702'}],'OK');
assert.equal(messages.at(-1).type,'lodging-results');
assert.equal(messages.at(-1).places.length,2,'Exclude a 60m-away place that the old 150m radius allowed');
assert.equal(messages.at(-1).places[0].name,'광화문빌딩');
context.selectLodging('888');
assert.equal(messages.at(-1).type,'lodging-selected');
assert.equal(parseLodgingPlace(messages.at(-1).place).name,'광화문빌딩');
assert.equal(parseLodgingPlace(messages.at(-1).place).placeId,'888');
mapClicks[0]({latLng:new Position(37.57,126.98)});
const stale=geocodes.at(-1);
mapClicks[0]({latLng:new Position(35.8,128.6)});
stale.callback([{road_address:{building_name:'이전 건물'}}],'OK');
assert.notEqual(searches.at(-1).query,'이전 건물','Discard a previous tap response');
geocodes.at(-1).callback([],'ZERO_RESULT');
assert.equal(categories.length,18);
for(const entry of categories) { assert.equal(entry.options.radius,50); entry.callback([{...hotel,id:'456'}],'OK'); }
assert.equal(messages.at(-1).type,'lodging-results');
assert.equal(messages.at(-1).places.length,1,'Deduplicate categorized results');
context.selectLodging('456');
assert.equal(messages.at(-1).place.name,'대구 호텔');
mapClicks[0]({latLng:new Position(35.8,128.6)});
geocodes.at(-1).callback([],'ZERO_RESULT');
for(const entry of categories.slice(18))entry.callback([],'ZERO_RESULT');
assert.equal(messages.at(-1).type,'selection-error','Do not substitute a raw address when no real places are available');
mapClicks[0]({latLng:new Position(35.8,128.6)});
const previous=geocodes.at(-1);
context.searchDestination('부산역');
previous.callback([],'ZERO_RESULT');
assert.equal(categories.length,36,'A typed search cancels the older tap lookup');
context.showDestinationUserLocation({latitude:35.8,longitude:128.6});
assert.equal(geocodes.at(-1).lat,35.8);
const timeSource = fs.readFileSync(path.join(__dirname, '../src/lib/time-wheel.ts'), 'utf8');
const timeExports = {};
vm.runInNewContext(ts.transpileModule(timeSource, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: timeExports });
assert.deepEqual(Array.from(timeExports.TIME_HOURS), Array.from({ length: 24 }, (_, i) => i));
assert.deepEqual(Array.from(timeExports.TIME_MINUTES), [0, 10, 20, 30, 40, 50]);
assert.equal(timeExports.formatWheelTime(9, 0), '09:00');
assert.equal(timeExports.wheelTimeParts('23:59').minute, 50);
console.log('Lodging message validation, SDK search filtering, selection and search-race checks passed.');

const arrivalHtml = buildLodgingMapHtml('public-key', 'transport', '부산광역시');
for (const script of arrivalHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  if (script[1].trim()) vm.runInContext(script[1], context);
}
assert.equal(searches.at(-1).query, '부산역');
searches.at(-1).callback([{...hotel, id:'800', place_name:'부산역 경부선', category_name:'교통 > 기차역 > KTX정차역', x:'129.041', y:'35.115'}], 'OK');
assert.equal(context.map.getCenter().getLat(),35.115);
assert.equal(context.map.getLevel(),3);
context.map.setCenter(new Position(35.10,129.02));
context.relayoutDestinationMap();
assert.equal(context.map.getCenter().getLat(),35.115,'Layout changes must restore the railway station, not a shifted center');
assert.equal(messages.at(-1).type,'ready');
console.log('Arrival map opens at the representative railway station.');
const yangyangHtml=buildLodgingMapHtml('public-key','transport','강원특별자치도 양양군');
for(const script of yangyangHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g))if(script[1].trim())vm.runInContext(script[1],context);
assert.equal(searches.at(-1).query,'양양역');
searches.at(-1).callback([], 'ZERO_RESULT');
assert.equal(searches.at(-1).query,'양양 터미널');
searches.at(-1).callback([{...hotel,id:'801',place_name:'양양종합여객터미널',category_name:'교통 > 고속,시외버스터미널',x:'128.62',y:'38.07'}], 'OK');
assert.equal(context.map.getCenter().getLat(),38.07);
assert.equal(context.map.getLevel(),3);
const noStationHtml=buildLodgingMapHtml('public-key','transport','경상북도 청송군');
for(const script of noStationHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g))if(script[1].trim())vm.runInContext(script[1],context);
searches.at(-1).callback([], 'ZERO_RESULT');searches.at(-1).callback([], 'ZERO_RESULT');
assert.equal(geocodes.at(-1).query,'경상북도 청송군');
geocodes.at(-1).callback([{x:'129',y:'36'}],'OK');
assert.equal(context.map.getLevel(),8);
const returnHtml = buildLodgingMapHtml('public-key', 'transport', '부산광역시', { latitude: 35.115, longitude: 129.041 });
const geocodesBeforeReturn = geocodes.length;
for (const script of returnHtml.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  if (script[1].trim()) vm.runInContext(script[1], context);
}
assert.equal(context.map.getCenter().getLat(), 35.115);
assert.equal(context.map.getCenter().getLng(), 129.041);
assert.equal(context.map.getLevel(), 3);
assert.equal(geocodes.length, geocodesBeforeReturn);
assert.equal(messages.at(-1).type, 'ready');