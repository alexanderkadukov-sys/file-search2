package com.ugraremkomp.openchat

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * OpenChat 0.4.0 native shell.
 * The existing public WebView is a separate activity and retains all working
 * Realtime, photo, and file flows. The Personal Inbox is deliberately empty
 * and locked: NEVER fetch personal messages through anonymous public Supabase.
 */
class HomeActivity : ComponentActivity() {
    private val unlocked = mutableStateOf(false)
    private val loginNotice = mutableStateOf<String?>(null)
    private var awaitingCredential = false
    private val credentialRequest = 2424

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(12, 19, 29)
        window.navigationBarColor = AndroidColor.rgb(12, 19, 29)
        window.decorView.systemUiVisibility = 0

        val preferences = getSharedPreferences("openchat-native", MODE_PRIVATE)
        val dark = mutableStateOf(preferences.getBoolean("dark", true))

        setContent {
            NativeApp(
                dark = dark.value,
                onThemeChange = { value ->
                    dark.value = value
                    preferences.edit().putBoolean("dark", value).apply()
                },
                personalUnlocked = unlocked.value,
                onUnlock = ::requestDeviceCredential,
                onLock = ::lockInbox,
                onOpenPublic = {
                    startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                },
                loginNotice = loginNotice.value,
                clearNotice = { loginNotice.value = null }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun requestDeviceCredential() {
        val manager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (manager?.isDeviceSecure != true) {
            loginNotice.value = "Для входа в личный раздел сначала включите PIN-код, пароль или графический ключ Android. Это не подключает внешние аккаунты."
            return
        }
        val intent = manager.createConfirmDeviceCredentialIntent(
            "OpenChat · Личный раздел",
            "Подтвердите владельца устройства"
        )
        if (intent == null) {
            loginNotice.value = "Не удалось открыть системную проверку устройства."
            return
        }
        awaitingCredential = true
        startActivityForResult(intent, credentialRequest)
    }

    @Deprecated("Android framework credential confirmation callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == credentialRequest) {
            awaitingCredential = false
            if (resultCode == RESULT_OK) {
                unlocked.value = true
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                unlocked.value = false
                loginNotice.value = "Вход не подтверждён."
            }
        }
    }

    private fun lockInbox() {
        unlocked.value = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        if (!awaitingCredential) lockInbox()
    }
}

private val NightBackground = Color(0xFF0D1621)
private val NightSurface = Color(0xFF182636)
private val NightRaised = Color(0xFF203347)
private val NightTextMuted = Color(0xFF9BAEBD)
private val Accent = Color(0xFF4CA6F4)
private val Mint = Color(0xFF55C7A2)

private fun themeColors(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF051E32),
        background = NightBackground,
        onBackground = Color(0xFFF0F5FA),
        surface = NightSurface,
        onSurface = Color(0xFFF0F5FA),
        surfaceVariant = NightRaised,
        onSurfaceVariant = NightTextMuted,
        outlineVariant = Color(0xFF2D4154)
    )
} else {
    lightColorScheme(
        primary = Color(0xFF147FC8),
        background = Color(0xFFF3F7FB),
        onBackground = Color(0xFF1A2A37),
        surface = Color.White,
        onSurface = Color(0xFF1A2A37),
        surfaceVariant = Color(0xFFE8F1F8),
        onSurfaceVariant = Color(0xFF607889),
        outlineVariant = Color(0xFFDCE7EF)
    )
}

private enum class Page(val title: String, val icon: ImageVector) {
    PUBLIC("Чаты", Icons.Outlined.Forum),
    INBOX("Личные", Icons.Outlined.Lock),
    CONNECTIONS("Каналы", Icons.Outlined.Tune),
    SETTINGS("Настройки", Icons.Outlined.Settings)
}

@Composable
private fun NativeApp(
    dark: Boolean,
    onThemeChange: (Boolean) -> Unit,
    personalUnlocked: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onOpenPublic: () -> Unit,
    loginNotice: String?,
    clearNotice: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(Page.PUBLIC.name) }
    val active = Page.entries.firstOrNull { it.name == page } ?: Page.PUBLIC
    val colors = themeColors(dark)

    BackHandler(enabled = active != Page.PUBLIC) {
        if (active == Page.INBOX) onLock()
        page = Page.PUBLIC.name
    }

    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Scaffold(
                containerColor = colors.background,
                topBar = {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(73.dp)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp)
                                .background(colors.primary, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("OpenChat", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = colors.onBackground, letterSpacing = (-0.55).sp)
                            Text("Единый центр общения", fontSize = 11.sp,
                                color = colors.onSurfaceVariant)
                        }
                        Surface(
                            shape = CircleShape,
                            color = colors.surfaceVariant
                        ) {
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = "Приватность",
                                tint = colors.primary,
                                modifier = Modifier.padding(10.dp).size(19.dp)
                            )
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = colors.surface,
                        tonalElevation = 0.dp
                    ) {
                        Page.entries.forEach { item ->
                            NavigationBarItem(
                                selected = active == item,
                                onClick = {
                                    if (active == Page.INBOX && item != Page.INBOX) onLock()
                                    page = item.name
                                },
                                icon = {
                                    Icon(item.icon, contentDescription = null,
                                        modifier = Modifier.size(23.dp))
                                },
                                label = { Text(item.title, fontSize = 10.sp, maxLines = 1) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            ) { inner ->
                Box(
                    Modifier.fillMaxSize().padding(inner)
                        .padding(horizontal = 18.dp)
                ) {
                    when (active) {
                        Page.PUBLIC -> PublicScreen(onOpenPublic)
                        Page.INBOX -> InboxScreen(personalUnlocked, onUnlock, onLock) {
                            page = Page.CONNECTIONS.name
                        }
                        Page.CONNECTIONS -> ConnectionsScreen()
                        Page.SETTINGS -> SettingsScreen(dark, onThemeChange, onOpenPublic, onLock)
                    }
                }
            }
            if (loginNotice != null) {
                AlertDialog(
                    onDismissRequest = clearNotice,
                    title = { Text("Проверка доступа") },
                    text = { Text(loginNotice) },
                    confirmButton = { TextButton(onClick = clearNotice) { Text("Понятно") } }
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.65).sp, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, fontSize = 13.sp,
            lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallTag(text: String, tint: Color = Accent) {
    Surface(shape = RoundedCornerShape(7.dp),
        color = tint.copy(alpha = .12f)) {
        Text(text, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun PrimarySurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .53f))
    ) { content() }
}

@Composable
private fun PublicScreen(openChat: () -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(17.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            SectionHeading("Чаты", "Публичные комнаты OpenChat. Общение без регистрации.")
        }
        item {
            PrimarySurface {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallTag("ПУБЛИЧНО", Color(0xFFE8B66C))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Открытое общение", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Реальные сообщения, фотографии и файлы через действующий Supabase. " +
                        "Откроется текущая рабочая версия чата.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = openChat, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)) {
                        Text("Открыть публичный чат", modifier = Modifier.padding(vertical = 5.dp))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        item { Text("Комнаты", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        items(listOf(
            Triple("Общий чат", "Сообщения и разговоры", Icons.Outlined.Forum),
            Triple("Фото", "Фотографии участников", Icons.Outlined.PermMedia),
            Triple("Файлы", "Документы и вложения", Icons.Outlined.Description)
        )) { (name, description, icon) ->
            PrimarySurface {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openChat() }
                        .padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .13f)) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(13.dp).size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(description, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            NoticeLine("Все сообщения в этих комнатах видны посетителям. " +
                "Личные переписки будут находиться в отдельном защищённом разделе.")
        }
        item { Spacer(Modifier.height(15.dp)) }
    }
}

@Composable
private fun InboxScreen(
    unlocked: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onConnections: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Spacer(Modifier.height(4.dp))
        SectionHeading("Личные сообщения",
            "Отдельный раздел для будущих личных переписок.")
        if (!unlocked) {
            PrimarySurface {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                        Icon(Icons.Outlined.Lock, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(20.dp).size(37.dp))
                    }
                    Text("Доступ закрыт", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Для входа потребуется системный PIN-код, пароль или " +
                        "ключ блокировки телефона. Приватная база ещё не подключена.",
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)) {
                        Icon(Icons.Outlined.LockOpen, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("Разблокировать раздел",
                            modifier = Modifier.padding(vertical = 5.dp))
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallTag("ДОСТУП К УСТРОЙСТВУ ПОДТВЕРЖДЁН", Mint)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLock) { Text("Закрыть") }
            }
            PrimarySurface {
                Column(
                    Modifier.fillMaxWidth().padding(23.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Icon(Icons.Outlined.Sms, null, Modifier.size(43.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Диалогов пока нет", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("Это защищённая оболочка будущего Inbox. " +
                        "Telegram, WhatsApp, VK и MAX пока не подключены. " +
                        "Мы не переносим личные сообщения в открытую базу.",
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onConnections) {
                        Text("Посмотреть подключения")
                        Spacer(Modifier.width(7.dp))
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(17.dp))
                    }
                }
            }
        }
        NoticeLine("PIN телефона защищает только локальный вход в этот экран. " +
            "Перед настоящей синхронизацией потребуется отдельная серверная авторизация и закрытое хранилище.")
    }
}

private data class Channel(
    val title: String, val detail: String, val mode: String,
    val symbol: String, val state: String, val color: Color
)
private val channels = listOf(
    Channel("Telegram", "Личный аккаунт через TDLib", "user_client",
        "T", "Интеграция не настроена", Color(0xFF49AAEF)),
    Channel("WhatsApp", "Личный аккаунт: ручной импорт", "manual",
        "W", "Автосинхронизация недоступна", Color(0xFF44C49D)),
    Channel("ВКонтакте", "Личный профиль: проверка разрешений", "conditional",
        "VK", "Не подключено", Color(0xFF4F98EA)),
    Channel("MAX", "Личный аккаунт: без публичного API", "manual",
        "M", "Не подключено", Color(0xFF9881EE))
)

@Composable
private fun ConnectionsScreen() {
    var detail by remember { mutableStateOf<Channel?>(null) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionHeading("Подключения",
            "Личные аккаунты. Статусы реальные — пока ни один не подключён.") }
        items(channels) { channel ->
            PrimarySurface {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { detail = channel }
                        .padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(15.dp),
                        color = channel.color.copy(alpha = .17f)) {
                        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
                            Text(channel.symbol, color = channel.color,
                                fontWeight = FontWeight.Black, fontSize = 17.sp)
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.title, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(channel.detail, fontSize = 11.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(channel.state, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Outlined.ArrowForward, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            NoticeLine("Подключения появятся только после настройки официального API, " +
                "безопасного хранилища сессий и теста на двух устройствах.")
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
    if (detail != null) {
        val item = detail!!
        AlertDialog(
            onDismissRequest = { detail = null },
            icon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
            title = { Text(item.title) },
            text = { Text(
                item.detail + "\n\nСтатус: " + item.state +
                    "\n\nЭто экран возможностей, не активная интеграция. " +
                    "Вход в личный аккаунт и обмен сообщениями пока не реализованы."
            ) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun SettingsScreen(
    dark: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onOpenPublic: () -> Unit,
    onLock: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(4.dp))
        SectionHeading("Настройки", "Приложение и защита данных")
        PrimarySurface {
            Row(Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DarkMode, null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("Тёмная тема", fontWeight = FontWeight.SemiBold)
                    Text("Оформление Android-оболочки", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dark, onCheckedChange = onThemeChange)
            }
        }
        PrimarySurface {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = Mint)
                    Spacer(Modifier.width(10.dp))
                    Text("Приватность", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("Личный Inbox не использует публичную базу OpenChat. " +
                    "Доступ к закрытому экрану требует подтверждения устройства. " +
                    "История из других мессенджеров сейчас не импортируется.",
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onLock) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Закрыть личный раздел")
                }
            }
        }
        PrimarySurface {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OpenChat для Android", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SmallTag("0.4.0 · НАТИВНАЯ ОБОЛОЧКА")
                Text("Публичные комнаты работают через действующий веб-чат. " +
                    "Личные Telegram, WhatsApp, VK и MAX ещё не подключены.",
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenPublic) {
                    Text("Открыть публичный чат")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                }
            }
        }
        NoticeLine("Никаких личных сообщений в открытой базе Supabase.")
    }
}

@Composable
private fun NoticeLine(message: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(9.dp))
        Text(message, fontSize = 11.sp, lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
