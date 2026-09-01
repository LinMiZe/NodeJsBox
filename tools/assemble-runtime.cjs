#!/usr/bin/env node
/**
 * 组装 Node 运行时（解包 .deb → ELF 补丁 → 生成 app/src/main/jniLibs/<abi>/）
 *
 * 作者: GLM-5.3
 * 日期: 2026-09-01
 *
 * 用法:
 *   node tools/assemble-runtime.cjs                      处理两个架构
 *   node tools/assemble-runtime.cjs --arch x86_64        只处理 x86_64
 *   node tools/assemble-runtime.cjs --fresh              删除 _extract 后重新解包
 *
 * 做什么:
 *   1. 解包 _runtime_src/<arch>/*.deb（bsdtar 直接支持 ar/deb 格式）
 *   2. 挑出 node 本体 + 9 个依赖 .so
 *   3. ELF 补丁（纯 Node 实现，无外部依赖）:
 *      Android 安装器只解压名字匹配 lib*.so 的 jniLibs 文件，且 bionic
 *      linker 按 DT_NEEDED 文件名查找。Termux 库带版本后缀（libcrypto.so.3 /
 *      libicuuc.so.78 等）不符合 lib*.so 模式 —— 因此原地改写每个 ELF 的
 *      .dynstr 中 DT_NEEDED / DT_SONAME 字符串（新名 ≤ 旧名，null 填充），
 *      文件名同步改为 lib<名>.so。
 *   4. 依赖闭包校验：每个 NEEDED 必须命中【产物集合 ∪ 系统 bionic 库】
 *   5. （可选）llvm-readelf 交叉验证（找到 NDK 时自动执行）
 *
 * 产物: app/src/main/jniLibs/{arm64-v8a,x86_64}/libnode.so + 9 个依赖库
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

// ----------------------------- 配置 -----------------------------

const ROOT = __dirname;
const SRC_DIR = path.join(ROOT, '_runtime_src');
const EXTRACT_DIR = path.join(SRC_DIR, '_extract');
const JNILIBS_DIR = path.join(ROOT, 'app', 'src', 'main', 'jniLibs');

// Termux usr 前缀（deb 内路径）
const PREFIX = 'data/data/com.termux/files/usr';

// 架构映射: termux arch -> android abi
const ARCH_MAP = { aarch64: 'arm64-v8a', x86_64: 'x86_64' };

// 需要从各包中挑出的文件（deb 内路径 → APK 内最终文件名）
// node 主程序按 Android 惯例命名为 libnode.so
const FILES = [
  { deb: 'nodejs-lts', p: `${PREFIX}/bin/node`,            out: 'libnode.so' },
  { deb: 'openssl',    p: `${PREFIX}/lib/libcrypto.so.3`,  out: 'libcrypto3.so' },
  { deb: 'openssl',    p: `${PREFIX}/lib/libssl.so.3`,     out: 'libssl3.so' },
  { deb: 'c-ares',     p: `${PREFIX}/lib/libcares.so`,     out: 'libcares.so' },
  { deb: 'libicu',     p: `${PREFIX}/lib/libicui18n.so.78`, out: 'libicui18n78.so' },
  { deb: 'libicu',     p: `${PREFIX}/lib/libicuuc.so.78`,  out: 'libicuuc78.so' },
  { deb: 'libicu',     p: `${PREFIX}/lib/libicudata.so.78`, out: 'libicudata78.so' },
  { deb: 'libsqlite',  p: `${PREFIX}/lib/libsqlite3.so`,   out: 'libsqlite3.so' },
  { deb: 'zlib',       p: `${PREFIX}/lib/libz.so.1`,       out: 'libz1.so' },
  { deb: 'libc++',     p: `${PREFIX}/lib/libc++_shared.so`, out: 'libc++_shared.so' },
];

// ELF 动态字符串改名表（旧 SONAME/NEEDED 名 → 新名；新名必须 ≤ 旧名长度）
const RENAME = {
  'libz.so.1':        'libz1.so',
  'libcrypto.so.3':   'libcrypto3.so',
  'libssl.so.3':      'libssl3.so',
  'libicui18n.so.78': 'libicui18n78.so',
  'libicuuc.so.78':   'libicuuc78.so',
  'libicudata.so.78': 'libicudata78.so',
};

// bionic 系统库（/system/lib64 提供，无需打包）
const SYSTEM_LIBS = new Set([
  'libc.so', 'libm.so', 'libdl.so', 'librt.so', 'libpthread.so',
  'ld-android.so', 'liblog.so', 'libandroid.so',
]);

// ----------------------------- 日志 -----------------------------
const log = (...a) => console.log('[asm]', ...a);
const warn = (...a) => console.warn('[asm] [WARN]', ...a);
const fail = (...a) => { console.error('[asm] [ERROR]', ...a); process.exit(1); };

// ----------------------------- ELF64 解析/补丁 -----------------------------

const PT_LOAD = 1, PT_DYNAMIC = 2;
const DT_NULL = 0, DT_NEEDED = 1, DT_STRTAB = 5, DT_SONAME = 14, DT_RPATH = 15, DT_RUNPATH = 29;

/** 解析 ELF64 LE 头与程序头表 */
function parseElf64(buf, fileLabel) {
  if (buf.length < 64) throw new Error(`${fileLabel}: 太小不是 ELF`);
  if (buf[0] !== 0x7f || buf[1] !== 0x45 || buf[2] !== 0x4c || buf[3] !== 0x46) throw new Error(`${fileLabel}: 非 ELF 魔数`);
  if (buf[4] !== 2) throw new Error(`${fileLabel}: 非 ELF64`);
  if (buf[5] !== 1) throw new Error(`${fileLabel}: 非小端`);

  const e_phoff = Number(buf.readBigUInt64LE(0x20));
  const e_phentsize = buf.readUInt16LE(0x36);
  const e_phnum = buf.readUInt16LE(0x38);

  const loads = []; let dyn = null;
  for (let i = 0; i < e_phnum; i++) {
    const off = e_phoff + i * e_phentsize;
    const p_type = buf.readUInt32LE(off);
    const p_offset = Number(buf.readBigUInt64LE(off + 8));
    const p_vaddr = Number(buf.readBigUInt64LE(off + 0x10));
    const p_filesz = Number(buf.readBigUInt64LE(off + 0x20));
    const p_align = Number(buf.readBigUInt64LE(off + 0x30));
    if (p_type === PT_LOAD) loads.push({ p_offset, p_vaddr, p_filesz, p_align });
    if (p_type === PT_DYNAMIC) dyn = { p_offset, p_filesz };
  }
  if (!dyn) throw new Error(`${fileLabel}: 无 PT_DYNAMIC`);
  return { loads, dyn };
}

/** vaddr → 文件偏移 */
function vaddrToOff(loads, vaddr, fileLabel) {
  for (const l of loads) {
    if (vaddr >= l.p_vaddr && vaddr < l.p_vaddr + l.p_filesz) return l.p_offset + (vaddr - l.p_vaddr);
  }
  throw new Error(`${fileLabel}: vaddr 0x${vaddr.toString(16)} 不在任何 PT_LOAD 内`);
}

/** 读取动态段信息: {strtabOff, entries:[{tag,val,off}], maxAlign} */
function readDynamic(buf, elf, fileLabel) {
  const entries = [];
  for (let off = elf.dyn.p_offset; off + 16 <= elf.dyn.p_offset + elf.dyn.p_filesz; off += 16) {
    const tag = Number(buf.readBigInt64LE(off)); // d_tag 有符号
    if (tag === DT_NULL) break;
    entries.push({ tag, val: Number(buf.readBigUInt64LE(off + 8)), off });
  }
  const st = entries.find(e => e.tag === DT_STRTAB);
  if (!st) throw new Error(`${fileLabel}: 无 DT_STRTAB`);
  const strtabOff = vaddrToOff(elf.loads, st.val, fileLabel);
  const maxAlign = Math.max(...elf.loads.map(l => l.p_align));
  return { strtabOff, entries, maxAlign };
}

/** 读取 dynstr 中 offset 处的 null 结尾字符串 */
function readStr(buf, strtabOff, strOff) {
  const start = strtabOff + strOff;
  let end = start;
  while (end < buf.length && buf[end] !== 0) end++;
  return buf.toString('utf8', start, end);
}

/**
 * ELF 补丁: 改写 DT_NEEDED / DT_SONAME / DT_RPATH / DT_RUNPATH 中的库名
 * 返回 { needed, soname, runpath, maxAlign, patched: [{old,new}] }（补丁后的值）
 */
function patchElf(buf, fileLabel) {
  const elf = parseElf64(buf, fileLabel);
  const { strtabOff, entries, maxAlign } = readDynamic(buf, elf, fileLabel);
  const patched = [];

  const rewrite = (strOff, oldStr) => {
    const newStr = RENAME[oldStr];
    if (!newStr) return null;
    if (newStr.length > oldStr.length) throw new Error(`${fileLabel}: 新名 "${newStr}" 比旧名 "${oldStr}" 长，无法原地补丁`);
    const at = strtabOff + strOff;
    buf.write(newStr, at, 'utf8');
    buf.fill(0, at + newStr.length, at + oldStr.length); // null 填充至旧串长度
    patched.push({ old: oldStr, new: newStr });
    return newStr;
  };

  const needed = []; let soname = null, runpath = null;
  for (const e of entries) {
    if (e.tag === DT_NEEDED) {
      const s = readStr(buf, strtabOff, e.val);
      needed.push(rewrite(e.val, s) || s);
    } else if (e.tag === DT_SONAME) {
      const s = readStr(buf, strtabOff, e.val);
      soname = rewrite(e.val, s) || s;
    } else if (e.tag === DT_RPATH || e.tag === DT_RUNPATH) {
      // termux 前缀的 RUNPATH 无害（目录不存在），仅记录不修改
      runpath = readStr(buf, strtabOff, e.val);
    }
  }
  return { needed, soname, runpath, maxAlign, patched };
}

/** 只读分析（校验用），返回补丁前的信息 */
function analyzeElf(buf, fileLabel) {
  const elf = parseElf64(buf, fileLabel);
  const { strtabOff, entries, maxAlign } = readDynamic(buf, elf, fileLabel);
  const needed = []; let soname = null, runpath = null;
  for (const e of entries) {
    if (e.tag === DT_NEEDED) needed.push(readStr(buf, strtabOff, e.val));
    else if (e.tag === DT_SONAME) soname = readStr(buf, strtabOff, e.val);
    else if (e.tag === DT_RPATH || e.tag === DT_RUNPATH) runpath = readStr(buf, strtabOff, e.val);
  }
  return { needed, soname, runpath, maxAlign };
}

// ----------------------------- 解包 .deb -----------------------------

function runTar(args, cwd) {
  const r = spawnSync('tar', args, { cwd, encoding: 'utf8', windowsHide: true, maxBuffer: 10 * 1024 * 1024 });
  // bsdtar 解包含 symlink 的 deb 在 Windows 可能非零退出，但普通文件已解出 —— 调用方自行校验目标文件
  return { ok: r.status === 0, stdout: (r.stdout || ''), stderr: (r.stderr || '').trim() };
}

/** 解包单个 deb: 返回解出的 data 根目录（含 data/data/com.termux/... 树） */
function extractDeb(debPath, workDir, label) {
  const dataMarker = path.join(workDir, PREFIX);
  if (fs.existsSync(dataMarker)) return; // 已解包，跳过
  fs.mkdirSync(workDir, { recursive: true });
  // Windows 路径防歧义
  const deb = path.resolve(debPath);
  let r = runTar(['-xf', deb], workDir);
  if (!fs.existsSync(path.join(workDir, 'data.tar.xz')) && !fs.existsSync(path.join(workDir, 'data.tar.gz'))) {
    // 有时需要 ./ 前缀
    r = runTar(['-xf', '.\\' + path.basename(deb)], workDir);
  }
  const dataTar = ['data.tar.xz', 'data.tar.gz', 'data.tar.zst'].map(n => path.join(workDir, n)).find(p => fs.existsSync(p));
  if (!dataTar) fail(`${label}: 解包 ${path.basename(deb)} 后找不到 data.tar.* (tar stderr: ${r.stderr})`);
  runTar(['-xf', '.\\' + path.basename(dataTar)], workDir);
  if (!fs.existsSync(dataMarker)) fail(`${label}: ${path.basename(deb)} 解包后缺少 ${PREFIX} 目录`);
}

// ----------------------------- llvm-readelf 交叉验证 -----------------------------

function findReadelf() {
  const cands = [];
  if (process.env.NDK_HOME) cands.push(path.join(process.env.NDK_HOME, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin', 'llvm-readelf.exe'));
  const sdk = process.env.ANDROID_HOME;
  if (!sdk) return null;
  const ndkRoot = path.join(sdk, 'ndk');
  if (fs.existsSync(ndkRoot)) {
    for (const v of fs.readdirSync(ndkRoot).sort().reverse()) {
      cands.push(path.join(ndkRoot, v, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin', 'llvm-readelf.exe'));
    }
  }
  return cands.find(p => fs.existsSync(p)) || null;
}

function readelfDynamic(file) {
  const readelf = findReadelf();
  if (!readelf) return null;
  const r = spawnSync(readelf, ['-d', file], { encoding: 'utf8', windowsHide: true, maxBuffer: 10 * 1024 * 1024 });
  if (r.status !== 0) return null;
  const needed = []; let soname = null;
  for (const m of (r.stdout || '').matchAll(/NEEDED.*\[(.+?)\]/g)) needed.push(m[1]);
  const m = (r.stdout || '').match(/SONAME.*\[(.+?)\]/);
  if (m) soname = m[1];
  return { needed, soname };
}

// ----------------------------- 主流程 -----------------------------

function processArch(arch, { fresh }) {
  const abi = ARCH_MAP[arch];
  if (!abi) fail(`未知架构 ${arch}`);
  log(`==== ${arch} → ${abi} ====`);

  const debDir = path.join(SRC_DIR, arch);
  const extDir = path.join(EXTRACT_DIR, arch);
  if (fresh && fs.existsSync(extDir)) fs.rmSync(extDir, { recursive: true, force: true });
  fs.mkdirSync(extDir, { recursive: true });

  // 1. 找 deb（按包名前缀匹配，容忍版本号/epoch 差异）
  const debFiles = fs.existsSync(debDir) ? fs.readdirSync(debDir).filter(f => f.endsWith('.deb')) : [];
  if (debFiles.length === 0) fail(`_runtime_src/${arch}/ 下没有 .deb，先运行 tools/fetch-termux-deps.cjs`);
  const findDeb = (pkg) => {
    // "libc++" → 文件名 "libc++_29_xxx.deb"（deb 文件名里 + 原样保留）
    const hit = debFiles.find(f => f.startsWith(pkg + '_'));
    if (!hit) fail(`_runtime_src/${arch}/ 下找不到包 ${pkg} 的 deb（现有: ${debFiles.join(', ')}）`);
    return path.join(debDir, hit);
  };

  // 2. 解包涉及的包
  const pkgs = [...new Set(FILES.map(f => f.deb))];
  for (const pkg of pkgs) {
    const workDir = path.join(extDir, pkg);
    extractDeb(findDeb(pkg), workDir, `${arch}/${pkg}`);
  }
  log(`已解包 ${pkgs.length} 个 deb: ${pkgs.join(', ')}`);

  // 3. 挑文件 + ELF 补丁 + 落盘 jniLibs
  const outDir = path.join(JNILIBS_DIR, abi);
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });

  const results = []; // {out, needed, soname, align, sizeMB, patched}
  for (const f of FILES) {
    const src = path.join(extDir, f.deb, f.p);
    if (!fs.existsSync(src)) fail(`缺少文件: ${f.deb}:${f.p}`);
    const buf = fs.readFileSync(src);
    const info = patchElf(buf, `${f.deb}:${path.basename(f.p)}`); // 原地补丁
    const dest = path.join(outDir, f.out);
    fs.writeFileSync(dest, buf);
    results.push({
      out: f.out, needed: info.needed, soname: info.soname,
      runpath: info.runpath, align: info.maxAlign, sizeMB: buf.length / 1024 / 1024,
      patched: info.patched,
    });
  }

  // 4. 依赖闭包校验
  const produced = new Set(results.map(r => r.out));
  let ok = true;
  for (const r of results) {
    for (const n of r.needed) {
      if (produced.has(n)) continue;
      if (SYSTEM_LIBS.has(n)) continue;
      // ICU libicudata 之类的名字必须已改
      ok = false;
      warn(`  ${r.out}: NEEDED "${n}" 既不在产物集合也不在系统库白名单！`);
    }
  }
  // 改名表里的旧名字不允许残留
  for (const r of results) {
    for (const old of Object.keys(RENAME)) {
      if (r.needed.includes(old) || r.soname === old) { ok = false; warn(`  ${r.out}: 仍残留旧名 ${old}`); }
    }
  }
  if (!ok) fail(`${arch}: 依赖闭包校验失败`);

  // 5. llvm-readelf 交叉验证（有 NDK 才做）
  const readelf = findReadelf();
  if (readelf) {
    let mismatch = 0;
    for (const r of results) {
      const re = readelfDynamic(path.join(outDir, r.out));
      if (!re) { warn(`  readelf 校验跳过: ${r.out}`); continue; }
      const same = JSON.stringify([...re.needed].sort()) === JSON.stringify([...r.needed].sort())
        && (re.soname || null) === (r.soname || null);
      if (!same) { mismatch++; warn(`  readelf 与内置解析不一致: ${r.out}\n    内置: needed=${r.needed} soname=${r.soname}\n    readelf: needed=${re.needed} soname=${re.soname}`); }
    }
    if (mismatch === 0) log(`llvm-readelf 交叉验证: ${results.length} 个文件全部一致 ✓`);
    else fail(`${arch}: ${mismatch} 个文件 readelf 校验不一致`);
  } else {
    warn('未找到 NDK llvm-readelf，跳过交叉验证');
  }

  // 6. 汇总
  log(`${abi} 产物 (${outDir}):`);
  for (const r of results.sort((a, b) => b.sizeMB - a.sizeMB)) {
    log(`  ${r.out.padEnd(20)} ${r.sizeMB.toFixed(2).padStart(7)} MB  soname=${r.soname || '-'}  align=${(r.align / 1024).toFixed(0)}KB  needed=[${r.needed.join(', ')}]`);
  }
  const totalMB = results.reduce((s, r) => s + r.sizeMB, 0);
  log(`合计 ${results.length} 个文件, ${totalMB.toFixed(1)} MB`);
  return results;
}

function main() {
  const fresh = process.argv.includes('--fresh');
  let archs = Object.keys(ARCH_MAP);
  const i = process.argv.indexOf('--arch');
  if (i !== -1 && process.argv[i + 1]) archs = [process.argv[i + 1]];

  let all = [];
  for (const a of archs) all = all.concat(processArch(a, { fresh }));

  log('');
  log('==== 全部完成 ====');
  log(`输出目录: ${JNILIBS_DIR}`);
  // 16KB 对齐提示（Android 15+ 16KB 页设备需要 LOAD 段 16KB 对齐）
  const badAlign = all.filter(r => r.align < 16384 && r.align > 0);
  if (badAlign.length) {
    warn(`提示: ${badAlign.length} 个产物 LOAD 段对齐为 4KB（Termux 当前按 4KB 构建）。`);
    warn('      4KB 页设备（绝大多数现役手机/模拟器）完全没问题；16KB 页设备（Android 15+ 少数新机型）不兼容。');
  }
}

main();
