package com.nodejsbox.container.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * NodeRuntime 命令行解析的单元测试（JVM 本地跑）。
 * 重点回归：cmd 型实例必须直接 exec node（不经 sh），否则信号（SIGTERM/SIGINT）到不了 node。
 */
class NodeRuntimeArgvTest {

    @Test
    fun `tokenize 按空白分词`() {
        assertEquals(listOf("node", "scripts/hello.js"), NodeRuntime.tokenizeCmd("node scripts/hello.js"))
        assertEquals(listOf("a", "b", "c"), NodeRuntime.tokenizeCmd("  a   b  c  "))
    }

    @Test
    fun `tokenize 双引号内空格不分隔`() {
        assertEquals(
            listOf("node", "/data/data/x/files/my dir/app.js"),
            NodeRuntime.tokenizeCmd("""node "/data/data/x/files/my dir/app.js"""")
        )
    }

    @Test
    fun `tokenize 引号成对剔除`() {
        assertEquals(listOf("pm", "install", "-r"), NodeRuntime.tokenizeCmd("pm install -r"))
        assertEquals(listOf("echo", "a b"), NodeRuntime.tokenizeCmd("echo \"a b\""))
    }

    @Test
    fun `tokenize 空串返回空列表`() {
        assertEquals(emptyList<String>(), NodeRuntime.tokenizeCmd(""))
        assertEquals(emptyList<String>(), NodeRuntime.tokenizeCmd("   "))
    }

    @Test
    fun `node 命令直接 exec 不经 sh`() {
        val argv = NodeRuntime.buildNodeArgv("/x/libnode.so", "node scripts/hello.js")
        assertEquals(
            listOf("/x/libnode.so", "scripts/hello.js"),
            argv
        )
        assertEquals("/x/libnode.so", argv[0]) // argv[0] = node 本体，信号直达
    }

    @Test
    fun `node 命令支持引号路径参数`() {
        val argv = NodeRuntime.buildNodeArgv("/n", """node "/my dir/a.js" --user=admin""")
        assertEquals(listOf("/n", "/my dir/a.js", "--user=admin"), argv)
    }

    @Test
    fun `非 node 命令走 sh -c 保留 shell 能力`() {
        val argv = NodeRuntime.buildNodeArgv("/n", "ls -la | grep x")
        assertEquals(listOf("/system/bin/sh", "-c", "ls -la | grep x"), argv)
    }

    @Test
    fun `node 出现在中部不替换`() {
        // 只有首 token 是 node 才翻译；"echo node" 是普通命令
        val argv = NodeRuntime.buildNodeArgv("/n", "echo node test")
        assertEquals(listOf("/system/bin/sh", "-c", "echo node test"), argv)
    }

    @Test
    fun `空命令抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeRuntime.buildNodeArgv("/n", "")
        }
    }
}
