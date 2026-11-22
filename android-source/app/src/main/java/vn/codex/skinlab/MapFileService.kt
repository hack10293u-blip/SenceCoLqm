package vn.codex.skinlab

import android.os.ParcelFileDescriptor
import java.io.File

internal const val GAME_MAP_PATH = "$GAME_FILES/$MAP_RELATIVE"

/** Binder operations are restricted to this game's Resources directory. */
class MapFileService : IMapFile.Stub() {
    private val store = DiskResources()
    private fun resource(relative: String): File {
        require(relative.startsWith("Resources/") && relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
        val root = File(GAME_FILES).canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + "/Resources/"))
        return target
    }
    @Synchronized override fun fixData(archive: ParcelFileDescriptor) = installArchive(archive, true)
    @Synchronized override fun installArchive(archive: ParcelFileDescriptor, replace: Boolean) {
        ParcelFileDescriptor.AutoCloseInputStream(archive).use {
            ResourceInstaller.install(store, it, replace, workDirectory = File("/data/local/tmp"))
        }
    }
    override fun canWrite(): Boolean = probe() == "Sẵn sàng"
    override fun probe(): String = if (runCatching { probeResourceWrite(store) }.getOrDefault(false)) "Sẵn sàng" else "Chưa tạo/ghi được tệp trong Resources"
    override fun openOriginal(): ParcelFileDescriptor = readResource(MAP_RELATIVE)
    @Synchronized override fun replace(replacement: ParcelFileDescriptor) = writeResource(MAP_RELATIVE, replacement)
    @Synchronized override fun writeResource(relative: String, replacement: ParcelFileDescriptor) {
        val target = resource(relative)
        ParcelFileDescriptor.AutoCloseInputStream(replacement).use { input ->
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Không tạo được thư mục đích trong Resources." }
            target.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
        }
    }
    override fun readResource(relative: String): ParcelFileDescriptor = ParcelFileDescriptor.open(resource(relative), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun resourceExists(relative: String): Boolean = resource(relative).exists()
    @Synchronized override fun removeMap() {
        val target = resource(MAP_RELATIVE)
        check(!target.exists() || target.delete()) { "Không xóa được tệp map mod." }
        check(!target.exists()) { "Tệp map vẫn còn sau khi xóa." }
    }
    override fun mapExists(): Boolean = resource(MAP_RELATIVE).exists()
    override fun destroy() { kotlin.system.exitProcess(0) }
}
