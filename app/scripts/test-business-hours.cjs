const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
const context = { exports: {} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync('services/business-hours.ts', 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, context);
const notice = context.exports.businessHoursNotice;
const place = { name: '식당', verified: true, visitTime: '12:00' };
assert.equal(notice(place, '2026-11-23').label, '운영시간 미확인');
const receipt = { status: 'VERIFIED', checkedAt: '2026-11-05T01:00:00Z', visitDate: '2026-11-23', checkedVisitTime: '12:00', basis: 'REGULAR' };
const checked = { ...place, businessHours: receipt };
assert.equal(notice(checked, '2026-11-23').verified, true);
assert.equal(notice(checked, '2026-11-23').label, '운영시간 확인됨.');
assert.match(notice(checked, '2026-11-23').detail, /임시휴무는 미확인/);
assert.equal(notice({ ...checked, visitTime: '15:00' }, '2026-11-23').label, '운영시간 재확인 필요');
assert.equal(notice(checked, '2026-11-24').label, '운영시간 재확인 필요');
assert.equal(notice(checked).verified, false);
assert.equal(notice({ ...checked, note: '메모만 변경' }, '2026-11-23').verified, true);
assert.equal(notice({ ...checked, businessHours: { ...receipt, checkedVisitTime: undefined } }, '2026-11-23').verified, false);
assert.match(notice({ ...checked, businessHours: { ...receipt, basis: 'DATED' } }, '2026-11-23').detail, /방문 날짜·시간 기준/);
assert.equal(notice({ ...place, businessHours: { status: 'UNKNOWN', detail: '조회 실패' } }, '2026-11-23').detail, '조회 실패');
console.log('Business-hours notice checks passed (10 cases).');
