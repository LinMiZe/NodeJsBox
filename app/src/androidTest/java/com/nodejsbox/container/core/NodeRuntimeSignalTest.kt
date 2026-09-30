package com.nodejsbox.container.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 信号传递回归测试（核心）：cmd 型实例必须直接 exec node，
 * SIGTERM/SIGINT 直达 node 进程（而不是被 sh -c 包装吃掉）。
 */
@RunWith(AndroidJUnit4::class)
class NodeRuntimeSignalTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 起一个注册了信号 handler 的 node，发信号，断言 handler 执行且退出码正确 */
    private fun spawnAndSignal(useDestroy: Boolean): String {
        // 脚本内只用单引号（双引号是 cmd 的 token 定界符）；不经 sh，无需转义。
        // handler 打印后延迟 200ms 再 exit：管道 stdout 是异步写，立即 exit 会丢日志
        val js = """
            process.on('SIGTERM', () => { console.log('RECV_SIGTERM'); setTimeout(() => process.exit(0), 200) });
            process.on('SIGINT', () => { console.log('RECV_SIGINT'); setTimeout(() => process.exit(0), 200) });
            console.log('READY');
            setInterval(() => {}, 1000);
        """.trimIndent()
        val cfg = NodeRuntime.Config(
            id = "test-signal-${System.nanoTime()}",
            name = "signal test",
            script = "",
            cmd = """node -e "$js"""",
        )
        val proc = NodeRuntime.spawn(ctx, cfg)

        // 等 READY（handler 注册完成）
        val lines = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    lines.add(line)
                    if (line == "READY") ready.countDown()
                }
            } catch (_: Exception) {
                // destroy() 时 Android 会关闭流，读取以 InterruptedIOException 结束——属预期
            }
        }
        reader.start()
        assertTrue("node 未在 5s 内就绪（${lines.joinToString()}）", ready.await(5, TimeUnit.SECONDS))

        // 发信号。注意不能用 proc.destroy()：Android libcore 的 destroy() 发的是
        // SIGNAL_QUIT(3) 而非 SIGTERM——与 RuntimeManager.stop 的实现保持一致
        if (useDestroy) {
            android.os.Process.sendSignal(pid(proc), 15) // SIGTERM
        } else {
            android.os.Process.sendSignal(pid(proc), 2) // SIGINT
        }
        val code = proc.waitFor()
        reader.join(3000)

        val all = lines.joinToString("\n")
        assertTrue("node 未收到信号（输出: $all）", all.contains(if (useDestroy) "RECV_SIGTERM" else "RECV_SIGINT"))
        assertEquals("退出码应为 0（handler 优雅退出）", 0, code)
        return all
    }

    private fun pid(p: Process): Int {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("pid")
                f.isAccessible = true
                return f.getInt(p)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Exception) {
                return -1
            }
        }
        return -1
    }

    @Test
    fun sigtermReachesNodeHandler() {
        val out = spawnAndSignal(useDestroy = true)
        assertTrue(out.contains("RECV_SIGTERM"))
    }

    @Test
    fun sigintReachesNodeHandler() {
        val out = spawnAndSignal(useDestroy = false)
        assertTrue(out.contains("RECV_SIGINT"))
    }

    @Test
    fun nonNodeCommandGoesThroughSh() {
        val cfg = NodeRuntime.Config(id = "t-sh", name = "", script = "", cmd = "echo sh_ok")
        val proc = NodeRuntime.spawn(ctx, cfg)
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor())
        assertTrue(out.contains("sh_ok"))
    }
}
