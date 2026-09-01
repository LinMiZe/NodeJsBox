'use strict';
/**
 * NodeJsBox 运行时自检脚本（内置）
 *
 * 覆盖常见 node 脚本所需的核心内置能力：
 *   fs（文件读写删）/ crypto（RSA 生成/签名/验签、随机数、摘要）/
 *   zlib（压缩往返）/ net（TCP 回环 listen/connect）
 *
 * 输出格式: key=value 逐行打印，末尾 SELFTEST_DONE（方便 adb 测试时依此判定）。
 */

const os = require('os');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const net = require('net');

const out = (k, v) => console.log(k + '=' + v);

out('selftest.nodeVersion', process.version);
out('selftest.arch', process.arch);
out('selftest.platform', process.platform);
out('selftest.execPath', process.execPath);
out('selftest.pid', process.pid);
out('selftest.osType', os.type());
out('selftest.osRelease', os.release());
out('selftest.homedir', os.homedir());
out('selftest.tmpdir', os.tmpdir());
out('selftest.cpus', os.cpus().length);
out('selftest.freememMB', Math.round(os.freemem() / 1024 / 1024));

let failed = 0;

// ---- 1. fs：写/读/删 ----
try {
  const probe = path.join(os.homedir(), 'selftest-probe.txt');
  fs.writeFileSync(probe, 'nodejsbox-runtime-ok', 'utf8');
  const back = fs.readFileSync(probe, 'utf8');
  fs.unlinkSync(probe);
  out('check.fs.writeReadDelete', back === 'nodejsbox-runtime-ok' ? 'ok' : 'MISMATCH:' + back);
} catch (e) {
  failed++;
  out('check.fs.writeReadDelete', 'EXCEPTION: ' + e.message);
}

// ---- 2. crypto：RSA 2048 生成 + 签名/验签 ----
try {
  const t0 = Date.now();
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  });
  out('check.crypto.rsa2048GenMs', Date.now() - t0);
  const sig = crypto.sign('sha256', Buffer.from('nodejsbox-payload'), privateKey);
  const ok = crypto.verify('sha256', Buffer.from('nodejsbox-payload'), publicKey, sig);
  out('check.crypto.signVerify', ok ? 'ok' : 'FAIL');
  if (!ok) failed++;
  out('check.crypto.randomBytes16', crypto.randomBytes(16).length === 16 ? 'ok' : 'FAIL');
  out('check.crypto.sha256Hex',
    crypto.createHash('sha256').update('nodejsbox').digest('hex').slice(0, 16) + '…');
} catch (e) {
  failed++;
  out('check.crypto', 'EXCEPTION: ' + e.message);
}

// ---- 3. zlib：gzip 往返 ----
try {
  const round = zlib.gunzipSync(zlib.gzipSync(Buffer.from('nodejsbox-zlib-roundtrip', 'utf8'))).toString('utf8');
  out('check.zlib.roundtrip', round === 'nodejsbox-zlib-roundtrip' ? 'ok' : 'MISMATCH:' + round);
} catch (e) {
  failed++;
  out('check.zlib.roundtrip', 'EXCEPTION: ' + e.message);
}

// ---- 4. net：TCP 回环 ----
const server = net.createServer((sock) => {
  sock.on('data', () => sock.end('pong:ok'));
  sock.on('error', () => {});
});
server.on('error', (e) => {
  failed++;
  out('check.net.tcpRoundtrip', 'EXCEPTION: ' + e.message);
  finish();
});
server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  out('check.net.listenPort', port);
  const c = net.connect(port, '127.0.0.1', () => c.write('ping'));
  let got = '';
  c.on('data', (d) => { got += d.toString('utf8'); });
  c.on('error', (e) => {
    failed++;
    out('check.net.tcpRoundtrip', 'EXCEPTION: ' + e.message);
    finish();
  });
  c.on('end', () => {
    out('check.net.tcpRoundtrip', got === 'pong:ok' ? 'ok' : 'MISMATCH:' + got);
    if (got !== 'pong:ok') failed++;
    finish();
  });
});

// ---- 收尾 ----
const timer = setTimeout(() => {
  out('selftest.timeout', '60s 超时未完成');
  process.exit(3);
}, 60000);

let finished = false;
function finish() {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  server.close(() => {});
  out('selftest.failedCount', failed);
  const result = failed === 0 ? 'PASS' : 'FAIL';
  // SELFTEST_RESULT 供 adb 判定；stdout 显式 flush 后再退出，防止管道截断
  process.stdout.write(`SELFTEST_RESULT=${result}\n`, () =>
    process.stdout.write('SELFTEST_DONE\n', () => process.exit(failed === 0 ? 0 : 1)));
}
