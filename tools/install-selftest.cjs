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
 *   node tools/install-selftest.cjs --config <id>          运行指定配置（默认 selftest）
 *   node tools/install-selftest.cjs --timeout 180          自检超时秒数（默认 180）
 *
 * 流程:
 *   1. 定位 SDK/adb，校验 APK
 *   2. 清 logcat → 卸载旧包（默认）→ adb install -r -t
 *   3. am start -W 启动 MainActivity 并带 --es start <configId>
 *      （Activity 收到 extra 后通过前台服务启动对应运行时实例）
 *   4. 轮询 logcat -d -s NodeJsBox，直到出现 SELFTEST_RESULT=PASS/FAIL 或超时
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
const DEFAULT_APK = path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const LOG_TAG = 'NodeJsBox';

const log = (...a) => console.log('[t3]', ...a);
const warn = (...a) => console.warn('[t3] [WARN]', ...a);
const fail = (...a) => { console.error('[t3] [ERROR]', ...a); process.exit(2); };

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
  const noReinstall = argv.includes('--no-reinstall');
  const ci = argv.indexOf('--config');
  const configId = ci !== -1 ? argv[ci + 1] : 'selftest';
  let cliDevice = null;
  const di = argv.indexOf('--device');
  if (di !== -1) cliDevice = argv[di + 1];
  const ti = argv.indexOf('--timeout');
  const timeoutSec = ti !== -1 ? Number(argv[ti + 1]) : 180;
  const ai = argv.indexOf('--apk');
  const apk = path.resolve(ai !== -1 ? argv[ai + 1] : DEFAULT_APK);

  if (!exists(apk)) fail(`APK 不存在: ${apk}（先运行 .\\gradlew.bat :app:assembleDebug）`);
  const apkMB = (fs.statSync(apk).size / 1024 / 1024).toFixed(1);
  log(`APK: ${apk} (${apkMB} MB)`);

  const sdk = resolveSdk();
  const adb = path.join(sdk, 'platform-tools', 'adb.exe');
  const serial = pickDevice(adb, cliDevice);
  log(`设备: ${serial} · 配置: ${configId}`);
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

  // 3. 强停后冷启动（Activity 收到 start extra → 前台服务启动实例）
  A(['shell', 'am', 'force-stop', PKG]);
  await sleep(500);
  const st = A(['shell', 'am', 'start', '-W', '-n', ACTIVITY, '--es', 'start', configId]);
  if (!st.ok || /Error/i.test(st.stdout)) {
    fail(`am start 失败: ${st.stdout}\n${st.stderr}`);
  }
  log('应用已启动，等待自检结果...');

  // 4. 轮询 logcat
  const deadline = Date.now() + timeoutSec * 1000;
  let result = null;       // PASS / FAIL
  let allLines = [];
  while (Date.now() < deadline) {
    await sleep(3000);
    const lc = A(['logcat', '-d', '-s', LOG_TAG]);
    if (!lc.ok) { warn('logcat 读取失败: ' + lc.stderr); continue; }
    allLines = lc.stdout.split(/\r?\n/).filter(l => l.includes(LOG_TAG));
    for (const l of allLines) {
      const m = l.match(/SELFTEST_RESULT=(PASS|FAIL)/);
      if (m) { result = m[1]; break; }
    }
    if (result) break;
    process.stdout.write('.');
  }
  process.stdout.write('\n');

  if (!result) {
    warn(`超时（${timeoutSec}s）未等到 SELFTEST_RESULT，打印现有 NodeJsBox 日志:`);
    for (const l of allLines) console.log('  ' + l.replace(/^.*NodeJsBox\W*/, ''));
    const runAs = A(['shell', 'run-as', PKG, 'cat', `files/logs/${configId}.log`]);
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
    if (k.startsWith('check.') || k.startsWith('step') || k.startsWith('runtime.') || k.startsWith('device.')) {
      console.log(`  ${k.padEnd(32)} ${v}`);
    }
  }
  console.log('==========================================\n');

  // 佐证: run-as 拉日志文件
  const runAs = A(['shell', 'run-as', PKG, 'cat', `files/logs/${configId}.log`]);
  if (runAs.ok && runAs.stdout) {
    const fileHasDone = /SELFTEST_DONE/.test(runAs.stdout) && /SELFTEST_RESULT/.test(runAs.stdout);
    log(`run-as 日志文件校验: ${fileHasDone ? '完整落盘 ✓' : '内容不完整（疑似写盘异常）'}`);
  } else {
    warn('run-as 拉取日志文件失败: ' + runAs.stderr);
  }

  log(`完整日志已存在 logcat（tag=${LOG_TAG}）。自检 ${result}。`);
  process.exit(result === 'PASS' ? 0 : 1);
}

main().catch(e => { fail('未捕获异常: ' + e.message); process.exit(2); });
