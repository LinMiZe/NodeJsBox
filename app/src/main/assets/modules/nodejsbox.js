'use strict';
/**
 * NodeJsBox 内置桥接模块（容器首次启动解包到 filesDir/modules/，升级时覆盖）
 *
 * 用法（容器内任意脚本，NODE_PATH 已指向 modules 目录）：
 *   const box = require('nodejsbox');
 *   await box.app.toast('你好');
 *   const info = await box.proc.start({ script: 'scripts/hello.js' });
 *   await box.proc.write(info.id, 'hi');           // 向子进程 stdin 写一行
 *   const { lines } = await box.proc.log(info.id); // 读子进程日志尾部
 *   const picked = await box.fs.pick({ mime: 'text/*' }); // 调系统文件管理器
 *
 * 协议：与容器 BridgeServer 的本地 TCP JSON-lines（连接复用，断线自动重连）。
 * 连接参数来自环境变量 NODEJSBOX_BRIDGE / NODEJSBOX_TOKEN（容器自动注入）。
 */

const net = require('net');
const EventEmitter = require('events');

const BRIDGE = process.env.NODEJSBOX_BRIDGE || '';
const TOKEN = process.env.NODEJSBOX_TOKEN || '';

if (!BRIDGE || !TOKEN) {
  const hint =
    'nodejsbox: 缺少环境变量 NODEJSBOX_BRIDGE/NODEJSBOX_TOKEN。\n' +
    '该模块只能在 NodeJsBox 容器内启动的脚本中使用（直接 node 运行不可用）。';
  module.exports = { available: false, error: hint };
  // 仍然导出一个所有方法都会 reject 的桩，方便脚本统一 await 而不崩
  const stub = () => Promise.reject(new Error(hint));
  module.exports.proc = ['list', 'start', 'stop', 'info', 'log', 'write']
    .reduce((o, k) => ((o[k] = stub), o), {});
  module.exports.fs = ['pick', 'export', 'listFiles'].reduce((o, k) => ((o[k] = stub), o), {});
  module.exports.app = ['info', 'toast'].reduce((o, k) => ((o[k] = stub), o), {});
} else {
  const [HOST, PORT] = BRIDGE.split(':');
  // 连接层状态（对外 API 在文件尾部组装导出）
  const conn = {
    socket: null,
    seq: 0,
    pending: new Map(), // seq → {resolve, reject}
    connecting: null,
    buffer: '',
  };

  function reset() {
    if (conn.socket) { try { conn.socket.destroy(); } catch (_) {} }
    conn.socket = null;
    conn.buffer = '';
    const pend = conn.pending;
    conn.pending = new Map();
    for (const { reject } of pend.values()) {
      reject(new Error('nodejsbox: 桥接连接已断开（容器可能重启）'));
    }
  }

  function connect() {
    if (conn.socket && !conn.socket.destroyed) return Promise.resolve(conn.socket);
    if (conn.connecting) return conn.connecting;
    conn.connecting = new Promise((resolve, reject) => {
      const sock = net.connect(Number(PORT), HOST, () => {
        // 握手：token 校验，失败容器会直接断开
        sock.write(JSON.stringify({ token: TOKEN }) + '\n');
        conn.socket = sock;
        conn.connecting = null;
        resolve(sock);
      });
      sock.setEncoding('utf8');
      let handshakeError = '';
      sock.on('data', (chunk) => {
        conn.buffer += chunk;
        let idx;
        while ((idx = conn.buffer.indexOf('\n')) >= 0) {
          const line = conn.buffer.slice(0, idx);
          conn.buffer = conn.buffer.slice(idx + 1);
          if (!line.trim()) continue;
          onLine(line);
        }
      });
      sock.on('error', (e) => {
        handshakeError = e.message;
        if (!conn.socket) { conn.connecting = null; reject(new Error('nodejsbox: 连接容器失败: ' + e.message)); }
        else reset();
      });
      sock.on('close', () => {
        if (!conn.socket) {
          if (!handshakeError) { conn.connecting = null; reject(new Error('nodejsbox: 连接被容器拒绝（token 校验失败）')); }
        } else reset();
      });
    });
    return conn.connecting;
  }

  function onLine(line) {
    let msg;
    try { msg = JSON.parse(line); } catch (_) { return; }
    const p = conn.pending.get(msg.seq);
    if (!p) return;
    conn.pending.delete(msg.seq);
    if (msg.ok) p.resolve(msg.data);
    else p.reject(new Error(String(msg.error || '未知错误')));
  }

  async function call(cmd, args = {}, timeoutMs = 0) {
    const sock = await connect();
    const seq = ++conn.seq;
    const payload = { seq, cmd, args };
    const p = new Promise((resolve, reject) => {
      conn.pending.set(seq, { resolve, reject });
      sock.write(JSON.stringify(payload) + '\n');
      if (timeoutMs > 0) {
        setTimeout(() => {
          if (conn.pending.has(seq)) {
            conn.pending.delete(seq);
            reject(new Error(`nodejsbox: ${cmd} 超时(${timeoutMs}ms)`));
          }
        }, timeoutMs);
      }
    });
    return p;
  }

  // ----------------------------- 对外 API -----------------------------

  const proc = {
    /** 列出容器内全部运行中实例 → [info] */
    list: () => call('proc.list').then((d) => d.instances),
    /** 动态拉起一个 node 脚本 → info；opts: {name,script,args,env,restart} */
    start: (opts = {}) => call('proc.start', opts),
    /** 停止实例（SIGTERM） */
    stop: (id) => call('proc.stop', { id }),
    /** 查询单个实例；未运行返回 null */
    info: (id) => call('proc.info', { id }),
    /** 读实例日志尾部；opts: {tail=200} → {lines} */
    log: (id, opts = {}) => call('proc.log', Object.assign({ id }, opts)),
    /** 向实例 stdin 写入；newline=true 时追加换行 */
    write: (id, text, newline = true) => call('proc.write', { id, text, newline }),
  };

  const fs = {
    /** 调系统文件管理器选文件，拷入容器 imports/ 后返回 {path,name,size}；opts: {mime}，mime 缺省任意类型 */
    pick: (opts = {}) => call('fs.pick', opts),
    /** 把容器内文件导出到用户选择的位置，返回 {name,size}；opts: {path, suggestedName} */
    export: (opts = {}) => call('fs.export', opts),
    /** 列 filesDir 一级子目录下的文件；opts: {dir="scripts"}，返回 {files:[{name,size}]} */
    listFiles: (opts = {}) => call('fs.listFiles', opts),
  };

  const app = {
    /** 容器信息：{nodeBin, filesDir, scripts, bridgePort} */
    info: () => call('app.info'),
    /** 弹 Android Toast */
    toast: (message) => call('app.toast', { message }),
  };

  module.exports = { available: true, proc, fs, app, call };
}
