#!/usr/bin/env node
/**
 * NodeJsBox 容器 APK 安装 + 运行时自检 + adb 日志分析（无 UI 交互测试）
 *
 * 作者: GLM-5.3
 * 日期: 2026-09-01
 *
 * 用法:
 *   node tools/install-selftest.cjs                       构建产物默认路径，自动选设备
 *   node tools/install-selftest.cjs --device emulator-5554 指定设备
 *   node tools/install-selftest.cjs --apk <path>           指定 APK
 *   node tools/install-selftest.cjs --no-reinstall         不重装（调试重复运行）
 *   node tools/install-selftest.cjs --script <路径>        运行指定脚本（默认 scripts/test-full.js）
 *   node tools/install-selftest.cjs --timeout 180          自检超时秒数（默认 180）
 *
 * 流程:
 *   1. 定位 SDK/adb，校验 APK
 *   2. 清 logcat → 卸载旧包（默认）→ adb install -r -t
 *   3. am start -W 启动 MainActivity 并带 --es run <脚本路径>
 *      （Activity 收到 extra 后通过前台服务拉起 dyn-script-* 动态实例）
 *   4. 轮询 logcat -d -s NodeJsBox，直到出现 selftest_RESULT=PASS/FAIL 或超时
 *      （实例日志格式: [NB:<id>] <line>）
 *   5. 解析 key=value 行，输出结构化结果；附 run-as 拉取的日志文件佐证
 *
 * 退出码: 0=自检 PASS；1=自检 FAIL；2=环境/流程错误
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const PKG = 'com.nodejsbox.container';
const ACTIVITY = `${PKG}/.MainActivity`;
// 定位项目根：从本文件所在目录向上找含 settings.gradle 的目录。
// （脚本可能位于仓库根或 tools/ 子目录，两种位置都要能正确解析相对路径）
function findProjectRoot(startDir) {
  let dir = path.resolve(startDir);
  for (;;) {
    if (fs.existsSync(path.join(dir, 'settings.gradle'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return path.resolve(startDir);
}
const PROJECT_ROOT = findProjectRoot(__dirname);
// debug 产物名受 build.gradle 的 ABI 分包 + outputFileName 影响（nodejsbox-v<版本>-<abi>-debug.apk），
// 这里从 build.gradle 解析 versionName 拼出默认路径；默认安装 release 包，找不到则回退旧命名 app-debug.apk
function defaultDebugApk() {
  const debugDir = path.join(PROJECT_ROOT, 'app', 'build', 'outputs', 'apk', 'release');
  try {
    const src = fs.readFileSync(path.join(PROJECT_ROOT, 'app', 'build.gradle'), 'utf8');
    const vn = /versionName\s+"([^"]+)"/.exec(src);
    if (vn) {
      const named = path.join(debugDir, `nodejsbox-v${vn[1]}-all.apk`);
      if (fs.existsSync(named)) return named;
    }
  } catch { /* ignore */ }
  return path.join(debugDir, 'app-debug.apk');
}
const DEFAULT_APK = defaultDebugApk();
const LOG_TAG = 'NodeJsBox';

const USAGE = `
用法: node tools/install-selftest.cjs [选项]
  --script <路径>      运行指定脚本（内置: scripts/test-full.js（默认）、scripts/test-bridge.js、
                      scripts/hello.js；也可传其它 files/ 相对路径）
  --device <serial>   指定 adb 设备（默认自动选第一个模拟器）
  --apk <path>        指定 APK（默认 app/build/outputs/apk/debug/app-debug.apk）
  --no-reinstall      跳过卸载重装（调试期快速复跑；注意重装会清空沙箱，npm 需重跑 'tools/install-npm.cjs' ）
  --timeout <秒>      等待结果标记的超时（默认 180）
  --marker <NAME>     指定结果标记名（默认自动扫描任意 <NAME>_RESULT=PASS/FAIL）
  --help, -h          打印本说明

结果判定协议（被测脚本须遵守）:
  stdout 末尾输出  <标记>_RESULT=PASS 或 FAIL，随后输出 <标记>_DONE
  容器会把脚本 stdout 逐行打进 logcat(tag=${LOG_TAG}) 与 files/logs/<id>.log

快速失败:
  被测实例进程退出后 6s 内仍未等到结果标记 → 立即终止并 dump 日志（不傻等超时）
`.trim();


const log = (...a) => console.log('[install-selftest]', ...a);
const warn = (...a) => console.warn('[install-selftest] [WARN]', ...a);
const fail = (...a) => { console.error('[install-selftest] [ERROR]', ...a); process.exit(2); };

const exists = p => { try { return fs.existsSync(p); } catch { return false; } };
const sleep = ms => new Promise(r => setTimeout(r, ms));

function runSync(cmd, args, opts = {}) {
  try {
    const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 20 * 1024 * 1024, ...opts });
    return { ok: r.status === 0, code: r.status, stdout: (r.stdout || '').trim(), stderr: (r.stderr || '').trim() };
  } catch (e) {
    return { ok: false, code: -1, stdout: '', stderr: String(e.message || e) };
  }
}

// ----------------------------- SDK 定位 -----------------------------
function resolveSdk() {
  const cands = [];
  if (process.env.ANDROID_HOME) cands.push(process.env.ANDROID_HOME);
  if (process.env.ANDROID_SDK_ROOT) cands.push(process.env.ANDROID_SDK_ROOT);
  if (process.env.LOCALAPPDATA) cands.push(path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'));
  for (const c of cands) {
    if (exists(path.join(c, 'platform-tools'))) return c;
  }
  fail('未找到 Android SDK（检查 ANDROID_HOME）');
}

function pickDevice(adb, cliDevice) {
  const r = runSync(adb, ['devices']);
  if (!r.ok) fail('adb devices 失败: ' + r.stderr);
  const list = [];
  for (const line of r.stdout.split(/\r?\n/)) {
    if (/^List of devices/i.test(line)) continue;
    const m = line.match(/^(\S+)\s+(\S+)/);
    if (m) list.push({ serial: m[1], state: m[2] });
  }
  const online = list.filter(d => d.state === 'device');
  if (cliDevice) {
    if (!online.some(d => d.serial === cliDevice)) fail(`设备 ${cliDevice} 不在线（现有: ${JSON.stringify(list)}）`);
    return cliDevice;
  }
  const emu = online.filter(d => d.serial.startsWith('emulator-'));
  if (emu.length) return emu[0].serial;
  if (online.length) return online[0].serial;
  fail('没有在线设备。请先启动模拟器');
}

// ----------------------------- 主流程 -----------------------------
async function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) { console.log('\n' + USAGE + '\n'); return; }
  const noReinstall = argv.includes('--no-reinstall');
  const si = argv.indexOf('--script');
  const scriptPath = si !== -1 ? argv[si + 1] : 'scripts/test-full.js';
  // --es run 走 RuntimeManager.startScript：实例 id 固定为 dyn-script-<去扩展名文件名>
  const instanceId = 'dyn-script-' + path.basename(scriptPath).replace(/\.js$/, '');
  let cliDevice = null;
  const di = argv.indexOf('--device');
  if (di !== -1) cliDevice = argv[di + 1];
  const ti = argv.indexOf('--timeout');
  const timeoutSec = ti !== -1 ? Number(argv[ti + 1]) : 180;
  const ai = argv.indexOf('--apk');
  const apk = path.resolve(ai !== -1 ? argv[ai + 1] : DEFAULT_APK);
  // 结果标记: --marker 显式指定只认该标记；否则自动扫描任意 <NAME>_RESULT=PASS/FAIL
  // （扫描脚本 stdout 里最后一次出现的标记，天然支持 selftest/BRIDGE/未来新标记）
  const mi = argv.indexOf('--marker');
  const MARKER = mi !== -1 ? argv[mi + 1] : null;
  const resultRe = MARKER
    ? new RegExp(MARKER + '_RESULT=(PASS|FAIL)')
    : /\b([A-Z][A-Z0-9_]*)_RESULT=(PASS|FAIL)\b/;

  if (!exists(apk)) fail(`APK 不存在: ${apk}（先运行 .\\gradlew.bat :app:assembleDebug）`);
  const apkMB = (fs.statSync(apk).size / 1024 / 1024).toFixed(1);
  log(`APK: ${apk} (${apkMB} MB)`);

  const sdk = resolveSdk();
  const adb = path.join(sdk, 'platform-tools', 'adb.exe');
  const serial = pickDevice(adb, cliDevice);
  log(`设备: ${serial} · 脚本: ${scriptPath} (实例 id: ${instanceId})`);
  const A = (args) => runSync(adb, ['-s', serial, ...args]);

  // 1. 清空 logcat
  A(['logcat', '-c']);
  log('logcat 已清空');

  // 2. 安装
  if (!noReinstall) {
    const un = A(['shell', 'pm', 'uninstall', PKG]);
    if (un.ok) log('旧包已卸载');
    log('安装中（较大，请稍候）...');
    const inst = A(['install', '-r', '-t', apk]);
    if (!inst.ok || !/Success/.test(inst.stdout)) {
      fail(`安装失败:\nstdout=${inst.stdout}\nstderr=${inst.stderr}`);
    }
    log('安装成功');
  } else {
    log('--no-reinstall: 跳过安装');
  }

  // 3. 强停后冷启动（Activity 收到 run extra → 前台服务拉起动态实例）
  A(['shell', 'am', 'force-stop', PKG]);
  await sleep(500);
  const st = A(['shell', 'am', 'start', '-W', '-n', ACTIVITY, '--es', 'run', scriptPath]);
  if (!st.ok || /Error/i.test(st.stdout)) {
    fail(`am start 失败: ${st.stdout}\n${st.stderr}`);
  }
  log('应用已启动，等待自检结果...');

  // 4. 轮询 logcat
  const deadline = Date.now() + timeoutSec * 1000;
  let result = null;       // PASS / FAIL
  let markerName = MARKER; // 实际匹配到的标记名（自动模式时回填）
  let exitedAt = 0;        // 首次看到被测实例退出的时刻（ms，0=未退出）
  const EXIT_GRACE_MS = 6000; // 退出后再给 6s 宽限拿结果标记，仍没有 → 快速失败
  let allLines = [];
  const exitRe = new RegExp(`\\[NB:${instanceId}\\] ---- 进程退出 code=(-?\\d+)`);
  while (Date.now() < deadline) {
    await sleep(3000);
    const lc = A(['logcat', '-d', '-s', LOG_TAG]);
    if (!lc.ok) { warn('logcat 读取失败: ' + lc.stderr); continue; }
    allLines = lc.stdout.split(/\r?\n/).filter(l => l.includes(LOG_TAG));
    for (const l of allLines) {
      const m = l.match(resultRe);
      if (m) { markerName = MARKER || m[1]; result = m[2]; break; }
    }
    if (result) break;
    // 被测实例退出检测：非零退出立即失败；code=0 给宽限期（脚本可能正常结束但漏打标记）
    for (const l of allLines) {
      const m = l.match(exitRe);
      if (m && !exitedAt) {
        exitedAt = Date.now();
        const code = Number(m[1]);
        if (code !== 0) { warn(`实例提前退出 code=${code}（未等到结果标记）`); exitedAt -= EXIT_GRACE_MS; }
        break;
      }
    }
    if (exitedAt && Date.now() - exitedAt >= EXIT_GRACE_MS) break;
    process.stdout.write('.');
  }
  process.stdout.write('\n');

  if (!result) {
    warn(exitedAt ? `实例已退出且 ${EXIT_GRACE_MS / 1000}s 内未等到结果标记（脚本崩溃或漏打 <标记>_RESULT），打印日志:` :
      `超时（${timeoutSec}s）未等到结果标记，打印现有 NodeJsBox 日志:`);
    for (const l of allLines) console.log('  ' + l.replace(/^.*NodeJsBox\W*/, ''));
    const runAs = A(['shell', 'run-as', PKG, 'cat', `files/logs/${instanceId}.log`]);
    if (runAs.ok && runAs.stdout) {
      warn('run-as 日志文件内容:');
      for (const l of runAs.stdout.split(/\r?\n/)) console.log('  ' + l);
    }
    fail('自检未完成（超时）');
  }

  // 5. 解析结果（剥离 logcat 前缀与 [NB:<id>] 实例前缀）
  const kv = {};
  for (const l of allLines) {
    const cleaned = l.replace(/^.*?: /, '').replace(/^\[NB:\S+\]\s*/, '');
    const m = cleaned.match(/^([A-Za-z0-9_.]+)=(.*)$/);
    if (m) kv[m[1]] = m[2];
  }

  console.log('\n================ 自检结果 ================');
  console.log(`结论        : ${result === 'PASS' ? '✓ PASS' : '✗ FAIL'}`);
  console.log(`node 版本   : ${kv['selftest.nodeVersion'] || '(未见输出)'}`);
  console.log(`arch        : ${kv['selftest.arch'] || '?'} / 平台 ${kv['selftest.platform'] || '?'}`);
  for (const [k, v] of Object.entries(kv)) {
    if (/^(check\.|step|runtime\.|device\.|h5\.|full\.)/.test(k)) {
      console.log(`  ${k.padEnd(32)} ${v}`);
    }
  }
  console.log('==========================================\n');

  // 佐证: run-as 拉日志文件
  const runAs = A(['shell', 'run-as', PKG, 'cat', `files/logs/${instanceId}.log`]);
  if (runAs.ok && runAs.stdout) {
    const fileHasDone = runAs.stdout.includes(markerName + '_DONE') && runAs.stdout.includes(markerName + '_RESULT');
    log(`run-as 日志文件校验: ${fileHasDone ? '完整落盘 ✓' : '内容不完整（疑似写盘异常）'}`);
  } else {
    warn('run-as 拉取日志文件失败: ' + runAs.stderr);
  }

  log(`完整日志已存在 logcat（tag=${LOG_TAG}）。自检 ${result}。`);
  process.exit(result === 'PASS' ? 0 : 1);
}

main().catch(e => { fail('未捕获异常: ' + e.message); process.exit(2); });
