package vn.codex.skinlab

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as D
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal const val GAME_FILES = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files"
internal fun allowedResourceRoot(path: String): File {
    require(path.startsWith('/')) { "Nhập đường dẫn đầy đủ." }
    val entered = File(path).canonicalFile
    val root = if (entered.name == "Resources") entered.parentFile ?: entered else entered
    val bases = listOf(GAME_FILES, "/storage/emulated/0/Android/data/com.memecheat.kgvn/files").map { File(it).canonicalPath }
    require(bases.any { root.path == it || root.path.startsWith(it + File.separator) }) { "Đường dẫn nằm ngoài thư mục files của hai gói trò chơi đã hỗ trợ." }
    return root
}
internal interface ResourceStore {
    fun list(path: String): List<Pair<String, Boolean>>
    fun exists(path: String): Boolean
    fun mkdir(path: String)
    fun read(path: String): InputStream
    fun write(path: String, input: InputStream)
    fun rename(from: String, to: String)
    fun delete(path: String)
}

internal class DiskResources(private val root: File = File(GAME_FILES)) : ResourceStore {
    private fun file(path: String): File {
        val value = File(root, path).canonicalFile
        require(value.path == root.canonicalPath || value.path.startsWith(root.canonicalPath + File.separator))
        return value
    }
    override fun list(path: String) = (file(path).listFiles() ?: error("Không đọc được thư mục $path")).map { it.name to it.isDirectory }
    override fun exists(path: String) = file(path).exists()
    override fun mkdir(path: String) { check(file(path).isDirectory || file(path).mkdirs()) { "Không tạo được $path" } }
    override fun read(path: String) = file(path).inputStream()
    override fun write(path: String, input: InputStream) { file(path).outputStream().use { input.copyTo(it); it.fd.sync() } }
    override fun rename(from: String, to: String) { check(!exists(to) && file(from).renameTo(file(to))) { "Không đổi tên được $from thành $to" } }
    override fun delete(path: String) { check(path.isNotEmpty() && file(path).deleteRecursively()) { "Không xóa được $path" } }
}

internal class SafResources(private val resolver: ContentResolver, private val tree: Uri) : ResourceStore {
    private val root = D.buildDocumentUriUsingTree(tree, D.getTreeDocumentId(tree))
    private fun children(parent: Uri): List<Triple<String, Boolean, Uri>> {
        val result = mutableListOf<Triple<String, Boolean, Uri>>()
        val uri = D.buildChildDocumentsUriUsingTree(tree, D.getDocumentId(parent))
        val columns = arrayOf(D.Document.COLUMN_DISPLAY_NAME, D.Document.COLUMN_MIME_TYPE, D.Document.COLUMN_DOCUMENT_ID)
        val cursor = resolver.query(uri, columns, null, null, null) ?: error("Không đọc được thư mục SAF.")
        cursor.use { while (it.moveToNext()) result.add(Triple(it.getString(0), it.getString(1) == D.Document.MIME_TYPE_DIR, D.buildDocumentUriUsingTree(tree, it.getString(2)))) }
        return result
    }
    private fun resolve(path: String): Uri? {
        var current = root
        for (part in path.split('/').filter { it.isNotEmpty() }) current = children(current).firstOrNull { it.first == part }?.third ?: return null
        return current
    }
    override fun list(path: String) = children(resolve(path) ?: error("Không thấy $path")).map { it.first to it.second }
    override fun exists(path: String) = resolve(path) != null
    override fun mkdir(path: String) {
        var current = root
        for (part in path.split('/').filter { it.isNotEmpty() }) current = children(current).firstOrNull { it.first == part }?.third
            ?: D.createDocument(resolver, current, D.Document.MIME_TYPE_DIR, part) ?: error("Không tạo được $part")
    }
    override fun read(path: String): InputStream = resolver.openInputStream(resolve(path) ?: error("Không thấy $path")) ?: error("Không đọc được $path")
    override fun write(path: String, input: InputStream) {
        val parent = resolve(path.substringBeforeLast('/', "")) ?: error("Không thấy thư mục đích.")
        val uri = resolve(path) ?: D.createDocument(resolver, parent, "application/octet-stream", path.substringAfterLast('/')) ?: error("Không tạo được tệp.")
        (resolver.openOutputStream(uri, "wt") ?: error("Không ghi được $path")).use { input.copyTo(it) }
    }
    override fun rename(from: String, to: String) {
        require(from.substringBeforeLast('/', "") == to.substringBeforeLast('/', ""))
        check(!exists(to) && D.renameDocument(resolver, resolve(from) ?: error("Không thấy $from"), to.substringAfterLast('/')) != null) { "Không đổi tên được $from" }
    }
    override fun delete(path: String) { require(path.isNotEmpty()); check(D.deleteDocument(resolver, resolve(path) ?: return)) { "Không xóa được $path" } }
}

internal object ResourceInstaller {
    fun canWrite(store: ResourceStore): Boolean {
        val original = "SencePermission-${UUID.randomUUID()}"
        val renamed = "$original-renamed"
        return try {
            store.mkdir(original)
            store.write("$original/probe", byteArrayOf(1).inputStream())
            check(store.read("$original/probe").use { it.read() } == 1)
            store.rename(original, renamed)
            store.delete(renamed)
            true
        } catch (_: Exception) { false } finally {
            runCatching { if (store.exists(original)) store.delete(original) }
            runCatching { if (store.exists(renamed)) store.delete(renamed) }
        }
    }
    private val backupName = Regex("Resources[1-9][0-9]*")
    fun deleteBackups(store: ResourceStore): Int {
        val backups = store.list("").filter { it.second && backupName.matches(it.first) }
        backups.forEach { store.delete(it.first) }
        return backups.size
    }
    fun install(store: ResourceStore, input: InputStream, replace: Boolean, phase: (String) -> Unit = {}) {
        val stage = "SenceResources-${UUID.randomUUID()}"
        val rollback = "SenceResourcesOld-${UUID.randomUUID()}"
        store.mkdir(stage)
        var old: String? = null
        var committed = false
        fun hash(stream: InputStream): ByteArray = stream.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest()
        }
        fun clone(from: String, to: String) {
            store.mkdir(to)
            for ((name, directory) in store.list(from)) {
                require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/'))
                if (directory) clone("$from/$name", "$to/$name") else store.read("$from/$name").use { store.write("$to/$name", it) }
            }
        }
        try {
            if (!replace && store.exists("Resources")) { phase("Đang giữ dữ liệu hiện tại…"); clone("Resources", stage) }
            phase("Đang giải nén và ghi tệp…")
            val names = HashSet<String>()
            var bytes = 0L
            var files = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("Resources/").trimEnd('/')
                    if (name.isEmpty() && entry.isDirectory) { zip.closeEntry(); continue }
                    require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.none { it.isISOControl() } && name.split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(name)) { "Đường dẫn trong ZIP không hợp lệ hoặc trùng lặp." }
                    require(names.size <= 100000) { "ZIP chứa quá nhiều mục." }
                    val destination = "$stage/$name"
                    if (entry.isDirectory) store.mkdir(destination) else {
                        store.mkdir(destination.substringBeforeLast('/'))
                        val digest = MessageDigest.getInstance("SHA-256")
                        val tracked = object : java.io.FilterInputStream(zip) {
                            override fun close() = Unit
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                val count = zip.read(buffer, offset, length)
                                if (count > 0) { bytes += count; require(bytes <= 4L * 1024 * 1024 * 1024) { "Giải nén vượt 4 GB." }; digest.update(buffer, offset, count) }
                                return count
                            }
                            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
                        }
                        store.write(destination, tracked)
                        check(digest.digest().contentEquals(hash(store.read(destination)))) { "Tệp sau khi ghi không khớp: $name" }
                        files++
                    }
                    zip.closeEntry()
                }
            }
            require(files > 0) { "ZIP không có tệp dữ liệu." }
            phase("Đang hoàn tất Resources…")
            if (store.exists("Resources")) {
                old = if (replace) {
                    val largest = store.list("").mapNotNull { if (backupName.matches(it.first)) it.first.removePrefix("Resources").toLongOrNull() else null }.maxOrNull() ?: 0
                    check(largest < Long.MAX_VALUE)
                    "Resources${largest + 1}"
                } else rollback
                store.rename("Resources", old!!)
            }
            try { store.rename(stage, "Resources"); committed = true } catch (failure: Exception) {
                old?.let { store.rename(it, "Resources") }
                throw failure
            }
            if (!replace && old != null) store.delete(old!!)
        } finally {
            if (!committed && store.exists(stage)) store.delete(stage)
        }
    }
}
