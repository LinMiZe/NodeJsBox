'use strict';
/**
 * NodeJsBox 桥接能力自检（内置）—— 验证 require('nodejsbox') 全链路
 *
 * 覆盖（无需用户交互的部分）：
 *   app.info / app.toast / proc.list / proc.start（动态拉起 test-echo.js）/
 *   proc.write（stdin 注入）/ proc.log（读子进程输出，验证回显）/
 *   proc.stop / fs.listFiles
 *
 * 输出格式: key=value 逐行；末尾 BRIDGE_RESULT=PASS/FAIL + BRIDGE_DONE
 * （fs.pick / fs.export 需要用户在系统文件管理器里操作，不在自动自检范围）
 */

const box = require('nodejsbox');
if (!box.available) {
  console.log('bridge.error=' + box.error.replace(/\n/g, ' '));
  console.log('BRIDGE_RESULT=FAIL');
  process.exit(1);
}

let failed = 0;
const out = (k, v) => console.log(k + '=' + v);

async function main() {
  // 1. app.info
  const info = await box.app.info();
  out('check.app.info.nodeBin', info.nodeBin ? 'ok' : 'MISSING');
  out('check.app.info.scripts', Array.isArray(info.scripts) ? info.scripts.length : -1);

  // 2. toast（结果无法程序断言，不失败即通过）
  await box.app.toast('NodeJsBox 桥接 OK');
  out('check.app.toast', 'ok');

  // 3. proc.start：动态拉起 test-echo.js
  const child = await box.proc.start({ name: '桥自检-echo', script: 'scripts/test-echo.js' });
  out('check.proc.start.id', child.id);
  out('check.proc.start.dynamic', child.dynamic === true ? 'ok' : 'FAIL');
  if (child.dynamic !== true) failed++;

  // 4. proc.write + proc.log：注入 stdin，等回显落日志
  await sleep(500);
  await box.proc.write(child.id, 'hello-bridge');
  let echoed = '';
  for (let i = 0; i < 10 && !echoed; i++) {
    await sleep(300);
    const log = await box.proc.log(child.id, 50);
    echoed = (log.lines || []).find((l) => l.includes('echo: hello-bridge')) || '';
  }
  out('check.proc.stdinEcho', echoed ? 'ok' : 'FAIL');
  if (!echoed) failed++;

  // 5. proc.list 应包含子实例
  const list = await box.proc.list();
  out('check.proc.list', list.some((x) => x.id === child.id) ? 'ok' : 'FAIL');
  if (!list.some((x) => x.id === child.id)) failed++;

  // 6. proc.stop
  await box.proc.stop(child.id);
  let gone = false;
  for (let i = 0; i < 15 && !gone; i++) {
    await sleep(300);
    gone = !(await box.proc.info(child.id));
  }
  out('check.proc.stop', gone ? 'ok' : 'FAIL');
  if (!gone) failed++;

  // 7. fs.listFiles
  const scripts = await box.fs.listFiles({ dir: 'scripts' });
  const hasEcho = (scripts.files || []).some((f) => f.name === 'test-echo.js');
  out('check.fs.listFiles', hasEcho ? 'ok' : 'FAIL');
  if (!hasEcho) failed++;

  // 8. 未知命令应返回明确错误
  try {
    await box.call('nope.nope', {});
    out('check.unknownCmd', 'FAIL(未抛错)');
    failed++;
  } catch (e) {
    out('check.unknownCmd', String(e.message).includes('未知命令') ? 'ok' : 'FAIL:' + e.message);
    if (!String(e.message).includes('未知命令')) failed++;
  }

  out('bridge.failedCount', failed);
  const result = failed === 0 ? 'PASS' : 'FAIL';
  process.stdout.write(`BRIDGE_RESULT=${result}\n`, () =>
    process.stdout.write('BRIDGE_DONE\n', () => process.exit(failed === 0 ? 0 : 1)));
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

main().catch((e) => {
  console.log('bridge.exception=' + (e && e.message ? e.message : String(e)));
  console.log('BRIDGE_RESULT=FAIL');
  process.exit(1);
});
