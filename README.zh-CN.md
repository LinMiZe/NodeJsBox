# NodeJsBox

[English](README.md) | [中文文档](README.zh-CN.md)

一个独立的、通用的 **Android Node.js 运行时容器**。把 Termux 官方 bionic 原生编译的 Node.js 打进普通 APK——无需 root、无需 Termux App、不依赖 Linux 环境，可直接在设备或模拟器上运行任意 node 脚本。

- 容器不知道也不关心跑的是什么脚本；
- 核心概念：**运行配置（RunConfig）** = 脚本 + 参数 + 环境变量 + 策略，每条配置一键启动一个独立 node 进程实例；多条配置可并发运行（也可指向同一脚本实现多开）。

```
┌─ NodeJsBox 容器 (com.nodejsbox.container, ~73 MB, 双 ABI) ────────────┐
│                                                                      │
│  configs.json (filesDir, 用户可编辑)                                  │
│    └─ 配置: id / name / script / args / env / restart / autostart     │
│                                                                      │
│  MainActivity        配置列表 UI：启动/停止/日志/重载配置              │
│  ContainerService    前台服务(dataSync)：保活 + 汇总通知 + 自灭        │
│  RuntimeManager      多实例管理：进程表/日志轮转/崩溃自动重启          │
│  RunConfigs          配置模型 + JSON 持久化 + 内置脚本解包            │
│  NodeRuntime         ProcessBuilder spawn libnode.so                 │
│                                                                      │
│  jniLibs/arm64-v8a/   (10 个原生库)                                   │
│  jniLibs/x86_64/      (10 个原生库)                                   │
│  assets/scripts/      selftest.js + hello.js（首次启动解包）          │
└───────────────────────────────────────────────────────────────────────┘
```

## 功能

| 能力 | 说明 |
|---|---|
| 多实例并发 | 每条配置一个实例；多条配置（可指向同一脚本）即多开 |
| 一键启动/停止 | UI 按钮或 adb `--es start/stop <id>` |
| 崩溃自动重启 | `restart:true` 的实例退出后指数退避重启（1s→2s→…→60s 封顶；稳定运行 60s 后计数重置；手动停止不重启） |
| 日志 | 每实例独立文件 `filesDir/logs/<id>.log`（512KB 轮转 .old）；logcat tag=NodeJsBox，格式 `[NB:<id>] <line>`；UI 内查看尾部 300 行 |
| 服务自灭 | 全部实例停止后前台服务自动撤销（不留常驻通知） |
| 掉线恢复 | 运行中实例 id 持久化；服务被系统 sticky 重建后恢复 `restart:true` 的在跑实例 + `autostart:true` 的实例 |
| 环境注入 | `LD_LIBRARY_PATH=nativeLibraryDir`、`HOME=filesDir`、`TMPDIR=cacheDir`，配置 env 可覆盖/追加 |

## 从源码构建

前置要求：JDK 17、Android SDK（Gradle 8.14.3 / AGP 8.13.1）、Node.js >= 18、`tar`（bsdtar，Windows 10+ 与多数 Linux 自带）。

原生运行时**不入库**——由构建工具从 Termux 官方仓库（packages.termux.dev）下载组装：

```bash
node tools/fetch-termux-deps.cjs --with-node   # 下载 nodejs-lts + 6 个依赖 .deb（双架构）
node tools/assemble-runtime.cjs                # 解包 + ELF 补丁 + 生成 app/src/main/jniLibs/
./gradlew :app:assembleDebug                   # 构建 APK（Windows 下用 gradlew.bat）
node tools/install-selftest.cjs                # 安装到运行中的模拟器/设备 + 自检
```

升级 Node：用 `tools/fetch-termux-deps.cjs` 下载新版，重跑 `tools/assemble-runtime.cjs` 后重新构建。注意 `libicu` 的 SONAME 大版本必须与 node 二进制链接的版本一致（当前为 78.x）。

## 使用

### UI
启动 App 即见配置列表：每行显示 名称/脚本/策略/状态，按钮 启动(停止)/日志；顶部「重载配置」「全部停止」。

### adb（自动化/无 UI）

```bash
adb shell am start -n com.nodejsbox.container/.MainActivity --es start hello
adb shell am start -n com.nodejsbox.container/.MainActivity --es stop  hello

# 查看实例日志
adb logcat -s NodeJsBox
adb shell run-as com.nodejsbox.container cat files/logs/hello.log

# 添加自己的脚本 + 配置
adb push my.js /data/local/tmp/my.js
adb shell chmod 644 /data/local/tmp/my.js
adb shell run-as com.nodejsbox.container cp /data/local/tmp/my.js files/scripts/my.js
# 然后编辑 filesDir/configs.json，App 内点「重载配置」
```

### configs.json 格式

```json
{ "configs": [
  { "id": "selftest", "name": "运行时自检", "script": "scripts/selftest.js",
    "args": [], "env": {}, "restart": false, "autostart": false },
  { "id": "hello", "name": "心跳演示", "script": "scripts/hello.js",
    "args": [], "env": {}, "restart": true, "autostart": false }
] }
```

- `script`：相对 `filesDir` 或绝对路径；`args` 传给 node 的命令行；`env` 追加环境变量
- `restart`：退出后自动重启；`autostart`：服务（重新）启动时自动拉起
- 多开 = 复制配置改 id（同一脚本跑 N 份）

## 核心技术

### ELF 补丁（jniLibs 里为什么是 libcrypto3.so 这种名字）
Android 安装器只解压 jniLibs 中名字匹配 `lib*.so` 的文件到 `nativeLibraryDir`，且 W^X 策略只允许 exec 该目录。Termux 库带版本后缀（`libcrypto.so.3` 等）不符合。解法：原地改写每个 ELF 的 `.dynstr` 中 `DT_NEEDED`/`DT_SONAME` 字符串（`libcrypto.so.3→libcrypto3.so`、`libicuuc.so.78→libicuuc78.so`…；新名 ≤ 旧名，null 填充，不改偏移）。纯 Node 实现于 `tools/assemble-runtime.cjs`，含依赖闭包校验 + NDK `llvm-readelf` 交叉验证（找到 NDK 时自动执行）。Gradle 需设置 `useLegacyPackaging = true`（.so 落盘才能 exec）。

### 运行时组成（每架构 10 个 .so）
Node.js LTS 24.18.0（本体 ~43MB）+ Termux 依赖：openssl 3.6.3、libicu 78.3（31.6MB，体积大头；SONAME 硬约束 78.x）、libc++ 29、c-ares 1.34.8、libsqlite 3.53.4、zlib 1.3.2。系统 libc/libm/libdl 由 bionic 提供。所有库按 Termux 构建规范 16KB 对齐。

### 关键实现点
- **进程隔离**：ProcessBuilder exec，node 崩溃不影响 App 进程；stdout/stderr 合流逐行泵到日志文件 + logcat
- **singleTop**：MainActivity 必须 `launchMode="singleTop"`，否则 Activity 在栈顶时 `am start --es` 的 extras 不会进 `onNewIntent`
- **FGS 合规**：onStartCommand 先 startForeground 再处理动作（5 秒规则）；实例全停即 stopForeground+stopSelf
- **优雅停止**：`destroy()` 先发 SIGTERM（脚本可监听清理后退出），3 秒不退 `destroyForcibly`

## 已知限制

1. `os.cpus()` 在 Android 返回空数组（已知怪癖），一般脚本无影响
2. **Phantom Process Killer（Android 12+）**：常驻实例依赖前台服务
3. arm64-v8a 产物与 x86_64 对称组装，尚未在 arm64 真机上验证
4. Termux node 的 RUNPATH 指向 termux 前缀（不存在，无害）；LD_LIBRARY_PATH 优先生效
5. 自动重启无次数上限（退避封顶 60s）；脚本自身 bug 导致的循环重启需人工处理
6. `adb shell am start --es ...` 依赖 Activity 可见；纯后台触发可后续加广播入口

## 致谢

- [Termux](https://termux.dev) —— 内置的原生运行时来自 Termux 官方包构建（[termux-packages](https://github.com/termux/termux-packages)，packages.termux.dev）。未使用 Termux App 的任何代码。
- [Node.js](https://nodejs.org)

## 第三方组件

原生库均为下列上游项目的构建产物，各自保留原有宽松许可（不含 copyleft 组件）：

| 组件 | 上游项目 | 许可 |
|---|---|---|
| node (LTS 24.x) | [Node.js](https://nodejs.org) | MIT |
| libcrypto / libssl | [OpenSSL 3.x](https://www.openssl.org) | Apache-2.0 |
| libicu* (78.x) | [ICU](https://icu.unicode.org) | ICU License（MIT 兼容） |
| libz | [zlib](https://zlib.net) | zlib License |
| libcares | [c-ares](https://c-ares.org) | MIT |
| libsqlite3 | [SQLite](https://sqlite.org) | Public Domain |
| libc++_shared | [LLVM libc++](https://libcxx.llvm.org) | Apache-2.0（含 LLVM 例外，允许闭源链接） |

若再分发本项目（如基于它构建的 APK），请保留各组件的版权与许可声明。

> 本项目由维护者与 AI 助手（GLM）协作开发。需求定义、方案决策、测试与维护均由维护者完成。

## License

[MIT](LICENSE)
