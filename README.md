# NodeJsBox

[中文文档](README.md) | [English](README.en.md)

> 在 Android 上直接运行 Node.js —— 不依赖 Termux / Linux 环境，把 Node 打进 APK 当容器用。

NodeJsBox 将 Termux 构建的 **Node.js 24（bionic 原生编译）** 打包进 App，形成一个自包含的 Node 运行时容器。App 本体极薄：**原生侧只有三个按钮（启动后端 / 打开 Web 前端 / 开源许可），全部运行能力都在内置的 H5 控制台里**——远程终端、进程管理、文件管理，经 WebSocket 打到本机 WebServer，由统一的进程引擎执行。

- 📦 **零外部依赖**：Node 运行时随 APK 分发，双 ABI（arm64-v8a / x86_64），约 73 MB
- 🖥️ **H5 控制台**：浏览器访问 `http://127.0.0.1:38080/`，终端 / 进程 / 文件一站管理
- 🔌 **脚本侧桥接 API**：容器内 `require('nodejsbox')` 即可管理子进程、读写沙箱文件
- 🧪 **内置自检**：`test-full.js` 全量回归，产出 HTML 报告
- 📁 **沙箱可对外浏览**：DocumentsProvider，系统 / 第三方文件管理器可直达数据目录

**License:** MIT · **最低版本:** Android 8.0（API 26）· **目标版本:** Android 15（API 35）

---

## 目录结构

```
NodeJsBox/
├── app/src/main/
│   ├── java/com/nodejsbox/container/
│   │   ├── MainActivity / FilePickerActivity      入口薄壳（三按钮 + adb --es run + SAF 中转）
│   │   ├── core/     NodeRuntime / RuntimeManager / WebServer / BridgeServer
│   │   │             ContainerBootstrap / ContainerService / Diag
│   │   ├── bridge/   BridgeDispatcher / ProcCommands / FileCommands（脚本侧命令域）
│   │   ├── provider/ FilesDocumentsProvider（数据目录对外浏览）
│   │   └── ui/       WebPanelActivity（Dialog 主题 WebView）
│   ├── assets/       web/index.html（H5 前端）· scripts/（内置脚本）· modules/（nodejsbox.js）
│   │                 licenses/（内置运行时许可告知 + Node.js LICENSE 全文）
│   └── jniLibs/      node 本体 + 依赖 .so（构建产物，不入库）
└── tools/            PC 侧构建 / 装机 / 自检脚本（Node.js，见「快速开始」）
```

依赖方向：`UI → core ← bridge`，单向无环。core 无状态总线：实例集合变化走 `RuntimeManager.StateListener`，逐行输出走 `RuntimeManager.LineListener`，两者互不依赖。

---

## 快速开始

### 环境要求

- **JDK 17+**、**Android SDK**（设置 `ANDROID_HOME` 环境变量）、**NDK**（提供 `llvm-readelf` 用于运行时校验）
- **Node.js**（运行 `tools/` 下的脚本）
- 一台 Android 设备或模拟器（arm64 / x86_64）

### 构建并运行

```bash
# 1) 下载 Node 运行时依赖（Termux deb，双架构）
node tools/fetch-termux-deps.cjs --with-node

# 2) 解包 + ELF 补丁 + 生成 jniLibs
node tools/assemble-runtime.cjs

# 3) 构建 APK
./gradlew :app:assembleDebug

# 4) 安装到设备并启动
node tools/install-selftest.cjs

# 5) 从 PC 拷入 npm（容器内 npm 是纯 JS CLI，无二进制；每次重装 APK 后需重跑）
node tools/install-npm.cjs

# 6) 端到端全量自检（装机 → --es run → 轮询结果标记 → 拉取实例日志佐证）
node tools/install-selftest.cjs --script scripts/test-full.js
```

常用参数：`node tools/install-selftest.cjs --help` 查看全部选项（`--device` 指定设备、`--no-reinstall` 调试期快速复跑、`--timeout` 自检超时等）。

> Windows 下把 `./gradlew` 换成 `gradlew.bat`。

### 运行测试

```bash
./gradlew :app:testDebugUnitTest            # ① JVM 单测（秒级，无需设备）
./gradlew :app:connectedDebugAndroidTest    # ② 仪器测试（设备上真跑）
node tools/install-selftest.cjs             # ③ 端到端自检（真实进程链）
```

---

## 使用方式

### 1. H5 控制台（主路径）

打开方式：主界面「打开 Web 前端」弹窗，或任意浏览器访问 `http://127.0.0.1:38080/`（仅回环监听，不对外网暴露）。单页四区块：

- **文件查看器**：浏览 / 编辑 / 新建 / 删除 / 改名，范围为整个 App 沙箱
- **进程查看器**：列出运行实例（含 pid），可停止 / Ctrl+C / 「查看输出 → 临时终端」
- **常驻终端**：输入命令回车执行，输出实时滚动；附着进程运行中输入直接写其 stdin
- **临时终端**：附着某个进程，回放历史输出 + 实时流式 + 可输入

顶栏另有「开源许可」按钮，直接展示内置运行时的来源告知与 Node.js LICENSE 全文（见下文「运行时来源与许可证」）。

WebSocket 协议（一条消息一行 JSON）：

```jsonc
// 请求
{"seq":1,"cmd":"shell.run","args":{"cmd":"ls -l"}}
// 响应
{"seq":1,"ok":true,"data":{ /* ... */ }}
{"seq":1,"ok":false,"error":"..."}
// 事件（需先 attach）
{"event":"out","id":"dyn-sh-…","line":"…"}
{"event":"exit","id":"…","code":0}
```

命令域：`shell.run` / `proc.list` / `proc.attach` / `proc.detach` / `proc.input` / `proc.kill` / `proc.signal` / `fs.list` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.delete` / `fs.rename` / `licenses.read`。

> H5 终端里的 `npm` / `npx` 会被自动改写为 `node <npm-cli.js> …`（npm 无二进制，需先跑 `tools/install-npm.cjs` 安装）。

### 2. adb（自动化）

```bash
PKG=com.nodejsbox.container
adb shell am start -n "$PKG/.MainActivity" --es run scripts/test-full.js   # 拉起并运行脚本
adb shell run-as $PKG cat files/logs/<id>.log                               # 看实例日志
adb shell run-as $PKG cat files/diag.log                                    # 看诊断日志
```

`--es` 即 `am start` 的标准 extra 参数（`e`=extra，`s`=String 类型）。停止实例没有 adb 入口：走 H5 进程查看器，或桥接 `proc.stop`。

### 3. 脚本侧桥接 API（`require('nodejsbox')`，零依赖，仅容器内可用）

```js
const box = require('nodejsbox');

await box.app.info();                    // { nodeBin, filesDir, scripts, bridgePort }
await box.app.toast('你好');

const ch = await box.proc.start({ script: 'scripts/test-echo.js', args: [], env: {}, restart: false });
await box.proc.write(ch.id, 'hello');    // 注入子实例 stdin（自动补 \n）
(await box.proc.log(ch.id, { tail: 50 })).lines;
await box.proc.list();
await box.proc.stop(ch.id);

await box.fs.pick({ mime: 'text/*' });   // 系统文件管理器选文件 → 拷入 files/imports/
await box.fs.export({ path: 'out.bin' }); // filesDir 内文件 → 用户选位置导出
await box.fs.listFiles({ dir: 'scripts' });
```

连接参数经环境变量 `NODEJSBOX_BRIDGE` / `NODEJSBOX_TOKEN` 自动注入。

---

## 实例模型

实例**全部是动态实例**（id 以 `dyn-` 开头），三个来源：

| 前缀 | 来源 | id 规则 |
|---|---|---|
| `dyn-sh-*` | H5 终端 shell.run | 每条命令一个进程 |
| `dyn-script-<名>` | `--es run` / H5 运行脚本 | 按文件名固定，**重复启动幂等** |
| `dyn-<uuid>` | 桥 `proc.start` | 每次全新 |

生命周期：

- 不持久化、**不跨重启恢复**——需保活的脚本由前端 / 脚本自行重新拉起，或 `restart=true` 进程内自动重启（指数退避 1s→2s→…→60s 封顶，稳定运行满 60s 重置；手动停止不重启）
- 每个实例独立日志 `files/logs/<id>.log`（512KB 轮转 `.old`）+ logcat `[NB:<id>] <line>`
- 有实例在跑 → 前台服务保活（`NodeRuntime.spawn` 自动触发）；实例全部停止 → 服务自灭

## 内置脚本（`assets/scripts/` → `files/scripts/`）

| 脚本 | 用途 |
|---|---|
| `hello.js` | 心跳演示（常驻 + stdin 回显） |
| `test-echo.js` | stdin 回显，被 test-bridge / test-full 拉起验证 |
| `test-bridge.js` | 桥接链路自检（`BRIDGE_RESULT` 标记） |
| `test-full.js` | **全量自检**（HTML 报告 → `files/reports/test-full-latest.html`，`FULL_RESULT` 标记） |

新增脚本只需放进 `assets/scripts/`，`ContainerBootstrap` 整目录递归解包（缺失才写、0 字节自愈、用户改过的不覆盖），H5 文件查看器直接可见。

## 沙箱目录（`/data/data/com.nodejsbox.container/`）

| 目录 | 用途 |
|---|---|
| `files/` | cwd = HOME；脚本 / 日志 / 报告 / 导入 |
| `files/modules/` | 内置桥接模块（`NODE_PATH` 注入，升级覆盖） |
| `files/lib/node_modules/npm/` | npm CLI（APK 不自带，跑 `tools/install-npm.cjs` 安装） |
| `files/npm-global/`、`files/npm-cache/` | npm 全局安装 / 缓存（容器 env 已重定向） |
| `cache/` | `os.tmpdir()`，临时文件 |
| `files/diag.log` | 诊断日志 |

---

## 运行环境与能力边界

容器内是 Termux 编译的 Node（bionic libc，非 glibc），与桌面 Node 存在差异。**核心结论**：纯 JS 类 Node 生态开箱即用（`npm` / `tsc` 实测通过）；自带原生二进制的工具（esbuild / rolldown native / node-gyp）在默认配置下不可用，但有绕过路线。

关键约束：

- **W^X**：targetSdk ≥ 29 只允许 exec `nativeLibraryDir/`；数据目录 spawn 二进制必 `EACCES`。绕过路线：jniLibs 打包原生库 / targetSdk 28 变体 / WASM 替代
- **信号语义**：`Process.destroy()` 发的是 `SIGQUIT(3)` 而非 `SIGTERM`，容器已改用 `sendSignal(pid, 15)`；`sh` 包装 node 会吞信号，cmd 首 token 为 `node` 时直接 exec
- **模块解析**：容器内自写脚本是 CommonJS（`.js` 无 `"type":"module"`）→ **禁止顶层 await**，异步包进 `async function main()`
- **网络怪癖**：`os.cpus()` 返回空数组；`dns.resolve*` 直连 53 常被拒（`dns.lookup` 正常）；`npm` 默认 prefix 指向只读目录，容器已注入 `npm_config_prefix`/`npm_config_cache` 重定向
- **存储**：sdcard 为 noexec，不能放需要 exec 的二进制；未授权时写 `/sdcard` 会 `EACCES`

## 常见问题

- **重装 APK 后 npm / 项目全没了？** 卸载重装 = 沙箱全清，需重跑 `tools/install-npm.cjs`。
- **H5 显示与进程实际状态不一致？** 抓诊断日志：`adb shell run-as com.nodejsbox.container cat files/diag.log`（异常路径带 `W` 标记，先 `grep " W "`）。或 `adb logcat -s NodeJsBox.Diag`。
- **常驻进程过几分钟无声消失？** 确认设备是否触发 Phantom Process Killer（Android 12+），自用设备可调大 `max_phantom_processes`。
- **文件管理器看不到数据目录？** provider 需声明 `android.content.action.DOCUMENTS_PROVIDER` intent-filter（本项目已实现）。

## 路线图

- H5 桌面启动器能力（脚本网格 + 一键运行）
- sdcard 工作区：「所有文件访问」授权 + node 直读直写 `/sdcard/NodeJsBox/`
- Bridge 增强：proc 退出事件主动推送给脚本侧
- WebServer：多会话终端共享附着、文件上传 / 下载（当前限 512KB 文本）

---

## 运行时来源与许可证

**内置的 Node 运行时不是本项目自研或交叉编译的**：`app/src/main/jniLibs/<abi>/` 下的 10 个 `.so` 取自 **Termux 官方 apt 仓库**预编译的 deb 包（`tools/fetch-termux-deps.cjs` 下载、`tools/assemble-runtime.cjs` 解包并按 Android 命名规则重命名，仅改 SONAME、不改代码）。

- apt 仓库：https://packages.termux.dev/apt/termux-main
- Termux 工程：https://github.com/termux/termux-app · https://github.com/termux/termux-packages

各组件沿用自己的上游许可，**均为宽松许可、不含 copyleft**：

| .so | 组件 | 版本 | 许可证 |
|---|---|---|---|
| `libnode.so` | Node.js | 24.18.0 | MIT |
| `libssl3.so` / `libcrypto3.so` | OpenSSL | 3.6.3 | Apache License 2.0 |
| `libicu*.so` | ICU (Unicode) | 78.3 | Unicode License（BSD 风格） |
| `libz1.so` | zlib | 1.3.2 | zlib License |
| `libcares.so` | c-ares | 1.34.8 | MIT |
| `libsqlite3.so` | SQLite | 3.53.4 | Public Domain |
| `libc++_shared.so` | libc++（NDK C++ 标准库） | 29 | Apache License 2.0 + LLVM Exception |

说明：

- Node.js 自带的 V8 / libuv / zlib / c-ares / ICU 等组件，许可全文统一收录在 Node 的 `LICENSE` 里，本项目原样随包分发（`app/src/main/assets/licenses/node-LICENSE.txt`）。
- 不在 Node LICENSE 内的组件（OpenSSL / SQLite / libc++ / c-ares）按上表各自许可分发；宽松许可的唯一义务是**保留版权与许可声明**，因此 App 主界面与 H5 控制台都提供「开源许可」入口，原文可读。
- Termux 的终端模拟器应用按 GPL v3 发布，本项目**未使用其任何代码**，仅以 termux 仓库作为上游二进制的下载来源；打包脚本本身按 BSD-3-Clause 发布，同样未包含在内。

## Contributing

欢迎 Issue 与 Pull Request。

## License

[MIT](LICENSE) © NodeJsBox contributors
