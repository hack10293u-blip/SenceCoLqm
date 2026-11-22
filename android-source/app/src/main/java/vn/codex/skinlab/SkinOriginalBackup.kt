package vn.codex.skinlab

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The clean baseline survives cache cleanup, repeated applications and restoration. */
internal class SkinOriginalBackup(private val directory: File) {
    private val original = File(directory, "original.bytes")
    private val checksum = File(directory, "original.sha256")
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun valid(): Boolean = runCatching {
        original.isFile && original.length() > 0 && checksum.isFile && hash(original) == checksum.readText().trim()
    }.getOrDefault(false)
    fun requireOriginal(): File {
        check(valid()) { "Chưa có bản skin gốc hợp lệ. Hãy khôi phục tài nguyên gốc bằng Fix Resources trước khi áp dụng lại skin." }
        return original
    }
    fun beforeApply(current: File, skinAlreadyActive: Boolean): File {
        if (skinAlreadyActive) return requireOriginal() // Never replace the baseline with an installed mod.
        require(current.isFile && current.length() > 0) { "Không đọc được tệp skin gốc. Chưa áp dụng skin." }
        if (valid() && hash(current) == hash(original)) return original
        check(directory.isDirectory || directory.mkdirs()) { "Không tạo được nơi lưu skin gốc." }
        val staged = File.createTempFile("original-", ".tmp", directory)
        val metadata = File.createTempFile("checksum-", ".tmp", directory)
        try {
            current.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            val expected = hash(current)
            check(hash(staged) == expected) { "Bản sao skin gốc không khớp. Chưa áp dụng skin." }
            metadata.outputStream().use { output -> output.write(expected.toByteArray()); output.fd.sync() }
            Files.move(staged.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(metadata.toPath(), checksum.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return requireOriginal()
        } finally { staged.delete(); metadata.delete() }
    }
}
