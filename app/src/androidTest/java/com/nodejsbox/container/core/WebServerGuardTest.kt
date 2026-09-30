package com.nodejsbox.container.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WebServer 命令改写 + 路径边界的仪器测试（真实 Context）。
 * 只测纯逻辑，不起进程、不写业务文件（临时文件写 cache 下并用完即删）。
 */
@RunWith(AndroidJUnit4::class)
class WebServerGuardTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ----------------------------- shell 命令改写 -----------------------------

    @Test
    fun nodeCommandPassesThrough() {
        val r = WebServer.rewriteShellCommand(ctx, "node scripts/hello.js")
        assertEquals("node scripts/hello.js", r) // 原样交给 NodeRuntime（直接 exec node）
    }

    @Test
    fun plainShellCommandPassesThrough() {
        assertEquals("ls -l", WebServer.rewriteShellCommand(ctx, "ls -l"))
        assertEquals("echo hi | wc -l", WebServer.rewriteShellCommand(ctx, "echo hi | wc -l"))
    }

    @Test
    fun npmRewrittenOrThrowsWhenMissing() {
        val npmCli = java.io.File(ctx.filesDir, "lib/node_modules/npm/bin/npm-cli.js")
        try {
            val r = WebServer.rewriteShellCommand(ctx, "npm install vite")
            assertTrue("应改写为 node + npm-cli.js", r.contains("npm-cli.js"))
            assertTrue(r.startsWith("\""))
            assertTrue(r.endsWith("npm install vite"))
        } catch (e: IllegalArgumentException) {
            // 容器未装 npm：必须报明确指引，而非静默原样透传
            assertTrue(e.message!!.contains("tool_6"))
            if (npmCli.isFile) fail("npm-cli.js 存在却报未安装?")
        }
    }

    @Test
    fun npxAlsoRewritten() {
        try {
            val r = WebServer.rewriteShellCommand(ctx, "npx tsc --version")
            assertTrue(r.contains("npm-cli.js"))
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tool_6"))
        }
    }

    @Test
    fun npmLookalikeNotRewritten() {
        // 路径里含 npm 但不是首 token 的命令不能被误改写
        val cmd = "cat lib/npm-stuff.txt"
        assertEquals(cmd, WebServer.rewriteShellCommand(ctx, cmd))
    }

    // ----------------------------- 路径边界 -----------------------------

    @Test
    fun relativePathResolvesInsideFilesDir() {
        val f = WebServer.resolveSandboxPath(ctx, "scripts")
        assertTrue(f.path.startsWith(ctx.filesDir.canonicalFile.path))
    }

    @Test
    fun emptyPathResolvesToSandboxRoot() {
        val root = WebServer.resolveSandboxPath(ctx, "")
        assertEquals(ctx.filesDir.canonicalFile.parentFile, root)
    }

    @Test
    fun dotDotEscapeRejected() {
        for (p in listOf("../android", "scripts/../../etc", "/data/local/tmp", "/system/bin/sh")) {
            try {
                WebServer.resolveSandboxPath(ctx, p)
                fail("应拒绝越界路径: $p")
            } catch (e: SecurityException) {
                // 预期
            }
        }
    }

    @Test
    fun internalAbsolutePathAccepted() {
        val f = WebServer.resolveSandboxPath(ctx, ctx.filesDir.canonicalFile.path + "/logs")
        assertTrue(f.path.endsWith("/logs"))
    }
}
