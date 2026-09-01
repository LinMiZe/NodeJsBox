package com.nodejsbox.container

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 内嵌 Node 二进制的启动封装。
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
    )

    /** 解析脚本路径：绝对路径原样，相对路径基于 filesDir */
    fun resolveScript(context: Context, script: String): File =
        if (script.startsWith("/")) File(script) else File(context.filesDir, script)

    /** 按运行配置 spawn 一个 node 进程（stdout/stderr 已合并，cwd=filesDir） */
    fun spawn(context: Context, config: RunConfigs.Config): Process {
        val nodeBin = nodeBinary(context)
        check(nodeBin.isFile) { "未找到 node 二进制: ${nodeBin.absolutePath}" }
        val script = resolveScript(context, config.script)
        check(script.isFile) { "脚本不存在: ${script.absolutePath}" }

        val cmd = mutableListOf(nodeBin.absolutePath, script.absolutePath)
        cmd.addAll(config.args)
        Log.i(TAG, "spawn [${config.id}]: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.directory(context.filesDir)
        val env = pb.environment()
        for ((k, v) in baseEnv(context)) env[k] = v
        for ((k, v) in config.env) env[k] = v
        return pb.start()
    }
}
