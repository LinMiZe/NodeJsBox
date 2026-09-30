#!/usr/bin/env node
/**
 * NodeJsBox：把 PC 端 npm（纯 JS，跨平台）安装进容器
 *
 * 作者: GLM-5.3
 * 日期: 2026-09-01
 *
 * 背景: 容器 APK 只打包 libnode.so 本体（termux deb 不含 npm），npm CLI 是纯 JS、
 *       跨平台通用 —— 从 PC 的 Node 安装目录打包推送到设备沙箱解压即可。
 *       安装后位置: files/lib/node_modules/npm，运行方式:
 *         node lib/node_modules/npm/bin/npm-cli.js <cmd>
 *       （全局安装位置已由容器 env 重定向: npm_config_prefix=<files>/npm-global）
 *
 * 用法:
 *   node tools/install-npm.cjs                       自动定位 PC 的 npm 并安装到默认设备
 *   node tools/install-npm.cjs --device emulator-5554 指定设备
 *   node tools/install-npm.cjs --npm-dir <path>       指定 npm 目录（默认自动探测）
 *
 * 退出码: 0=成功；2=环境/流程错误
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const PKG = 'com.nodejsbox.container';
const REMOTE_DIR = 'files/lib/node_modules';
// 定位项目根：从本文件所在目录向上找含 settings.gradle 的目录（兼容仓库根 / tools/ 两种位置）。
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

const log = (...a) => console.log('[install-npm]', ...a);
const warn = (...a) => console.warn('[install-npm] [WARN]', ...a);
const fail = (...a) => { console.error('[install-npm] [ERROR]', ...a); process.exit(2); };

const exists = (p) => { try { return fs.existsSync(p); } catch { return false; } };

function runSync(cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 16 * 1024 * 1024 });
  return { ok: r.status === 0, stdout: (r.stdout || '').trim(), stderr: (r.stderr || '').trim() };
}

// ----------------------------- 定位 npm -----------------------------
function findNpmDir(cliArg) {
  const cands = [cliArg];
  const exe = process.execPath; // <nodeDir>/node.exe 或 nvm 链接
  cands.push(path.join(path.dirname(exe), 'node_modules', 'npm'));
  if (process.env.ANDROID_HOME) cands.push(path.join(process.env.ANDROID_HOME, '..', 'nodejs', 'node_modules', 'npm'));
  for (const c of cands) {
    if (c && exists(path.join(c, 'bin', 'npm-cli.js'))) return c;
  }
  fail('未找到 PC 端 npm 目录（需含 bin/npm-cli.js）。用 --npm-dir 显式指定');
}

function resolveSdk() {
  const cands = [];
  if (process.env.ANDROID_HOME) cands.push(process.env.ANDROID_HOME);
  if (process.env.ANDROID_SDK_ROOT) cands.push(process.env.ANDROID_SDK_ROOT);
  if (process.env.LOCALAPPDATA) cands.push(path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'));
  for (const c of cands) if (exists(path.join(c, 'platform-tools'))) return c;
  fail('未找到 Android SDK（检查 ANDROID_HOME）');
}

function pickDevice(adb, cliDevice) {
  const r = runSync(adb, ['devices']);
  if (!r.ok) fail('adb devices 失败: ' + r.stderr);
  const online = r.stdout.split(/\r?\n/)
    .map((l) => l.match(/^(\S+)\s+device$/)).filter(Boolean).map((m) => m[1]);
  if (cliDevice) {
    if (!online.includes(cliDevice)) fail(`设备 ${cliDevice} 不在线（现有: ${online.join(', ') || '无'}）`);
    return cliDevice;
  }
  const emu = online.find((s) => s.startsWith('emulator-')) || online[0];
  if (!emu) fail('没有在线设备');
  return emu;
}

// ----------------------------- 主流程 -----------------------------
function main() {
  const argv = process.argv.slice(2);
  const di = argv.indexOf('--device');
  const cliDevice = di !== -1 ? argv[di + 1] : null;
  const ni = argv.indexOf('--npm-dir');
  const npmDir = path.resolve(findNpmDir(ni !== -1 ? argv[ni + 1] : null));

  const npmVer = JSON.parse(fs.readFileSync(path.join(npmDir, 'package.json'), 'utf8')).version;
  log(`npm 目录: ${npmDir} (v${npmVer})`);

  const sdk = resolveSdk();
  const adb = path.join(sdk, 'platform-tools', 'adb.exe');
  const serial = pickDevice(adb, cliDevice);
  log(`设备: ${serial}`);
  const A = (args) => runSync(adb, ['-s', serial, ...args]);

  // 1. tar 打包（Windows bsdtar；npm 目录内无 symlink，ustar 足够）
  const tarPath = path.join(PROJECT_ROOT, '.npm.tar.gz');
  log('打包 npm …');
  const tar = runSync('tar', ['--format=ustar', '-czf', tarPath, '-C', path.dirname(npmDir), path.basename(npmDir)]);
  if (!tar.ok) fail('tar 打包失败: ' + tar.stderr);
  log(`打包完成: ${(fs.statSync(tarPath).size / 1048576).toFixed(1)} MB`);

  // 2. push + 沙箱内解压
  log('推送到设备 …');
  const push = A(['push', tarPath, '/data/local/tmp/npm.tar.gz']);
  if (!push.ok) fail('push 失败: ' + push.stderr);

  log('沙箱内解压 …');
  const sh = (script) => A(['shell', `run-as ${PKG} sh -c "${script}"`]);
  if (!sh(`mkdir -p ${REMOTE_DIR}`).ok && !sh(`mkdir -p ${REMOTE_DIR}`).ok) fail('mkdir 失败（app 未运行时 run-as 仍应可用）');
  const untar = A(['shell', `run-as ${PKG} tar -xzf /data/local/tmp/npm.tar.gz -C ${REMOTE_DIR}`]);
  if (!untar.ok) fail('解压失败: ' + untar.stderr);

  // 3. 校验
  const check = A(['shell', `run-as ${PKG} ls ${REMOTE_DIR}/npm/bin/npm-cli.js`]);
  if (!check.ok) fail('安装后校验失败');
  log(`安装完成: files/lib/node_modules/npm (v${npmVer})`);
  log('验证: node tools/install-selftest.cjs --no-reinstall');
}

main();
