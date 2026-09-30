package com.nodejsbox.container.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RuntimeManager 状态数据路径回归（真实 spawn node，靠轮询状态断言，不碰 UI）。
 *
 * 覆盖：动态 id 前缀强约束 / 幂等启动 / spawn 失败自愈（实例移除+日志留痕）/
 * 停止后实例移除 / 未运行时 stdin·interrupt·tailLog 的容错语义。
 * 实例 id 一律用 dyn-test-* 唯一前缀：不持久化、不跨重启恢复，@After 统一清理。
 */
@RunWith(AndroidJUnit4::class)
class RuntimeManagerStateTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 轮询等待状态谓词成立（超时 fail 并给出上下文） */
    private fun await(desc: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        fail(desc, timeoutMs)
    }

    private fun fail(desc: String, timeoutMs: Long): Nothing =
        throw AssertionError("等待超时(${timeoutMs}ms): $desc；running=${RuntimeManager.runningIds()}")

    private fun sleepCmd() = """node -e "console.log('READY'); setInterval(()=>{},1000)""""

    @After
    fun tearDown() {
        RuntimeManager.stopAll(ctx)
        await("全部测试实例停止") {
            RuntimeManager.runningIds().none { it.startsWith("dyn-test-") }
        }
    }

    // ----------------------------- 启动约束 -----------------------------

    @Test
    fun startScriptUsesPredictableIdAndIsIdempotent() {
        // --es run / H5 运行脚本入口：id 固定为 dyn-script-<去扩展名>，重复启动不重拉
        val rel = "scripts/__test_idempotent__.js"
        val expected = "dyn-script-__test_idempotent__"
        assertEquals(expected, RuntimeManager.startScript(ctx, rel))
        assertEquals(expected, RuntimeManager.startScript(ctx, rel))
        // 脚本不存在 → pump 线程 自愈移除，但 id 预测性不变
        await("失败实例被自动移除", timeoutMs = 8000) { !RuntimeManager.isRunning(expected) }
    }

    @Test
    fun dynamicIdMustCarryPrefix() {
        // 非动态前缀的 id 必须被 require 拒绝（只支持动态实例，无静态配置后门）
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManager.startWithConfig(
                ctx, NodeRuntime.Config(id = "static-lookalike", name = "x", script = "", cmd = "echo hi")
            )
        }
        assertFalse(RuntimeManager.isRunning("static-lookalike"))
    }

    @Test
    fun duplicateStartIsIdempotent() {
        val cfg = NodeRuntime.Config(id = "dyn-test-${System.nanoTime()}", name = "t", script = "", cmd = sleepCmd())
        assertTrue(RuntimeManager.startWithConfig(ctx, cfg))
        await("实例进入 running") { RuntimeManager.info(cfg.id)?.running == true }

        val startedAt = RuntimeManager.info(cfg.id)!!.startedAt
        assertTrue("重复启动应幂等返回 true", RuntimeManager.startWithConfig(ctx, cfg))
        assertEquals("不应触发重启（startedAt 不变）", startedAt, RuntimeManager.info(cfg.id)!!.startedAt)
        assertEquals("同名实例只应存在一个", 1, RuntimeManager.runningIds().count { it == cfg.id })
    }

    // ----------------------------- 失败自愈 -----------------------------

    @Test
    fun spawnFailureRemovesInstanceAndLogsError() {
        val cfg = NodeRuntime.Config(id = "dyn-test-${System.nanoTime()}", name = "t", script = "scripts/__no_such__.js")
        assertTrue("startWithConfig 本身应返回 true（失败发生在 pump 线程）", RuntimeManager.startWithConfig(ctx, cfg))
        await("失败实例被自动移除", timeoutMs = 8000) { !RuntimeManager.isRunning(cfg.id) }
        val lines = RuntimeManager.tailLog(ctx, cfg.id, 50)
        assertNotNull("失败必须留日志", lines)
        assertTrue("日志应含 ERROR 标记: $lines", lines!!.any { it.contains("ERROR: 启动失败") })
        assertTrue("日志应含具体原因: $lines", lines.any { it.contains("脚本不存在") })
    }

    @Test
    fun manualStopRemovesInstance() {
        val cfg = NodeRuntime.Config(id = "dyn-test-${System.nanoTime()}", name = "t", script = "", cmd = sleepCmd())
        RuntimeManager.startWithConfig(ctx, cfg)
        await("实例进入 running") { RuntimeManager.info(cfg.id)?.running == true }
        RuntimeManager.stop(ctx, cfg.id)
        await("停止后实例被 pump 收尾移除") { !RuntimeManager.isRunning(cfg.id) }
    }

    // ----------------------------- 未运行时的容错语义 -----------------------------

    @Test
    fun stopUnknownIdIsNoop() {
        RuntimeManager.stop(ctx, "dyn-test-never-started") // 不抛即通过
    }

    @Test
    fun stdinAndInterruptFailWhenNotRunning() {
        val absent = "dyn-test-absent"
        assertFalse(RuntimeManager.writeStdin(absent, "hi"))
        assertFalse(RuntimeManager.sendInterrupt(absent))
    }

    @Test
    fun tailLogReturnsNullForMissingFile() {
        assertNull(RuntimeManager.tailLog(ctx, "dyn-test-absent", 10))
    }
}
