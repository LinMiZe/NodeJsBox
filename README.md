# NodeJsBox

[English](README.md) | [中文文档](README.zh-CN.md)

A standalone, general-purpose **Node.js runtime container for Android**. It ships Termux's natively-compiled Node.js (bionic build) inside a regular APK — no root, no Termux app, no Linux environment required. Run any Node.js script directly on a device or emulator.

- The container is script-agnostic: it knows nothing about what it runs.
- Core concept: a **RunConfig** = script + args + env vars + policy. Each config launches one independent `node` process instance; multiple configs can run concurrently (even from the same script).

```
┌─ NodeJsBox container (com.nodejsbox.container, ~73 MB, dual ABI) ─────┐
│                                                                       │
│  configs.json (filesDir, user-editable)                               │
│    └─ config: id / name / script / args / env / restart / autostart   │
│                                                                       │
│  MainActivity        config list UI: start/stop/logs/reload           │
│  ContainerService    foreground service (dataSync): keep-alive        │
│  RuntimeManager      multi-instance mgmt: proc table, log rotation,   │
│                      crash auto-restart (exponential backoff)         │
│  RunConfigs          config model + JSON persistence + asset unpack   │
│  NodeRuntime         ProcessBuilder spawn of libnode.so               │
│                                                                       │
│  jniLibs/arm64-v8a/   (10 native libs)                                │
│  jniLibs/x86_64/      (10 native libs)                                │
│  assets/scripts/      selftest.js + hello.js (unpacked on first run)  │
└───────────────────────────────────────────────────────────────────────┘
```

## Features

| Capability | Description |
|---|---|
| Multi-instance | One instance per config; multiple configs (even pointing to the same script) run concurrently |
| One-tap start/stop | UI buttons, or adb `--es start/stop <id>` |
| Crash auto-restart | Instances with `restart:true` are restarted with exponential backoff (1s → 2s → … capped at 60s; counter resets after 60s of stable running; manual stops do not restart) |
| Logs | Per-instance log file `filesDir/logs/<id>.log` (512 KB rotation to `.old`); logcat tag `NodeJsBox` with `[NB:<id>]` prefix; UI shows last 300 lines |
| Service self-dismiss | The foreground service withdraws itself once all instances stop (no lingering notification) |
| Reconnect recovery | Running instance ids are persisted; after a sticky service restart by the system, instances with `restart:true` and `autostart:true` are resumed |
| Env injection | `LD_LIBRARY_PATH=nativeLibraryDir`, `HOME=filesDir`, `TMPDIR=cacheDir`; per-config `env` can override/append |

## Building from source

Prerequisites: JDK 17, Android SDK (Gradle 8.14.3 / AGP 8.13.1), Node.js >= 18, `tar` (bsdtar, bundled with Windows 10+/most Linux).

The native runtime is **not** committed to the repository — it is assembled from official Termux packages (`packages.termux.dev`) by the build tools:

```bash
node tools/fetch-termux-deps.cjs --with-node   # download nodejs-lts + 6 dependency .deb (both ABIs)
node tools/assemble-runtime.cjs                # unpack + ELF patch + generate app/src/main/jniLibs/
./gradlew :app:assembleDebug                   # build the APK (on Windows: gradlew.bat)
node tools/install-selftest.cjs                # install on a running emulator/device + self-test
```

To upgrade Node.js: download the new version via `tools/fetch-termux-deps.cjs`, re-run `tools/assemble-runtime.cjs`, and rebuild. Note that `libicu`'s SONAME major version must match what the node binary links against (currently 78.x).

## Usage

### UI
Launch the app to see the config list. Each row shows name / script / policy / status with Start (Stop) and Log buttons; top bar has "Reload configs" and "Stop all".

### adb (automation / headless)

```bash
adb shell am start -n com.nodejsbox.container/.MainActivity --es start hello
adb shell am start -n com.nodejsbox.container/.MainActivity --es stop  hello

# instance logs
adb logcat -s NodeJsBox
adb shell run-as com.nodejsbox.container cat files/logs/hello.log

# add your own script + config
adb push my.js /data/local/tmp/my.js
adb shell chmod 644 /data/local/tmp/my.js
adb shell run-as com.nodejsbox.container cp /data/local/tmp/my.js files/scripts/my.js
# then edit filesDir/configs.json and tap "Reload configs" in the app
```

### configs.json format

```json
{ "configs": [
  { "id": "selftest", "name": "Runtime self-test", "script": "scripts/selftest.js",
    "args": [], "env": {}, "restart": false, "autostart": false },
  { "id": "hello", "name": "Heartbeat demo", "script": "scripts/hello.js",
    "args": [], "env": {}, "restart": true, "autostart": false }
] }
```

- `script`: relative to `filesDir` or absolute; `args` are passed to node; `env` adds environment variables
- `restart`: auto-restart on exit; `autostart`: auto-start when the service (re)starts
- Multi-instance: duplicate a config with a different id (run N copies of the same script)

## Technical highlights

### ELF patching (why jniLibs contains names like `libcrypto3.so`)
Android's installer only extracts jniLibs files matching `lib*.so` into `nativeLibraryDir`, and W^X only allows executing from there. Termux libraries carry version suffixes (`libcrypto.so.3`, …) that don't match. The fix: rewrite each ELF's `.dynstr` entries for `DT_NEEDED`/`DT_SONAME` in place (`libcrypto.so.3 → libcrypto3.so`, `libicuuc.so.78 → libicuuc78.so`, …). New names are ≤ old length, null-padded, no offset changes. Implemented in pure Node in `tools/assemble-runtime.cjs`, with dependency-closure validation and optional cross-validation via NDK `llvm-readelf`. Gradle must set `useLegacyPackaging = true` so `.so` files are extracted to disk (needed for exec).

### Runtime composition (per ABI: 10 native libs)
Node.js LTS 24.18.0 (~43 MB) plus dependencies from Termux: openssl 3.6.3, libicu 78.3 (31.6 MB — the bulk; SONAME hard constraint 78.x), libc++ 29, c-ares 1.34.8, libsqlite 3.53.4, zlib 1.3.2. System libc/libm/libdl come from bionic. All libraries are 16 KB-aligned per Termux build conventions.

### Implementation notes
- **Process isolation**: node is spawned via `ProcessBuilder`; a node crash never takes down the app process. stdout/stderr are merged and pumped line-by-line to the log file and logcat.
- **singleTop**: `MainActivity` must use `launchMode="singleTop"`, otherwise `am start --es` extras are not delivered to `onNewIntent` when the activity is already on top.
- **FGS compliance**: `onStartCommand` calls `startForeground` before handling the action (5-second rule); the service stops itself when no instance is running.
- **Graceful stop**: `destroy()` sends SIGTERM first (scripts can clean up on SIGTERM), then `destroyForcibly()` after 3 seconds.

## Known limitations

1. `os.cpus()` returns an empty array on Android (known quirk); rarely matters in practice.
2. **Phantom Process Killer (Android 12+)**: persistent instances rely on the foreground service.
3. arm64-v8a builds are assembled symmetrically with x86_64 but have not been verified on a physical arm64 device.
4. Termux node's RUNPATH points to a nonexistent Termux prefix (harmless); `LD_LIBRARY_PATH` takes precedence.
5. Auto-restart has no attempt cap (backoff caps at 60s); restart loops caused by script bugs need manual intervention.
6. `adb shell am start --es ...` requires the activity to be visible; a broadcast receiver entry point could be added for fully background control.

## Acknowledgements

- [Termux](https://termux.dev) — the bundled native runtime is assembled from Termux's official package builds ([termux-packages](https://github.com/termux/termux-packages), `packages.termux.dev`). No Termux app code is used.
- [Node.js](https://nodejs.org)

## Third-party components

The native libraries are builds of the upstream projects below and remain under their respective permissive licenses (no copyleft components):

| Component | Upstream | License |
|---|---|---|
| node (LTS 24.x) | [Node.js](https://nodejs.org) | MIT |
| libcrypto / libssl | [OpenSSL 3.x](https://www.openssl.org) | Apache-2.0 |
| libicu* (78.x) | [ICU](https://icu.unicode.org) | ICU License (MIT-compatible) |
| libz | [zlib](https://zlib.net) | zlib License |
| libcares | [c-ares](https://c-ares.org) | MIT |
| libsqlite3 | [SQLite](https://sqlite.org) | Public Domain |
| libc++_shared | [LLVM libc++](https://libcxx.llvm.org) | Apache-2.0 with LLVM exception |

If you redistribute this project (e.g. an APK built from it), keep the corresponding copyright and license notices.

> This project was developed in collaboration with an AI assistant (GLM). Design, direction, testing and maintenance are done by the maintainer.

## License

[MIT](LICENSE)
