package com.nodejsbox.container.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桥命令分发器的协议层回归（org.json + Diag 依赖 Android 运行时，故为仪器测试）。
 *
 * 协议契约：
 *   请求  {"seq":n,"cmd":"x.y","args":{...}}  /  非法 JSON → seq=-1 错误行
 *   响应  {"seq":n,"ok":true,"data":...}  /  {"seq":n,"ok":false,"error":"..."}
 *   业务异常(BridgeException) → ok=false + message；
 *   意外异常 → ok=false + 内部错误（绝不能让单条命令把分发链路/连接线程搞崩）
 */
@RunWith(AndroidJUnit4::class)
class BridgeDispatcherTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        // 注册测试专用命令（独立前缀，不与真实命令域冲突；register 幂等覆盖）
        BridgeDispatcher.register("testcmd.echo") { _, args ->
            JSONObject().put("echo", args.optString("v"))
        }
        BridgeDispatcher.register("testcmd.fail") { _, _ -> throw BridgeException("业务失败原因") }
        BridgeDispatcher.register("testcmd.boom") { _, _ -> throw IllegalStateException("意外崩溃") }
        BridgeDispatcher.register("testcmd.null") { _, _ -> null }
    }

    private fun dispatch(cmd: String, args: String? = null, seq: Int = 1): JSONObject {
        val argsPart = if (args != null) ",\"args\":$args" else ""
        val line = "{\"seq\":$seq,\"cmd\":\"$cmd\"$argsPart}"
        return JSONObject(BridgeDispatcher.dispatch(ctx, line))
    }

    // ----------------------------- 正常路径 -----------------------------

    @Test
    fun echoRoundTrip() {
        val r = dispatch("testcmd.echo", "{\"v\":\"hi\"}")
        assertTrue(r.getBoolean("ok"))
        assertEquals(1, r.getInt("seq"))
        assertEquals("hi", r.getJSONObject("data").getString("echo"))
    }

    @Test
    fun seqIsPassedThrough() {
        assertEquals(42, dispatch("testcmd.echo", "{\"v\":\"\"}", seq = 42).getInt("seq"))
    }

    @Test
    fun missingArgsDefaultsToEmptyObject() {
        // 无 args 字段不抛异常，handler 收到空 JSONObject
        val r = dispatch("testcmd.echo")
        assertTrue(r.getBoolean("ok"))
        assertEquals("", r.getJSONObject("data").getString("echo"))
    }

    @Test
    fun nullDataIsExplicitJsonNull() {
        val r = dispatch("testcmd.null")
        assertTrue(r.getBoolean("ok"))
        assertTrue("data 应为 JSON null", r.isNull("data"))
    }

    // ----------------------------- 错误路径 -----------------------------

    @Test
    fun malformedJsonLineYieldsErrorRow() {
        val r = JSONObject(BridgeDispatcher.dispatch(ctx, "{not json"))
        assertFalse(r.getBoolean("ok"))
        assertEquals(-1, r.getInt("seq"))
        assertTrue(r.getString("error").contains("不是合法 JSON"))
    }

    @Test
    fun unknownCommandListsAvailable() {
        val r = dispatch("no.such")
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("未知命令: no.such"))
        assertTrue("错误信息应提示可用命令", r.getString("error").contains("可用"))
    }

    @Test
    fun bridgeExceptionBecomesOkFalseWithMessage() {
        val r = dispatch("testcmd.fail")
        assertFalse(r.getBoolean("ok"))
        assertEquals("业务失败原因", r.getString("error"))
    }

    @Test
    fun unexpectedExceptionDoesNotCrashDispatch() {
        val r = dispatch("testcmd.boom")
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("内部错误"))
        assertTrue(r.getString("error").contains("意外崩溃"))
    }

    // ----------------------------- 握手 -----------------------------

    @Test
    fun handshakeTokenMatching() {
        assertTrue(BridgeDispatcher.verifyHandshake("""{"token":"abc"}""", "abc"))
        assertFalse("token 不匹配", BridgeDispatcher.verifyHandshake("""{"token":"xxx"}""", "abc"))
        assertFalse("expectedToken 为空必须拒绝", BridgeDispatcher.verifyHandshake("""{"token":""}""", ""))
        assertFalse("非法 JSON 拒绝", BridgeDispatcher.verifyHandshake("garbage", "abc"))
    }
}
