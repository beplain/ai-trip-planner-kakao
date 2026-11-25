const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
function compile(file, requireFn) {
  const context = { exports: {}, require: requireFn };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText, context);
  return context.exports;
}
const helpers = compile('src/lib/flight-selection.ts', () => ({}));
const trains = compile('src/lib/train-selection.ts', () => ({}));
const endDate = (d, n) => { const date = new Date(d + 'T00:00:00Z'); date.setUTCDate(date.getUTCDate() + n - 1); return date.toISOString().slice(0, 10); };
const ui = compile('src/components/travel-details-input.tsx', id => {
  if (id === '@/lib/flight-selection') return helpers;
  if (id === '@/lib/train-selection') return trains;
  if (id === '@/lib/format') return { tripEndDate: endDate };
  return {};
});
const gmp = { placeId: '1', name: '김포국제공항' }, cju = { placeId: '2', name: '제주국제공항' };
const out = { id: 'out', date: '2026-11-26', airline: '항공사', flightNo: 'A1', originAirportId: 'GMP', arrivalAirportId: 'CJU', originAirport: gmp.name, arrivalAirport: cju.name, departureTime: '07:05', arrivalTime: '08:20' };
const back = { ...out, id: 'back', date: '2026-11-28', originAirportId: 'CJU', arrivalAirportId: 'GMP', originAirport: cju.name, arrivalAirport: gmp.name, departureTime: '20:00', arrivalTime: '21:10' };
let t = { mode: 'PLANE', arrivalPlace: cju.name, departurePlace: cju.name, originLocation: gmp, arrivalLocation: cju, departureLocation: cju, returnArrivalLocation: gmp, arrivalAuto: true, returnArrivalAuto: true, ...helpers.selectFlight(out, false), ...helpers.selectFlight(back, true) };
const details = transport => ({ movementMode: 'CAR', stays: [], transport });
assert.equal(ui.travelDetailsError(details(t), 3, out.date), undefined);
assert.equal(helpers.validFlightSelection(t, out.date), true);
assert.equal(helpers.validFlightSelection(t, back.date, true), true);
assert.equal(helpers.validFlightSelection(t, '2026-11-27'), false);
assert.equal(helpers.validFlightSelection({ ...t, arrivalLocation: gmp }, out.date), false);
assert.equal(helpers.validFlightSelection({ ...t, originTime: '08:00' }, out.date), false);
assert.ok(ui.travelDetailsError(details(t), 4, out.date).includes('오는 편'));
assert.ok(ui.travelDetailsError(details({ ...t, flight: undefined }), 3, out.date).includes('가는 편'));
const manual = { ...t, arrivalAuto: false, returnArrivalAuto: false, flight: undefined, returnFlight: undefined };
assert.equal(ui.travelDetailsError(details(manual), 3, out.date), undefined);
assert.ok(ui.travelDetailsError(details({ ...manual, returnArrivalTime: '19:00' }), 3, out.date).includes('늦은 도착'));
assert.ok(ui.travelDetailsError(details({ ...manual, arrivalTime: '06:00' }), 3, out.date).includes('늦어야'));
assert.ok(ui.travelDetailsError(details({ ...manual, arrivalLocation: gmp }), 3, out.date).includes('다르게'));
assert.equal(trains.patchTransport(t, helpers.selectFlight(out, false)).returnFlight.id, 'back');
console.log('Flight selection checks passed: both legs, changed dates/airports/times, manual fallback, and invalid times.');
