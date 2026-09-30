package com.nodejsbox.container.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/**
 * 数据目录文档提供程序（Termux 模式）。
 *
 * 把本 app 的 filesDir 挂成 SAF 文档树：第三方文件管理器（Mix 等）通过
 * 「添加存储 → 文档提供程序 → NodeJsBox」即可直接浏览/编辑数据目录，
 * 且文件真实落在同一物理目录 —— node 进程直接读写，无需拷贝。
 *
 * 实现说明：DocumentsProvider 的抽象方法较多，这里按"目录树 + 文件"最小集实现
 * （列目录 / 打开读写 / 新建 / 删除 / 重命名），flags 声明支持写。
 */
class FilesDocumentsProvider : DocumentsProvider() {

    companion object {
        /** 与 AndroidManifest 中 provider 的 android:authorities 一致 */
        const val AUTHORITY = "com.nodejsbox.container.documents"
        const val ROOT_ID = "files"
        private val DEFAULT_MIME = DocumentsContract.Document.MIME_TYPE_DIR
        /** 声明支持的操作（可写/可删除/可重命名/可复制/可移动） */
        private val FILE_FLAGS = DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
            DocumentsContract.Document.FLAG_SUPPORTS_COPY or
            DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        /** 目录额外支持：可创建子项 */
        private val DIR_FLAGS = FILE_FLAGS or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
    }

    private lateinit var baseDir: File

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        baseDir = ctx.filesDir
        baseDir.mkdirs()
        return true
    }

    private fun rootFile(): File = baseDir

    /** docId = 相对 filesDir 的路径（根为 ROOT_ID，子项为 "scripts/foo.js"） */
    private fun fileFor(docId: String): File = when (docId) {
        ROOT_ID -> rootFile()
        else -> File(baseDir, docId)
    }

    private fun docIdFor(file: File): String {
        val abs = file.canonicalPath
        val base = baseDir.canonicalPath
        if (abs == base) return ROOT_ID
        require(abs.startsWith(base + "/")) { "越权路径: $abs" }
        return abs.substring(base.length + 1)
    }

    private fun mimeFor(file: File): String = if (file.isDirectory) DEFAULT_MIME
    else {
        val n = file.name.lowercase()
        when {
            n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs") -> "text/javascript"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
            n.endsWith(".css") -> "text/css"
            n.endsWith(".txt") || n.endsWith(".log") -> "text/plain"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    /** 按调用方 projection 构造一行（列名 → 值动态匹配；DocumentsUI 的 projection 列数不定，必须动态构造） */
    private fun rowFor(cursor: MatrixCursor, file: File) {
        val docId = docIdFor(file)
        val isDir = file.isDirectory
        val flags = if (isDir) DIR_FLAGS else FILE_FLAGS
        val values = mutableListOf<Any?>()
        for (col in cursor.columnNames) {
            values.add(when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeFor(file)
                DocumentsContract.Document.COLUMN_FLAGS -> flags
                DocumentsContract.Document.COLUMN_SIZE -> if (isDir) null else file.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                DocumentsContract.Document.COLUMN_ICON -> null
                DocumentsContract.Document.COLUMN_SUMMARY -> null
                else -> null
            })
        }
        cursor.addRow(values)
    }

    // ----------------------------- 查询 -----------------------------

    private val DOC_COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    override fun queryRoots(projection: Array<String>?): Cursor {
        val p = projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        val c = MatrixCursor(p)
        val values = mutableListOf<Any?>()
        for (col in c.columnNames) {
            values.add(when (col) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_TITLE -> "NodeJsBox 数据目录"
                DocumentsContract.Root.COLUMN_SUMMARY -> "node 脚本与项目（files/）"
                DocumentsContract.Root.COLUMN_ICON -> 0
                DocumentsContract.Root.COLUMN_FLAGS ->
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                DocumentsContract.Root.COLUMN_MIME_TYPES -> null
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> baseDir.usableSpace
                else -> null
            })
        }
        c.addRow(values)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val f = fileFor(documentId)
        if (f.exists()) rowFor(c, f)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        for (f in fileFor(parentDocumentId).listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()) {
            rowFor(c, f)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    // ----------------------------- 读写 -----------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        val f = fileFor(documentId)
        if (!f.exists() && "w" in mode) f.createNewFile()
        return ParcelFileDescriptor.open(f, accessMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        var name = displayName
        if (mimeType == DEFAULT_MIME) {
            val dir = File(parent, name)
            dir.mkdirs()
            return docIdFor(dir)
        }
        // 重名自动加序号
        var f = File(parent, name)
        var i = 1
        while (f.exists()) {
            val dot = name.lastIndexOf('.')
            f = if (dot > 0) File(parent, "${name.substring(0, dot)}($i)${name.substring(dot)}")
            else File(parent, "$name($i)")
            i++
        }
        f.createNewFile()
        name = f.name
        return docIdFor(f)
    }

    override fun deleteDocument(documentId: String) {
        fileFor(documentId).deleteRecursively()
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fileFor(documentId)
        val target = File(f.parentFile, displayName)
        if (f.renameTo(target)) return docIdFor(target)
        throw IllegalStateException("重命名失败: $documentId → $displayName")
    }
}
