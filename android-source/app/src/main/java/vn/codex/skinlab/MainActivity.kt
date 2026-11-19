package vn.codex.skinlab

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9BFFF), onPrimary = Color(0xFF101017),
    secondary = Color(0xFF9FE5CA), tertiary = Color(0xFFFFB8CF),
    background = Color(0xFF101017), surface = Color(0xFF1B1A26),
    onSurface = Color(0xFFF6F1FF), onSurfaceVariant = Color(0xFFB8AFC8), outline = Color(0xFF302B40),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF6946A4), onPrimary = Color.White,
    secondary = Color(0xFF237458), tertiary = Color(0xFFA7426A),
    background = Color(0xFFF5F1FA), surface = Color(0xFFFFFAFF),
    onSurface = Color(0xFF231A35), onSurfaceVariant = Color(0xFF685C76), outline = Color(0xFFE0D6EB),
)
private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Border: Color @Composable get() = MaterialTheme.colorScheme.outline
private val TextMain: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Lilac: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Mint: Color @Composable get() = MaterialTheme.colorScheme.secondary
private val Pink: Color @Composable get() = MaterialTheme.colorScheme.tertiary
private val Tile = RoundedCornerShape(20.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", true)) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                SkinLab(dark) { enabled ->
                    dark = enabled
                    preferences.edit().putBoolean("dark", enabled).apply()
                }
            }
        }
    }
}

private enum class Screen(val title: String, val icon: ImageVector) {
    Home("Tổng quan", Icons.Default.Home),
    Catalog("Kho gói", Icons.Default.List),
    Import("Nhập ZIP", Icons.Default.Add),
    Repair("Khôi phục", Icons.Default.Refresh),
    Access("Kết nối", Icons.Default.Settings),
    Guide("Hướng dẫn", Icons.Default.Info),
    Settings("Cài đặt chung", Icons.Default.Settings),
}

private data class SkinPack(
    val name: String,
    val category: String,
    val description: String,
    val accent: Color,
    val icon: ImageVector,
)

private val packs = listOf(
    SkinPack("Dạ quang", "Trang phục", "Giáp đen ánh tím", DarkColors.primary, Icons.Default.Star),
    SkinPack("Hải lưu", "Bản đồ", "Tông xanh biển", DarkColors.secondary, Icons.Default.LocationOn),
    SkinPack("Tín hiệu", "Nút bấm", "Biểu tượng màu hồng", DarkColors.tertiary, Icons.Default.Favorite),
    SkinPack("Bình minh", "Trang phục", "Bề mặt ánh đồng", Color(0xFFF4C39C), Icons.Default.Star),
)

private val tabs = listOf(Screen.Home, Screen.Catalog, Screen.Import, Screen.Repair, Screen.Settings)

@Composable
private fun SkinLab(dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var accessBack by rememberSaveable { mutableStateOf(Screen.Home) }
    var guideBack by rememberSaveable { mutableStateOf(Screen.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Tất cả") }
    var connection by rememberSaveable { mutableStateOf<String?>(null) }
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedZip by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            if (name?.endsWith(".zip", ignoreCase = true) == true) {
                selectedZip = name
            } else {
                notice = "Hãy chọn một tệp có đuôi .zip."
            }
        }
    }
    val goBack = {
        screen = when (screen) {
            Screen.Access -> accessBack
            Screen.Guide -> guideBack
            else -> Screen.Home
        }
    }
    val openAccess = {
        accessBack = screen
        screen = Screen.Access
    }
    val openGuide = {
        guideBack = screen
        screen = Screen.Guide
    }
    BackHandler(screen != Screen.Home, onBack = goBack)

    Scaffold(
        containerColor = Background,
        topBar = {
            Header(
                screen = screen,
                onBack = goBack,
                onGuide = openGuide,
            )
        },
        bottomBar = {
            if (screen in tabs) BottomTabs(screen) { screen = it }
        },
    ) { insets ->
        key(screen) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (screen) {
                    Screen.Home -> {
                        item { PageTitle("Tùy biến giao diện", "Chọn một gói có sẵn hoặc mang theo tệp ZIP của bạn.") }
                        item { Hero { preview = packs.first().name } }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Shortcut("Kho gói", Icons.Default.List, Lilac, Modifier.weight(1f)) { screen = Screen.Catalog }
                                Shortcut("Nhập ZIP", Icons.Default.Add, Mint, Modifier.weight(1f)) { screen = Screen.Import }
                                Shortcut("Khôi phục", Icons.Default.Refresh, Pink, Modifier.weight(1f)) { screen = Screen.Repair }
                            }
                        }
                        item { SectionTitle("Trạng thái") }
                        item {
                            ActionRow(
                                "Phương thức ghi", connection?.let { "$it · Mô phỏng" } ?: "Chưa chọn phương thức",
                                Icons.Default.Settings, Lilac, onClick = openAccess,
                            )
                        }
                        item {
                            ActionRow(
                                "Gói đang dùng", active ?: "Chưa áp dụng gói nào",
                                Icons.Default.Star, Mint,
                            ) { screen = Screen.Repair }
                        }
                    }
                    Screen.Catalog -> {
                        item { PageTitle("Kho gói", "Chọn gói để xem trước và áp dụng thử.") }
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = Tile,
                                singleLine = true,
                                label = { Text("Tìm tên gói") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            )
                        }
                        item {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf("Tất cả", "Trang phục", "Bản đồ", "Nút bấm").forEach { filter ->
                                    FilterChip(selected = category == filter, onClick = { category = filter }, label = { Text(filter) })
                                }
                            }
                        }
                        val results = packs.filter {
                            (category == "Tất cả" || it.category == category) &&
                                it.name.contains(query.trim(), ignoreCase = true)
                        }
                        if (results.isEmpty()) item { Text("Không tìm thấy gói phù hợp.", color = Muted) }
                        items(results, key = { it.name }) { pack ->
                            ActionRow(pack.name, "${pack.category} · ${pack.description}", pack.icon, if (dark) pack.accent else Lilac) {
                                preview = pack.name
                            }
                        }
                    }
                    Screen.Import -> {
                        item { PageTitle("Nhập ZIP", "Chọn gói từ bộ nhớ điện thoại để xem trước.") }
                        item {
                            Surface(shape = Tile, color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Symbol(Icons.Default.Add, Mint, 62)
                                    Text(selectedZip ?: "Tệp gói giao diện", fontWeight = FontWeight.Bold)
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                    ) { Text("Chọn tệp .zip") }
                                    selectedZip?.let { name ->
                                        OutlinedButton(onClick = { preview = name }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Xem trước tệp")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            ActionRow("Cách nhập tệp", "Xem các bước sử dụng", Icons.Default.Info, Lilac, onClick = openGuide)
                        }
                    }
                    Screen.Repair -> {
                        item { PageTitle("Khôi phục", "Xem gói đang dùng và thử thao tác gỡ.") }
                        item {
                            ActionRow(active ?: "Chưa có gói đang dùng", "Trạng thái trong bản demo", Icons.Default.Star, Lilac)
                        }
                        item {
                            Button(
                                onClick = { confirmRestore = true },
                                enabled = active != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Gỡ gói đang dùng") }
                        }
                        item { SectionTitle("Trợ giúp") }
                        item {
                            ActionRow("Chẩn đoán kết nối", "Xem hoặc đổi phương thức ghi", Icons.Default.Build, Mint, onClick = openAccess)
                        }
                        item {
                            ActionRow("Hướng dẫn khôi phục", "Tìm hiểu luồng gỡ gói", Icons.Default.Info, Pink, onClick = openGuide)
                        }
                    }
                    Screen.Access -> {
                        item { PageTitle("Phương thức ghi", "Chọn một phương thức để thử luồng cấp quyền.") }
                        item {
                            ActionRow(
                                "Shizuku",
                                if (connection == "Shizuku") "Đang chọn · Mô phỏng" else "Kết nối qua dịch vụ Shizuku",
                                Icons.Default.Build, Lilac,
                            ) { connection = "Shizuku" }
                        }
                        item {
                            ActionRow(
                                "ADB Wi-Fi",
                                if (connection == "ADB Wi-Fi") "Đang chọn · Mô phỏng" else "Ghép đôi qua gỡ lỗi không dây",
                                Icons.Default.Refresh, Mint,
                            ) { connection = "ADB Wi-Fi" }
                        }
                        item {
                            ActionRow("Chọn tệp qua Android", "Dùng để nhập ZIP từ bộ nhớ", Icons.Default.List, Pink)
                        }
                        item {
                            Button(onClick = goBack, enabled = connection != null, modifier = Modifier.fillMaxWidth()) {
                                Text("Xong")
                            }
                        }
                    }
                    Screen.Guide -> {
                        item { PageTitle("Hướng dẫn", "Các bước để thử bản demo.") }
                        item { ActionRow("Chọn kết nối", "Mở trang Phương thức ghi và chọn Shizuku hoặc ADB Wi-Fi.", Icons.Default.Build, Lilac, onClick = openAccess) }
                        item { ActionRow("Chọn gói hoặc ZIP", "Mở Kho gói hoặc chọn tệp .zip trong mục Nhập ZIP.", Icons.Default.List, Mint) }
                        item { ActionRow("Áp dụng và gỡ", "Xem trước, bấm Áp dụng thử rồi mở mục Khôi phục.", Icons.Default.Refresh, Pink) }
                    }
                    Screen.Settings -> {
                        item { Text("Cài đặt chung", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold) }
                        item { AppProfile() }
                        item {
                            SettingsGroup("Chung") {
                                SettingRow("Giao diện tối", Icons.Default.Star, Lilac) {
                                    Switch(checked = dark, onCheckedChange = onDarkChange, modifier = Modifier.semantics {
                                        contentDescription = "Giao diện tối"
                                    })
                                }
                                HorizontalDivider(color = Border)
                                SettingRow("Ngôn ngữ", Icons.Default.Info, Mint) {
                                    Text("Tiếng Việt", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                        item {
                            SettingsGroup("Tiện ích") {
                                SettingsLink(
                                    "Phương thức ghi", connection?.let { "$it · Mô phỏng" } ?: "Chưa kết nối",
                                    Icons.Default.Settings, Mint, openAccess,
                                )
                                HorizontalDivider(color = Border)
                                SettingsLink("Thêm widget", "Ghim vào màn hình chính", Icons.Default.Add, Lilac) {
                                    notice = "Thêm widget đang ở bản xem trước."
                                }
                            }
                        }
                        item {
                            SettingsGroup("Liên hệ và cộng đồng") {
                                SettingsLink("YouTube", "Video hướng dẫn và cập nhật", Icons.Default.PlayArrow, Pink) {
                                    notice = "Kênh YouTube đang ở bản xem trước."
                                }
                                HorizontalDivider(color = Border)
                                SettingsLink("Fanpage", "Tin tức và thông báo", Icons.Default.Info, Lilac) {
                                    notice = "Fanpage đang ở bản xem trước."
                                }
                                HorizontalDivider(color = Border)
                                SettingsLink("Nhóm Facebook", "Trao đổi với cộng đồng", Icons.Default.Person, Mint) {
                                    notice = "Nhóm Facebook đang ở bản xem trước."
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.takeUnless { screen == Screen.Access }?.let { name ->
        val pack = packs.firstOrNull { it.name == name }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pack == packs.first()) {
                        Image(
                            painterResource(R.drawable.obsidian_cover), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(pack?.description ?: "Tệp ZIP đã chọn")
                    Text(
                        connection?.let { "Phương thức ghi: $it (mô phỏng)" } ?: "Chọn phương thức ghi để áp dụng thử.",
                        color = Muted,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (connection == null) {
                        openAccess()
                    } else {
                        preview = null
                        active = name
                        notice = "Đã áp dụng thử gói."
                        screen = Screen.Repair
                    }
                }) { Text(if (connection == null) "Chọn phương thức" else "Áp dụng thử") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Đóng") } },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Gỡ gói đang dùng?") },
            text = { Text("Thao tác này đặt lại trạng thái gói trong bản demo.") },
            confirmButton = {
                Button(onClick = {
                    active = null
                    confirmRestore = false
                    notice = "Đã gỡ gói trong bản demo."
                }) { Text("Gỡ gói") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Hủy") } },
        )
    }
    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("SkinLab") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Header(screen: Screen, onBack: () -> Unit, onGuide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(62.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (screen in tabs) {
            Symbol(Icons.Default.Star, Lilac, 38)
        } else {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Quay lại", tint = Lilac) }
        }
        Spacer(Modifier.width(10.dp))
        Text("SkinLab", color = TextMain, fontWeight = FontWeight.Black, fontSize = 23.sp, modifier = Modifier.weight(1f))
        if (screen in tabs) {
            Text("DEMO 1.0", fontSize = 10.sp, letterSpacing = 1.sp, color = Muted, fontWeight = FontWeight.Bold)
            IconButton(onClick = onGuide) { Icon(Icons.Default.Info, "Hướng dẫn", tint = Lilac) }
        }
    }
}

@Composable
private fun BottomTabs(screen: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(
        containerColor = Panel,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = screen == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(tab.title, fontSize = 10.sp, maxLines = 2, lineHeight = 11.sp, textAlign = TextAlign.Center) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Lilac,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = TextMain,
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextMain, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.1).sp)
        Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Hero(onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().height(224.dp).clip(shape).background(Panel)
            .border(1.dp, Border, shape),
    ) {
        Image(
            painterResource(R.drawable.obsidian_cover), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Color(0xF51B122A), Color(0xB11B122A), Color.Transparent))
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(18.dp).fillMaxWidth(0.68f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("GÓI NỔI BẬT", color = DarkColors.primary, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            Text("Dạ quang", color = DarkColors.onSurface, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
            Text("Giáp đen ánh tím", color = DarkColors.onSurface, fontSize = 13.sp)
            Button(onClick = onClick) { Text("Xem gói") }
        }
    }
}

@Composable
private fun Shortcut(title: String, icon: ImageVector, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(Tile).background(Panel).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Symbol(icon, accent)
        Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 19.sp)
}

@Composable
private fun AppProfile() {
    Surface(shape = Tile, color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(65.dp).background(
                    Brush.horizontalGradient(listOf(Color(0xFF38274F), Color(0xFF614175)))
                )
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Image(
                    painterResource(R.drawable.skinlab_icon), contentDescription = null,
                    modifier = Modifier.size(62.dp).clip(CircleShape).border(3.dp, Panel, CircleShape),
                )
                Text("SkinLab", color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                Text("Phiên bản demo 1.0", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = Tile, color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 3.dp)) {
            Text(title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String, icon: ImageVector, accent: Color,
    modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, accent, 38)
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    SettingRow(title, icon, accent, Modifier.clickable(onClick = onClick), subtitle) {
        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Symbol(icon: ImageVector, accent: Color, size: Int = 42) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(accent.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(Tile).background(Panel).border(1.dp, Border, Tile)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(icon, accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (onClick != null) Icon(Icons.Default.ArrowForward, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
    }
}
