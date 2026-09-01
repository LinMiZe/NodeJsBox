'use strict';
/**
 * NodeJsBox 心跳演示脚本（内置）—— 常驻运行
 *
 * 用于演示/验证容器的常驻能力：
 *   - 每 5s 打印一次心跳（uptime + RSS）
 *   - 响应 SIGTERM 优雅退出（容器「停止」按钮发送 SIGTERM）
 *   - 配置 restart=true 时被杀死后会由容器自动拉起
 */
const os = require('os');

const start = Date.now();
console.log('hello: node ' + process.version + ' pid=' + process.pid);
console.log('hello: homedir=' + os.homedir() + ' tmpdir=' + os.tmpdir());

const timer = setInterval(() => {
  const up = Math.round((Date.now() - start) / 1000);
  const mem = Math.round(process.memoryUsage().rss / 1024 / 1024);
  console.log('hello: heartbeat uptime=' + up + 's rss=' + mem + 'MB');
}, 5000);

process.on('SIGTERM', () => {
  console.log('hello: SIGTERM 收到，优雅退出');
  clearInterval(timer);
  process.exit(0);
});
