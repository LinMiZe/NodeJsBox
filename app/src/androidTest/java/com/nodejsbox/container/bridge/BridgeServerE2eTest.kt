package com.nodejsbox.container.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nodejsbox.container.core.BridgeServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/**
 * 本地桥 TCP 链路端到端回归：真实 socket 连接 → 握手 → 命令往返。
 *
 * 覆盖：服务启动/端口/env 注入、错 token 被断开、正确 token 用真实命令（proc.info）往返、
 * 空行跳过 + 多请求串行。客户端统一 5s 读超时——服务端若不按契约响应，测试失败而非挂死。
 */
@RunWith(AndroidJUnit4::class)
class BridgeServerE2eTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        BridgeServer.stop()
    }

    private fun connected(): Socket {
        BridgeServer.ensureStarted(ctx)
        val s = Socket("127.0.0.1", BridgeServer.port)
        s.soTimeout = 5000
        return s
    }

    private fun Socket.send(line: String) {
        getOutputStream().write((line + "\n").toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    private fun Socket.reader(): BufferedReader =
        BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

    // ----------------------------- 用例 -----------------------------

    @Test
    fun startsAndInjectsEnv() {
        BridgeServer.ensureStarted(ctx)
        assertTrue("服务应处于运行状态", BridgeServer.isRunning)
        assertTrue("应监听到非零端口", BridgeServer.port > 0)
        val env = BridgeServer.envForSpawn()
        assertEquals("127.0.0.1:${BridgeServer.port}", env["NODEJSBOX_BRIDGE"])
        assertTrue("token 必须非空", env.getValue("NODEJSBOX_TOKEN").isNotEmpty())
    }

    @Test
    fun wrongTokenGetsDisconnected() {
        connected().use { s ->
            s.send("""{"token":"wrong-token"}""")
            val r = s.reader()
            assertNull("错 token 应被服务端直接断开（读到 EOF）", r.readLine())
        }
    }

    @Test
    fun roundTripWithRealCommand() {
        connected().use { s ->
            val token = BridgeServer.authToken
            s.send("""{"token":"$token"}""")
            s.send("""{"seq":7,"cmd":"proc.info","args":{"id":"no-such-instance"}}""")
            val r = JSONObject(s.reader().readLine())
            assertTrue("proc.info 对不存在实例应 ok=true data=null", r.getBoolean("ok"))
            assertEquals(7, r.getInt("seq"))
            assertTrue(r.isNull("data"))
        }
    }

    @Test
    fun blankLinesSkippedAndRequestsSerial() {
        connected().use { s ->
            val token = BridgeServer.authToken
            s.send("""{"token":"$token"}""")
            s.send("") // 空行应被跳过，不产生响应
            s.send("""{"seq":1,"cmd":"proc.info","args":{"id":"a"}}""")
            s.send("""{"seq":2,"cmd":"proc.info","args":{"id":"b"}}""")
            val reader = s.reader()
            val r1 = JSONObject(reader.readLine())
            val r2 = JSONObject(reader.readLine())
            assertEquals(1, r1.getInt("seq"))
            assertEquals(2, r2.getInt("seq"))
            assertTrue(r1.getBoolean("ok") && r2.getBoolean("ok"))
        }
    }
}
