#!/usr/bin/env node
/**
 * 下载 Termux 依赖库 .deb（node 运行时齐套依赖，支持多架构）
 *
 * 作者: GLM-5.3
 * 日期: 2026-09-01
 *
 * 用法:
 *   node tools/fetch-termux-deps.cjs                        下载两个架构的依赖 .deb
 *   node tools/fetch-termux-deps.cjs --arch x86_64          只下载 x86_64
 *   node tools/fetch-termux-deps.cjs --arch aarch64         只下载 aarch64
 *   node tools/fetch-termux-deps.cjs --list                 仅列出仓库中各包的最新版本
 *   node tools/fetch-termux-deps.cjs --with-node            连同 nodejs-lts 主包一起下载
 *
 * 背景:
 *   nodejs-lts_24.18.0-1 的 DT_NEEDED：
 *     libz.so.1 / libcares.so / libsqlite3.so / libcrypto.so.3 / libssl.so.3
 *     libicui18n.so.78 / libicuuc.so.78 / libc++_shared.so (+ 系统 libc/libm/libdl)
 *   control 的 Depends: libc++, openssl, c-ares, libicu, libsqlite, zlib
 *   （libicu 必须是 78.x —— node 二进制按 SONAME .78 链接）
 *
 * 目录约定: _runtime_src/<arch>/*.deb
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const SRC_DIR = path.join(__dirname, '_runtime_src');
const REPO_BASE = 'https://packages.termux.dev/apt/termux-main';
const ARCHS = ['aarch64', 'x86_64'];

// 需要的包名（Termux 仓库中的 Package 名）
const WANTED = ['libc++', 'openssl', 'c-ares', 'libicu', 'libsqlite', 'zlib'];
// libicu SONAME 硬约束: node 24.18.0 链接 libicuuc.so.78 → 必须 78.x
const ICU_MAJOR = 78;

function log(...a) { console.log('[dl]', ...a); }
function warn(...a) { console.warn('[dl] [WARN]', ...a); }
function fail(...a) { console.error('[dl] [ERROR]', ...a); process.exit(1); }

async function fetchBuffer(url) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}: ${url}`);
  return Buffer.from(await res.arrayBuffer());
}

/** 解析 Debian control 格式的 Packages 索引 */
function parsePackages(text) {
  const out = new Map();
  for (const para of text.split(/\n\n+/)) {
    const m = para.match(/^Package:\s*(\S+)/m);
    if (!m) continue;
    const rec = { Package: m[1] };
    for (const line of para.split(/\r?\n/)) {
      const kv = line.match(/^([A-Za-z-]+):\s*(.*)$/);
      if (kv) rec[kv[1]] = kv[2];
    }
    out.set(rec.Package, rec);
  }
  return out;
}

async function downloadArch(arch, index, { onlyList, withNode }) {
  log(`==== 架构 ${arch} ====`);
  const dir = path.join(SRC_DIR, arch);
  fs.mkdirSync(dir, { recursive: true });

  const pkgs = withNode ? ['nodejs-lts', ...WANTED] : WANTED;
  const plan = [];
  let ok = true;
  for (const pkg of pkgs) {
    const rec = index.get(pkg);
    if (!rec) { warn(`  仓库中找不到包 ${pkg}`); ok = false; continue; }
    const ver = rec.Version || '?';
    const file = rec.Filename || '?';
    if (pkg === 'libicu' && !new RegExp(`^${ICU_MAJOR}\\.`).test(ver)) {
      warn(`  libicu 版本为 ${ver}，但 node 需要 ${ICU_MAJOR}.x —— 不匹配！`);
      ok = false;
    }
    // Windows/NTFS: 文件名中的 ':' 是 ADS 分隔符，必须清洗（Debian epoch 版本号含 ':'）
    const safeName = path.basename(file).replace(/:/g, '_');
    log(`  ${pkg.padEnd(10)} ${ver.padEnd(22)} -> ${safeName}`);
    plan.push({ pkg, ver, url: REPO_BASE + '/' + file.replace(/^\//, ''), safeName });
  }
  if (!ok && !onlyList) fail(`架构 ${arch} 存在缺失/不匹配的包，中止。`);

  if (onlyList) return;
  for (const p of plan) {
    const dest = path.join(dir, p.safeName);
    if (fs.existsSync(dest) && fs.statSync(dest).size > 0) {
      log(`  已存在，跳过: ${p.safeName}`);
      continue;
    }
    log(`  下载 ${p.safeName} ...`);
    const buf = await fetchBuffer(p.url);
    fs.writeFileSync(dest, buf);
    log(`    -> ${dest} (${(buf.length / 1024 / 1024).toFixed(2)} MB)`);
  }
  log(`架构 ${arch} 依赖 .deb 就绪。`);
}

async function main() {
  const onlyList = process.argv.includes('--list');
  const withNode = process.argv.includes('--with-node');
  let archs = ARCHS;
  const archIdx = process.argv.indexOf('--arch');
  if (archIdx !== -1 && process.argv[archIdx + 1]) {
    archs = [process.argv[archIdx + 1]];
  }

  const pkgUrls = archs.map(a => `${REPO_BASE}/dists/stable/main/binary-${a}/Packages.gz`);
  log('下载 Packages 索引 ...', pkgUrls.join(' , '));
  const indexes = new Map(); // arch -> Map(pkg -> rec)
  for (let i = 0; i < archs.length; i++) {
    const gz = await fetchBuffer(pkgUrls[i]);
    indexes.set(archs[i], parsePackages(zlib.gunzipSync(gz).toString('utf8')));
  }
  log('索引解析完成', archs.map(a => `${a}:${indexes.get(a).size}包`).join(' '));

  for (const a of archs) {
    await downloadArch(a, indexes.get(a), { onlyList, withNode });
  }
  if (onlyList) log('--list 模式，结束。');
}

main().catch(e => { fail('未捕获异常:', e.message); process.exit(99); });
