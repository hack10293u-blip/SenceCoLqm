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
internal const val LDPLAYER_FILES = "/storage/emulated/0/Android/data/com.garena.game.kgvn64x/files"
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
    override fun write(path: String, input: InputStream) { mkdir(path.substringBeforeLast('/', "")); file(path).outputStream().use { input.copyTo(it); it.fd.sync() } }
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
    fun resolve(path: String): Uri? {
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
        mkdir(path.substringBeforeLast('/', ""))
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
    fun install(store: ResourceStore, input: InputStream, replace: Boolean, phase: (String) -> Unit = {}, workDirectory: File = File(System.getProperty("java.io.tmpdir") ?: ".")) {
        val work = File.createTempFile("sence-resources-", ".tmp", workDirectory)
        check(work.delete() && work.mkdir()) { "Không tạo được bộ nhớ tạm riêng." }
        val extracted = File(work, "extracted").apply { check(mkdir()) }
        val snapshots = File(work, "snapshots").apply { check(mkdir()) }
        val changes = mutableListOf<Pair<String, File?>>()
        val createdDirectories = mutableListOf<String>()
        val payload = mutableListOf<Pair<String, File>>()
        val requiredDirectories = linkedSetOf<String>()
        var backup: String? = null
        var started = false
        var retain = false
        fun hash(stream: InputStream): ByteArray = stream.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val size = source.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest()
        }
        fun ensureDirectory(path: String) {
            var current = ""
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                current = if (current.isEmpty()) part else "$current/$part"
                if (!store.exists(current)) { store.mkdir(current); createdDirectories.add(current) }
            }
        }
        fun put(path: String, file: File) {
            file.inputStream().use { store.write(path, it) }
            check(hash(file.inputStream()).contentEquals(hash(store.read(path)))) { "Tệp sau khi ghi không khớp: $path" }
        }
        try {
            phase("Đang chuẩn bị tài nguyên…")
            val names = HashSet<String>()
            var bytes = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("Resources/").trimEnd('/')
                    if (name.isEmpty() && entry.isDirectory) { zip.closeEntry(); continue }
                    require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.none { it.isISOControl() } && name.split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(name)) { "Đường dẫn trong ZIP không hợp lệ hoặc trùng lặp." }
                    require(names.size <= 100000) { "ZIP chứa quá nhiều mục." }
                    val destination = File(extracted, name)
                    var directory = if (entry.isDirectory) name else name.substringBeforeLast('/', "")
                    while (directory.isNotEmpty()) {
                        requiredDirectories.add(directory)
                        directory = directory.substringBeforeLast('/', "")
                    }
                    if (entry.isDirectory) check(destination.isDirectory || destination.mkdirs()) else {
                        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val size = zip.read(buffer); if (size < 0) break
                                bytes += size
                                require(bytes <= 4L * 1024 * 1024 * 1024) { "Dữ liệu giải nén vượt 4 GB." }
                                output.write(buffer, 0, size)
                            }
                            output.fd.sync()
                        }
                        payload.add(name to destination)
                    }
                    zip.closeEntry()
                }
            }
            require(requiredDirectories.contains("1.64.1")) { "ZIP phải chứa Resources/1.64.1 hoặc 1.64.1 ở thư mục gốc." }
            phase(if (replace) "Đang cập nhật tài nguyên…" else "Đang áp dụng tài nguyên…")
            if (replace && store.exists("Resources")) {
                val largest = store.list("").mapNotNull { if (it.second && backupName.matches(it.first)) it.first.removePrefix("Resources").toLongOrNull() else null }.maxOrNull() ?: 0
                check(largest < Long.MAX_VALUE)
                val nextBackup = "Resources${largest + 1}"
                backup = nextBackup
                store.rename("Resources", nextBackup)
            }
            started = true
            ensureDirectory("Resources")
            for (directory in requiredDirectories.sortedBy { it.count { char -> char == '/' } }) {
                ensureDirectory("Resources/$directory")
            }
            for ((name, file) in payload) {
                val target = "Resources/$name"
                ensureDirectory(target.substringBeforeLast('/'))
                if (!replace) {
                    val snapshot = if (store.exists(target)) File(snapshots, name).apply {
                        check(parentFile!!.isDirectory || parentFile!!.mkdirs())
                        store.read(target).use { source -> outputStream().use { source.copyTo(it) } }
                    } else null
                    changes.add(target to snapshot)
                }
                put(target, file)
            }
            phase("Đang kiểm tra đầy đủ thư mục…")
            val verifiedParents = mutableMapOf<String, List<Pair<String, Boolean>>>()
            for (directory in requiredDirectories) {
                val path = "Resources/$directory"
                val parent = path.substringBeforeLast('/')
                val children = verifiedParents.getOrPut(parent) { store.list(parent) }
                check(children.any { it.first == path.substringAfterLast('/') && it.second }) { "Thiếu thư mục sau khi cập nhật: $directory" }
            }
            phase("Đã cập nhật tài nguyên.")
        } catch (failure: Exception) {
            if (started || backup != null) {
                val recovered = runCatching {
                    if (replace) {
                        val existingBackup = backup?.takeIf { store.exists(it) }
                        if (existingBackup != null) {
                            if (store.exists("Resources")) store.delete("Resources")
                            store.rename(existingBackup, "Resources")
                        } else if (backup == null && started) {
                            if (store.exists("Resources")) store.delete("Resources")
                        } else check(store.exists("Resources")) { "Chưa xác định được tài nguyên cũ sau khi mất kết nối." }
                    } else {
                        for ((path, snapshot) in changes.asReversed()) {
                            if (snapshot == null) { if (store.exists(path)) store.delete(path) } else put(path, snapshot)
                        }
                        for (directory in createdDirectories.asReversed()) if (store.exists(directory)) store.delete(directory)
                    }
                }
                if (recovered.isFailure) { retain = true; error("Thao tác thất bại và chưa khôi phục hết. Bản sao Resources nằm tại ${backup ?: work.path}. Lỗi ban đầu: ${failure.message}") }
            }
            throw failure
        } finally {
            if (!retain) work.deleteRecursively()
        }
    }
}

internal const val MAP_RELATIVE = "Resources/1.64.1/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle"
internal const val LDPLAYER_MAP_RELATIVE = "Extra/2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle"
internal const val LDPLAYER_MAP_PATH = "$LDPLAYER_FILES/$LDPLAYER_MAP_RELATIVE"
internal const val SKIN_RELATIVE = "Resources/1.64.1/Databin/Client/AutoChess/ResACChessInfoCfg.bytes"

/** Test a disposable sibling, never create or truncate the actual game file during a check. */
internal fun probeResourceWrite(store: ResourceStore, target: String = MAP_RELATIVE): Boolean {
    var parent = target.substringBeforeLast('/', "")
    try { while (parent.isNotEmpty() && !store.exists(parent)) parent = parent.substringBeforeLast('/', "") } catch (_: Exception) { return false }
    val scratch = (if (parent.isEmpty()) "" else "$parent/") + ".sencelab-probe-" + UUID.randomUUID()
    return try {
        if (store.exists(target)) store.read(target).use { it.read() }
        store.write(scratch, byteArrayOf(31).inputStream())
        check(store.read(scratch).use { it.read() } == 31)
        store.delete(scratch)
        true
    } catch (_: Exception) { false } finally { runCatching { if (store.exists(scratch)) store.delete(scratch) } }
}
