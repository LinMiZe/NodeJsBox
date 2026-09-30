'use strict';
/**
 * NodeJsBox stdin 回显演示（内置）
 *
 * 用途：验证容器的 stdin 注入能力（UI 终端输入行 / Bridge proc.write）。
 * 行为：逐行读取 stdin，回显 "echo: <line>"；输入 exit 退出；SIGTERM 优雅退出。
 */
process.stdin.setEncoding('utf8');
let buffer = '';

process.stdout.write('echo: 就绪（输入任意内容回显，exit 退出）\n');

process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).replace(/\r$/, '');
    buffer = buffer.slice(idx + 1);
    if (line === 'exit') {
      process.stdout.write('echo: bye\n');
      process.exit(0);
    }
    process.stdout.write('echo: ' + line + '\n');
  }
});

process.stdin.on('error', () => {});

process.on('SIGTERM', () => {
  process.stdout.write('echo: SIGTERM 收到，优雅退出\n');
  process.exit(0);
});
