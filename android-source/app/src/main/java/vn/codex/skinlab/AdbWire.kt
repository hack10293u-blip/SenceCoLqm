package vn.codex.skinlab

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal interface AdbChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}
internal data class AdbCommandResult(val output: String, val exitCode: Int)

/** Explicit completion markers and SYNC DONE/OKAY distinguish EOF from truncated transfers. */
internal class AdbWire(private val open: (String) -> AdbChannel) {
    fun command(script: String): AdbCommandResult {
        val marker = "SENCE_END_" + UUID.randomUUID().toString().replace("-", "")
        val wrapped = "($script); sence_exit=\$?; printf '\\n$marker:%s\\n' \"\$sence_exit\""
        open("exec:$wrapped").use { channel ->
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.input.read(buffer)
                if (count < 0) throw IOException("ADB đóng luồng trước khi xác nhận lệnh hoàn tất.")
                collected.write(buffer, 0, count)
                require(collected.size() <= 8 * 1024 * 1024) { "Phản hồi ADB quá lớn." }
                val text = collected.toString("UTF-8")
                val match = Regex("\\n$marker:([0-9]{1,3})\\n").find(text)
                if (match != null) return AdbCommandResult(text.substring(0, match.range.first), match.groupValues[1].toInt())
            }
        }
    }

    private fun packet(output: DataOutputStream, id: String, bytes: ByteArray) {
        output.write(id.toByteArray(Charsets.US_ASCII))
        output.writeInt(Integer.reverseBytes(bytes.size))
        output.write(bytes)
    }

    fun send(path: String, source: InputStream) {
        open("sync:").use { channel ->
            val output = DataOutputStream(channel.output.buffered(65536))
            packet(output, "SEND", "$path,33200".toByteArray(Charsets.UTF_8))
            val buffer = ByteArray(65536)
            while (true) {
                val size = source.read(buffer)
                if (size < 0) break
                if (size > 0) packet(output, "DATA", buffer.copyOf(size))
            }
            output.writeBytes("DONE")
            output.writeInt(Integer.reverseBytes((System.currentTimeMillis() / 1000).toInt()))
            output.flush()
            val input = DataInputStream(channel.input)
            val code = ByteArray(4); input.readFully(code)
            val length = Integer.reverseBytes(input.readInt())
            require(length in 0..65536) { "Phản hồi ghi ADB không hợp lệ." }
            val message = ByteArray(length); input.readFully(message)
            check(String(code, Charsets.US_ASCII) == "OKAY" && length == 0) { "ADB chưa ghi được tệp: ${String(message, Charsets.UTF_8)}" }
        }
    }

    fun receive(path: String): InputStream {
        val channel = open("sync:")
        try {
            val output = DataOutputStream(channel.output)
            packet(output, "RECV", path.toByteArray(Charsets.UTF_8)); output.flush()
        } catch (failure: Exception) { channel.close(); throw failure }
        return object : InputStream() {
            private val input = DataInputStream(channel.input)
            private var remaining = 0
            private var complete = false
            override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                if (complete) return -1
                while (remaining == 0) {
                    val id = ByteArray(4); input.readFully(id)
                    val size = Integer.reverseBytes(input.readInt())
                    when (String(id, Charsets.US_ASCII)) {
                        "DATA" -> { require(size in 1..65536) { "Gói dữ liệu ADB không hợp lệ." }; remaining = size }
                        "DONE" -> { complete = true; return -1 }
                        "FAIL" -> {
                            require(size in 0..65536)
                            val message = ByteArray(size); input.readFully(message)
                            throw IOException("ADB không đọc được tệp: ${String(message, Charsets.UTF_8)}")
                        }
                        else -> throw IOException("Phản hồi đọc ADB không hợp lệ.")
                    }
                }
                val count = minOf(length, remaining)
                input.readFully(bytes, offset, count)
                remaining -= count
                return count
            }
            override fun close() = channel.close()
        }
    }
}
