package vn.codex.skinlab

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

internal const val GAME_MAP_PATH =
    "/storage/emulated/0/Android/data/com.garena.game.kgvn/files/Extra/2022.V3/" +
        "assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle"

/** Shizuku exposes only this single file, never a caller-supplied path or shell command. */
class MapFileService : IMapFile.Stub() {
    @Synchronized override fun installResourcesAt(archive: ParcelFileDescriptor, replace: Boolean, root: String) {
        ParcelFileDescriptor.AutoCloseInputStream(archive).use { ResourceInstaller.install(DiskResources(allowedResourceRoot(root)), it, replace, workDirectory = File("/data/local/tmp")) }
    }
    @Synchronized override fun deleteResourceBackupsAt(root: String): Int = ResourceInstaller.deleteBackups(DiskResources(allowedResourceRoot(root)))
    @Synchronized override fun canWriteResourcesAt(root: String): Boolean = ResourceInstaller.canWrite(DiskResources(allowedResourceRoot(root)))
    @Synchronized override fun canWriteResources(): Boolean = ResourceInstaller.canWrite(DiskResources())
    @Synchronized override fun installResources(archive: ParcelFileDescriptor, replace: Boolean) {
        ParcelFileDescriptor.AutoCloseInputStream(archive).use { ResourceInstaller.install(DiskResources(), it, replace, workDirectory = File("/data/local/tmp")) }
    }
    @Synchronized override fun deleteResourceBackups(): Int = ResourceInstaller.deleteBackups(DiskResources())
    private val target = File(GAME_MAP_PATH)

    @Synchronized
    override fun fixData(archive: ParcelFileDescriptor) {
        val parent = File("/storage/emulated/0/Android/data/com.garena.game.kgvn/files")
        val extra = File(parent, "Extra")
        val backup = File(parent, "Extra1")
        val stage = File(parent, "SenceLabExtraPending")
        ParcelFileDescriptor.AutoCloseInputStream(archive).use { input ->
            check(!backup.exists()) { "Extra1 đã tồn tại. Giữ lại bản sao này trước khi Fix Data lần nữa." }
            check(extra.isDirectory) { "Không thấy thư mục Extra." }
            check(stage.mkdir()) { "Không tạo được thư mục tạm. Android có thể đang chặn quyền ghi." }
            try {
                var total = 0L
                var count = 0
                java.util.zip.ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        check(++count <= 100000) { "Tệp ZIP chứa quá nhiều mục." }
                        val name = entry.name.replace('\\', '/')
                        val relative = name.removePrefix("Extra/")
                        if (name == "Extra/" && entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        check(!name.startsWith('/') && !name.contains(':')) { "Đường dẫn ZIP không hợp lệ." }
                        val destination = File(stage, relative).canonicalFile
                        check(destination.path.startsWith(stage.canonicalPath + File.separator)) { "Đường dẫn ZIP vượt thư mục Extra." }
                        if (entry.isDirectory) {
                            check(destination.isDirectory || destination.mkdirs())
                        } else {
                            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                            destination.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val size = zip.read(buffer)
                                    if (size < 0) break
                                    total += size
                                    check(total <= 4L * 1024 * 1024 * 1024) { "Dữ liệu giải nén vượt 4 GB." }
                                    output.write(buffer, 0, size)
                                }
                                output.fd.sync()
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(File(stage, "2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle").length() > 0) { "extra.zip thiếu tệp map tại đường dẫn yêu cầu." }
                check(extra.renameTo(backup)) { "Android từ chối đổi tên Extra thành Extra1." }
                if (!stage.renameTo(extra)) {
                    check(backup.renameTo(extra)) { "Không khôi phục được Extra. Bản gốc đang nằm tại Extra1." }
                    error("Không đưa được dữ liệu mới vào Extra. Đã khôi phục bản gốc.")
                }
            } finally {
                if (stage.exists()) stage.deleteRecursively()
            }
        }
    }

    override fun canWrite(): Boolean = probe() == "Sẵn sàng"

    override fun probe(): String {
        try {
            val fd = Os.open(GAME_MAP_PATH, OsConstants.O_RDONLY, 0)
            try {
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return "Đường dẫn không trỏ tới tệp thường"
                if (Os.read(fd, ByteArray(1), 0, 1) == 0) return "Tệp đích trống"
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            return when (e.errno) {
                OsConstants.ENOENT, OsConstants.ENOTDIR -> "Shizuku không thấy tệp đích (ENOENT)"
                OsConstants.EACCES, OsConstants.EPERM -> "Android từ chối đọc tệp (${e.errno})"
                else -> "Không đọc được tệp (${e.errno})"
            }
        }
        return try {
            // No O_TRUNC or O_CREAT: this is only a permission check.
            Os.close(Os.open(GAME_MAP_PATH, OsConstants.O_WRONLY, 0))
            "Sẵn sàng"
        } catch (e: ErrnoException) {
            "Đọc được, chưa mở được quyền ghi (${e.errno})"
        }
    }

    override fun openOriginal(): ParcelFileDescriptor =
        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)

    @Synchronized
    override fun replace(replacement: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(replacement).use { input ->
            check(canWrite()) { "Shizuku không có quyền ghi vào tệp map." }
            target.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }
}
