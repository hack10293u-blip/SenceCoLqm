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
    private val target = File(GAME_MAP_PATH)

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
