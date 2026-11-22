package vn.codex.skinlab

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal fun importRouteRoot(route: String): File = when (route) {
    "garena" -> File("/storage/emulated/0/Android/data/com.garena.game.kgvn/files")
    "memecheat" -> File("/storage/emulated/0/Android/data/com.memecheat.kgvn/files")
    else -> error("Chọn đường dẫn Garena hoặc Memecheat trong tab Nhập.")
}

/** Tab Nhập only: no ADB/Shizuku commands or connection preconditions. */
internal fun applyImportedDirect(store: ResourceStore, file: File, category: String, work: File, phase: (String) -> Unit = {}) {
    require(category in listOf("Map cờ", "Map 5v5", "Skin"))
    if (category != "Map cờ") {
        file.inputStream().use { ResourceInstaller.install(store, it, false, phase, work) }
        return
    }
    fun hash(input: InputStream): ByteArray = input.use { source ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val size = source.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest()
    }
    val previous = File.createTempFile("import-before-", ".bundle", work)
    var retain = false
    try {
        val existed = store.exists(MAP_RELATIVE)
        if (existed) store.read(MAP_RELATIVE).use { input -> previous.outputStream().use { input.copyTo(it) } }
        fun put(source: File) {
            source.inputStream().use { store.write(MAP_RELATIVE, it) }
            check(hash(source.inputStream()).contentEquals(hash(store.read(MAP_RELATIVE)))) { "Tệp sau khi ghi không khớp." }
        }
        try { phase("Đang ghi tệp…"); put(file) } catch (failure: Exception) {
            val rollback = runCatching { if (existed) put(previous) else if (store.exists(MAP_RELATIVE)) store.delete(MAP_RELATIVE) }
            if (rollback.isFailure) { retain = true; error("Ghi tệp chưa hoàn tất. Bản sao trước thao tác được giữ trong SenceLab.") }
            throw failure
        }
    } finally { if (!retain) previous.delete() }
}
