package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.OffsetDateTime
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.MoreScreen
import ru.servpanel.mobile.data.ProxyBotStatus
import ru.servpanel.mobile.data.ProxyCapabilities
import ru.servpanel.mobile.data.ProxyStatus
import ru.servpanel.mobile.data.ProxyUser
import ru.servpanel.mobile.data.ProxyUserLinks
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.formatUptime
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

/**
 * Пользователи MTProto-прокси: MTProxyL и Telemt через совместимый API
 * панели (`/api/proxy/...`). MTProto.zig пользователей не умеет — экран
 * показывает состояние движка и предлагает установить MTProxyL.
 */
@Composable
fun ProxyScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var status by remember(server.id) { mutableStateOf<ProxyStatus?>(null) }
    var users by remember(server.id) { mutableStateOf<List<ProxyUser>>(emptyList()) }
    var bot by remember(server.id) { mutableStateOf<ProxyBotStatus?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var deleteTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var rotateTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var resetQuotaTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var resetTrafficTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var linksTarget by remember { mutableStateOf<ProxyUser?>(null) }
    var showBot by remember { mutableStateOf(false) }

    var showMtLinks by remember { mutableStateOf(false) }
    var mtLinks by remember { mutableStateOf<List<String>?>(null) }
    var mtLinksLoading by remember { mutableStateOf(false) }
    var mtLinksError by remember { mutableStateOf<String?>(null) }

    var userLinks by remember { mutableStateOf<ProxyUserLinks?>(null) }
    var userLinksLoading by remember { mutableStateOf(false) }
    var userLinksError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (status == null) loading = true
        error = null
        try {
            val next = vm.call(server) { proxyStatus() }
            status = next
            users = if (next.available && next.capabilities.users) {
                vm.call(server) { proxyUsers() }
            } else {
                emptyList()
            }
            if (auth.isAdmin) {
                bot = runCatching { vm.call(server) { proxyBot() } }.getOrNull()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null) error = e.message ?: "Не удалось прочитать состояние прокси"
        } finally {
            loading = false
        }
    }

    fun report(ok: Boolean, text: String) {
        messageOk = ok
        message = text
    }

    fun runAction(user: ProxyUser, action: String, okText: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                vm.call(server) { proxyUserAction(user.username, action) }
                report(true, okText)
                reload++
            } catch (e: Exception) {
                report(false, e.message ?: "Не удалось выполнить действие")
            } finally {
                busy = false
            }
        }
    }

    fun runDelete(user: ProxyUser) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                vm.call(server) { deleteProxyUser(user.username) }
                report(true, "Пользователь ${user.username} удалён")
                reload++
            } catch (e: Exception) {
                report(false, e.message ?: "Не удалось удалить пользователя")
            } finally {
                busy = false
            }
        }
    }

    fun loadMtLinks() {
        showMtLinks = true
        if (mtLinks != null) return
        mtLinksLoading = true
        mtLinksError = null
        scope.launch {
            try {
                mtLinks = vm.call(server) { mtprotoLinks() }
            } catch (e: Exception) {
                mtLinksError = e.message ?: "Не удалось прочитать ссылки"
            } finally {
                mtLinksLoading = false
            }
        }
    }

    fun loadUserLinks(user: ProxyUser) {
        linksTarget = user
        userLinks = null
        userLinksError = null
        userLinksLoading = true
        scope.launch {
            try {
                userLinks = vm.call(server) { proxyUserLinks(user.username) }
            } catch (e: Exception) {
                userLinksError = e.message ?: "Не удалось получить ссылки"
            } finally {
                userLinksLoading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            ScreenHeader(
                title = "Прокси",
                subtitle = "MTProto · пользователи и лимиты",
                trailing = {
                    IconButton(onClick = { loadMtLinks() }) {
                        Icon(Icons.Filled.Link, contentDescription = "Ссылки MTProto", tint = PanelMuted)
                    }
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (messageOk) PanelGreen else PanelRed
                )
            }
        }

        when {
            loading && status == null -> LoadingState("Проверяем MTProto-прокси…")

            status == null -> Column(Modifier.padding(16.dp)) {
                ErrorState(error ?: "Нет данных", onRetry = { reload++ })
            }

            else -> {
                val current = status!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        ProxyStatusCard(
                            status = current,
                            isAdmin = auth.isAdmin,
                            busy = busy,
                            onRestart = {
                                if (busy) return@ProxyStatusCard
                                busy = true
                                scope.launch {
                                    try {
                                        vm.call(server) { restartProxy() }
                                        report(true, "Прокси перезапущен")
                                        reload++
                                    } catch (e: Exception) {
                                        report(false, e.message ?: "Не удалось перезапустить прокси")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onIssueKey = {
                                if (busy) return@ProxyStatusCard
                                busy = true
                                scope.launch {
                                    try {
                                        vm.call(server) { rotateProxyApiKey() }
                                        report(true, "Ключ API MTProxyL выдан и сохранён")
                                        reload++
                                    } catch (e: Exception) {
                                        report(false, e.message ?: "Не удалось выдать ключ")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onInstall = { vm.openMore(MoreScreen.Install, installModuleKey = "mtproxyl") }
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "Пользователи",
                                subtitle = if (current.capabilities.users) {
                                    "Всего ${users.size}"
                                } else {
                                    "Движок не поддерживает пользователей"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (auth.isAdmin && current.capabilities.users) {
                                IconButton(onClick = { showCreate = true }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Создать пользователя", tint = PanelAccent)
                                }
                            }
                        }
                    }

                    if (current.capabilities.users && users.isEmpty()) {
                        item {
                            PanelCard {
                                EmptyState(
                                    icon = Icons.Filled.Person,
                                    title = "Пользователей пока нет",
                                    text = "Создайте первого пользователя — панель покажет ссылку и QR-код."
                                )
                                if (auth.isAdmin) {
                                    PanelButton(
                                        text = "Создать пользователя",
                                        onClick = { showCreate = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        icon = Icons.Filled.Add
                                    )
                                }
                            }
                        }
                    }

                    items(users, key = { it.username }) { user ->
                        ProxyUserCard(
                            user = user,
                            canEdit = auth.isAdmin,
                            caps = current.capabilities,
                            onLinks = { loadUserLinks(user) },
                            onEdit = { editTarget = user },
                            onToggle = {
                                runAction(
                                    user,
                                    if (user.enabled) "disable" else "enable",
                                    if (user.enabled) "Пользователь ${user.username} отключён"
                                    else "Пользователь ${user.username} включён"
                                )
                            },
                            onRotate = { rotateTarget = user },
                            onResetQuota = { resetQuotaTarget = user },
                            onResetTraffic = { resetTrafficTarget = user },
                            onDelete = { deleteTarget = user }
                        )
                    }

                    if (auth.isAdmin && current.available) {
                        item {
                            PanelCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PanelAccent.copy(alpha = 0.12f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Send,
                                            contentDescription = null,
                                            tint = PanelAccent,
                                            modifier = Modifier.padding(9.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Telegram-бот панели",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = PanelText
                                        )
                                        Text(
                                            when {
                                                bot == null -> "Управление прокси из Telegram"
                                                bot!!.enabled -> "Включён · ${bot!!.admins.size} админ(ов)"
                                                else -> "Выключен"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PanelMuted
                                        )
                                    }
                                    TextButton(onClick = { showBot = true }) {
                                        Text("Настроить", color = PanelAccent)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        PanelCard {
                            SectionHeader("Ссылки MTProto", "Сохранены установщиком MEKO")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Ссылки `tg://` из /root/mtproto_links.txt: Telemt, MTProto.zig и фикс.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PanelMuted
                            )
                            Spacer(Modifier.height(10.dp))
                            PanelOutlinedButton(
                                text = "Открыть ссылки",
                                onClick = { loadMtLinks() },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Filled.Link
                            )
                        }
                    }

                    if (current.reason.isNotBlank()) {
                        item {
                            PanelCard {
                                Text(
                                    current.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        ProxyUserDialog(
            user = null,
            caps = status?.capabilities ?: ProxyCapabilities(),
            onDismiss = { showCreate = false },
            onSave = { body ->
                if (busy) return@ProxyUserDialog
                busy = true
                scope.launch {
                    try {
                        vm.call(server) { createProxyUser(body) }
                        report(true, "Пользователь создан")
                        showCreate = false
                        reload++
                    } catch (e: Exception) {
                        report(false, e.message ?: "Не удалось создать пользователя")
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }

    editTarget?.let { user ->
        ProxyUserDialog(
            user = user,
            caps = status?.capabilities ?: ProxyCapabilities(),
            onDismiss = { editTarget = null },
            onSave = { body ->
                if (busy) return@ProxyUserDialog
                busy = true
                scope.launch {
                    try {
                        vm.call(server) { patchProxyUser(user.username, body) }
                        report(true, "Настройки ${user.username} сохранены")
                        editTarget = null
                        reload++
                    } catch (e: Exception) {
                        report(false, e.message ?: "Не удалось сохранить настройки")
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }

    deleteTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = PanelElevated,
            title = { Text("Удалить пользователя?") },
            text = { Text("Пользователь «${user.username}» и его ссылки перестанут работать.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    runDelete(user)
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    rotateTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { rotateTarget = null },
            containerColor = PanelElevated,
            title = { Text("Ротировать секрет?") },
            text = {
                Text(
                    "Старые ссылки и QR-код «${user.username}» перестанут работать. " +
                        "Клиенту нужно будет подключиться по новой ссылке."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rotateTarget = null
                    runAction(user, "rotate-secret", "Секрет ${user.username} обновлён")
                }) { Text("Ротировать", color = PanelAmber) }
            },
            dismissButton = {
                TextButton(onClick = { rotateTarget = null }) { Text("Отмена") }
            }
        )
    }

    resetQuotaTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { resetQuotaTarget = null },
            containerColor = PanelElevated,
            title = { Text("Сбросить квоту?") },
            text = { Text("Счётчик израсходованного трафика «${user.username}» обнулится.") },
            confirmButton = {
                TextButton(onClick = {
                    resetQuotaTarget = null
                    runAction(user, "reset-quota", "Квота ${user.username} сброшена")
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { resetQuotaTarget = null }) { Text("Отмена") }
            }
        )
    }

    resetTrafficTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { resetTrafficTarget = null },
            containerColor = PanelElevated,
            title = { Text("Сбросить трафик?") },
            text = { Text("Статистика трафика «${user.username}» обнулится.") },
            confirmButton = {
                TextButton(onClick = {
                    resetTrafficTarget = null
                    runAction(user, "reset-traffic", "Трафик ${user.username} сброшен")
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { resetTrafficTarget = null }) { Text("Отмена") }
            }
        )
    }

    linksTarget?.let { user ->
        ProxyLinksDialog(
            username = user.username,
            data = userLinks,
            loading = userLinksLoading,
            error = userLinksError,
            onRetry = { loadUserLinks(user) },
            onDismiss = { linksTarget = null; userLinks = null; userLinksError = null }
        )
    }

    if (showMtLinks) {
        MtProtoLinksDialog(
            links = mtLinks,
            loading = mtLinksLoading,
            error = mtLinksError,
            onReload = {
                mtLinks = null
                loadMtLinks()
            },
            onDismiss = { showMtLinks = false }
        )
    }

    if (showBot) {
        ProxyBotDialog(
            initial = bot,
            onDismiss = { showBot = false },
            onSave = { enabled, token, admins ->
                if (busy) return@ProxyBotDialog
                busy = true
                scope.launch {
                    try {
                        bot = vm.call(server) { saveProxyBot(enabled, token, admins) }
                        report(true, "Настройки бота сохранены")
                        showBot = false
                    } catch (e: Exception) {
                        report(false, e.message ?: "Не удалось сохранить бота")
                    } finally {
                        busy = false
                    }
                }
            },
            onTest = {
                scope.launch {
                    try {
                        vm.call(server) { testProxyBot() }
                        report(true, "Тестовое сообщение отправлено")
                    } catch (e: Exception) {
                        report(false, e.message ?: "Не удалось отправить сообщение")
                    }
                }
            }
        )
    }
}

@Composable
private fun ProxyStatusCard(
    status: ProxyStatus,
    isAdmin: Boolean,
    busy: Boolean,
    onRestart: () -> Unit,
    onIssueKey: () -> Unit,
    onInstall: () -> Unit
) {
    val color = when {
        status.needsKey -> PanelAmber
        status.available && status.running -> PanelGreen
        status.available -> PanelAmber
        else -> PanelMuted
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    when {
                        status.needsKey -> "Нужен ключ API панели"
                        status.available && status.running -> "Работает"
                        status.available -> "Остановлен"
                        status.backend == "zig" -> "Без пользователей и лимитов"
                        else -> "Не установлен"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
            Badge(
                text = when {
                    status.needsKey -> "ключ"
                    status.available && status.running -> "online"
                    status.available -> "offline"
                    else -> "нет"
                },
                color = color
            )
        }

        if (status.available) {
            Spacer(Modifier.height(10.dp))
            if (status.version.isNotBlank()) InfoRow("Версия", status.version)
            if (status.mode.isNotBlank()) InfoRow("Режим", status.mode)
            status.port?.let { InfoRow("Порт", it.toString()) }
            if (status.domain.isNotBlank()) InfoRow("Домен", status.domain)
            InfoRow("Соединения", status.connections.toString())
            InfoRow("Уникальные IP", status.uniqueIps.toString())
            InfoRow("Трафик", formatBytes(status.trafficTotal))
            if (status.uptime > 0) InfoRow("Аптайм", formatUptime(status.uptime))
        }

        if (status.needsKey) {
            Spacer(Modifier.height(8.dp))
            Text(
                "MTProxyL установлен, но API панели ещё не подключён. Панель выдаст ключ " +
                    "и сохранит его в /etc/mtproxyl-api/servpanel.key.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            Spacer(Modifier.height(10.dp))
            PanelButton(
                text = "Выдать ключ API",
                onClick = onIssueKey,
                modifier = Modifier.fillMaxWidth(),
                enabled = isAdmin && !busy,
                loading = busy,
                icon = Icons.Filled.VpnKey
            )
        }

        if (!status.available) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (status.backend == "zig") {
                    "MTProto.zig не поддерживает несколько пользователей и лимиты. " +
                        "Для управления пользователями установите MTProxyL."
                } else {
                    "MTProto-прокси не найден. Установите MTProxyL или Telemt из раздела «Установка»."
                },
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            if (isAdmin) {
                Spacer(Modifier.height(10.dp))
                PanelButton(
                    text = "Установить MTProxyL",
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Settings
                )
            }
        }

        if (isAdmin && status.available && status.capabilities.restart) {
            Spacer(Modifier.height(12.dp))
            PanelOutlinedButton(
                text = "Перезапустить прокси",
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                loading = busy,
                icon = Icons.Filled.Autorenew
            )
        }

        if (status.error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(status.error, style = MaterialTheme.typography.labelSmall, color = PanelRed)
        }
    }
}

@Composable
private fun ProxyUserCard(
    user: ProxyUser,
    canEdit: Boolean,
    caps: ProxyCapabilities,
    onLinks: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onRotate: () -> Unit,
    onResetQuota: () -> Unit,
    onResetTraffic: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember(user.username) { mutableStateOf(false) }
    val accent = when {
        !user.enabled -> PanelMuted
        user.expired -> PanelRed
        user.currentConnections > 0 -> PanelGreen
        else -> PanelAccent
    }
    val used = user.traffic.totalBytes.takeIf { it > 0 } ?: user.totalOctets

    PanelCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accent.copy(alpha = 0.12f),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(9.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        user.username,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = PanelText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        !user.enabled -> Badge("выключен", PanelMuted)
                        user.expired -> Badge("истёк", PanelRed)
                        user.currentConnections > 0 -> Badge("online", PanelGreen)
                    }
                }
                Text(
                    buildString {
                        append("${user.currentConnections} соед.")
                        if (caps.uniqueIps) append(" · ${user.activeUniqueIps} IP")
                        if (caps.traffic) append(" · ${formatBytes(used)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
            if (canEdit) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Действия", tint = PanelMuted)
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = PanelElevated
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ссылки и QR-код") },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            onClick = { menu = false; onLinks() }
                        )
                        if (caps.limits || caps.quota || caps.rate || caps.expiry) {
                            DropdownMenuItem(
                                text = { Text("Изменить лимиты") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menu = false; onEdit() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (user.enabled) "Отключить" else "Включить") },
                            leadingIcon = {
                                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                            },
                            onClick = { menu = false; onToggle() }
                        )
                        DropdownMenuItem(
                            text = { Text("Ротировать секрет") },
                            leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                            onClick = { menu = false; onRotate() }
                        )
                        if (caps.quota) {
                            DropdownMenuItem(
                                text = { Text("Сбросить квоту") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                onClick = { menu = false; onResetQuota() }
                            )
                        }
                        if (caps.traffic) {
                            DropdownMenuItem(
                                text = { Text("Сбросить трафик") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                onClick = { menu = false; onResetTraffic() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Удалить", color = PanelRed) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = PanelRed)
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            } else {
                IconButton(onClick = onLinks) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "Ссылки", tint = PanelMuted)
                }
            }
        }

        if (user.quotaBytes != null && user.quotaBytes > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { user.quotaFraction ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = if ((user.quotaFraction ?: 0f) >= 1f) PanelRed else PanelAccent,
                trackColor = PanelBorder
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(used)} из ${formatBytes(user.quotaBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        }

        val limits = buildList {
            user.maxTcpConns?.let { add("$it TCP") }
            user.maxUniqueIps?.let { add("$it IP") }
            if (user.rateUpBps != null || user.rateDownBps != null) {
                add("↑ ${formatProxyRate(user.rateUpBps)} ↓ ${formatProxyRate(user.rateDownBps)}")
            }
        }
        if (limits.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                limits.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        }

        if (user.expiration.isNotBlank()) {
            Text(
                "до ${user.expiration.take(10)}" + if (user.expired) " · истёк" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (user.expired) PanelRed else PanelMuted
            )
        }
        if (user.notes.isNotBlank()) {
            Text(
                user.notes,
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!user.inRuntime && user.enabled) {
            Text(
                "Секрет ещё не применён движком",
                style = MaterialTheme.typography.labelSmall,
                color = PanelAmber
            )
        }
    }
}

@Composable
private fun ProxyUserDialog(
    user: ProxyUser?,
    caps: ProxyCapabilities,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit
) {
    val editing = user != null
    var username by remember { mutableStateOf(user?.username ?: "") }
    var secret by remember { mutableStateOf("") }
    var maxTcp by remember { mutableStateOf(user?.maxTcpConns?.toString() ?: "") }
    var maxIps by remember { mutableStateOf(user?.maxUniqueIps?.toString() ?: "") }
    var quotaGb by remember {
        mutableStateOf(user?.quotaBytes?.takeIf { it > 0 }?.let { formatQuotaGb(it) } ?: "")
    }
    var rateUp by remember {
        mutableStateOf(user?.rateUpBps?.takeIf { it > 0 }?.let { formatRateMbps(it) } ?: "")
    }
    var rateDown by remember {
        mutableStateOf(user?.rateDownBps?.takeIf { it > 0 }?.let { formatRateMbps(it) } ?: "")
    }
    var adTag by remember { mutableStateOf(user?.adTag ?: "") }
    var notes by remember { mutableStateOf(user?.notes ?: "") }
    var expiry by remember {
        mutableStateOf(if (user?.expiration.isNullOrBlank()) "Бессрочно" else "Не менять")
    }
    var enabled by remember { mutableStateOf(user?.enabled ?: true) }

    val usernameOk = editing || Regex("^[A-Za-z0-9_.-]{1,64}$").matches(username.trim())

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    if (editing) "Лимиты ${user?.username.orEmpty()}" else "Новый пользователь",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    if (editing) "Пустое поле снимает ограничение"
                    else "MTProto-пользователь прокси",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(14.dp))

                if (!editing) {
                    PanelTextField(
                        value = username,
                        onValueChange = { username = it.trim() },
                        label = "Имя пользователя",
                        supportingText = "Буквы, цифры, . _ - (до 64 символов)"
                    )
                    Spacer(Modifier.height(10.dp))
                    PanelTextField(
                        value = secret,
                        onValueChange = { secret = it.trim() },
                        label = "Секрет (необязательно)",
                        supportingText = "32 hex-символа; пусто — сгенерировать"
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.connections) {
                    PanelTextField(
                        value = maxTcp,
                        onValueChange = { maxTcp = it.filter(Char::isDigit).take(6) },
                        label = "Максимум TCP-соединений",
                        placeholder = "без ограничения",
                        keyboardType = KeyboardType.Number
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.uniqueIps) {
                    PanelTextField(
                        value = maxIps,
                        onValueChange = { maxIps = it.filter(Char::isDigit).take(5) },
                        label = "Максимум уникальных IP",
                        placeholder = "без ограничения",
                        keyboardType = KeyboardType.Number
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.quota) {
                    PanelTextField(
                        value = quotaGb,
                        onValueChange = { quotaGb = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                        label = "Квота, ГБ",
                        placeholder = "без ограничения",
                        keyboardType = KeyboardType.Decimal
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.rate) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PanelTextField(
                            value = rateUp,
                            onValueChange = { rateUp = it.filter(Char::isDigit) },
                            label = "Отдача, Мбит/с",
                            placeholder = "0",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                        PanelTextField(
                            value = rateDown,
                            onValueChange = { rateDown = it.filter(Char::isDigit) },
                            label = "Приём, Мбит/с",
                            placeholder = "0",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.expiry) {
                    PanelDropdown(
                        label = "Срок действия",
                        options = listOf("Не менять", "Бессрочно", "1 день", "7 дней", "30 дней", "90 дней"),
                        selected = expiry,
                        onSelect = { expiry = it }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.adTag) {
                    PanelTextField(
                        value = adTag,
                        onValueChange = { adTag = it.trim() },
                        label = "ad_tag (необязательно)",
                        supportingText = "32 hex-символа"
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (caps.notes) {
                    PanelTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Заметка",
                        singleLine = false,
                        minLines = 2
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (editing) {
                    ToggleRow(
                        title = "Пользователь включён",
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelOutlinedButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = if (editing) "Сохранить" else "Создать",
                        onClick = {
                            val body = JSONObject()
                            if (editing) {
                                body.put("enabled", enabled)
                                body.put("max_tcp_conns", maxTcp.toLongOrNull() ?: JSONObject.NULL)
                                body.put("max_unique_ips", maxIps.toLongOrNull() ?: JSONObject.NULL)
                                body.put("data_quota_bytes", quotaGbBytes(quotaGb) ?: JSONObject.NULL)
                                body.put("rate_limit_up_bps", rateMbpsBps(rateUp) ?: JSONObject.NULL)
                                body.put("rate_limit_down_bps", rateMbpsBps(rateDown) ?: JSONObject.NULL)
                                when (expiry) {
                                    "Не менять" -> Unit
                                    "Бессрочно" -> body.put("expiration_rfc3339", JSONObject.NULL)
                                    else -> expiryValue(expiry)?.let { body.put("expiration_rfc3339", it) }
                                }
                                body.put("user_ad_tag", if (adTag.isBlank()) JSONObject.NULL else adTag)
                                body.put("notes", notes.trim())
                            } else {
                                body.put("username", username.trim())
                                if (secret.isNotBlank()) body.put("secret", secret)
                                maxTcp.toLongOrNull()?.let { body.put("max_tcp_conns", it) }
                                maxIps.toLongOrNull()?.let { body.put("max_unique_ips", it) }
                                quotaGbBytes(quotaGb)?.let { body.put("data_quota_bytes", it) }
                                rateMbpsBps(rateUp)?.let { body.put("rate_limit_up_bps", it) }
                                rateMbpsBps(rateDown)?.let { body.put("rate_limit_down_bps", it) }
                                expiryValue(expiry)?.let { body.put("expiration_rfc3339", it) }
                                if (adTag.isNotBlank()) body.put("user_ad_tag", adTag)
                                if (notes.isNotBlank()) body.put("notes", notes.trim())
                            }
                            onSave(body)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = usernameOk
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyLinksDialog(
    username: String,
    data: ProxyUserLinks?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(data?.qr) { decodeDataUrl(data?.qr) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Ссылки $username",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "tg:// для Telegram",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(14.dp))

                when {
                    loading -> LoadingState("Получаем ссылки и QR-код…")

                    error != null -> {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = PanelRed)
                        Spacer(Modifier.height(10.dp))
                        PanelOutlinedButton(
                            text = "Повторить",
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    data == null || data.links.isEmpty() -> Text(
                        "Движок не вернул ссылок. Проверьте, что пользователь включён и прокси запущен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )

                    else -> {
                        if (bitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "QR-код пользователя $username",
                                    modifier = Modifier.size(220.dp)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        data.links.forEach { link ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PanelSurface,
                                border = BorderStroke(1.dp, PanelBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        link,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PanelText,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        copyToClipboard(context, link, "Ссылка скопирована")
                                    }) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = "Копировать",
                                            tint = PanelMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { openUrl(context, link) }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Открыть",
                                            tint = PanelAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                PanelButton(
                    text = "Готово",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun MtProtoLinksDialog(
    links: List<String>?,
    loading: Boolean,
    error: String?,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Ссылки MTProto",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "/root/mtproto_links.txt",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(14.dp))

                when {
                    loading -> LoadingState("Читаем ссылки установщика…")

                    error != null -> {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = PanelRed)
                        Spacer(Modifier.height(10.dp))
                        PanelOutlinedButton(
                            text = "Повторить",
                            onClick = onReload,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    links == null || links.isEmpty() -> Text(
                        "Ссылок пока нет. Они появляются после установки MTProto через панель " +
                            "или MEKO и сохраняются в /root/mtproto_links.txt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )

                    else -> links.forEach { link ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PanelSurface,
                            border = BorderStroke(1.dp, PanelBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    link,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelText,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    copyToClipboard(context, link, "Ссылка скопирована")
                                }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Копировать",
                                        tint = PanelMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { openUrl(context, link) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Открыть",
                                        tint = PanelAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelOutlinedButton(
                        text = "Обновить",
                        onClick = onReload,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Refresh
                    )
                    PanelButton(
                        text = "Готово",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyBotDialog(
    initial: ProxyBotStatus?,
    onDismiss: () -> Unit,
    onSave: (Boolean, String?, List<String>) -> Unit,
    onTest: () -> Unit
) {
    var enabled by remember { mutableStateOf(initial?.enabled ?: false) }
    var token by remember { mutableStateOf("") }
    var admins by remember { mutableStateOf(initial?.admins?.joinToString(", ").orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Telegram-бот",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "Long polling внутри панели: /status, /users, /add, /del, /link и другие",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(12.dp))

                ToggleRow(
                    title = "Бот включён",
                    subtitle = "Отвечает только ID из списка администраторов",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )

                Spacer(Modifier.height(6.dp))

                PanelTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = "Токен бота",
                    placeholder = if (initial?.tokenSet == true) "сохранён: ${initial.token}" else "123456:ABC…",
                    supportingText = "Пусто — оставить текущий токен"
                )

                Spacer(Modifier.height(10.dp))

                PanelTextField(
                    value = admins,
                    onValueChange = { admins = it },
                    label = "ID администраторов",
                    placeholder = "123456789, 987654321",
                    supportingText = "Через запятую или пробел"
                )

                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelOutlinedButton(
                        text = "Тест",
                        onClick = onTest,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = "Сохранить",
                        onClick = {
                            val ids = admins.split(Regex("[\\s,;]+"))
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            onSave(enabled, token.ifBlank { null }, ids)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Закрыть", color = PanelMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Форматирование и разбор лимитов
// ---------------------------------------------------------------------------

private fun formatProxyRate(bps: Long?): String {
    if (bps == null || bps <= 0L) return "—"
    return formatSpeed(bps / 8)
}

private fun formatQuotaGb(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 10) String.format("%.0f", gb) else String.format("%.1f", gb).replace('.', ',')
}

private fun formatRateMbps(bps: Long): String =
    (bps / 1_000_000.0).let { if (it >= 10) String.format("%.0f", it) else String.format("%.1f", it) }

private fun quotaGbBytes(text: String): Long? {
    val gb = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (gb <= 0) return null
    return (gb * 1024.0 * 1024.0 * 1024.0).toLong()
}

private fun rateMbpsBps(text: String): Long? {
    val mbps = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (mbps <= 0) return null
    return (mbps * 1_000_000.0).toLong()
}

private fun expiryValue(choice: String): String? {
    val days = when (choice) {
        "1 день" -> 1L
        "7 дней" -> 7L
        "30 дней" -> 30L
        "90 дней" -> 90L
        else -> return null
    }
    return OffsetDateTime.now().plusDays(days).withNano(0).toString()
}
