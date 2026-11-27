package vn.codex.skinlab

import android.app.Activity
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Html
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.json.JSONArray
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.UUID

class SenceActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val catalogWorker = Executors.newSingleThreadExecutor()
    private val counterWorker = Executors.newSingleThreadExecutor()
    private val state by lazy { getSharedPreferences("map-state", MODE_PRIVATE) }
    private val counterOutbox by lazy { getSharedPreferences("download-outbox", MODE_PRIVATE) }
    private val adminState by lazy { getSharedPreferences("admin-session", MODE_PRIVATE) }
    private val bundleLabels by lazy { getSharedPreferences("bundle-labels", MODE_PRIVATE) }
    @Volatile private var selectedBundle: Uri? = null
    @Volatile private var selectedCategory = "Map cờ"
    private val bundleCategories by lazy { getSharedPreferences("bundle-categories", MODE_PRIVATE) }
    @Volatile private var sessionSaf: Uri? = null
    private lateinit var view: WebView
    private lateinit var shell: FrameLayout
    @Volatile private var mapService: IMapFile? = null
    private var binding = false
    private val adbWifi: LocalAdbWifi by lazy {
        LocalAdbWifi(this) { message -> runOnUiThread {
            adbSetupMessage = message
            if (message.startsWith("Đã ghép đôi")) adbPaired = true
            emit("adb", JSONObject().put("message", message))
            emitAdbSetup()
            if (message.contains("đã kết nối")) checkPermissionState()
        } }
    }
    private var adbStarted = false
    private var adbPaired = false
    private var adbSetupMessage = "Chọn cách nhận mã để bắt đầu."
    @Volatile private var resetAllowedAt = Long.MAX_VALUE
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checkPermissionState()
    }
    private fun permissionEnabled(name: String): Boolean = !state.getBoolean("permissionsReset", false) ||
        state.getStringSet("enabledMethods", emptySet()).orEmpty().contains(name)
    private fun directWritable(): Boolean = permissionEnabled("LDPlayer trực tiếp") && Build.VERSION.SDK_INT <= 28 &&
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        runCatching { probeResourceWrite(DiskResources(File(LDPLAYER_FILES)), LDPLAYER_MAP_RELATIVE) }.getOrDefault(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        adbWifi.discoverPairing()
        emitAdbSetup()
    }
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, MapFileService::class.java.name))
            .tag("map-file").processNameSuffix("map_file").version(BuildConfig.VERSION_CODE).daemon(false)
    }
    private val mapConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            mapService = IMapFile.Stub.asInterface(binder)
            checkPermissionState()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binding = false
            mapService = null
        }
    }
    private val binderAvailable = Shizuku.OnBinderReceivedListener { connectShizuku() }
    private val binderLost = Shizuku.OnBinderDeadListener {
        mapService = null
        binding = false
        checkPermissionState()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 26 && result == PackageManager.PERMISSION_GRANTED) connectShizuku()
        else if (code == 26) emit("permission", JSONObject().put("message", "Chưa cấp quyền Shizuku."))
    }
    private val backup by lazy { File(filesDir, "original.bundle") }
    private val skinOriginal by lazy { SkinOriginalBackup(File(filesDir, "skin-original-1.64.1")) }

    private val chooseBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        task("bundle-selected") {
            val name = displayName(uri)
            val extension = if (selectedCategory == "Map cờ") ".assetbundle" else ".zip"
            require(name.endsWith(extension, true)) { "Chỉ hỗ trợ tệp $extension cho $selectedCategory." }
            selectedBundle = uri
            JSONObject().put("name", name).put("category", selectedCategory)
        }
    }

    private val chooseTarget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        task("permission") {
            val documentMatches = uri.authority == "com.android.externalstorage.documents" && runCatching { DocumentsContract.getDocumentId(uri) == targetDocument }.getOrDefault(false)
            val fileMatches = runCatching { contentResolver.openFileDescriptor(uri, "rw")?.use {
                val path = android.system.Os.readlink("/proc/self/fd/${it.fd}")
                path == GAME_MAP_PATH || (path.startsWith("/mnt/") && path.endsWith(GAME_MAP_PATH.removePrefix("/storage/emulated/0")))
            } == true }.getOrDefault(false)
            val nameMatches = runCatching { displayName(uri) == targetName }.getOrDefault(false)
            require(documentMatches || fileMatches || nameMatches) {
                "Hãy chọn tệp $targetName trong thư mục Resources/1.64.1/assetbundle/modules/autochess của trò chơi."
            }
            val header = read(uri).use { readUpTo(it, 8).toString(Charsets.US_ASCII) }
            require(header.startsWith("UnityFS")) { "Tệp đã chọn không phải AssetBundle hợp lệ." }
            val writable = runCatching {
                contentResolver.openFileDescriptor(uri, "rw")?.use { true } == true
            }.getOrDefault(false)
            require(writable) { "Đã nhận đúng tệp nhưng ứng dụng quản lý tệp chưa cấp quyền ghi cho SenceLab. Hãy thử SAF V1 hoặc trình chọn tệp Android." }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (!persisted) sessionSaf = uri
            check(state.edit().putString("target", uri.toString()).putString("saf2", uri.toString()).commit())
            runOnUiThread { checkPermissionState() }
            JSONObject().put("message", if (persisted) "Đã lưu quyền SAF V2 cho đúng tệp map." else "Đã cấp quyền SAF V2 trong phiên này. Khi mở lại app có thể cần chọn lại tệp.")
        }
    }

    private val chooseFixFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            task("fix-folder") {
                require(uri.authority == "com.android.externalstorage.documents" &&
                    DocumentsContract.getTreeDocumentId(uri) == "primary:Android/data/com.garena.game.kgvn/files") {
                    "Hãy chọn đúng thư mục Android/data/com.garena.game.kgvn/files."
                }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                check(state.edit().putString("fixTree", uri.toString()).commit())
                JSONObject().put("message", "Đã cấp quyền thư mục. Bạn có thể kiểm tra quyền và tiếp tục.")
            }
        }
    }

    private val chooseSafTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) task("permission") {
            val id = DocumentsContract.getTreeDocumentId(tree)
            require(tree.authority == "com.android.externalstorage.documents" && targetDocument.startsWith("$id/") &&
                id.startsWith("primary:Android/data/com.garena.game.kgvn/files")) { "Chọn thư mục files hoặc thư mục chứa tệp map bên trong." }
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val target = DocumentsContract.buildDocumentUriUsingTree(tree, targetDocument)
            check(state.edit().putString("saf1Tree", tree.toString()).putString("saf1", target.toString()).commit())
            require(safRootStore()?.let { probeResourceWrite(it) } == true) { "Thư mục đã chọn chưa cho phép tạo và ghi tệp map." }
            if (id == "primary:Android/data/com.garena.game.kgvn/files") state.edit().putString("fixTree", tree.toString()).apply()
            runOnUiThread { checkPermissionState() }
            JSONObject().put("message", "Đã cấp quyền SAF V1 cho tệp map.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!state.getBoolean("resources220", false)) {
            state.edit().remove("target").remove("saf1").remove("saf2").remove("saf1Tree")
                .remove("uri").remove("pack").remove("installedHash").putBoolean("resources220", true).commit()
        }
        enableEdgeToEdge()
        shell = FrameLayout(this)
        view = WebView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "SenceAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            loadUrl("file:///android_asset/index.html")
        }
        shell.addView(view, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(shell) { container, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            container.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(shell)
        setBarTheme(false)
        ViewCompat.requestApplyInsets(shell)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                view.evaluateJavascript("typeof window.senceBack === 'function' && window.senceBack()") { handled ->
                    if (handled != "true" && !isDestroyed) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderDeadListener(binderLost)
        Shizuku.addBinderReceivedListenerSticky(binderAvailable)
    }

    private fun emitAdbSetup() {
        if (::view.isInitialized && !isDestroyed) emit("adb-setup", JSONObject().put("notifications", adbWifi.notificationsAllowed())
             .put("reading", false).put("paired", adbPaired).put("connected", adbStarted && adbWifi.isConnected()).put("message", adbSetupMessage))
    }
    override fun onResume() {
        super.onResume()
        if (::view.isInitialized) { emitAdbSetup(); if (adbStarted) checkPermissionState() }
    }

    private fun openAdbSettingsOutside() {
        runCatching { applicationContext.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            .onFailure { adbSetupMessage = "Không mở được cài đặt. Hãy mở Tùy chọn nhà phát triển trên máy."; emitAdbSetup() }
    }

    private fun setBarTheme(dark: Boolean) {
        shell.setBackgroundColor(if (dark) 0xff101017.toInt() else 0xffeee5ff.toInt())
        WindowCompat.getInsetsController(window, shell).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        Shizuku.removeBinderReceivedListener(binderAvailable)
        Shizuku.removeBinderDeadListener(binderLost)
        if (binding || mapService != null) runCatching { Shizuku.unbindUserService(serviceArgs, mapConnection, false) }
        if (adbStarted) adbWifi.close()
        worker.shutdown()
        catalogWorker.shutdown()
        counterWorker.shutdown()
        view.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun isLdPlayer(): Boolean = File("/system/bin/ldinit").exists() ||
            runCatching { packageManager.getPackageInfo("com.ldmnq.launcher3", 0); true }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT <= 28 && Build.SUPPORTED_ABIS.any { it == "x86" || it == "x86_64" })
        @JavascriptInterface fun chooseZarchiver() = runOnUiThread {
            val packages = listOf("ru.zdevs.zarchiver", "ru.zdevs.zarchiver.pro")
            val intent = packages.map { name ->
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    setPackage(name)
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }.firstOrNull { it.resolveActivity(packageManager) != null }
            if (intent == null) emit("zarchiver", JSONObject().put("error", "Chưa tìm thấy ZArchiver có chức năng chọn tệp trên máy. Hãy cài ZArchiver rồi thử lại."))
            else {
                state.edit().putString("selectedPermission", "SAF V2").apply()
                val enabled = state.getStringSet("enabledMethods", emptySet()).orEmpty().toMutableSet().apply { add("SAF V2") }
                state.edit().putStringSet("enabledMethods", enabled).apply()
                runCatching { chooseTarget.launch(intent) }.onFailure {
                    emit("zarchiver", JSONObject().put("error", "Không mở được trình chọn tệp ZArchiver."))
                }
            }
        }
        @JavascriptInterface fun backHint() = runOnUiThread {
            android.widget.Toast.makeText(this@SenceActivity, "Bấm Trở về lần nữa để thoát", android.widget.Toast.LENGTH_SHORT).show()
        }
        @JavascriptInterface fun resetPermissions() = task("permissions-reset") {
            sessionSaf = null
            check(state.edit().putBoolean("permissionsReset", true).putStringSet("enabledMethods", emptySet())
                .remove("target").remove("saf1Tree").remove("saf1").remove("saf2").remove("fixTree").remove("selectedPermission").commit())
            contentResolver.persistedUriPermissions.forEach { grant ->
                var flags = 0
                if (grant.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (grant.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.releasePersistableUriPermission(grant.uri, flags) }
            }
            if (adbStarted) adbWifi.disconnect()
            adbStarted = false
            runOnUiThread {
                if (binding || mapService != null) runCatching { Shizuku.unbindUserService(serviceArgs, mapConnection, true) }
                binding = false
                mapService = null
                checkPermissionState()
            }
            JSONObject().put("message", "Đã đặt lại quyền truy cập của SenceLab. Hãy chọn và cấp lại phương thức khi cần.")
        }
        @JavascriptInterface fun autoVersion() = task("auto-version") { latestRelease() }
        @JavascriptInterface fun beginReset() { resetAllowedAt = android.os.SystemClock.elapsedRealtime() + 5000 }
        @JavascriptInterface fun resetApp() = runOnUiThread {
            if (android.os.SystemClock.elapsedRealtime() < resetAllowedAt) return@runOnUiThread
            resetAllowedAt = Long.MAX_VALUE
            getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData()
        }
        @JavascriptInterface fun cacheSize() = task("cache-size") {
            JSONObject().put("bytes", cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }
        @JavascriptInterface fun skinActive(): Boolean = state.getBoolean("skinChessActive", false)
        @JavascriptInterface fun active(): String = state.getString("pack", "") ?: ""
        @JavascriptInterface fun setTheme(dark: Boolean) = runOnUiThread { setBarTheme(dark) }

        @JavascriptInterface fun clearCache() = runOnUiThread {
            runCatching { view.clearCache(true) }.onFailure {
                emit("cache", JSONObject().put("error", "Không xóa được bộ nhớ đệm WebView."))
            }.onSuccess {
                task("cache") {
                    val temporary = cacheDir.listFiles().orEmpty()
                    check(temporary.all { it.deleteRecursively() || !it.exists() }) { "Chưa xóa được một số tệp tạm." }
                    JSONObject().put("message", "Đã xóa bộ nhớ đệm.")
                }
            }
        }

        @JavascriptInterface fun adminLogin(user: String, password: String): Boolean {
            if (user != "TTT" || password != "2007") return false
            return adminState.edit().putBoolean("signed-in", true).commit()
        }

        @JavascriptInterface fun isAdmin(): Boolean = adminState.getBoolean("signed-in", false)

        @JavascriptInterface fun adminLogout() { adminState.edit().remove("signed-in").apply() }

        @JavascriptInterface fun chooseImport(category: String) = runOnUiThread {
            require(category in listOf("Map cờ", "Map 5v5", "Skin"))
            selectedCategory = category
            selectedBundle = null
            chooseBundle.launch(arrayOf("*/*"))
        }

        @JavascriptInterface fun chooseAssetBundle() = runOnUiThread {
            selectedCategory = "Map cờ"
            selectedBundle = null
            chooseBundle.launch(arrayOf("*/*"))
        }

        @JavascriptInterface fun listBundles(): String = importedBundles().toString()

        @JavascriptInterface fun saveBundle(name: String) = task("bundles") {
            val label = name.trim()
            require(label.length in 1..80 && label.none { it.isISOControl() }) { "Đặt tên từ 1 đến 80 ký tự." }
            val uri = selectedBundle ?: error("Hãy chọn lại tệp .assetbundle.")
            val id = UUID.randomUUID().toString()
            val destination = bundleFile(id)
            try {
                read(uri).use { input -> destination.outputStream().use { copyLimited(input, it) } }
                if (selectedCategory == "Map cờ") validateBundle(destination) else validateResourceZip(destination)
                check(bundleCategories.edit().putString(id, selectedCategory).commit())
                check(bundleLabels.edit().putString(id, label).commit()) { "Không lưu được tên tệp." }
            } catch (failure: Exception) {
                destination.delete()
                throw failure
            }
            selectedBundle = null
            JSONObject().put("entries", importedBundles()).put("message", "Đã nhập $label.")
        }

        @JavascriptInterface fun deleteBundle(id: String) = task("bundles") {
            val file = bundleFile(id)
            check(!file.exists() || file.delete()) { "Không xóa được tệp đã nhập." }
            bundleLabels.edit().remove(id).apply()
            bundleCategories.edit().remove(id).apply()
            JSONObject().put("entries", importedBundles()).put("message", "Đã xóa tệp đã nhập.")
        }

        @JavascriptInterface fun applyImport(id: String, route: String) = task("import-applied") {
            require(bundleLabels.contains(id)) { "Tệp đã bị xóa. Hãy nhập lại." }
            val file = bundleFile(id)
            val category = bundleCategories.getString(id, "Map cờ") ?: "Map cờ"
            val root = importRouteRoot(route)
            if (category == "Map cờ") validateBundle(file) else validateResourceZip(file)
            operationPhase("Đang áp dụng tệp đã nhập…")
            try {
                val store = DiskResources(root)
                applyImportedDirect(store, file, category, cacheDir, ::operationPhase)
            } catch (failure: Exception) {
                throw IllegalStateException("Chưa ghi được vào thư mục đã chọn. Android phải cho phép ghi thực tế tại đường dẫn này. " + (failure.message ?: ""), failure)
            }
            JSONObject().put("message", "Đã áp dụng và xác minh tệp tại đường dẫn đã chọn.").put("route", route)
        }

        @JavascriptInterface fun applyMap(data: String) = task("apply") {
            val pack = JSONObject(data)
            if (pack.optString("category") == "Mod skin") return@task applyOnlineSkin(pack)
            if (pack.has("localId") && bundleCategories.getString(pack.getString("localId"), "Map cờ") != "Map cờ") return@task applyImportedZip(pack)
            val shizukuConnected = shizukuConnected()
            val adbConnected = adbStarted && adbWifi.isConnected()
            val target = writableTarget()
            if (target == null && !shizukuConnected && !adbConnected) return@task JSONObject()
                .put("error", "Hãy cấp quyền bằng Shizuku, ADB WiFi, ADB LDPlayer hoặc SAF trong Cấp quyền bộ nhớ.")
                .put("permissionAction", "access")
            if (target == null) {
                val fixedBefore = state.getString("fixedResourcesVersion", "") == "1.64.1"
                val needsFix = needsResourceFix(true, false, fixedBefore, resourceVersionPresent())
                return@task JSONObject().put("error", if (needsFix) "Đã kết nối nhưng chưa ghi được tài nguyên. Bạn có thể thử Fix Resources sau khi đóng trò chơi." else "Tài nguyên đã được làm mới trước đó. Hãy kết nối lại hoặc kiểm tra quyền ghi của phiên hiện tại.")
                    .put("permissionAction", if (needsFix) "fix-data" else "permission-check")
            }
            applyPack(target, pack)
        }

        @JavascriptInterface fun requestMemory(mode: String) = runOnUiThread {
            val enabled = state.getStringSet("enabledMethods", emptySet()).orEmpty().toMutableSet().apply { add(mode) }
            state.edit().putStringSet("enabledMethods", enabled).apply()
            state.edit().putString("selectedPermission", mode).apply()
            when (mode) {
                "Shizuku" -> requestShizuku()
                "LDPlayer trực tiếp" -> {
                    if (Build.VERSION.SDK_INT <= 28) legacyStoragePermission.launch(arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    else emit("permission", JSONObject().put("message", "Ghi trực tiếp dành cho Android 9 trở xuống. Hãy dùng Shizuku, ADB WiFi hoặc SAF trên máy này."))
                }
                "ADB LDPlayer" -> {
                    adbStarted = true
                    adbWifi.connectLdPlayer()
                }
                "ADB WiFi" -> {
                    adbStarted = true
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else adbWifi.discoverPairing()
                }
                "SAF V1" -> chooseSafTree.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fcom.garena.game.kgvn%2Ffiles"))
                "SAF V2" -> chooseTarget.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
            }
        }

        @JavascriptInterface fun checkMemory() = checkPermissionState()
        @JavascriptInterface fun fixFolder() = runOnUiThread { chooseFixFolder.launch(null) }
        @JavascriptInterface fun fixData() = task("fix-data") {
            val service = mapService?.takeIf { shizukuConnected() }
            val adb = adbStarted && adbWifi.isConnected()
            val direct = legacyDiskAvailable()
            val tree = state.getString("fixTree", null)?.let(Uri::parse)?.takeIf { uri ->
                contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            }
            if (service == null && !adb && !direct && tree == null) return@task JSONObject()
                .put("error", "Fix Resources qua SAF cần quyền cho thư mục files, để đổi tên Resources và giải nén dữ liệu.")
                .put("needsFolder", true)
            val item = driveList("1TzGAJ018zKwUQx375uJIPH9RGqdrSLu9")
                .singleOrNull { !it.folder && it.name.equals("Resources.zip", true) }
                ?: error("Chưa lấy được dữ liệu tài nguyên. Vui lòng thử lại sau.")
            val archive = File.createTempFile("resources-", ".zip", cacheDir)
            try {
                driveFile(item.id) { input -> archive.outputStream().use { copyLimited(input, it, 4L * 1024 * 1024 * 1024) } }
                validateResourceZip(archive)
                operationPhase("Đang cập nhật tài nguyên…")
                when {
                    direct -> archive.inputStream().use { ResourceInstaller.install(DiskResources(File(LDPLAYER_FILES)), it, true, ::operationPhase, cacheDir) }
                    service != null -> ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { service.fixData(it) }
                    adb -> adbWifi.fixData(archive)
                    else -> archive.inputStream().use { ResourceInstaller.install(SafResources(contentResolver, checkNotNull(tree)), it, true, ::operationPhase, cacheDir) }
                }
                state.edit().putString("fixedResourcesVersion", "1.64.1").putLong("resourcesFixedAt", System.currentTimeMillis()).putBoolean("skinChessActive", false).remove("pack").remove("uri").remove("installedHash").commit()
                JSONObject().put("skinActive", false).put("message", "Đã làm mới tài nguyên. Bạn có thể kiểm tra quyền và áp dụng map.")
            } finally { archive.delete() }
        }
        @JavascriptInterface fun disableDebugging() = task("debug-disabled") {
            when {
                shizukuConnected() -> checkNotNull(mapService).disableDebugging()
                adbStarted && adbWifi.isConnected() -> adbWifi.disableDebugging()
                else -> error("Cần kết nối ADB WiFi hoặc Shizuku để tắt gỡ lỗi tự động.")
            }
            fun value(key: String) = runCatching { Settings.Global.getInt(contentResolver, key, -1) }.getOrDefault(-1)
            var wireless: Int
            var usb: Int
            var developer: Int
            val deadline = android.os.SystemClock.elapsedRealtime() + 6000
            do {
                wireless = value("adb_wifi_enabled")
                usb = value(Settings.Global.ADB_ENABLED)
                developer = value(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
                if (wireless == 0 && usb == 0 && developer == 0) break
                Thread.sleep(300)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            val confirmed = wireless == 0 && usb == 0 && developer == 0
            if (adbStarted) adbWifi.disconnect()
            adbStarted = false
            adbPaired = false
            state.edit().putBoolean("permissionsReset", true).putStringSet("enabledMethods", setOf("SAF V1", "SAF V2", "LDPlayer trực tiếp")).remove("selectedPermission").commit()
            runOnUiThread {
                if (binding || mapService != null) runCatching { Shizuku.unbindUserService(serviceArgs, mapConnection, true) }
                mapService = null; binding = false
                emitAdbSetup(); checkPermissionState()
            }
            JSONObject().put("verified", confirmed).put("wireless", wireless).put("usb", usb).put("developer", developer)
                .put("message", if (confirmed) "Đã tắt gỡ lỗi và ngắt kết nối. Bạn nên khởi động lại điện thoại để kết thúc các phiên gỡ lỗi còn lại." else "Đã gửi yêu cầu tắt nhưng chưa xác nhận được tất cả mục. Hãy kiểm tra Gỡ lỗi không dây, Gỡ lỗi USB và Tùy chọn nhà phát triển.")
        }
        @JavascriptInterface fun adbSetupState() = runOnUiThread { emitAdbSetup() }
        @JavascriptInterface fun adbNotifications() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (!adbWifi.notificationsAllowed()) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            else { adbWifi.discoverPairing(); emitAdbSetup() }
        }
        @JavascriptInterface fun adbSettings() = runOnUiThread {
            openAdbSettingsOutside()
        }
        @JavascriptInterface fun adbConnect() = runOnUiThread { if (adbStarted) adbWifi.connect() }
        @JavascriptInterface fun adbPair(code: String, port: String) = runOnUiThread { if (adbStarted) adbWifi.pair(code, port) }
        @JavascriptInterface fun adbNotificationSettings() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        }

        @JavascriptInterface fun removeSkin() = task("skin-remove") {
            if (!skinOriginal.valid()) return@task JSONObject().put("error", "Bản skin đã áp dụng trước đây chưa có bản sao gốc. Hãy khôi phục tài nguyên gốc bằng Fix Resources; app sẽ không xóa tệp hiện tại.").put("needsOriginal", true)
            val original = skinOriginal.requireOriginal()
            val expected = fileHash(original)
            val store = resourceStore()
            val service = if (store == null) mapService?.takeIf { shizukuConnected() } else null
            check(store != null || service != null) { "Hãy cấp quyền ADB WiFi, Shizuku hoặc SAF V1 cho thư mục skin để khôi phục skin gốc." }
            val previous = File.createTempFile("before-skin-restore-", ".bytes", filesDir)
            var retainPrevious = false
            fun readCurrent(): InputStream = store?.read(SKIN_RELATIVE) ?: ParcelFileDescriptor.AutoCloseInputStream(service!!.readResource(SKIN_RELATIVE))
            fun put(file: File) {
                if (store != null) file.inputStream().use { store.write(SKIN_RELATIVE, it) }
                else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { service!!.restoreSkin(it) }
                check(fileHash(file) == readCurrent().use(::digest).toHex()) { "Chưa xác minh được skin sau khi khôi phục." }
            }
            try {
                val existed = store?.exists(SKIN_RELATIVE) ?: service!!.resourceExists(SKIN_RELATIVE)
                if (existed) readCurrent().use { input -> previous.outputStream().use { copyLimited(input, it) } }
                operationPhase("Đang khôi phục skin gốc…")
                try {
                    put(original)
                    check(readCurrent().use(::digest).toHex() == expected) { "Skin khôi phục không khớp bản gốc." }
                } catch (failure: Exception) {
                    if (existed) retainPrevious = runCatching { put(previous) }.isFailure
                    if (retainPrevious) error("Khôi phục chưa hoàn tất. Bản gốc vẫn được lưu an toàn. Hãy kết nối lại và thử lại.")
                    throw failure
                }
                check(state.edit().putBoolean("skinChessActive", false).commit())
                JSONObject().put("skinActive", false).put("message", "Đã khôi phục skin gốc. Map đang dùng được giữ nguyên.")
            } finally { if (!retainPrevious) previous.delete() }
        }

        @JavascriptInterface fun restore() = task("restore") {
            restoreOriginalMap()
        }

        @JavascriptInterface fun verifyInstalled() = task("map-state") {
            val prior = state.getString("uri", null)
            val expected = state.getString("installedHash", null)
            val message = when {
                prior == null -> "Chưa áp dụng file mod."
                expected == null -> "Bản cài trước chưa lưu mã xác minh. Có thể gỡ bằng bản sao lưu."
                else -> {
                    val target = if (isFixedTarget(prior)) writableTarget(prior) else prior
                    if (target == null) "Cấp quyền để kiểm tra tệp map đang dùng."
                    else if (targetHash(target) == expected) "Đúng tệp trên bộ nhớ · SHA-256 ${expected.take(12)}"
                    else "Tệp trên bộ nhớ đã khác bản mod. Có thể tài nguyên đã được trò chơi cập nhật."
                }
            }
            JSONObject().put("message", message)
        }

        @JavascriptInterface fun checkVersion() = task("version") { latestRelease() }

        @JavascriptInterface fun loadSkins() = task("skins") {
            val root = driveList(skinFolder)
            val entries = JSONArray()
            fun add(name: String, id: String, files: List<DriveItem>) {
                val preview = files.singleOrNull { !it.folder && it.name.equals("preview.png", true) } ?: return
                val payload = files.singleOrNull { !it.folder && it.name == "ResACChessInfoCfg.bytes" } ?: return
                entries.put(JSONObject().put("id", id).put("name", name).put("category", "Mod skin")
                    .put("image", "https://drive.google.com/thumbnail?id=${preview.id}&sz=w480")
                    .put("fileName", payload.name))
            }
            add("SkinChess", skinFolder, root)
            root.filter { it.folder }.take(100).forEach { folder -> add(folder.name, folder.id, driveList(folder.id)) }
            JSONObject().put("entries", entries)
        }

        @JavascriptInterface fun loadDrive() = task("drive") {
            val root = driveList("1l3U7OM5WNNLpFB91oXkKHY-glQrO1Znp")
            val defaultPass = root.firstOrNull { !it.folder && it.name.equals("pass.txt", true) }?.id
            val rankingFile = root.firstOrNull { !it.folder && it.name.equals("downloads.json", true) }
            val ranking = rankingFile?.let { file ->
                runCatching {
                    driveFile(file.id) { input ->
                        val data = readUpTo(input, 65537)
                        require(data.size <= 65536) { "Tệp thống kê quá lớn." }
                        JSONObject(data.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                    }
                }.getOrNull()
            }
            val folders = root.filter { it.folder }.take(100)
            val jobs = Executors.newFixedThreadPool(6)
            try {
                val entries = JSONArray()
                jobs.invokeAll(folders.map { folder -> Callable {
                    runCatching {
                        val files = driveList(folder.id).filterNot { it.folder }
                        val image = files.firstOrNull { it.name.equals("preview.png", true) } ?: return@Callable null
                        val bundle = files.firstOrNull { it.name == targetName } ?: return@Callable null
                        val password = files.firstOrNull { it.name.equals("pass.txt", true) }?.id ?: defaultPass
                        JSONObject().put("name", folder.name).put("id", folder.id)
                            .put("image", "https://drive.google.com/thumbnail?id=${image.id}&sz=w480")
                            .put("bundleId", bundle.id).put("passId", password ?: "")
                            .apply {
                                val count = ranking?.optLong(folder.id, -1L)?.takeIf { it >= 0 }
                                    ?: ranking?.optLong(folder.name, -1L)
                                if (count != null && count in 0L..9007199254740991L) put("downloads", count)
                            }
                    }.getOrNull()
                } }).forEach { result -> result.get()?.let { entries.put(it) } }
                check(entries.length() > 0) { "Không tìm thấy map có ảnh và tệp bundle trên Drive." }
                JSONObject().put("entries", entries)
            } finally {
                jobs.shutdownNow()
            }
        }

        @JavascriptInterface fun refreshDownloads() {
            if (BuildConfig.COUNTER_URL.isNotEmpty()) syncDownloads()
        }

        @JavascriptInterface fun mapPass(id: String) = task("map-pass") {
            require(isAdmin()) { "Cần đăng nhập Admin." }
            JSONObject().put("id", id).put("pass", drivePass(id))
        }

        @JavascriptInterface fun openExternal(value: String) = runOnUiThread {
            val uri = Uri.parse(value)
            if (uri.scheme == "https" && uri.host in externalHosts) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
    }

    private fun connectShizuku() {
        if (!permissionEnabled("Shizuku")) return
        if (binding || mapService != null || !Shizuku.pingBinder()) return
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding = true
                Shizuku.bindUserService(serviceArgs, mapConnection)
            }
        }.onFailure {
            binding = false
            emit("permission", JSONObject().put("message", "Không kết nối được dịch vụ Shizuku."))
        }
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            emit("permission", JSONObject().put("message", "Hãy cài và bật Shizuku. Có thể khởi động qua Gỡ lỗi không dây."))
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(::startActivity)
            return
        }
        runCatching {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    connectShizuku()
                    checkPermissionState()
                }
                Shizuku.shouldShowRequestPermissionRationale() ->
                    emit("permission", JSONObject().put("message", "Mở Shizuku và cho phép SenceLab trong danh sách ứng dụng."))
                else -> Shizuku.requestPermission(26)
            }
        }.onFailure {
            emit("permission", JSONObject().put("message", "Không xin được quyền Shizuku."))
        }
    }

    private fun legacyDiskAvailable(): Boolean = permissionEnabled("LDPlayer trực tiếp") && Build.VERSION.SDK_INT <= 28 &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun safRootStore(): ResourceStore? {
        val uri = state.getString("saf1Tree", null)?.let(Uri::parse) ?: return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val base = "primary:Android/data/com.garena.game.kgvn/files"
        if (uri.authority != "com.android.externalstorage.documents" || !(id == base || id.startsWith("$base/"))) return null
        if (contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission }) return null
        val prefix = id.removePrefix(base).trim('/')
        val delegate = SafResources(contentResolver, uri)
        if (prefix.isEmpty()) return delegate
        return object : ResourceStore {
            fun scoped(path: String): String { require(path == prefix || path.startsWith("$prefix/")) { "SAF V1 chưa cấp quyền cho thư mục này. Hãy chọn files." }; return path.removePrefix(prefix).trimStart('/') }
            override fun list(path: String) = delegate.list(scoped(path))
            override fun exists(path: String) = (path == prefix || path.startsWith("$prefix/")) && delegate.exists(scoped(path))
            override fun mkdir(path: String) = delegate.mkdir(scoped(path))
            override fun read(path: String) = delegate.read(scoped(path))
            override fun write(path: String, input: InputStream) = delegate.write(scoped(path), input)
            override fun rename(from: String, to: String) = delegate.rename(scoped(from), scoped(to))
            override fun delete(path: String) = delegate.delete(scoped(path))
        }
    }

    private fun resourceStore(): ResourceStore? = when {
        legacyDiskAvailable() -> DiskResources(File(LDPLAYER_FILES))
        adbStarted && adbWifi.isConnected() -> adbWifi.resourceStore()
        else -> safRootStore()
    }

    private fun accessMethod(uri: String): String = when (uri) {
        shizukuTarget -> "Shizuku"
        adbTarget -> "ADB WiFi"
        directTarget -> "LDPlayer trực tiếp"
        else -> "SAF"
    }

    private fun targetExists(uri: String): Boolean = when (uri) {
        safTreeTarget -> checkNotNull(safRootStore()).exists(MAP_RELATIVE)
        directTarget -> File(LDPLAYER_MAP_PATH).exists()
        shizukuTarget -> requireShizuku().mapExists()
        adbTarget -> adbWifi.mapExists()
        else -> contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
    }

    private fun deleteTarget(uri: String) {
        when (uri) {
            safTreeTarget -> checkNotNull(safRootStore()).let { if (it.exists(MAP_RELATIVE)) it.delete(MAP_RELATIVE) }
            directTarget -> DiskResources(File(LDPLAYER_FILES)).let { store ->
                if (store.exists(LDPLAYER_MAP_RELATIVE)) store.delete(LDPLAYER_MAP_RELATIVE)
                check(!store.exists(LDPLAYER_MAP_RELATIVE)) { "Tệp map vẫn còn sau khi xóa." }
            }
            shizukuTarget -> requireShizuku().removeMap()
            adbTarget -> adbWifi.removeMap()
            else -> check(!targetExists(uri) || DocumentsContract.deleteDocument(contentResolver, Uri.parse(uri))) { "Trình chọn tệp chưa cho phép xóa. Hãy dùng quyền thư mục SAF V1, Shizuku hoặc ADB WiFi." }
        }
    }

    private fun validateResourceZip(file: File) {
        java.util.zip.ZipFile(file).use { zip ->
            val names = HashSet<String>()
            var bytes = 0L
            for (entry in zip.entries()) {
                val name = entry.name.trimEnd('/')
                require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.none { it.isISOControl() } && name.split('/').none { it.isEmpty() || it == "." || it == ".." } && names.add(name)) { "ZIP có đường dẫn không hợp lệ hoặc trùng lặp." }
                require(names.size <= 100000 && entry.size >= 0) { "ZIP có quá nhiều mục hoặc thiếu dung lượng." }
                bytes += entry.size
                require(bytes <= archiveLimit) { "Dữ liệu ZIP vượt 4 GB." }
            }
            require(names.any { it == "Resources/1.64.1" || it == "1.64.1" || it.startsWith("Resources/1.64.1/") || it.startsWith("1.64.1/") }) { "ZIP phải chứa Resources/1.64.1 hoặc 1.64.1 ở thư mục gốc." }
        }
    }

    private fun applyImportedZip(pack: JSONObject): JSONObject {
        val id = pack.getString("localId")
        require(bundleLabels.contains(id)) { "Tệp đã bị xóa." }
        val archive = bundleFile(id)
        validateResourceZip(archive)
        operationPhase("Đang giải nén và áp dụng…")
        val store = resourceStore()
        val method: String
        if (store != null) {
            archive.inputStream().use { ResourceInstaller.install(store, it, false, ::operationPhase, cacheDir) }
            method = if (legacyDiskAvailable()) "LDPlayer trực tiếp" else if (adbStarted && adbWifi.isConnected()) "ADB WiFi" else "SAF"
        } else {
            check(shizukuConnected()) { "Hãy cấp quyền Shizuku, ADB WiFi, SAF V1 cho thư mục files hoặc LDPlayer. SAF V2 chỉ cấp quyền một tệp map, không đủ để giải nén ZIP." }
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { checkNotNull(mapService).installArchive(it, false) }
            method = "Shizuku"
        }
        return JSONObject().put("message", "Đã giải nén, ghi đè và xác minh các tệp trong Resources.")
            .put("pack", state.getString("pack", null)?.let(::JSONObject)).put("accessMethod", method)
    }

    private fun applyOnlineSkin(pack: JSONObject): JSONObject {
        val wasActive = state.getBoolean("skinChessActive", false)
        if (wasActive && !skinOriginal.valid()) return JSONObject().put("error", "Skin hiện tại chưa có bản sao gốc. Hãy dùng Fix Resources để khôi phục tài nguyên gốc trước khi áp dụng lại.").put("permissionAction", "fix-data")
        val store = resourceStore()
        val service = if (store == null) mapService?.takeIf { shizukuConnected() } else null
        check(store != null || service != null) { "Hãy cấp quyền thư mục SAF V1, Shizuku, ADB WiFi hoặc LDPlayer. SAF V2 chỉ dùng cho tệp map cờ." }
        // Resolve the current public folder server-side; do not trust a file ID supplied by the WebView.
        val items = driveList(skinFolder)
        val source = if (pack.optString("id") == skinFolder) items else {
            val folder = items.singleOrNull { it.folder && it.id == pack.optString("id") } ?: error("Skin không còn trong SkinChess.")
            driveList(folder.id)
        }
        val file = source.singleOrNull { !it.folder && it.name == "ResACChessInfoCfg.bytes" } ?: error("Không tìm thấy tệp skin hợp lệ.")
        val downloaded = File.createTempFile("skin-", ".bytes", cacheDir)
        val snapshot = File.createTempFile("skin-before-", ".bytes", filesDir)
        var retain = false
        try {
            driveFile(file.id) { input -> downloaded.outputStream().use { copyLimited(input, it) } }
            require(downloaded.length() > 0) { "Tệp skin trống." }
            fun exists() = store?.exists(SKIN_RELATIVE) ?: service!!.resourceExists(SKIN_RELATIVE)
            fun current(): InputStream = store?.read(SKIN_RELATIVE) ?: ParcelFileDescriptor.AutoCloseInputStream(service!!.readResource(SKIN_RELATIVE))
            fun put(file: File) {
                operationPhase("Đang ghi tệp skin…")
                if (store != null) file.inputStream().use { store.write(SKIN_RELATIVE, it) }
                else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { service!!.writeResource(SKIN_RELATIVE, it) }
                val expected = fileHash(file)
                val actual = current().use(::digest).toHex()
                check(expected == actual) {
                    "Tệp skin sau khi ghi không khớp (${if (service != null) "Shizuku" else "Bộ nhớ"}; nguồn ${file.length()} byte; mã ${expected.take(12)}/${actual.take(12)}). Hãy đóng trò chơi rồi thử lại."
                }
            }
            val hadOriginal = exists()
            require(hadOriginal) { "Không thấy tệp skin gốc trong tài nguyên. Hãy khôi phục bằng Fix Resources trước khi áp dụng skin." }
            current().use { input -> snapshot.outputStream().use { copyLimited(input, it) } }
            operationPhase("Đang lưu skin gốc…")
            skinOriginal.beforeApply(snapshot, wasActive || fileHash(snapshot) == fileHash(downloaded))
            try { put(downloaded) } catch (failure: Exception) {
                if (hadOriginal) retain = runCatching { put(snapshot) }.isFailure
                if (retain) error("Ghi skin thất bại. Bản sao cũ được giữ tại ${snapshot.name} trong dữ liệu SenceLab.")
                throw failure
            }
            check(state.edit().putBoolean("skinChessActive", true).commit())
            val applied = JSONObject(pack.toString()).apply { remove("enteredPass"); remove("fromHistory") }
            return JSONObject().put("skinActive", true).put("applied", applied).put("pack", state.getString("pack", null)?.let(::JSONObject))
                .put("message", "Đã ghi và xác minh tệp Mod skin.").put("accessMethod", if (service != null) "Shizuku" else if (legacyDiskAvailable()) "LDPlayer trực tiếp" else if (adbStarted && adbWifi.isConnected()) "ADB WiFi" else "SAF")
        } finally { downloaded.delete(); if (!retain) snapshot.delete() }
    }

    private fun safPermission(key: String): Uri? = state.getString(key, null)?.let(Uri::parse)?.takeIf { uri ->
        (sessionSaf == uri || contentResolver.persistedUriPermissions.any { (it.uri == uri || (DocumentsContract.isTreeUri(uri) &&
            it.uri == DocumentsContract.buildTreeDocumentUri(uri.authority!!, DocumentsContract.getTreeDocumentId(uri)))) && it.isReadPermission && it.isWritePermission }) &&
            runCatching { contentResolver.openFileDescriptor(uri, "rw")?.use { true } == true }.getOrDefault(false)
    }

    private fun savedSaf(): Uri? = safPermission("target") ?: safPermission("saf1") ?: safPermission("saf2")

    private fun shizukuWritable(): Boolean = runCatching {
        permissionEnabled("Shizuku") && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            mapService?.canWrite() == true
    }.getOrDefault(false)

    private fun writableTarget(preferred: String? = null): String? {
        if (directWritable()) return directTarget
        if (safRootStore()?.let { probeResourceWrite(it) } == true) return safTreeTarget
        if (preferred == shizukuTarget && shizukuWritable()) return shizukuTarget
        if (preferred == adbTarget && adbStarted && adbWifi.canWrite()) return adbTarget
        val saf = savedSaf()?.toString()
        if (preferred != null && preferred == saf) return saf
        return when {
            adbStarted && adbWifi.canWrite() -> adbTarget
            shizukuWritable() -> shizukuTarget
            else -> saf
        }
    }

    private fun isFixedTarget(target: String): Boolean {
        if (target == shizukuTarget || target == adbTarget || target == directTarget || target == safTreeTarget) return true
        val uri = Uri.parse(target)
        return uri.authority == "com.android.externalstorage.documents" &&
            runCatching { DocumentsContract.getDocumentId(uri) == targetDocument }.getOrDefault(false)
    }

    private fun shizukuConnected(): Boolean = runCatching {
        permissionEnabled("Shizuku") && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            mapService?.asBinder()?.isBinderAlive == true
    }.getOrDefault(false)

    private fun resourceVersionPresent(): Boolean? = runCatching {
        val store = resourceStore()
        if (store != null) {
            if (store.list("").none { it.first == "Resources" && it.second }) false
            else store.list("Resources").any { it.first == "1.64.1" && it.second }
        } else if (shizukuConnected()) checkNotNull(mapService).resourceExists("Resources/1.64.1") else null
    }.getOrNull()

    private fun checkPermissionState() = task("permission") {
        val shizukuRunning = Shizuku.pingBinder()
        val shizukuGranted = permissionEnabled("Shizuku") && shizukuRunning && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val probe = if (shizukuGranted) runCatching { mapService?.probe() }
            .getOrElse { "Không kiểm tra được tệp qua Shizuku" } else null
        val shizukuReady = probe == "Sẵn sàng"
        val adbReady = adbStarted && adbWifi.canWrite()
        val safReady = savedSaf() != null
        val legacySaf = safPermission("target")
        val saf1Ready = safRootStore()?.let { probeResourceWrite(it) } == true || safPermission("saf1") != null || (legacySaf != null && DocumentsContract.isTreeUri(legacySaf))
        val saf2Ready = safPermission("saf2") != null || (legacySaf != null && !DocumentsContract.isTreeUri(legacySaf))
        val directReady = directWritable()
        val methods = listOf(
            Triple("Shizuku", shizukuReady, shizukuConnected()),
            Triple("ADB WiFi", adbReady && !adbWifi.ldPlayerMode, adbStarted && !adbWifi.ldPlayerMode && adbWifi.isConnected()),
            Triple("SAF V1", saf1Ready, saf1Ready), Triple("SAF V2", saf2Ready, saf2Ready),
            Triple("LDPlayer trực tiếp", directReady, directReady),
        )
        val fixedBefore = state.getString("fixedResourcesVersion", "") == "1.64.1"
        val ready = shizukuReady || adbReady || safReady || directReady
        val versionPresent = resourceVersionPresent()
        val suggestFix = needsResourceFix(shizukuConnected() || (adbStarted && adbWifi.isConnected()), ready, fixedBefore, versionPresent)
        val selected = methods.firstOrNull { it.first == state.getString("selectedPermission", "") }
        val active = selected?.takeIf { it.second } ?: methods.firstOrNull { it.second }
            ?: selected?.takeIf { it.third } ?: methods.firstOrNull { it.third }
        val shizukuLabel = when {
            shizukuReady -> "Sẵn sàng"
            shizukuGranted && mapService == null -> "Đang kết nối"
            shizukuGranted -> probe ?: "Không kiểm tra được tệp qua Shizuku"
            shizukuRunning -> "Đang chạy, chưa cấp quyền"
            else -> "Chưa chạy"
        }
        val adbLabel = when {
            adbReady -> "Sẵn sàng"
            adbStarted && adbWifi.isConnected() -> "Đã kết nối, chưa ghi được tệp"
            else -> "Chưa kết nối"
        }
        JSONObject().put("message", "Shizuku: $shizukuLabel · ADB WiFi: $adbLabel")
            .put("shizuku", shizukuLabel)
            .put("adb", if (adbWifi.ldPlayerMode) "Chưa kết nối" else adbLabel)
            .put("ld", if (directWritable()) "Sẵn sàng · ghi trực tiếp" else if (adbWifi.ldPlayerMode) adbLabel else "Chưa có quyền ghi")
            .put("saf", if (safReady) "Sẵn sàng" else "Chưa có quyền ghi")
            .put("ready", shizukuReady || adbReady || safReady || directWritable())
            .put("methods", JSONArray().apply { methods.forEach { (name, ready, connected) ->
                put(JSONObject().put("name", name).put("ready", ready).put("connected", connected))
            } })
            .put("summary", active?.let { "${it.first} đã kết nối" } ?: "Chưa có quyền bộ nhớ")
            .put("statusColor", if (active?.second == true) "green" else if (active?.third == true) "yellow" else "red")
            .put("selectedReady", selected?.second == true)
            .put("selectedMethod", selected?.first ?: "").put("activeMethod", active?.first ?: "")
            .put("resourcesFixed", fixedBefore).put("resourcesWritable", ready).put("resourcesVersionPresent", versionPresent)
            .put("canFix", suggestFix)
    }

    private data class DriveItem(val name: String, val id: String, val folder: Boolean)

    private fun driveList(id: String): List<DriveItem> =
        driveRead("https://drive.google.com/embeddedfolderview?id=$id") { input ->
            val html = input.bufferedReader().use { it.readText() }
            driveEntry.findAll(html).map {
                DriveItem(Html.fromHtml(it.groupValues[3], Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                    it.groupValues[2], it.groupValues[1] == "drive/folders")
            }.toList()
        }

    private fun drivePass(id: String): String {
        require(id.isNotBlank()) { "Chưa có pass.txt công khai cho map này." }
        return driveFile(id) { input ->
            val bytes = readUpTo(input, 4097)
            require(bytes.size <= 4096) { "pass.txt quá dài." }
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim().also {
                require(it.isNotBlank() && !it.startsWith("<") && !it.contains('\n')) { "pass.txt cần chứa một mật khẩu trên một dòng." }
            }
        }
    }

    private fun <T> driveFile(id: String, read: (InputStream) -> T): T {
        require(id.matches(Regex("[a-zA-Z0-9_-]{10,}"))) { "Mã tệp Drive không hợp lệ." }
        return driveRead("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t", read)
    }

    private fun <T> driveRead(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        try {
            check(connection.responseCode == 200) { "Drive trả HTTP ${connection.responseCode}." }
            val fileDownload = connection.url.host == "drive.usercontent.google.com"
            val length = connection.contentLengthLong
            var downloaded = 0L
            var lastUpdate = 0L
            fun progress(done: Boolean = false) {
                if (!fileDownload) return
                val now = android.os.SystemClock.elapsedRealtime()
                if (!done && now - lastUpdate < 150) return
                lastUpdate = now
                val data = JSONObject().put("bytes", downloaded).put("total", length)
                    .put("percent", if (done) 100 else if (length > 0) (downloaded * 100 / length).coerceAtMost(99) else -1)
                runOnUiThread { emit("file-progress", data) }
            }
            progress()
            val result = connection.inputStream.use { source ->
                val tracked = object : java.io.FilterInputStream(source) {
                    override fun read(): Int {
                        val value = source.read()
                        if (value >= 0) { downloaded++; progress() }
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                        val size = source.read(buffer, offset, count)
                        if (size > 0) { downloaded += size; progress() }
                        return size
                    }
                }
                read(tracked)
            }
            progress(true)
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: error("Không đọc được tên tệp.")

    private fun read(uri: Uri): InputStream =
        contentResolver.openInputStream(uri) ?: error("Không đọc được tệp đã chọn.")

    private fun bundleFile(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Mã tệp đã nhập không hợp lệ." }
        return File(File(filesDir, "assetbundles").apply { mkdirs() }, "$id.assetbundle")
    }

    private fun importedBundles(): JSONArray = JSONArray().apply {
        bundleLabels.all.entries.mapNotNull { (id, label) ->
            val name = label as? String ?: return@mapNotNull null
            val file = bundleFile(id)
            if (file.isFile) Triple(id, name, file.lastModified()) else null
        }.sortedByDescending { it.third }.forEach { (id, name) ->
            put(JSONObject().put("localId", id).put("name", name).put("category", bundleCategories.getString(id, "Map cờ")).put("bytes", bundleFile(id).length()))
        }
    }

    private fun applyPack(uri: String, pack: JSONObject): JSONObject {
        val staged = File.createTempFile("replacement", ".bundle", cacheDir)
        val snapshot = File.createTempFile("before-apply", ".bundle", filesDir)
        var retainSnapshot = false
        try {
            stagePack(pack, staged)
            operationPhase("Đang xác minh tệp…")
            val savedPack = JSONObject(pack.toString()).apply { remove("enteredPass"); remove("fromHistory") }
            val hadOriginal = targetExists(uri)
            if (hadOriginal) readTarget(uri).use { input -> snapshot.outputStream().use { copyLimited(input, it) } }
            val previousHash = if (hadOriginal) fileHash(snapshot) else ""
            val replacementHash = fileHash(staged)
            require(previousHash != replacementHash) { "Bundle giống hệt tệp đang có trên máy. Không có nội dung mới để thay." }
            try {
                write(uri, staged, replacementHash)
                confirmTarget(uri, replacementHash)
                check(state.edit().putString("uri", uri).putString("pack", savedPack.toString())
                    .putString("installedHash", replacementHash).commit()) { "Không lưu được thông tin map." }
            } catch (failure: Exception) {
                retainSnapshot = runCatching { if (hadOriginal) write(uri, snapshot, previousHash) else deleteTarget(uri) }.isFailure
                if (retainSnapshot) error("Ghi thất bại. Bản chụp tệp cũ vẫn được giữ. Hãy đóng trò chơi và cấp lại quyền để khôi phục.")
                throw failure
            }
            return JSONObject().put("pack", savedPack).put("applied", savedPack).put("accessMethod", accessMethod(uri))
                .put("message", "Đã ghi và xác minh tệp đích · SHA-256 ${replacementHash.take(12)}")
        } finally {
            staged.delete()
            if (!retainSnapshot) snapshot.delete()
        }
    }

    private fun stagePack(pack: JSONObject, output: File) {
        if (pack.has("localId")) {
            val local = bundleFile(pack.getString("localId"))
            require(local.isFile && bundleLabels.contains(pack.getString("localId"))) { "Tệp đã bị xóa. Hãy nhập lại AssetBundle." }
            local.inputStream().use { input -> output.outputStream().use { copyLimited(input, it) } }
            validateBundle(output)
            return
        }
        if (pack.has("bundleId")) {
            require(pack.optBoolean("fromHistory", false) || pack.optString("enteredPass") == drivePass(pack.optString("passId"))) { "Pass không đúng." }
            downloadBundle(pack.getString("bundleId"), output)
            recordDownload(pack.optString("id"))
            return
        }
        error("Không tìm thấy tệp AssetBundle.")
    }

    private fun downloadBundle(id: String, output: File) {
        driveFile(id) { input -> output.outputStream().use { copyLimited(input, it) } }
        validateBundle(output)
    }

    private fun validateBundle(file: File) {
        operationPhase("Đang xác minh tệp tải về…")
        val header = file.inputStream().use { readUpTo(it, 8).toString(Charsets.US_ASCII) }
        require(header.startsWith("UnityFS")) { "Tệp không phải Unity AssetBundle." }
    }

    private fun restoreOriginalMap(): JSONObject {
        val target = writableTarget() ?: error("Chưa có quyền gỡ map. Hãy cấp quyền bộ nhớ.")
        if (target == directTarget) {
            val original = File.createTempFile("original-map-", ".bundle", cacheDir)
            val previous = File.createTempFile("before-map-restore-", ".bundle", filesDir)
            var retainPrevious = false
            try {
                operationPhase("Đang tải map gốc…")
                val entry = driveList(originalMapFolder).singleOrNull { !it.folder && it.name == targetName }
                    ?: error("Chưa tìm thấy tệp map gốc. Hãy thử lại sau.")
                downloadBundle(entry.id, original)
                val expected = fileHash(original)
                val existed = targetExists(target)
                if (existed) readTarget(target).use { input -> previous.outputStream().use { copyLimited(input, it) } }
                val previousHash = if (existed) fileHash(previous) else ""
                try {
                    operationPhase("Đang khôi phục map gốc…")
                    write(target, original, expected)
                    operationPhase("Đang xác minh map gốc…")
                    confirmTarget(target, expected)
                } catch (failure: Exception) {
                    retainPrevious = runCatching {
                        if (existed) write(target, previous, previousHash) else deleteTarget(target)
                    }.isFailure
                    if (retainPrevious) error("Khôi phục chưa hoàn tất. Bản chụp tệp trước đó vẫn được giữ. Hãy cấp lại quyền rồi thử lại.")
                    throw failure
                }
                clearRecovery()
                return JSONObject().put("message", "Đã khôi phục map mặc định.").put("accessMethod", accessMethod(target))
            } finally {
                original.delete()
                if (!retainPrevious) previous.delete()
            }
        }
        operationPhase("Đang xóa tệp map mod…")
        deleteTarget(target)
        operationPhase("Đang xác minh đã gỡ…")
        check(!targetExists(target)) { "Tệp map vẫn còn sau khi xóa." }
        clearRecovery()
        return JSONObject().put("message", "Đã xóa tệp map mod trong Resources.").put("accessMethod", accessMethod(target))
    }

    private fun recordDownload(mapId: String) {
        if (BuildConfig.COUNTER_URL.isEmpty() || !mapId.matches(Regex("[A-Za-z0-9_-]{10,180}"))) return
        counterOutbox.edit().putString(UUID.randomUUID().toString(), mapId).commit()
        syncDownloads()
    }

    private fun syncDownloads() = task("downloads") {
        for ((eventId, value) in counterOutbox.all.entries.take(20)) {
            val mapId = value as? String ?: continue
            counterRequest(JSONObject().put("eventId", eventId).put("mapId", mapId))
            counterOutbox.edit().remove(eventId).apply()
        }
        JSONObject().put("ranking", counterRequest())
    }

    private fun counterRequest(event: JSONObject? = null): JSONObject {
        val base = URL(BuildConfig.COUNTER_URL)
        val appsScript = base.host == "script.google.com" && base.path.endsWith("/exec")
        val endpoint = if (appsScript) base else URL(BuildConfig.COUNTER_URL + "/downloads")
        return readCounterResponse(endpoint, event, appsScript).also {
            check(!it.has("error")) { "Máy chủ thống kê chưa chấp nhận yêu cầu." }
        }
    }

    private fun readCounterResponse(endpoint: URL, event: JSONObject?, allowScriptRedirect: Boolean): JSONObject {
        val connection = endpoint.openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        try {
            if (event != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(event.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (allowScriptRedirect && (status == 302 || status == 303)) {
                val location = connection.getHeaderField("Location") ?: error("Thiếu địa chỉ phản hồi từ Apps Script.")
                val redirect = URL(endpoint, location)
                require(redirect.protocol == "https" && redirect.host == "script.googleusercontent.com" &&
                    redirect.path == "/macros/echo" && redirect.userInfo == null &&
                    (redirect.port == -1 || redirect.port == 443)) { "Địa chỉ phản hồi Apps Script không hợp lệ." }
                return readCounterResponse(redirect, null, false)
            }
            check(status == 200) { "Máy chủ thống kê chưa phản hồi." }
            return connection.inputStream.use {
                val data = readUpTo(it, 65537)
                require(data.size <= 65536) { "Bảng thống kê quá lớn." }
                JSONObject(data.toString(Charsets.UTF_8))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readTarget(uri: String): InputStream =
        when (uri) {
            safTreeTarget -> checkNotNull(safRootStore()).read(MAP_RELATIVE)
            directTarget -> File(LDPLAYER_MAP_PATH).inputStream()
            adbTarget -> {
                check(adbStarted) { "Mở Cấp quyền bộ nhớ và kết nối lại ADB WiFi." }
                adbWifi.readMap()
            }
            shizukuTarget -> ParcelFileDescriptor.AutoCloseInputStream(requireShizuku().openOriginal())
            else -> read(Uri.parse(uri))
        }

    private fun requireShizuku(): IMapFile =
        mapService?.takeIf { shizukuWritable() } ?: error("Cần cấp lại quyền Shizuku cho tệp map.")

    private fun write(uri: String, file: File, expected: String = fileHash(file)) {
        operationPhase("Đang ghi tệp vào trò chơi…")
        if (uri == safTreeTarget) {
            file.inputStream().use { checkNotNull(safRootStore()).write(MAP_RELATIVE, it) }
        } else if (uri == directTarget) {
            check(directWritable()) { "Chưa có quyền ghi trực tiếp." }
            file.inputStream().use { DiskResources(File(LDPLAYER_FILES)).write(LDPLAYER_MAP_RELATIVE, it) }
        } else if (uri == adbTarget) {
            check(adbStarted) { "Mở Cấp quyền bộ nhớ và kết nối lại ADB WiFi." }
            adbWifi.writeMap(file)
        } else if (uri == shizukuTarget) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { requireShizuku().replace(it) }
        } else {
            file.inputStream().use { input ->
                (contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: error("Không ghi được tệp đích."))
                    .use { input.copyTo(it) }
            }
        }
        operationPhase("Đang xác minh tệp sau khi ghi…")
        check(targetHash(uri) == expected) { "Nội dung tệp đích sau khi ghi không khớp." }
    }

    private fun confirmTarget(uri: String, expected: String) {
        operationPhase("Đang kiểm tra hoàn tất…")
        Thread.sleep(450)
        check(targetHash(uri) == expected) { "Tệp đích đã thay đổi ngay sau khi ghi. Hãy đóng trò chơi và thử lại." }
    }

    private fun fileHash(file: File): String = file.inputStream().use(::digest).toHex()
    private fun targetHash(uri: String): String = readTarget(uri).use(::digest).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun digest(input: InputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return sha.digest()
            sha.update(buffer, 0, size)
        }
    }

    private fun clearRecovery() {
        check(state.edit().remove("uri").remove("pack").remove("installedHash").commit())
        backup.delete()
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long = archiveLimit) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "Tệp vượt giới hạn ${limit / (1024 * 1024)} MB." }
            output.write(buffer, 0, count)
        }
    }

    private fun readUpTo(input: InputStream, cap: Int): ByteArray {
        val bytes = ByteArray(cap)
        var offset = 0
        while (offset < cap) {
            val count = input.read(bytes, offset, cap - offset)
            if (count < 0) break
            offset += count
        }
        return bytes.copyOf(offset)
    }

    private fun latestRelease(): JSONObject {
        val connection = URL("https://api.github.com/repos/hack10293u-blip/SenceCoLqm/releases/latest")
            .openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SenceLab-Android")
        try {
            if (connection.responseCode == 404) return JSONObject().put("message", "Chưa tìm thấy release công khai trên GitHub.")
            check(connection.responseCode == 200) { "GitHub trả về lỗi ${connection.responseCode}." }
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val tag = release.getString("tag_name")
            val version = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$").matchEntire(tag)
                ?: return JSONObject().put("message", "Tag $tag chưa có định dạng phiên bản hỗ trợ.")
            val parts = version.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
            val installed = BuildConfig.VERSION_NAME.split('.').map(String::toInt)
            val newer = compareValuesBy(parts, installed, { it[0] }, { it[1] }, { it[2] }) > 0
            if (!newer) return JSONObject().put("message", "Bạn đang dùng phiên bản mới nhất ($tag).")
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                it.optString("name").endsWith(".apk", true) &&
                    it.optString("browser_download_url").startsWith("https://github.com/hack10293u-blip/SenceCoLqm/releases/download/")
            }
            return JSONObject()
                .put("message", if (apk == null) "Có bản $tag, nhưng release chưa đính kèm APK." else "Có bản $tag.")
                .put("url", apk?.optString("browser_download_url"))
        } finally {
            connection.disconnect()
        }
    }

    private fun task(event: String, action: () -> JSONObject) {
        val executor = when (event) {
            "drive", "skins" -> catalogWorker
            "downloads" -> counterWorker
            else -> worker
        }
        executor.execute {
            val result = runCatching(action).getOrElse {
                JSONObject().put("error", it.message ?: "Thao tác không thành công.")
            }
            runOnUiThread { emit(event, result) }
        }
    }

    private fun emit(event: String, data: JSONObject) {
        if (!isDestroyed) view.evaluateJavascript("window.senceResult(${JSONObject.quote(event)}, ${data})", null)
    }

    private fun operationPhase(message: String) {
        runOnUiThread { emit("operation-phase", JSONObject().put("message", message)) }
    }

    companion object {
        private const val safTreeTarget = "saf-tree"
        private const val skinFolder = "16j1Cw6RRJFUWfzRXfQDp3kaGXh81xShg"
        private const val directTarget = "ldplayer-direct"
        private const val targetName = "scene_artist_autochess_01.assetbundle"
        private const val originalMapFolder = "1TzGAJ018zKwUQx375uJIPH9RGqdrSLu9"
        private const val shizukuTarget = "shizuku"
        private const val adbTarget = "adb-wifi"
        private const val targetDocument = "primary:Android/data/com.garena.game.kgvn/files/Resources/1.64.1/assetbundle/modules/autochess/" + targetName
        private const val archiveLimit = 4L * 1024 * 1024 * 1024
        private val externalHosts = setOf("github.com", "drive.google.com", "t.me", "tiktok.com", "www.youtube.com")
        private val driveEntry = Regex("""<a\s+href="https://drive\.google\.com/(drive/folders|file/d)/([A-Za-z0-9_-]+)[^"]*"[^>]*>[^<]*(?:<(?!/a>)[^>]+>[^<]*)*?<div class="flip-entry-title">([^<]+)</div>""")
    }
}
