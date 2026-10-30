/* global __dirname */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const vm = require('node:vm');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../services/user-message.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, { exports: exportsObject });
const { userMessage } = exportsObject;
for (const raw of [
  "SQL 오류: DELETE FROM travel_db.users WHERE id=1",
  'No static resource api/drafts/private-id/title',
  '인증 실패: JWT token=private-value',
  'EXPO_PUBLIC_API_BASE_URL을 .env.local에 설정해 주세요.',
  "조회 실패: Table travel_db.users doesn't exist",
  '<html>502 Bad Gateway</html>',
  '카카오 로그인 실패. 서버 로그의 KOE 오류코드를 확인해 주세요.',
]) {
  const shown = userMessage(raw);
  assert.ok(!/SQL|travel_db|JWT|private|EXPO|localhost|KOE|html|API|서버/i.test(shown), shown);
}
assert.equal(userMessage('변경 내용을 DB에 저장했어요.'), '변경 내용을 저장했어요.');
assert.equal(userMessage('당일 여행의 출발 시각은 도착 시각보다 늦어야 합니다.'), '당일 여행의 출발 시각은 도착 시각보다 늦어야 합니다.');
assert.ok(userMessage('서버에 OPENAI_API_KEY가 없어 샘플 일정을 만들었습니다.').includes('참고용'));
assert.ok(userMessage('실제 경로 조회 키가 설정되지 않아 직선거리로 동선을 보정했습니다.').includes('대략적인 동선'));
process.stdout.write('User-facing error and saved warning wording checks passed.\n');
