#!/usr/bin/env node
/**
 * NodeJsBox 发布流水线：自动准备签名密钥 → 编译 release APK（arm64-v8a / x86_64）
 * → apksigner 校验 → 打印 GitHub Release 上传命令。
 *
 * 作者: Qwen-3.8-Flash
 * 日期: 2026-09-30
 *
 * 用法:
 *   node tools/release-apk.cjs                   全流程（缺密钥则自动生成，产物含双架构分包、双架构通用包）
 *   node tools/release-apk.cjs --skip-universal  不产出双架构通用包（-all.apk）
 *   node tools/release-apk.cjs --skip-build      只准备密钥/检查环境，不编译
 *   node tools/release-apk.cjs --clean           编译前先 gradlew clean
 *   node tools/release-apk.cjs --keystore-dir <dir>   密钥存放目录（默认 ..\keys\nodejsbox）
 *   node tools/release-apk.cjs --alias <name>         密钥别名（默认 nodejsbox）
 *   node tools/release-apk.cjs --pass-from-env        密码从环境变量 NODEJSBOX_RELEASE_PASSWORD 读
 *   node tools/release-apk.cjs --tag v0.2.0           指定 git tag（默认取 versionName）
 *   node tools/release-apk.cjs --dry-run              只打印将要执行的动作，不实际执行
 *
 * 流程:
 *   1. 解析 app/build.gradle 的 versionName（产物命名、默认 tag 都靠它）
 *   2. 密钥准备: local.properties 里 RELEASE_STORE_FILE 指向的 keystore 存在则跳过；
 *      不存在则定位 JDK keytool（JAVA_HOME → PATH），随机生成密码，
 *      keytool -genkeypair 创建，并把 RELEASE_* 四项写回 local.properties
 *   3. 环境检查: jniLibs 是否已解包（缺则提示先跑 fetch-termux-deps + assemble-runtime）
 *   4. 编译: gradlew.bat :app:assembleRelease（ABI 分包由 build.gradle 的 splits 决定）
 *   5. 校验: 用 SDK build-tools 的 apksigner verify --print-certs 逐个检查产物
 *   6. 输出产物清单 + gh release create / git tag 命令（不自动执行上传）
 *
 * 退出码: 0=成功；1=校验失败；2=环境/流程错误
 */

'use strict';

const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const IS_WIN = process.platform === 'win32';

// 定位项目根：向上找含 settings.gradle 的目录（与 install-selftest.cjs 同套路）
function findProjectRoot(startDir) {
  let dir = path.resolve(startDir);
  for (;;) {
    if (fs.existsSync(path.join(dir, 'settings.gradle'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return path.resolve(startDir);
    dir = parent;
  }
}
const PROJECT_ROOT = findProjectRoot(__dirname);
const BUILD_GRADLE = path.join(PROJECT_ROOT, 'app', 'build.gradle');
const LOCAL_PROPS = path.join(PROJECT_ROOT, 'local.properties');
const OUT_DIR = path.join(PROJECT_ROOT, 'app', 'build', 'outputs', 'apk', 'release');

const USAGE = `
用法: node tools/release-apk.cjs [选项]
  --skip-universal       不产出双架构通用包（传 -PskipUniversalApk 给 gradlew）
  --skip-build           只准备密钥 + 检查环境，不执行编译
  --clean                编译前先执行 gradlew clean
  --keystore-dir <dir>   密钥存放目录（默认 <项目同级>/keys/nodejsbox）
  --alias <name>         密钥别名（默认 nodejsbox）
  --pass-from-env        不随机生成密码，改从环境变量 NODEJSBOX_RELEASE_PASSWORD 读
  --tag <name>           GitHub Release 用的 tag（默认 v<versionName>）
  --dry-run              只打印将要执行的动作
  -h, --help             显示本帮助
`;

// ----------------------------- 参数解析 -----------------------------

const args = process.argv.slice(2);
const opts = {
  skipUniversal: false,
  skipBuild: false,
  clean: false,
  keystoreDir: null,
  alias: 'nodejsbox',
  passFromEnv: false,
  tag: null,
  dryRun: false,
};
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  switch (a) {
    case '--skip-universal': opts.skipUniversal = true; break;
    case '--skip-build': opts.skipBuild = true; break;
    case '--clean': opts.clean = true; break;
    case '--pass-from-env': opts.passFromEnv = true; break;
    case '--dry-run': opts.dryRun = true; break;
    case '-h': case '--help': console.log(USAGE); process.exit(0); break;
    case '--keystore-dir': opts.keystoreDir = need(i + 1, a); i++; break;
    case '--alias': opts.alias = need(i + 1, a); i++; break;
    case '--tag': opts.tag = need(i + 1, a); i++; break;
    default:
      console.error(`未知选项: ${a}\n${USAGE}`);
      process.exit(2);
  }
}
function need(idx, flag) {
  const v = args[idx];
  if (!v || v.startsWith('--')) {
    console.error(`${flag} 缺少参数值`);
    process.exit(2);
  }
  return v;
}

function step(msg) { console.log(`\n==> ${msg}`); }
function die(msg, code = 2) { console.error(`\n[错误] ${msg}`); process.exit(code); }

// ----------------------------- ① 解析 versionName -----------------------------

step('读取 app/build.gradle 版本号');
const gradleSrc = fs.readFileSync(BUILD_GRADLE, 'utf8');
const vn = /versionName\s+"([^"]+)"/.exec(gradleSrc);
if (!vn) die('未在 app/build.gradle 中找到 versionName');
const versionName = vn[1];
const tag = opts.tag || `v${versionName}`;
console.log(`    versionName=${versionName}  tag=${tag}`);

// ----------------------------- ② 密钥准备 -----------------------------

step('检查 release 签名配置（local.properties）');
if (!fs.existsSync(LOCAL_PROPS)) die('local.properties 不存在（项目里应有 Android SDK 配置）');
let props = parseProps(fs.readFileSync(LOCAL_PROPS, 'utf8'));

const keystoreDir = opts.keystoreDir
  ? path.resolve(opts.keystoreDir)
  : path.join(path.dirname(PROJECT_ROOT), 'keys', 'nodejsbox');
const keystorePath = path.join(keystoreDir, 'nodejsbox.keystore');

// 归一化用户可能带引号的路径（java Properties 不剥引号，Gradle 会找不到文件）
function stripQuotes(v) { return v.replace(/^"|"$/g, ''); }
for (const k of ['RELEASE_STORE_FILE']) {
  if (props[k]) props[k] = stripQuotes(props[k]);
}

const configuredStore = props.RELEASE_STORE_FILE;
const hasFullConfig = configuredStore && props.RELEASE_STORE_PASSWORD
  && props.RELEASE_KEY_ALIAS && props.RELEASE_KEY_PASSWORD;

if (hasFullConfig && fs.existsSync(configuredStore)) {
  console.log(`    已有密钥，跳过生成: ${configuredStore}`);
} else {
  step('生成签名密钥（keytool）');

  let genKeystoreDir = keystoreDir;
  let genKeystorePath = keystorePath
  if (configuredStore && !fs.existsSync(configuredStore)) {
      console.log(`    local.properties 指向的密钥不存在: ${configuredStore}，将重新生成`);
      genKeystoreDir = path.dirname(configuredStore);
      genKeystorePath = configuredStore;
  }

  const keytool = findKeytool();
  if (!keytool) die('找不到 keytool。请安装 JDK 17+ 并设置 JAVA_HOME（或将 keytool 加入 PATH）');
  console.log(`    keytool: ${keytool}`);

  let password;
  if (opts.passFromEnv) {
    password = process.env.NODEJSBOX_RELEASE_PASSWORD;
    if (!password) die('--pass-from-env 需要设置环境变量 NODEJSBOX_RELEASE_PASSWORD');
  } else if(props.RELEASE_STORE_PASSWORD) {
    password = props.RELEASE_STORE_PASSWORD;
  } else {
    password = genPassword();
  }

  if (opts.dryRun) {
    console.log(`    [dry-run] 将在 ${genKeystoreDir} 生成 nodejsbox.keystore（alias=${opts.alias}）并写回 local.properties`);
  } else {
    fs.mkdirSync(genKeystoreDir, { recursive: true });
    if (fs.existsSync(genKeystorePath)) die(`目标密钥文件已存在但不完整配置: ${genKeystorePath}（避免覆盖，请人工处理）`);
    // keytool 的 -dname 用 CN=NodeJsBox 即可，发布密钥不依赖证书主体
    const r = run(keytool, [
      '-genkeypair', '-v',
      '-keystore', genKeystorePath,
      '-alias', opts.alias,
      '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
      '-storepass', password, '-keypass', password,
      '-dname', 'CN=NodeJsBox, O=NodeJsBox, C=CN',
    ], { cwd: PROJECT_ROOT });
    if (r.status !== 0) die('keytool 生成密钥失败（见上方输出）');

    writeLocalProps({
      RELEASE_STORE_FILE: toForwardSlash(genKeystorePath),
      RELEASE_STORE_PASSWORD: password,
      RELEASE_KEY_ALIAS: opts.alias,
      RELEASE_KEY_PASSWORD: password,
    });
    console.log('');
    console.log('    ⚠ 密码已写入 local.properties（该文件不入库）。请务必备份:');
    console.log(`      密钥文件: ${genKeystorePath}`);
    console.log(`      密码:     ${password}`);
    console.log('    丢了这两个就无法再对同一 applicationId 发布可覆盖安装的更新。');
  }
}

// ----------------------------- ③ 环境检查 -----------------------------

step('检查运行时 jniLibs');
const jniArm = path.join(PROJECT_ROOT, 'app', 'src', 'main', 'jniLibs', 'arm64-v8a', 'libnode.so');
const jniX86 = path.join(PROJECT_ROOT, 'app', 'src', 'main', 'jniLibs', 'x86_64', 'libnode.so');
if (!fs.existsSync(jniArm) || !fs.existsSync(jniX86)) {
  die('缺少 Node 运行时（jniLibs 未解包，注意它不入库）。请先执行:\n' +
      '    node tools/fetch-termux-deps.cjs --with-node\n' +
      '    node tools/assemble-runtime.cjs');
}
console.log('    arm64-v8a / x86_64 libnode.so 均在位');

// ----------------------------- ④ 编译 -----------------------------

if (opts.skipBuild) {
  console.log('\n==> 已按 --skip-build 跳过编译');
} else if (opts.dryRun) {
  console.log('\n==> [dry-run] 将执行: gradlew :app:assembleRelease' + (opts.skipUniversal ? ' -PskipUniversalApk' : ''));
} else {
  step('编译 release APK');
  const gradlew = path.join(PROJECT_ROOT, IS_WIN ? 'gradlew.bat' : 'gradlew');
  if (opts.clean) runGradle(['clean']);
  const buildArgs = [':app:assembleRelease'];
  if (opts.skipUniversal) buildArgs.push('-PskipUniversalApk');
  runGradle(buildArgs);
}

// ----------------------------- ⑤ 校验产物 -----------------------------

const expectedAbis = ['arm64-v8a', 'x86_64'];
const expectApks = expectedAbis.map((abi) => path.join(OUT_DIR, `nodejsbox-v${versionName}-${abi}.apk`));
if (!opts.skipUniversal) expectApks.push(path.join(OUT_DIR, `nodejsbox-v${versionName}-all.apk`));

if (!opts.skipBuild) {
  step('校验产物与签名');
  let missing = 0;
  for (const apk of expectApks) {
    if (!fs.existsSync(apk)) { console.error(`    缺少产物: ${apk}`); missing++; continue; }
    const mb = (fs.statSync(apk).size / 1024 / 1024).toFixed(1);
    console.log(`    ${path.basename(apk)}  (${mb} MB)`);
  }
  if (missing) die(`${missing} 个 APK 未产出，检查 gradle 输出`, 1);

  const apksigner = findApksigner();
  if (apksigner) {
    for (const apk of expectApks) {
      step(`apksigner verify: ${path.basename(apk)}`);
      const r = run(apksigner, ['verify', '--print-certs', apk], { cwd: PROJECT_ROOT, quiet: true });
      if (r.status !== 0) die(`签名校验失败: ${apk}（未配置 release 签名？检查 local.properties）`, 1);
      const cn = /Signer #1 certificate DN: (.+)/.exec(r.stdout || '');
      console.log(`    签名有效 ${cn ? `— ${cn[1].trim()}` : ''}`);
    }
  } else {
    console.log('\n    [提示] 找不到 apksigner（ANDROID_HOME 未设置？），跳过签名校验');
  }
}

// ----------------------------- ⑥ 上传指引 -----------------------------

step('下一步：【如何发布到 GitHub Release ？】');
const apkArgs = expectApks.map((p) => `"${toForwardSlash(p)}"`).join(' \\\n  ');
console.log(`
  # 1) 打 tag 并推送
  git push origin main
  git tag -a ${tag} -m "NodeJsBox ${tag}"
  git push origin ${tag}

  # 2) 用 gh CLI 创建 Release 并上传 APK
  gh release create ${tag} \\
    ${apkArgs} \\
    --title "NodeJsBox ${tag}" \\
    --notes "Node.js 运行时容器 · arm64-v8a 与 x86_64 单架构包"

  # （未装 gh 也可在网页 Releases → Draft a new release 手动拖入上面产物）
`);

// ----------------------------- 工具函数 -----------------------------

function parseProps(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#') || t.startsWith('!')) continue;
    const eq = t.indexOf('=');
    if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1).trim();
  }
  return out;
}

// 写回 local.properties：保留原有内容，RELEASE_* 四项覆盖或追加
function writeLocalProps(values) {
  const text = fs.readFileSync(LOCAL_PROPS, 'utf8');
  const lines = text.split(/\r?\n/);
  const wrote = new Set();
  const merged = lines.map((line) => {
    const eq = line.indexOf('=');
    const key = eq > 0 ? line.slice(0, eq).trim() : null;
    if (key && values[key] !== undefined) {
      wrote.add(key);
      return `${key}=${values[key]}`;
    }
    return line;
  });
  const appended = Object.entries(values).filter(([k]) => !wrote.has(k)).map(([k, v]) => `${k}=${v}`);
  if (appended.length) {
    while (merged.length && merged[merged.length - 1].trim() === '') merged.pop();
    merged.push('', '# release signing (by tools/release-apk.cjs)', ...appended, '');
  }
  fs.writeFileSync(LOCAL_PROPS, merged.join('\n'), 'utf8');
  console.log('    RELEASE_* 配置已写入 local.properties');
}

function genPassword() {
  // 32 位随机密码，只含 properties/shell 都安全的字符
  const charset = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789';
  const bytes = crypto.randomBytes(32);
  let s = '';
  for (const b of bytes) s += charset[b % charset.length];
  return s;
}

function toForwardSlash(p) { return p.replace(/\\/g, '/'); }

function findKeytool() {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const t = path.join(javaHome, 'bin', IS_WIN ? 'keytool.exe' : 'keytool');
    if (fs.existsSync(t)) return t;
  }
  const r = spawnSync(IS_WIN ? 'where' : 'which', ['keytool'], { encoding: 'utf8' });
  if (r.status === 0) {
    const first = r.stdout.split(/\r?\n/).find((l) => l.trim());
    if (first) return first.trim();
  }
  return null;
}

function findApksigner() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) return null;
  const btDir = path.join(sdk, 'build-tools');
  if (!fs.existsSync(btDir)) return null;
  const versions = fs.readdirSync(btDir).sort();
  for (let i = versions.length - 1; i >= 0; i--) {
    const t = path.join(btDir, versions[i], IS_WIN ? 'apksigner.bat' : 'apksigner');
    if (fs.existsSync(t)) return t;
  }
  return null;
}

function run(cmd, cmdArgs, { cwd = PROJECT_ROOT, quiet = false } = {}) {
  const r = spawnSync(cmd, cmdArgs, {
    cwd,
    encoding: 'utf8',
    shell: IS_WIN && /\.(bat|cmd)$/.test(cmd),
    stdio: quiet ? ['ignore', 'pipe', 'pipe'] : 'inherit',
  });
  return r;
}

function runGradle(taskArgs) {
  const gradlew = path.join(PROJECT_ROOT, IS_WIN ? 'gradlew.bat' : 'gradlew');
  step(`gradlew ${taskArgs.join(' ')}`);
  const r = run(gradlew, taskArgs, { cwd: PROJECT_ROOT });
  if (r.status !== 0) die(`gradlew ${taskArgs.join(' ')} 失败（exit ${r.status}）`);
}
