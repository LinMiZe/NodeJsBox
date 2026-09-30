# NodeJsBox

[中文文档](README.md) | [English](README.en.md)

> Run Node.js directly on Android — no Termux / Linux environment required, Node is bundled inside the APK as a container.

NodeJsBox packages **Node.js 24 (native Termux/bionic build)** into the app, forming a self-contained Node runtime container. The app itself is intentionally thin: **the native side has only two buttons (start backend / open web frontend) — all runtime capabilities live in the built-in H5 console**: remote terminal, process management, and file management, all reaching the local WebServer over WebSocket, executed by a unified process engine.

- 📦 **Zero external dependencies**: the Node runtime ships with the APK, dual ABI (arm64-v8a / x86_64), ~73 MB
- 🖥️ **H5 console**: visit `http://127.0.0.1:38080/` in any browser — terminal / processes / files in one place
- 🔌 **In-script bridge API**: `require('nodejsbox')` inside the container to manage child processes and read/write sandbox files
- 🧪 **Built-in self-test**: `test-full.js` full regression with an HTML report
- 📁 **Sandbox browsable externally**: DocumentsProvider lets system / third-party file managers reach the data directory

**License:** MIT · **Min SDK:** Android 8.0 (API 26) · **Target SDK:** Android 15 (API 35)

---

## Project Layout

```
NodeJsBox/
├── app/src/main/
│   ├── java/com/nodejsbox/container/
│   │   ├── MainActivity / FilePickerActivity      thin entry shell (2 buttons + adb --es run + SAF relay)
│   │   ├── core/     NodeRuntime / RuntimeManager / WebServer / BridgeServer
│   │   │             ContainerBootstrap / ContainerService / Diag
│   │   ├── bridge/   BridgeDispatcher / ProcCommands / FileCommands (script-side command domains)
│   │   ├── provider/ FilesDocumentsProvider (expose data directory for browsing)
│   │   └── ui/       WebPanelActivity (dialog-themed WebView)
│   ├── assets/       web/index.html (H5 frontend) · scripts/ (bundled scripts) · modules/ (nodejsbox.js)
│   └── jniLibs/      node binary + dependent .so files (build artifacts, not committed)
└── tools/            PC-side build / install / self-test scripts (Node.js, see "Quick Start")
```

Dependency direction: `UI → core ← bridge`, strictly one-way, no cycles. `core` is a stateless bus: instance-set changes are delivered via `RuntimeManager.StateListener`, per-line output via `RuntimeManager.LineListener`; the two listeners are independent of each other.

---

## Quick Start

### Requirements

- **JDK 17+**, **Android SDK** (`ANDROID_HOME` set), **NDK** (provides `llvm-readelf` for runtime validation)
- **Node.js** (to run the scripts under `tools/`)
- An Android device or emulator (arm64 / x86_64)

### Build and Run

```bash
# 1) Download Node runtime deps (Termux debs, both architectures)
node tools/fetch-termux-deps.cjs --with-node

# 2) Unpack + ELF patch + generate jniLibs
node tools/assemble-runtime.cjs

# 3) Build the APK
./gradlew :app:assembleDebug

# 4) Install and launch on device
node tools/install-selftest.cjs

# 5) Copy npm from PC (npm inside the container is a pure-JS CLI with no binary;
#    re-run after every APK reinstall)
node tools/install-npm.cjs

# 6) End-to-end full self-test (install → --es run → poll result markers → pull instance logs as evidence)
node tools/install-selftest.cjs --script scripts/test-full.js
```

Common flags: run `node tools/install-selftest.cjs --help` for all options (`--device` to pick a device, `--no-reinstall` for quick re-runs while debugging, `--timeout` for the self-test timeout, etc.).

> On Windows, replace `./gradlew` with `gradlew.bat`.

### Running Tests

```bash
./gradlew :app:testDebugUnitTest            # ① JVM unit tests (seconds, no device needed)
./gradlew :app:connectedDebugAndroidTest    # ② instrumentation tests (run on a real device)
node tools/install-selftest.cjs             # ③ end-to-end self-test (real process chain)
```

---

## Usage

### 1. H5 Console (primary path)

Open via the "Open Web Frontend" button on the main screen, or visit `http://127.0.0.1:38080/` in any browser (loopback-only, never exposed externally). The single page has four sections:

- **File viewer**: browse / edit / create / delete / rename, scoped to the entire app sandbox
- **Process viewer**: list running instances (with pid), stop / Ctrl+C / "view output → ephemeral terminal"
- **Persistent terminal**: type a command, press Enter; output streams live; while attached to a process, input goes straight to its stdin
- **Ephemeral terminal**: attached to one process — replays history output + live stream + interactive input

WebSocket protocol (one JSON object per line):

```jsonc
// request
{"seq":1,"cmd":"shell.run","args":{"cmd":"ls -l"}}
// response
{"seq":1,"ok":true,"data":{ /* ... */ }}
{"seq":1,"ok":false,"error":"..."}
// events (requires attach first)
{"event":"out","id":"dyn-sh-…","line":"…"}
{"event":"exit","id":"…","code":0}
```

Command domains: `shell.run` / `proc.list` / `proc.attach` / `proc.detach` / `proc.input` / `proc.kill` / `proc.signal` / `fs.list` / `fs.read` / `fs.write` / `fs.mkdir` / `fs.delete` / `fs.rename`.

> `npm` / `npx` typed in the H5 terminal is automatically rewritten to `node <npm-cli.js> …` (npm ships no binary; run `tools/install-npm.cjs` first).

### 2. adb (automation)

```bash
PKG=com.nodejsbox.container
adb shell am start -n "$PKG/.MainActivity" --es run scripts/test-full.js   # launch and run a script
adb shell run-as $PKG cat files/logs/<id>.log                               # read instance log
adb shell run-as $PKG cat files/diag.log                                    # read diagnostics log
```

`--es` is the standard string extra of `am start` (`e`=extra, `s`=String). There is no adb entry for stopping an instance: use the H5 process viewer or the bridge's `proc.stop`.

### 3. Script-side Bridge API (`require('nodejsbox')`, zero-dependency, container-only)

```js
const box = require('nodejsbox');

await box.app.info();                    // { nodeBin, filesDir, scripts, bridgePort }
await box.app.toast('hello');

const ch = await box.proc.start({ script: 'scripts/test-echo.js', args: [], env: {}, restart: false });
await box.proc.write(ch.id, 'hello');    // write to the child instance's stdin (newline appended automatically)
(await box.proc.log(ch.id, { tail: 50 })).lines;
await box.proc.list();
await box.proc.stop(ch.id);

await box.fs.pick({ mime: 'text/*' });   // pick a file in the system file manager → copied into files/imports/
await box.fs.export({ path: 'out.bin' }); // a file inside filesDir → user picks a destination to export
await box.fs.listFiles({ dir: 'scripts' });
```

Connection parameters are injected automatically via the `NODEJSBOX_BRIDGE` / `NODEJSBOX_TOKEN` environment variables.

---

## Instance Model

All instances are **dynamic instances** (ids prefixed with `dyn-`), coming from three sources:

| Prefix | Source | id rule |
|---|---|---|
| `dyn-sh-*` | H5 terminal `shell.run` | one process per command |
| `dyn-script-<name>` | `--es run` / running a script from H5 | fixed by file name, **idempotent on restart** |
| `dyn-<uuid>` | bridge `proc.start` | brand new every time |

Lifecycle:

- Not persisted, **no restore across reboots** — scripts that must stay alive are re-launched by the frontend / scripts themselves, or use `restart=true` for in-process auto restart (exponential backoff 1s→2s→…→60s cap; reset after 60s of stable running; manual stop never restarts)
- Each instance has its own log `files/logs/<id>.log` (512KB rotation to `.old`) plus logcat `[NB:<id>] <line>`
- Any running instance → kept alive by a foreground service (triggered automatically by `NodeRuntime.spawn`); all instances stopped → the service terminates itself

## Bundled Scripts (`assets/scripts/` → `files/scripts/`)

| Script | Purpose |
|---|---|
| `hello.js` | heartbeat demo (resident + stdin echo) |
| `test-echo.js` | stdin echo; spawned by test-bridge / test-full for verification |
| `test-bridge.js` | bridge-chain self-test (`BRIDGE_RESULT` marker) |
| `test-full.js` | **full self-test** (HTML report → `files/reports/test-full-latest.html`, `FULL_RESULT` marker) |

To add a script, just drop it into `assets/scripts/`: `ContainerBootstrap` unpacks the whole directory recursively (written only when missing, self-heals 0-byte files, never overwrites user edits), and it shows up immediately in the H5 file viewer.

## Sandbox Layout (`/data/data/com.nodejsbox.container/`)

| Path | Purpose |
|---|---|
| `files/` | cwd = HOME; scripts / logs / reports / imports |
| `files/modules/` | built-in bridge module (injected via `NODE_PATH`, overwritten on upgrade) |
| `files/lib/node_modules/npm/` | npm CLI (not shipped in the APK; install with `tools/install-npm.cjs`) |
| `files/npm-global/`, `files/npm-cache/` | npm global installs / cache (container env already redirected) |
| `cache/` | `os.tmpdir()`, temporary files |
| `files/diag.log` | diagnostics log |

---

## Runtime Environment & Capability Boundaries

Node inside the container is the Termux build (bionic libc, not glibc) and differs from desktop Node. **Bottom line**: pure-JS areas of the Node ecosystem work out of the box (`npm` / `tsc` verified); tools that ship native binaries (esbuild / rolldown native / node-gyp) do not work under default configuration, but workarounds exist.

Key constraints:

- **W^X**: with targetSdk ≥ 29, exec is only allowed from `nativeLibraryDir/`; spawning binaries from the data directory always fails with `EACCES`. Workarounds: package native libs via jniLibs / a targetSdk 28 variant / WASM alternatives
- **Signal semantics**: `Process.destroy()` sends `SIGQUIT(3)`, not `SIGTERM`; the container switched to `sendSignal(pid, 15)`. A `sh` wrapper around node swallows signals, so when the first token of a command is `node` it is exec'd directly
- **Module resolution**: your own scripts inside the container are CommonJS (`.js` without `"type":"module"`) → **top-level await is not allowed**; wrap async code in `async function main()`
- **Network quirks**: `os.cpus()` returns an empty array; `dns.resolve*` connecting directly to port 53 is often refused (`dns.lookup` works fine); npm's default prefix points to a read-only directory, so the container injects `npm_config_prefix` / `npm_config_cache` redirects
- **Storage**: sdcard is mounted noexec — binaries that need exec cannot live there; writes to `/sdcard` fail with `EACCES` without permission

## Troubleshooting

- **npm / projects gone after reinstalling the APK?** Uninstall + reinstall wipes the sandbox — re-run `tools/install-npm.cjs`.
- **H5 display inconsistent with actual process state?** Grab the diagnostics log: `adb shell run-as com.nodejsbox.container cat files/diag.log` (exception paths are tagged `W`; start with `grep " W "`). Or `adb logcat -s NodeJsBox.Diag`.
- **Resident process silently dies after a few minutes?** Check whether the device's Phantom Process Killer (Android 12+) kicked in; on personal devices you can raise `max_phantom_processes`.
- **File manager can't see the data directory?** The provider must declare the `android.content.action.DOCUMENTS_PROVIDER` intent-filter (already implemented in this project).

## Roadmap

- H5 desktop-launcher style UI (script grid + one-tap run)
- sdcard workspace: "All files access" grant + node reading/writing `/sdcard/NodeJsBox/` directly
- Bridge enhancements: push proc exit events to the script side
- WebServer: multi-session shared terminal attach, file upload / download (currently limited to 512KB text)

---

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE) © NodeJsBox contributors
