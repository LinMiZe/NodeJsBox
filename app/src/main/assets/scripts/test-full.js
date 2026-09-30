'use strict';
/**
 * test-full.js —— NodeJsBox 容器全量自检
 *
 * 检查范围（每项打印「用什么 → 执行什么」，一律绝对路径）：
 *   A 运行时身份     cwd / HOME / TMPDIR / execPath / 脚本位置
 *   B 关键环境变量
 *   C 内置模块 require
 *   D 全局 Web API（fetch / WebCrypto / WebAssembly）
 *   E 文件系统读写探测（files / cache / nativeLibraryDir / sdcard —— 含「预期被拒」断言）
 *   F exec 能力探测（nativeLibraryDir 可 exec；files 下 exec 预期被 W^X 拒绝）
 *   G package.json / node_modules 解析
 *   H npm：可用性 / 本地安装及落点 / 全局安装及落点（自动清理）
 *   I 网络（dns.lookup / http / https）
 *   J crypto / worker_threads
 *   K 桥接能力（proc.start 动态实例 / echo 子进程 stdin 回显 / proc.log / proc.stop / fs.* / app.*）
 *
 * 输出：
 *   终端 key=value（FULL_RESULT / FULL_DONE 结尾，tool_5 兼容）
 *   HTML 报告: files/reports/test-full.js.out.html（主界面「查看报告」WebView 打开）
 */

const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { createRequire } = require('module');

const report = { sections: [] };
let cur = null;
let failed = 0;

function log(s) { process.stdout.write(s + '\n'); }
function section(title, note) { cur = { title, note: note || '', items: [] }; report.sections.push(cur); log('\n=== ' + title + ' ==='); }

/**
 * 记录一个检查项。
 * status: 'ok' | 'fail' | 'warn' | 'info'（fail 计入判定；warn/info 仅展示）
 * 每项都要求给出「用什么 + 执行什么 + 绝对路径」以便排查。
 */
function record(name, status, detail, cmd) {
  cur.items.push({ name, status, detail: detail || '', cmd: cmd || '' });
  const tag = { ok: '[ OK ]', fail: '[FAIL]', warn: '[WARN]', info: '[ -- ]' }[status];
  const line = `${tag} ${name}  ${detail}`;
  log(line.length > 150 ? line.slice(0, 150) + '…' : line);
}
const ok = (n, d, c) => record(n, 'ok', d, c);
const fail = (n, d, c) => { failed++; record(n, 'fail', d, c); };
const warn = (n, d, c) => record(n, 'warn', d, c);
const info = (n, d, c) => record(n, 'info', d, c);

/** 断言「该路径不可写」——被拒才算通过（符合 targetSdk35 预期） */
function expectDenied(name, target, cmd) {
  try {
    fs.writeFileSync(target, 'x');
    fs.unlinkSync(target);
    record(name, 'warn', `写入成功了！当前环境允许写 ${path.dirname(target)}（targetSdk 28 环境？），记录环境差异`);
  } catch (e) {
    ok(name, `按预期被拒（${e.code || e.message}）`, cmd);
  }
}

/** 断言「该路径可写」 */
function expectWritable(name, dir) {
  const f = path.join(dir, `.writeprobe-${Date.now()}`);
  try {
    fs.writeFileSync(f, 'ok');
    const back = fs.readFileSync(f, 'utf8');
    fs.unlinkSync(f);
    ok(name, back === 'ok' ? `可写（探测文件已清理）` : '内容不一致', `fs.writeFileSync("${f}")`);
  } catch (e) {
    fail(name, `${e.code || e.message}`, `fs.writeFileSync("${f}")`);
  }
}

function main() {
  const HOME = os.homedir();                                  // /data/.../files
  const CWD = process.cwd();                                  // /data/.../files
  const CACHE = os.tmpdir();
  const SCRIPT = path.resolve(__filename);
  const NODE_BIN = process.execPath;
  const NATIVE_DIR = path.dirname(NODE_BIN);
  const NPM_CLI = path.join(CWD, 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js');
  let MOD;
  try { MOD = require('nodejsbox'); } catch (e) {
    MOD = { available: false, error: `nodejsbox 模块加载失败: ${e.message}（NODE_PATH=${process.env.NODE_PATH || '未设置'}）` };
  }

  // ---------- A. 运行时身份 ----------
  section('A. 运行时身份（绝对路径）');
  info('A.cwd',        CWD,                                  'process.cwd()');
  info('A.homedir',    HOME,                                 'os.homedir()');
  info('A.tmpdir',     CACHE,                                'os.tmpdir()');
  info('A.nodeBin',    NODE_BIN,                             'process.execPath');
  info('A.scriptSelf', SCRIPT,                               '本次自检脚本自身路径');
  info('A.scriptDir',  path.dirname(SCRIPT),                 '脚本所在目录');
  info('A.nodeVersion', process.version + ` (v8 ${process.versions.v8}, openssl ${process.versions.openssl})`, 'process.version');
  info('A.uidGid',     (() => { try { return `${process.getuid()}/${process.getgid()}`; } catch { return '不可用'; } })(), 'process.getuid()/getgid()');

  // ---------- B. 关键环境变量 ----------
  section('B. 关键环境变量');
  for (const k of ['HOME', 'TMPDIR', 'NODE_PATH', 'NODEJSBOX_BRIDGE', 'npm_config_prefix', 'npm_config_cache', 'LD_LIBRARY_PATH', 'PATH']) {
    const v = process.env[k];
    if (v === undefined) warn(`B.env.${k}`, '(未设置)', `process.env["${k}"]`);
    else ok(`B.env.${k}`, v, `process.env["${k}"]`);
  }

  // ---------- C. 内置模块 ----------
  section('C. 内置模块 require（node 自带，绝对路径=内部模块）');
  for (const m of ['fs', 'crypto', 'zlib', 'net', 'tls', 'http', 'https', 'dns', 'child_process', 'worker_threads', 'v8', 'vm', 'node:wasi']) {
    try {
      const mod = require(m);
      ok(`C.require.${m}`, `${Object.keys(mod).length} 个导出`, `require("${m}")`);
    } catch (e) { fail(`C.require.${m}`, e.message, `require("${m}")`); }
  }

  // ---------- D. 全局 Web API ----------
  section('D. 全局 Web API');
  try {
    const h = require('crypto').createHash('sha256').update('abc').digest('hex');
    /^[0-9a-f]{64}$/.test(h) ? ok('D.sha256', h.slice(0, 16) + '…', 'crypto.createHash("sha256")') : fail('D.sha256', '摘要格式异常');
  } catch (e) { fail('D.sha256', e.message); }
  try {
    if (!globalThis.crypto?.subtle) throw new Error('crypto.subtle 不可用');
    ok('D.webCrypto', 'crypto.subtle 存在', 'globalThis.crypto.subtle');
  } catch (e) { fail('D.webCrypto', e.message); }
  typeof WebAssembly !== 'undefined' ? ok('D.webAssembly', 'WebAssembly 可用（wasm 包的前提）', 'typeof WebAssembly')
    : warn('D.webAssembly', '不可用');

  // ---------- E. 文件系统读写探测 ----------
  section('E. 文件系统读写探测（每个探测文件用后即删；绝对路径见命令列）');
  expectWritable('E.write.files', CWD);
  expectWritable('E.write.cache', CACHE);
  expectDenied('E.write.nativeLibDir(预期只读)', path.join(NATIVE_DIR, '.write-probe'),
    `fs.writeFileSync("${path.join(NATIVE_DIR, '.write-probe')}")`);
  expectDenied('E.write.rootDir(预期只读)', '/.write-probe', 'fs.writeFileSync("/.write-probe")');
  try {
    const d = '/sdcard/NodeJsBox';
    fs.mkdirSync(d, { recursive: true });
    const f = path.join(d, '.probe-' + Date.now());
    fs.writeFileSync(f, 'ok'); fs.unlinkSync(f);
    ok('E.write.sdcard', '已授权，可直写', `fs.writeFileSync("${f}")`);
  } catch (e) {
    warn('E.write.sdcard', `不可写（${e.code}）——未授「所有文件访问」权限，属可选能力`, `fs.writeFileSync("/sdcard/NodeJsBox/.probe-*")`);
  }
  // nativeLibraryDir 列表（展示有哪些打包二进制可 exec）
  try {
    const so = fs.readdirSync(NATIVE_DIR).filter((f) => f.endsWith('.so'));
    ok('E.list.nativeLibDir', `${so.length} 个 .so: ${so.join(', ').slice(0, 120)}`, `fs.readdirSync("${NATIVE_DIR}")`);
  } catch (e) { fail('E.list.nativeLibDir', e.message); }

  // ---------- F. exec 能力探测 ----------
  section('F. exec 能力探测（W^X：targetSdk≥29 只许 exec nativeLibraryDir）');
  {
    const r = spawnSync(NODE_BIN, ['--version'], { encoding: 'utf8', timeout: 15000 });
    r.status === 0 ? ok('F.exec.nodeBin', `v${(r.stdout || '').trim()}（node 自身就是 nativeLibraryDir 的 libnode.so）`, `spawn("${NODE_BIN}", ["--version"])`)
      : fail('F.exec.nodeBin', (r.stderr || r.stdout || '').trim().split('\n')[0]);
  }
  {
    const r = spawnSync('/system/bin/sh', ['-c', 'echo exec-ok'], { encoding: 'utf8', timeout: 10000 });
    (r.stdout || '').trim() === 'exec-ok' ? ok('F.exec.systemSh', '/system/bin/sh 可执行', `spawn("/system/bin/sh", ["-c", "echo exec-ok"])`)
      : warn('F.exec.systemSh', '不可用: ' + ((r.stderr || '').trim().split('\n')[0] || r.status));
  }
  { // files 下 exec —— targetSdk35 预期 EACCES；若成功说明是 targetSdk28 环境
    const copyDst = path.join(CWD, '.exec-probe-cat');
    try {
      fs.copyFileSync('/system/bin/cat', copyDst);
      const r = spawnSync(copyDst, ['.exec-probe-self'], { cwd: CWD, encoding: 'utf8', timeout: 10000 });
      if (r.error) { ok('F.exec.filesDir', `按预期被拒（${r.error.code || 'EACCES'}，W^X）`, `spawn("${copyDst}")`); }
      else if (r.status === 0) { record('F.exec.filesDir', 'warn', '数据目录可以 exec！当前为 targetSdk≤28 环境或 W^X 未生效'); }
      else { ok('F.exec.filesDir', `按预期失败 code=${r.status}（W^X）`, `spawn("${copyDst}")`); }
    } catch (e) {
      ok('F.exec.filesDir', `按预期被拒（${e.code || e.message}，W^X）`, `spawn("${path.join(CWD, '.exec-probe-cat')}")`);
    } finally {
      try { fs.unlinkSync(copyDst); } catch {}
    }
  }

  // ---------- G. package.json / node_modules 解析 ----------
  section('G. package.json 与模块解析');
  try {
    const nm = path.join(CWD, 'node_modules', 'left-pad');
    fs.mkdirSync(nm, { recursive: true });
    fs.writeFileSync(path.join(nm, 'index.js'), "module.exports=()=>'left-pad-ok'");
    fs.writeFileSync(path.join(nm, 'package.json'), JSON.stringify({ name: 'left-pad', main: 'index.js' }));
    const r = require('left-pad')();
    r === 'left-pad-ok' ? ok('G.resolve', '沿目录链向上找到 files/node_modules 并读取其 package.json', `require("left-pad")（实际解析: ${nm}）`)
      : fail('G.resolve', '返回不符');
  } catch (e) { fail('G.resolve', e.message); }
  info('G.nodePath', process.env.NODE_PATH || '(未设)', 'NODE_PATH（内置桥接模块 require("nodejsbox") 的查找目录）');

  // ---------- H. npm ----------
  section('H. npm（CLI / 本地安装 / 全局安装）');
  if (!fs.existsSync(NPM_CLI)) {
    fail('H.npm.cli', `不存在（跑 tools/install-npm.cjs）`, NPM_CLI);
  } else {
    const npmVer = spawnSync(NODE_BIN, [NPM_CLI, '--version'], { encoding: 'utf8', timeout: 30000 });
    npmVer.status === 0 ? ok('H.npm.cli', `npm ${(npmVer.stdout || '').trim()}`, `spawn("${NODE_BIN}", ["${NPM_CLI}", "--version"])`)
      : fail('H.npm.cli', (npmVer.stderr || '').trim().split('\n')[0]);

    // 本地安装：装进临时项目，验证落点后清理
    const proj = path.join(CWD, '.selftest-npm');
    try {
      fs.rmSync(proj, { recursive: true, force: true });
      fs.mkdirSync(proj, { recursive: true });
      fs.writeFileSync(path.join(proj, 'package.json'), '{}');
      const t0 = Date.now();
      const r = spawnSync(NODE_BIN, [NPM_CLI, 'install', 'is-odd@3.0.1', '--no-audit', '--no-fund', '--ignore-scripts', '--loglevel', 'error'],
        { cwd: proj, encoding: 'utf8', timeout: 180000, maxBuffer: 32 * 1024 * 1024 });
      const dst = path.join(proj, 'node_modules', 'is-odd', 'package.json');
      if (r.status !== 0) fail('H.npm.localInstall', (r.stderr || r.stdout || '').trim().split('\n').slice(-3).join(' | '));
      else if (!fs.existsSync(dst)) fail('H.npm.localInstall', `装完但落点不存在: ${dst}`);
      else {
        const req = createRequire(path.join(proj, 'package.json'));
        const fn = req('is-odd');
        fn(1) === true && fn(2) === false
          ? ok('H.npm.localInstall', `ok(${((Date.now() - t0) / 1000).toFixed(0)}s) 落点已验证且可 require（is-odd(1)=true, is-odd(2)=false）`, `npm install is-odd@3.0.1 → ${path.dirname(path.dirname(dst))}`)
          : fail('H.npm.localInstall', `is-odd 断言不符: is-odd(1)=${fn(1)}, is-odd(2)=${fn(2)}`);
      }
    } catch (e) { fail('H.npm.localInstall', e.message); }

    // 全局安装：落到 npm_config_prefix（files/npm-global），验证后清理
    try {
      const prefix = process.env.npm_config_prefix || path.join(CWD, 'npm-global');
      const r = spawnSync(NODE_BIN, [NPM_CLI, 'install', '-g', 'is-even@1.0.0', '--no-audit', '--no-fund', '--ignore-scripts', '--loglevel', 'error'],
        { encoding: 'utf8', timeout: 180000, maxBuffer: 32 * 1024 * 1024 });
      const dst = path.join(prefix, 'lib', 'node_modules', 'is-even', 'package.json');
      if (r.status !== 0) fail('H.npm.globalInstall', (r.stderr || r.stdout || '').trim().split('\n').slice(-3).join(' | '));
      else if (!fs.existsSync(dst)) fail('H.npm.globalInstall', `装完但落点不存在: ${dst}`);
      else ok('H.npm.globalInstall', `落点已验证（prefix=${prefix}）`, `npm install -g is-even@1.0.0 → ${dst}`);
      fs.rmSync(path.join(prefix, 'lib', 'node_modules', 'is-even'), { recursive: true, force: true });
    } catch (e) { fail('H.npm.globalInstall', e.message); }
    fs.rmSync(proj, { recursive: true, force: true });
  }

  // ---------- I. 网络 ----------
  section('I. 网络');
  try {
    const dns = require('dns');
    dns.promises.lookup('example.com').then((r) => ok('I.dnsLookup', `example.com -> ${r.address}`, 'dns.promises.lookup("example.com")'))
      .catch((e) => fail('I.dnsLookup', e.message));
    // lookup 是异步的，网络节用同步 http 探测 + 等 dns promise（见 finish 前）
    const http = require('http');
    const hr = spawnSync(NODE_BIN, ['-e',
      "require('http').get('http://example.com',{timeout:8000},res=>{let n=0;res.on('data',c=>n+=c.length);res.on('end',()=>console.log('HTTP '+res.statusCode+' '+n+'B'))}).on('error',e=>{console.log('ERR '+e.message);process.exit(1)})"],
      { encoding: 'utf8', timeout: 20000 });
    /HTTP 200/.test(hr.stdout || '') ? ok('I.httpGet', (hr.stdout || '').trim(), `spawn("${NODE_BIN}", ["-e", "http.get example.com"])`)
      : fail('I.httpGet', (hr.stderr || hr.stdout || '').trim().split('\n')[0]);
    const hs = spawnSync(NODE_BIN, ['-e',
      "require('https').get('https://example.com',{timeout:10000},res=>{res.resume();console.log('HTTPS '+res.statusCode)}).on('error',e=>{console.log('ERR '+e.message);process.exit(1)})"],
      { encoding: 'utf8', timeout: 25000 });
    /HTTPS 200/.test(hs.stdout || '') ? ok('I.httpsGet', (hs.stdout || '').trim() + '（证书校验通过）', `spawn("${NODE_BIN}", ["-e", "https.get example.com"])`)
      : warn('I.httpsGet', (hs.stderr || hs.stdout || '').trim().split('\n')[0] || '超时');
  } catch (e) { fail('I.network', e.message); }

  // ---------- J. worker_threads ----------
  section('J. worker_threads');
  try {
    const { Worker } = require('worker_threads');
    const script = "const {parentPort}=require('worker_threads');parentPort.postMessage('worker-alive:'+process.pid)";
    const w = new Worker(script, { eval: true });
    w.on('message', (m) => { ok('J.worker', m, `new Worker("${script.slice(0, 40)}…", {eval:true})`); w.terminate(); });
    w.on('error', (e) => fail('J.worker', e.message));
  } catch (e) { fail('J.worker', e.message); }

  // ---------- K. 桥接（require nodejsbox + 子进程 stdin 回显） ----------
  section('K. 桥接能力（nodejsbox → 容器）');
  if (!MOD.available) {
    fail('K.bridge', MOD.error.replace(/\n/g, ' '));
  } else {
    (async () => {
      try {
        const info = await MOD.app.info();
        ok('K.app.info', `filesDir=${info.filesDir} bridgePort=${info.bridgePort}`, `box.app.info()`);
        await MOD.app.toast('full-test 运行中');
        ok('K.app.toast', '已弹出（无法程序断言，不失败即通过）', 'box.app.toast("full-test 运行中")');

        const scripts = await MOD.fs.listFiles({ dir: 'scripts' });
        ok('K.fs.listFiles', `scripts/ 共 ${(scripts.files || []).length} 个文件`, 'box.fs.listFiles({dir:"scripts"})');

        // 动态实例 + echo stdin 回显
        const echoScript = path.join(CWD, 'scripts', 'test-echo.js');
        if (!fs.existsSync(echoScript)) { fail('K.proc.echo', `test-echo.js 不存在: ${echoScript}`); return finish(); }
        const child = await MOD.proc.start({ name: '自检-echo', script: 'scripts/test-echo.js' });
        ok('K.proc.start', `动态实例 ${child.id}（pid 位置: logs/${child.id}.log）`, `box.proc.start({script:"${echoScript}"})`);
        await new Promise((r) => setTimeout(r, 500));
        await MOD.proc.write(child.id, 'hello-full-test');
        let echoed = '';
        for (let i = 0; i < 10 && !echoed; i++) {
          await new Promise((r) => setTimeout(r, 300));
          const lg = await MOD.proc.log(child.id, { tail: 50 });
          echoed = (lg.lines || []).find((l) => l.includes('echo: hello-full-test')) || '';
        }
        echoed ? ok('K.proc.stdinEcho', `子进程回显: ${echoed}`, `box.proc.write("${child.id}", "hello-full-test") + box.proc.log`)
          : fail('K.proc.stdinEcho', '未在子进程日志中看到回显');
        const list = await MOD.proc.list();
        list.some((x) => x.id === child.id) ? ok('K.proc.list', `列表含 ${child.id}`, 'box.proc.list()')
          : fail('K.proc.list', `未找到 ${child.id}`);
        await MOD.proc.stop(child.id);
        let gone = false;
        for (let i = 0; i < 15 && !gone; i++) { await new Promise((r) => setTimeout(r, 300)); gone = !(await MOD.proc.info(child.id)); }
        gone ? ok('K.proc.stop', `${child.id} 已退出`, `box.proc.stop("${child.id}")`)
          : fail('K.proc.stop', '停止后仍在列表');
      } catch (e) {
        fail('K.bridge', e.message);
      }
      finish();
    })();
    return;
  }
  finish();

  // ---------- 汇总 + HTML 报告 ----------
  function finish() {
    // 给网络异步探测留一拍
    setTimeout(() => {
      const repDir = path.join(CWD, 'reports');
      fs.mkdirSync(repDir, { recursive: true });
      const htmlPath = path.join(repDir, 'test-full.js.out.html');
      fs.writeFileSync(htmlPath, renderHtml(report, failed));
      log('full.reportHtml=' + htmlPath);
      const result = failed === 0 ? 'PASS' : 'FAIL';
      log('full.failedCount=' + failed);
      process.stdout.write(`FULL_RESULT=${result}\n`, () =>
        process.stdout.write('FULL_DONE\n', () => process.exit(failed === 0 ? 0 : 1)));
    }, 1500);
  }
}

function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }

function renderHtml(rep, failedCount) {
  const now = new Date().toLocaleString('zh-CN');
  const sections = rep.sections.map((sec) => {
    const rows = sec.items.map((it) => {
      const cls = { ok: 'ok', fail: 'fail', warn: 'warn', info: 'info' }[it.status];
      const badge = { ok: 'OK', fail: 'FAIL', warn: 'WARN', info: 'INFO' }[it.status];
      return `<tr class="${cls}" data-t="${esc(it.name + ' ' + it.detail + ' ' + it.cmd)}">` +
        `<td><span class="b ${cls}">${badge}</span></td><td>${esc(it.name)}</td>` +
        `<td>${esc(it.detail)}</td><td class="cmd">${esc(it.cmd)}</td></tr>`;
    }).join('\n');
    const bad = sec.items.filter((i) => i.status === 'fail').length;
    return `<h2>${esc(sec.title)}${bad ? ` <span class="b fail">FAIL×${bad}</span>` : ''}</h2>` +
      (sec.note ? `<p class="note">${esc(sec.note)}</p>` : '') +
      `<table><thead><tr><th></th><th>检查项</th><th>结果 / 说明</th><th>执行命令（绝对路径）</th></tr></thead><tbody>${rows}</tbody></table>`;
  }).join('\n');
  return `<!doctype html><html lang="zh"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<title>NodeJsBox 全量自检报告</title><style>
body{font-family:monospace;background:#0b1220;color:#dbe7ff;margin:0;padding:12px;font-size:13px}
h1{font-size:18px;color:#7dd3fc}h2{font-size:15px;color:#a5b4fc;margin:18px 0 6px}
table{border-collapse:collapse;width:100%;margin-bottom:8px}
td,th{border:1px solid #24304d;padding:4px 6px;vertical-align:top;word-break:break-all}
th{background:#111a2e;color:#93c5fd;text-align:left}
tr.ok td:nth-child(3){color:#86efac}.b{padding:1px 6px;border-radius:3px;font-size:11px}
.b.ok{background:#052e16;color:#4ade80}.b.fail{background:#450a0a;color:#f87171}
.b.warn{background:#422006;color:#fbbf24}.b.info{background:#1e293b;color:#94a3b8}
.cmd{color:#67e8f9;font-size:11px}.note{color:#94a3b8;font-size:12px}
#q{width:100%;padding:8px;font-size:14px;background:#111a2e;color:#e2e8f0;border:1px solid #334155;border-radius:6px}
.summary{color:#facc15}
</style></head><body>
<h1>NodeJsBox 全量自检报告</h1>
<p class="summary">生成时间: ${esc(now)} ｜ 失败项: ${failedCount} ｜ 结论: <b>${failedCount === 0 ? 'PASS' : 'FAIL'}</b></p>
<input id="q" placeholder="🔍 输入关键字过滤（状态/检查项/命令…）">
<script>
document.getElementById('q').addEventListener('input', function () {
  var q = this.value.toLowerCase();
  document.querySelectorAll('tr[data-t]').forEach(function (tr) {
    tr.style.display = tr.getAttribute('data-t').toLowerCase().includes(q) ? '' : 'none';
  });
});
</script>
${sections}
</body></html>`;
}

main();
