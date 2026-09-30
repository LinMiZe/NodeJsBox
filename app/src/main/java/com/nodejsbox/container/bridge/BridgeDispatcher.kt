package com.nodejsbox.container.bridge

import android.content.Context
import com.nodejsbox.container.core.Diag
import org.json.JSONObject

/** 命令处理中的业务错误（转为 ok=false 响应） */
class BridgeException(message: String) : Exception(message)

/** 单条命令处理器 */
fun interface BridgeCommand {
    /** @return 响应中的 data 字段（可为 null） */
    fun handle(context: Context, args: JSONObject): Any?
}

/**
 * 桥命令分发器：解析请求行 → 鉴权 → 查表执行 → 编码响应。
 *
 * 请求行格式: {"seq":1,"cmd":"proc.list","args":{...}}
 * 响应行格式: {"seq":1,"ok":true,"data":...} / {"seq":1,"ok":false,"error":"..."}
 */
object BridgeDispatcher {

    private val commands = LinkedHashMap<String, BridgeCommand>()

    /**
     * 触发各命令模块的类初始化（各模块 init 块中注册命令）。
     * BridgeServer.ensureStarted 时调用一次；新增命令模块时在此补充引用。
     */
    fun ensureCommandsRegistered() {
        ProcCommands
        FileCommands
        AppCommands
    }

    /** 注册命令（各命令文件在 init 块中调用） */
    fun register(name: String, cmd: BridgeCommand) {
        commands[name] = cmd
    }

    fun knownCommands(): Set<String> = commands.keys

    fun verifyHandshake(line: String, expectedToken: String): Boolean = try {
        JSONObject(line).optString("token") == expectedToken && expectedToken.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    fun dispatch(context: Context, line: String): String {
        val req = try { JSONObject(line) } catch (e: Exception) {
            return errorJson(-1, "请求不是合法 JSON: ${e.message}")
        }
        val seq = req.optInt("seq", -1)
        val cmd = req.optString("cmd")
        Diag.log("[bridge] cmd=$cmd seq=$seq args=${req.optJSONObject("args")}")
        val handler = commands[cmd] ?: return errorJson(seq, "未知命令: $cmd（可用: ${knownCommands().joinToString(",")}）")
        return try {
            val data = handler.handle(context, req.optJSONObject("args") ?: JSONObject())
            Diag.log("[bridge] cmd=$cmd seq=$seq ok")
            okJson(seq, data)
        } catch (e: BridgeException) {
            Diag.log("[bridge] cmd=$cmd seq=$seq 失败: ${e.message}")
            errorJson(seq, e.message ?: "执行失败")
        } catch (e: Exception) {
            Diag.log("[bridge] cmd=$cmd seq=$seq 异常: ${e.message}")
            errorJson(seq, "内部错误: ${e.message}")
        }
    }

    private fun okJson(seq: Int, data: Any?): String {
        val o = JSONObject()
        o.put("seq", seq)
        o.put("ok", true)
        o.put("data", data ?: JSONObject.NULL)
        return o.toString()
    }

    fun errorJson(seq: Int, message: String): String {
        val o = JSONObject()
        o.put("seq", seq)
        o.put("ok", false)
        o.put("error", message)
        return o.toString()
    }
}
