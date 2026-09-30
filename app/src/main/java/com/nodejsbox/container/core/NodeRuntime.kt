package com.nodejsbox.container.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内嵌 Node 二进制的启动封装 + 运行配置数据模型。
 *
 * 原理：
 *  - Termux 构建的 node 打包进 jniLibs/<abi>/libnode.so，安装后位于
 *    applicationInfo.nativeLibraryDir（系统管理、只读、可执行）；
 *  - Android 10+ 的 W^X 策略只允许 exec 该目录下的文件；
 *  - 依赖库（libcrypto3.so 等）同目录，通过 LD_LIBRARY_PATH 交给 bionic linker；
 *  - ProcessBuilder 直接 exec，node 崩溃只影响该实例（进程隔离）。
 */
object NodeRuntime {

    const val TAG = "NodeJsBox"

    /**
     * 运行配置数据模型（进程引擎的输入，非配置文件），
     * 由 RuntimeManager / WebServer / 桥 proc.start 共用：
     *
     *   id        唯一标识（同时作为实例名和日志文件名 files/logs/<id>.log）
     *   name      显示名
     *   script    node 脚本路径（相对 filesDir 或绝对路径）
     *   cmd       单行命令（非空时忽略 script/args；首 token 为 node 时直接 exec，
     *             其余交 /system/bin/sh -c——见 buildNodeArgv）
     *   args      传给 node 的命令行参数
     *   env       附加环境变量（覆盖 baseEnv）
     *   restart   进程退出后是否自动重启（指数退避 1s→60s）
     */
    data class Config(
        val id: String,
        val name: String,
        val script: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val restart: Boolean = false,
        val cmd: String? = null,
    )

    /** node 可执行文件（安装后位于 nativeLibraryDir） */
    fun nodeBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /** 所有实例共用的基础环境变量 */
    fun baseEnv(context: Context): Map<String, String> = mapOf(
        // 依赖库查找路径（bionic linker 按此解析 DT_NEEDED）
        "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
        // os.homedir() → filesDir
        "HOME" to context.filesDir.absolutePath,
        // os.tmpdir() → cacheDir
        "TMPDIR" to context.cacheDir.absolutePath,
        // 让脚本可以 require('nodejsbox')（内置桥接模块，见 assets/modules/）
        "NODE_PATH" to File(context.filesDir, "modules").absolutePath,
        // npm 全局安装位置（默认会指向只读的 nativeLibraryDir，必须重定向）
        "npm_config_prefix" to File(context.filesDir, "npm-global").absolutePath,
        // npm 缓存位置（HOME 已是 filesDir，默认 ~/.npm 也在沙箱内，显式声明更稳）
        "npm_config_cache" to File(context.filesDir, "npm-cache").absolutePath,
    )

    /** 解析脚本路径：绝对路径原样，相对路径基于 filesDir */
    fun resolveScript(context: Context, script: String): File =
        if (script.startsWith("/")) File(script) else File(context.filesDir, script)

    /**
     * 按运行配置 spawn 一个进程（stdout/stderr 已合并，cwd=filesDir）。
     * - config.cmd 非空：执行单行命令。首 token 为 `node` 时直接 exec node（**不经 sh**——
     *   否则 SIGTERM/SIGINT 会发给 sh，依赖其转发，node 收不到信号且退出码错乱）；
     *   其余系统命令（可能含 shell 语法）仍交给 /system/bin/sh -c。
     * - 否则：执行 node 脚本 config.script + args。
     */
    fun spawn(context: Context, config: Config): Process {
        // 桥接服务幂等启动：保证每个进程都能拿到桥接 env
        BridgeServer.ensureStarted(context)
        // 有任何实例即将跑起：确保前台服务保活（否则 App 退后台后进程会被系统回收）
        ContainerService.ensureAlive(context)

        val nodeBin = nodeBinary(context)
        check(nodeBin.isFile) { "未找到 node 二进制: ${nodeBin.absolutePath}" }

        val cmdList: List<String> = if (!config.cmd.isNullOrBlank()) {
            commandArgv(context, config.cmd!!.trim())
        } else {
            val script = resolveScript(context, config.script)
            check(script.isFile) { "脚本不存在: ${script.absolutePath}" }
            check(script.length() > 0) { "脚本是空文件（0 字节）: ${script.absolutePath}" }
            mutableListOf(nodeBin.absolutePath, script.absolutePath).apply { addAll(config.args) }
        }
        Log.i(TAG, "spawn [${config.id}]: ${cmdList.joinToString(" ")}")
        Diag.log("[spawn] id=${config.id} argv=$cmdList")

        val pb = ProcessBuilder(cmdList)
        pb.redirectErrorStream(true)
        pb.directory(context.filesDir)
        val env = pb.environment()
        for ((k, v) in baseEnv(context)) env[k] = v
        for ((k, v) in BridgeServer.envForSpawn()) env[k] = v
        for ((k, v) in config.env) env[k] = v
        return pb.start()
    }

    /**
     * cmd → argv：
     *  - 首个 token 为 `node`：替换为内置 node 绝对路径，直接 exec（信号直达 node 进程）；
     *    双引号包裹的部分作为一个参数（支持含空格路径），无嵌套转义。
     *  - 其它：原样交给 /system/bin/sh -c（保留管道/重定向等 shell 能力）。
     */
    private fun commandArgv(context: Context, cmd: String): List<String> {
        val oneLine = cmd.replace(Regex("\\s+"), " ").trim()
        return buildNodeArgv(nodeBinary(context).absolutePath, oneLine)
    }

    // ----------------------------- cmd 解析（纯函数，可单元测试） -----------------------------

    /** 按空白分词，双引号内的空格不分隔（引号本身剔除） */
    internal fun tokenizeCmd(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in s) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch == ' ' && !inQuote -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** cmd 单行 → argv（node 路径由调用方传入） */
    internal fun buildNodeArgv(nodePath: String, oneLine: String): List<String> {
        val tokens = tokenizeCmd(oneLine)
        if (tokens.isEmpty()) throw IllegalArgumentException("命令为空")
        return if (tokens[0] == "node") {
            listOf(nodePath) + tokens.drop(1)
        } else {
            listOf("/system/bin/sh", "-c", oneLine)
        }
    }
}
