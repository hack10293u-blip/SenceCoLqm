package vn.codex.skinlab

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.SocketException
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

internal class LocalAdbWifi(
    private val context: Context,
    private val status: (String) -> Unit,
) : Closeable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val work = Executors.newSingleThreadExecutor()
    private val manager by lazy { Connection(context.applicationContext) }
    private val pairing = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private data class PairEndpoint(val service: String, val host: String, val port: Int)
    @Volatile private var pairingEndpoint: PairEndpoint? = null
    @Volatile private var connected = false
    @Volatile var ldPlayerMode = false
        private set
    @Volatile private var closed = false
    init {
        AdbPairReplyReceiver.handler = { code -> pair(code) }
    }

    fun discoverPairing() {
        if (closed || pairing.get()) return
        if (Build.VERSION.SDK_INT < 30) {
            status("Gỡ lỗi không dây cần Android 11 trở lên.")
            return
        }
        val notificationReady = showPairNotification(
            if (pairingEndpoint != null) "Đã thấy phiên ghép đôi. Bấm Nhập mã."
            else "Mở Ghép nối thiết bị bằng mã trong cài đặt Android, rồi bấm Nhập mã ở đây."
        )
        status(when {
            !notificationReady -> "Thông báo ADB WiFi đang bị chặn. Hãy bật thông báo hoặc nhập mã và cổng trong SenceLab."
            pairingEndpoint != null -> "Đã thấy phiên ghép đôi. Mở thông báo SenceLab để nhập mã."
            else -> "Đã gửi thông báo. Đang dò phiên ghép đôi trên thiết bị…"
        })
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                discovery = null
                status("Không dò được dịch vụ ghép đôi. Hãy thử lại.")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                if (pairingEndpoint?.service == service.serviceName) pairingEndpoint = null
            }
            @Suppress("DEPRECATION")
            override fun onServiceFound(service: NsdServiceInfo) {
                if (closed) return
                runCatching { nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host ?: return
                        if (closed || !isLocal(host) || info.port !in 1..65535) return
                        pairingEndpoint = PairEndpoint(info.serviceName, host.hostAddress ?: return, info.port)
                        if (!pairing.get()) status("Đã tìm thấy phiên ghép đôi. Nhập mã từ thông báo hoặc trong SenceLab.")
                    }
                }) }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                discovery = null
                status("Không khởi động được dò ADB WiFi.")
            }
    }

    private fun isLocal(address: InetAddress?): Boolean =
        address != null && (address.isLoopbackAddress ||
            runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false))

    fun pair(code: String, manualPort: String = "") {
        if (closed || pairing.get()) return
        if (Build.VERSION.SDK_INT < 30) {
            status("Gỡ lỗi không dây cần Android 11 trở lên.")
            return
        }
        val discovered = pairingEndpoint
        val port = manualPort.toIntOrNull()
        val problem = when {
            !code.matches(Regex("[0-9]{6}")) -> "Nhập mã ghép nối gồm 6 chữ số."
            manualPort.isNotEmpty() && (port == null || port !in 1..65535) -> "Nhập cổng hiện trong cửa sổ Ghép nối thiết bị bằng mã."
            port == null && discovered == null -> "Chưa dò được phiên ghép đôi. Giữ cửa sổ ghép nối mở và nhập mã cùng cổng trong SenceLab."
            else -> null
        }
        if (problem != null) {
            status(problem)
            showPairNotification(problem)
            return
        }
        val endpoint = when {
            port == null -> checkNotNull(discovered)
            discovered?.port == port -> discovered
            else -> PairEndpoint("", loopback, port)
        }
        if (!pairing.compareAndSet(false, true)) return
        showPairNotification("Đang ghép đôi ADB WiFi…", false)
        status("Đang ghép đôi ADB WiFi…")
        work.execute {
            val result = runCatching {
                val paired = try {
                    manager.pair(endpoint.host, endpoint.port, code)
                } catch (failure: IOException) {
                    if (endpoint.host == loopback || (failure !is ConnectException && failure !is NoRouteToHostException)) throw failure
                    manager.pair(loopback, endpoint.port, code)
                }
                if (!paired) throw IOException("Could not exchange peer info.")
            }
            if (result.isSuccess && pairingEndpoint == endpoint) pairingEndpoint = null
            val message = result.exceptionOrNull()?.let(::pairingFailure)
                ?: "Đã ghép đôi. Quay lại SenceLab rồi bấm Bắt đầu."
            if (!closed) {
                status(message)
                showPairNotification(message, result.isFailure)
            }
            pairing.set(false)
        }
    }

    private fun pairingFailure(failure: Throwable): String {
        Log.w("SenceLabAdb", "Pairing failed (${failure.javaClass.simpleName})")
        return when {
            failure is SSLException || failure is GeneralSecurityException || failure is LinkageError || failure is AdbIdentityException ->
                "Không tạo được phiên bảo mật ADB WiFi (${failure.javaClass.simpleName})."
            failure is SocketException || failure is EOFException ->
                "Cổng ghép đôi đã đóng hoặc không phản hồi. Mở lại cửa sổ ghép nối trên Android và dùng mã, cổng mới."
            failure.message == "Could not exchange peer info." ->
                "Android từ chối xác thực. Kiểm tra mã thuộc đúng cửa sổ ghép nối đang mở."
            else -> "Không hoàn tất được ghép đôi (${failure.javaClass.simpleName}). Mở lại cửa sổ ghép nối và thử lại."
        }
    }

    fun connect() {
        if (closed || Build.VERSION.SDK_INT < 30) return
        val switchMode = ldPlayerMode
        ldPlayerMode = false
        work.execute {
            if (switchMode && manager.isConnected) { manager.disconnect(); connected = false }
            status("Đang kết nối ADB WiFi…")
            connected = runCatching {
                (manager.isConnected || manager.connect(loopback, connectionPort())) && manager.isConnected
            }.getOrDefault(false)
            status(when {
                !connected -> "Chưa kết nối được. Bật Gỡ lỗi không dây và ghép đôi lại nếu cần."
                canWrite() -> "ADB WiFi đã kết nối và có quyền ghi tệp map."
                else -> "ADB WiFi đã kết nối, nhưng Android chưa cho ghi tệp map."
            })
        }
    }

    fun connectLdPlayer() {
        if (closed) return
        val switchMode = !ldPlayerMode
        ldPlayerMode = true
        work.execute {
            if (switchMode && manager.isConnected) { manager.disconnect(); connected = false }
            status("Đang kết nối ADB LDPlayer…")
            connected = runCatching {
                (manager.isConnected || manager.connect(loopback, 5555)) && manager.isConnected
            }.getOrDefault(false)
            status(when {
                !connected -> "Chưa kết nối được LDPlayer. Bật ADB debugging: Open local connection trong cài đặt giả lập rồi thử lại."
                canWrite() -> "ADB LDPlayer đã kết nối và có quyền ghi tệp map."
                else -> "ADB LDPlayer đã kết nối, nhưng chưa có quyền ghi tệp map."
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun connectionPort(): Int {
        val ready = CountDownLatch(1)
        val port = AtomicInteger()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = ready.countDown()
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching { nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (isLocal(info.host)) {
                            port.set(info.port)
                            ready.countDown()
                        }
                    }
                }) }
            }
        }
        nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            check(ready.await(7, TimeUnit.SECONDS) && port.get() > 0) { "Không tìm thấy cổng ADB WiFi của máy này." }
            return port.get()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    fun isConnected(): Boolean = connected && manager.isConnected

    fun resourceStore(root: String = GAME_FILES): ResourceStore {
        check(isConnected()) { "Hãy kết nối ADB trước." }
        val directory = allowedResourceRoot(root).path
        fun path(relative: String): String {
            require(relative.split('/').none { it == ".." || it == "." } && !relative.startsWith('/'))
            return "'" + ("$directory/$relative").replace("'", "'\\''") + "'"
        }
        fun execute(script: String): String = manager.openStream("exec:$script").use { it.openInputStream().bufferedReader().readText() }
        fun checked(script: String) { check(execute("$script && printf SENCE_OK").endsWith("SENCE_OK")) { "ADB không thực hiện được thao tác Resources." } }
        return object : ResourceStore {
            override fun list(relative: String): List<Pair<String, Boolean>> = execute("ls -1p ${path(relative)}").lineSequence().filter { it.isNotEmpty() }.map { it.trimEnd('/') to it.endsWith('/') }.toList()
            override fun exists(relative: String) = execute("[ -e ${path(relative)} ] && printf yes") == "yes"
            override fun mkdir(relative: String) = checked("mkdir -p ${path(relative)}")
            override fun read(relative: String): InputStream {
                val stream = manager.openStream("exec:cat ${path(relative)}")
                return object : FilterInputStream(stream.openInputStream()) { override fun close() = stream.close() }
            }
            override fun write(relative: String, input: InputStream) {
                manager.openStream("sync:").use { stream ->
                    val output = java.io.DataOutputStream(stream.openOutputStream())
                    fun packet(name: String, bytes: ByteArray) {
                        output.write(name.toByteArray(Charsets.US_ASCII)); output.writeInt(Integer.reverseBytes(bytes.size)); output.write(bytes)
                    }
                    packet("SEND", "$directory/$relative,33200".toByteArray(Charsets.UTF_8))
                    val buffer = ByteArray(65536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; packet("DATA", buffer.copyOf(size)) }
                    output.writeBytes("DONE"); output.writeInt(Integer.reverseBytes((System.currentTimeMillis() / 1000).toInt())); output.flush()
                    val response = java.io.DataInputStream(stream.openInputStream())
                    val code = ByteArray(4); response.readFully(code)
                    val length = Integer.reverseBytes(response.readInt())
                    require(length in 0..65536)
                    val message = ByteArray(length); response.readFully(message)
                    check(String(code, Charsets.US_ASCII) == "OKAY") { "ADB ghi thất bại: ${String(message, Charsets.UTF_8)}" }
                }
            }
            override fun rename(from: String, to: String) = checked("[ ! -e ${path(to)} ] && mv ${path(from)} ${path(to)}")
            override fun delete(relative: String) { require(relative.isNotEmpty()); checked("rm -rf ${path(relative)}") }
        }
    }

    fun disconnect() {
        connected = false
        pairingEndpoint = null
        work.execute { runCatching { manager.disconnect() } }
    }

    @Synchronized fun fixData(file: File) {
        check(isConnected()) { "Hãy kết nối ADB WiFi hoặc ADB LDPlayer." }
        val wrapped = validateExtraArchive(file)
        val archive = "/data/local/tmp/sencelab-${java.util.UUID.randomUUID()}.zip"
        fun command(script: String): String = manager.openStream("exec:$script")
            .use { it.openInputStream().bufferedReader().readText() }
        try {
            manager.openStream("exec:head -c ${file.length()} > '$archive' && printf done").use { stream ->
                file.inputStream().use { it.copyTo(stream.openOutputStream()) }
                stream.openOutputStream().flush()
                check(stream.openInputStream().bufferedReader().readText() == "done") { "Không chuyển được ZIP vào ADB." }
            }
            val parent = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files"
            val stage = "$parent/SenceLabExtraPending"
            val source = if (wrapped) "$stage/Extra" else stage
            val target = "2022.V3/assetbundle/modules/autochess/scene_artist_autochess_01.assetbundle"
            val script = "if [ -e '$parent/Extra1' ]; then printf 'Extra1 đã tồn tại'; " +
                "elif [ ! -d '$parent/Extra' ]; then printf 'Không thấy Extra'; " +
                "elif mkdir '$stage'; then " +
                "if unzip -oq '$archive' -d '$stage' && [ -s '$source/$target' ] && mv '$parent/Extra' '$parent/Extra1'; then " +
                "if mv '$source' '$parent/Extra'; then printf FIX_OK; else mv '$parent/Extra1' '$parent/Extra'; printf 'Không thay được Extra'; fi; " +
                "else printf 'Không giải nén hoặc đổi tên được Extra'; fi; rm -rf '$stage'; else printf 'Không tạo được thư mục tạm'; fi"
            val result = command(script)
            check(result.trim() == "FIX_OK") { "Fix Data qua ADB thất bại: $result" }
        } finally {
            runCatching { command("rm -f '$archive'") }
        }
    }

    @Synchronized fun canWrite(): Boolean = isConnected() && runCatching {
        manager.openStream("exec:if [ -f '$GAME_MAP_PATH' ] && [ -r '$GAME_MAP_PATH' ] && [ -w '$GAME_MAP_PATH' ]; then printf yes; fi")
            .use { it.openInputStream().bufferedReader().readText() == "yes" }
    }.getOrDefault(false)

    fun readMap(): InputStream {
        check(connected) { "Hãy kết nối lại ADB WiFi." }
        val stream = manager.openStream("exec:cat '$GAME_MAP_PATH'")
        return object : FilterInputStream(stream.openInputStream()) {
            override fun close() = stream.close()
        }
    }

    @Synchronized fun writeMap(file: File) {
        check(connected && canWrite()) { "ADB WiFi không có quyền ghi tệp map." }
        manager.openStream("exec:head -c ${file.length()} > '$GAME_MAP_PATH' && printf done")
            .use { stream ->
                val output = stream.openOutputStream()
                file.inputStream().use { it.copyTo(output) }
                output.flush()
                check(stream.openInputStream().bufferedReader().readText() == "done") { "ADB WiFi ghi tệp thất bại." }
            }
    }

    private fun showPairNotification(message: String, replyEnabled: Boolean = true): Boolean {
        if (closed || (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !notifications.areNotificationsEnabled()) return false
        notifications.createNotificationChannel(NotificationChannel(channel, "Ghép đôi ADB WiFi", NotificationManager.IMPORTANCE_HIGH))
        if (notifications.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val reply = PendingIntent.getBroadcast(context, notificationId, Intent(context, AdbPairReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(null, "Nhập mã", reply)
            .addRemoteInput(RemoteInput.Builder(AdbPairReplyReceiver.replyKey).setLabel("Mã ghép nối 6 chữ số").build())
            .setAllowGeneratedReplies(false).build()
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("SenceLab · ADB WiFi")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setOnlyAlertOnce(true)
        if (replyEnabled) notification.addAction(action)
        return runCatching { notifications.notify(notificationId, notification.build()) }.isSuccess
    }

    override fun close() {
        closed = true
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        AdbPairReplyReceiver.handler = null
        notifications.cancel(notificationId)
        work.execute { if (connected) runCatching { manager.disconnect() } }
        work.shutdown()
    }

    private class AdbIdentityException(cause: Exception) : Exception(cause)

    private class Connection(context: Context) : AbsAdbConnectionManager() {
        private val entry by lazy {
            try {
                LocalAdbIdentity.load(context)
            } catch (failure: Exception) {
                throw AdbIdentityException(failure)
            }
        }
        init { setApi(Build.VERSION.SDK_INT); setHostAddress(loopback); setTimeout(8, TimeUnit.SECONDS) }
        override fun getPrivateKey() = entry.privateKey
        override fun getCertificate() = entry.certificate
        override fun getDeviceName() = "SenceLab"
    }

    companion object {
        private const val loopback = "127.0.0.1"
        private const val channel = "sencelab-adb-pairing"
        private const val notificationId = 154
    }
}

class AdbPairReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(replyKey)?.toString()?.trim() ?: ""
        handler?.invoke(code)
    }

    companion object {
        internal const val replyKey = "pairing_code"
        internal var handler: ((String) -> Unit)? = null
    }
}
