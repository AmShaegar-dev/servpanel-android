package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.AuditEntry
import ru.servpanel.mobile.data.Fail2banStatus
import ru.servpanel.mobile.data.NotifyStatus
import ru.servpanel.mobile.data.SecurityStatus
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.TwoFaSetup
import ru.servpanel.mobile.data.TwoFaStatus
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

private data class SecurityData(
    val status: SecurityStatus,
    val twoFa: TwoFaStatus?,
    val fail2ban: Fail2banStatus?,
    val notify: NotifyStatus?,
    val audit: List<AuditEntry>
)

@Composable
fun SecurityScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var data by remember(server.id) { mutableStateOf<SecurityData?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (data == null) loading = true
        error = null
        try {
            data = coroutineScope {
                val status = async { vm.call(server) { securityStatus() } }
                val twoFa = async { runCatching { vm.call(server) { twoFaStatus() } }.getOrNull() }
                val fail2ban = async { runCatching { vm.call(server) { fail2ban() } }.getOrNull() }
                val notify = async { runCatching { vm.call(server) { notifyStatus() } }.getOrNull() }
                val audit = async {
                    runCatching { vm.call(server) { audit(150) } }.getOrDefault(emptyList())
                }
                SecurityData(
                    status = status.await(),
                    twoFa = twoFa.await(),
                    fail2ban = fail2ban.await(),
                    notify = notify.await(),
                    audit = audit.await()
                )
            }
        } catch (e: Exception) {
            if (data == null) error = e.message ?: "Не удалось загрузить состояние защиты"
        } finally {
            loading = false
        }
    }

    if (loading && data == null) {
        LoadingState("Проверяем состояние защиты…")
        return
    }

    val current = data
    if (current == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { reload++ })
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "Защита",
                subtitle = "Доступ, 2FA и журнал действий",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
        }

        item { StatusCard(current.status) }

        item {
            ChangePasswordCard(
                isAdmin = auth.isAdmin,
                onSave = { password, callback ->
                    scope.launch {
                        val result = runCatching { vm.call(server) { changePassword(password) } }
                        callback(result.isSuccess, result.exceptionOrNull()?.message)
                    }
                }
            )
        }

        item {
            TwoFactorCard(
                status = current.twoFa,
                isAdmin = auth.isAdmin,
                onSetup = { callback ->
                    scope.launch {
                        val result = runCatching { vm.call(server) { twoFaSetup() } }
                        callback(result.getOrNull(), result.exceptionOrNull()?.message)
                    }
                },
                onEnable = { code, callback ->
                    scope.launch {
                        val result = runCatching { vm.call(server) { twoFaEnable(code) } }
                        callback(result.getOrNull(), result.exceptionOrNull()?.message)
                    }
                },
                onDisable = { password, callback ->
                    scope.launch {
                        val result = runCatching { vm.call(server) { twoFaDisable(password) } }
                        callback(result.isSuccess, result.exceptionOrNull()?.message)
                    }
                },
                onRefresh = { reload++ }
            )
        }

        item {
            Fail2banCard(
                status = current.fail2ban,
                isAdmin = auth.isAdmin,
                onUnban = { ip ->
                    scope.launch {
                        runCatching { vm.call(server) { fail2banUnban(ip) } }
                        reload++
                    }
                }
            )
        }

        if (auth.isAdmin) {
            item {
                NotifyCard(
                    status = current.notify,
                    onSave = { enabled, token, chatId, callback ->
                        scope.launch {
                            val result = runCatching {
                                vm.call(server) { saveNotify(enabled, token, chatId) }
                            }
                            callback(result.isSuccess, result.exceptionOrNull()?.message)
                            reload++
                        }
                    },
                    onTest = { callback ->
                        scope.launch {
                            val result = runCatching { vm.call(server) { testNotify() } }
                            callback(result.isSuccess, result.exceptionOrNull()?.message)
                        }
                    }
                )
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Журнал действий",
                    subtitle = "Последние ${current.audit.size} записей"
                )
                Spacer(Modifier.height(8.dp))
                if (current.audit.isEmpty()) {
                    Text(
                        "Журнал пуст или недоступен для вашей роли.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                } else {
                    current.audit.take(60).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.action,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelText,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${entry.time} · ${entry.ip} · ${entry.user}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = PanelMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Сессия хранится только на устройстве. Пароль в приложении не сохраняется.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }
    }

    if (current.status.defaultPassword) {
        LaunchedEffect(current.status.defaultPassword) {
            if (current.status.defaultPassword) {
                // Предупреждение уже видно в карточке статуса.
            }
        }
    }
}

@Composable
private fun StatusCard(status: SecurityStatus) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(PanelAccent.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Состояние панели",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "${status.user} · ${if (status.isAdmin) "администратор" else "только просмотр"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
            Badge(
                if (status.https) "HTTPS" else "HTTP",
                if (status.https) PanelGreen else PanelAmber
            )
        }

        Spacer(Modifier.height(14.dp))

        InfoRow(
            "HTTPS",
            if (status.https) "Включён" else "Не настроен",
            valueColor = if (status.https) PanelGreen else PanelAmber
        )
        InfoRow(
            "2FA",
            if (status.totpEnabled) "Включена" else "Выключена",
            valueColor = if (status.totpEnabled) PanelGreen else PanelMuted
        )
        InfoRow("Node.js", status.node)
        InfoRow("Порт панели", status.panelPublicPort.ifBlank { status.panelPort })
        InfoRow("Пользователей", status.users.toString())
        InfoRow("Каталог данных", status.dataDir)

        if (status.defaultPassword) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelAmber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, PanelAmber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = PanelAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Используется стандартный пароль администратора. Смените его ниже.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangePasswordCard(
    isAdmin: Boolean,
    onSave: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    PanelCard {
        SectionHeader("Смена пароля", "Минимум 8 символов")

        Spacer(Modifier.height(10.dp))

        PanelTextField(
            value = password,
            onValueChange = { password = it },
            label = "Новый пароль",
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy
        )

        Spacer(Modifier.height(10.dp))

        PanelTextField(
            value = repeat,
            onValueChange = { repeat = it },
            label = "Повторите пароль",
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            isError = repeat.isNotBlank() && repeat != password,
            supportingText = if (repeat.isNotBlank() && repeat != password) "Пароли не совпадают" else null,
            enabled = !busy
        )

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (success) PanelGreen else PanelRed
            )
        }

        Spacer(Modifier.height(12.dp))

        PanelButton(
            text = "Сменить пароль",
            onClick = {
                busy = true
                message = null
                onSave(password) { ok, error ->
                    busy = false
                    success = ok
                    message = if (ok) "Пароль изменён" else (error ?: "Не удалось сменить пароль")
                    if (ok) {
                        password = ""
                        repeat = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
            enabled = password.length >= 8 && password == repeat
        )
    }
}

@Composable
private fun TwoFactorCard(
    status: TwoFaStatus?,
    isAdmin: Boolean,
    onSetup: ((TwoFaSetup?, String?) -> Unit) -> Unit,
    onEnable: (String, (List<String>?, String?) -> Unit) -> Unit,
    onDisable: (String, (Boolean, String?) -> Unit) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf<TwoFaSetup?>(null) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<List<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDisable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    PanelCard {
        SectionHeader(
            title = "Двухфакторная аутентификация",
            subtitle = "TOTP: Google Authenticator, Aegis, 1Password",
            trailing = {
                Badge(
                    if (status?.enabled == true) "Включена" else "Выключена",
                    if (status?.enabled == true) PanelGreen else PanelMuted
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        if (status?.enabled != true && setup == null) {
            Text(
                "Включите 2FA, чтобы вход требовал одноразовый код. " +
                    "Приложение покажет QR-код и резервные коды.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            Spacer(Modifier.height(12.dp))
            PanelButton(
                text = "Настроить 2FA",
                onClick = {
                    busy = true
                    message = null
                    onSetup { result, error ->
                        busy = false
                        setup = result
                        if (result == null) message = error ?: "Не удалось начать настройку"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                loading = busy
            )
        }

        if (status?.enabled == true) {
            Text(
                "Вход в панель защищён одноразовыми кодами.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelGreen
            )
            Spacer(Modifier.height(12.dp))
            PanelOutlinedButton(
                text = "Отключить 2FA",
                onClick = { showDisable = true },
                modifier = Modifier.fillMaxWidth(),
                color = PanelRed
            )
        }

        setup?.let { current ->
            Spacer(Modifier.height(14.dp))
            Text(
                "1. Отсканируйте QR-код в приложении-аутентификаторе",
                style = MaterialTheme.typography.bodySmall,
                color = PanelText
            )
            Spacer(Modifier.height(10.dp))

            val bitmap = remember(current.qr) { decodeDataUrl(current.qr) }
            if (bitmap != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR-код 2FA",
                        modifier = Modifier.size(200.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelSurface,
                border = BorderStroke(1.dp, PanelBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Секрет", style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                    Text(
                        current.secret,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            PanelOutlinedButton(
                text = "Скопировать секрет",
                onClick = { copyToClipboard(context, current.secret, "Секрет скопирован") },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.ContentCopy
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "2. Введите шестизначный код из приложения",
                style = MaterialTheme.typography.bodySmall,
                color = PanelText
            )
            Spacer(Modifier.height(8.dp))

            PanelTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = "Код",
                keyboardType = KeyboardType.NumberPassword,
                enabled = !busy
            )

            Spacer(Modifier.height(10.dp))

            PanelButton(
                text = "Включить 2FA",
                onClick = {
                    busy = true
                    message = null
                    onEnable(code) { codes, error ->
                        busy = false
                        if (codes != null) {
                            recovery = codes
                            setup = null
                            code = ""
                            onRefresh()
                        } else {
                            message = error ?: "Неверный код"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
                enabled = code.length == 6
            )
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = PanelRed)
        }

        recovery?.let { codes ->
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelGreen.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, PanelGreen.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "2FA включена. Сохраните резервные коды:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelGreen
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        codes.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText
                    )
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = "Скопировать коды",
                        onClick = { copyToClipboard(context, codes.joinToString("\n"), "Коды скопированы") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showDisable) {
        AlertDialog(
            onDismissRequest = { showDisable = false },
            containerColor = PanelElevated,
            title = { Text("Отключить 2FA?") },
            text = {
                Column {
                    Text(
                        "Подтвердите паролем текущего пользователя.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    PanelTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Пароль",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        busy = true
                        onDisable(password) { ok, error ->
                            busy = false
                            if (ok) {
                                showDisable = false
                                password = ""
                                message = null
                                onRefresh()
                            } else {
                                message = error ?: "Не удалось отключить 2FA"
                            }
                        }
                    },
                    enabled = password.isNotBlank() && !busy
                ) { Text("Отключить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDisable = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun Fail2banCard(
    status: Fail2banStatus?,
    isAdmin: Boolean,
    onUnban: (String) -> Unit
) {
    PanelCard {
        SectionHeader(
            title = "Fail2ban",
            subtitle = "Защита входа от перебора паролей",
            trailing = {
                Badge(
                    when {
                        status?.installed != true -> "Не установлен"
                        status.jail -> "Активен"
                        else -> "Нет jail"
                    },
                    when {
                        status?.installed != true -> PanelMuted
                        status.jail -> PanelGreen
                        else -> PanelAmber
                    }
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        InfoRow("Установлен", if (status?.installed == true) "Да" else "Нет")
        InfoRow("Jail servpanel", if (status?.jail == true) "Активен" else "Нет")
        InfoRow("Сейчас неудачных попыток", status?.currentlyFailed?.toString().orEmpty())
        InfoRow("Всего неудачных", status?.totalFailed?.toString().orEmpty())

        Spacer(Modifier.height(10.dp))

        val banned = status?.banned.orEmpty()
        if (banned.isEmpty()) {
            Text(
                "Заблокированных адресов нет.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
        } else {
            Text(
                "Заблокированы:",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
            Spacer(Modifier.height(6.dp))
            banned.forEach { ip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Block, contentDescription = null, tint = PanelRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ip,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText,
                        modifier = Modifier.weight(1f)
                    )
                    if (isAdmin) {
                        TextButton(onClick = { onUnban(ip) }) {
                            Text("Разбанить", color = PanelAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifyCard(
    status: NotifyStatus?,
    onSave: (Boolean, String?, String?, (Boolean, String?) -> Unit) -> Unit,
    onTest: ((Boolean, String?) -> Unit) -> Unit
) {
    var enabled by remember(status?.enabled) { mutableStateOf(status?.enabled ?: false) }
    var token by remember { mutableStateOf("") }
    var chatId by remember(status?.chatId) { mutableStateOf(status?.chatId.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    PanelCard {
        SectionHeader(
            title = "Telegram-уведомления",
            subtitle = "Алерты о сбоях каскада и панели",
            trailing = {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = PanelMuted)
            }
        )

        Spacer(Modifier.height(10.dp))

        ToggleRow(
            title = "Включить уведомления",
            subtitle = if (status?.hasToken == true) "Токен уже сохранён" else "Нужны токен бота и chat_id",
            checked = enabled,
            onCheckedChange = { enabled = it }
        )

        Spacer(Modifier.height(8.dp))

        PanelTextField(
            value = token,
            onValueChange = { token = it },
            label = "Токен бота",
            placeholder = if (status?.hasToken == true) "оставьте пустым, чтобы не менять" else "123456:ABC…",
            enabled = !busy
        )

        Spacer(Modifier.height(10.dp))

        PanelTextField(
            value = chatId,
            onValueChange = { chatId = it },
            label = "chat_id",
            placeholder = "123456789 или @channel",
            enabled = !busy
        )

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (success) PanelGreen else PanelRed
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                text = "Сохранить",
                onClick = {
                    busy = true
                    message = null
                    onSave(
                        enabled,
                        token.ifBlank { null },
                        chatId.ifBlank { null }
                    ) { ok, error ->
                        busy = false
                        success = ok
                        message = if (ok) "Настройки сохранены" else (error ?: "Не удалось сохранить")
                        if (ok) token = ""
                    }
                },
                modifier = Modifier.weight(1f),
                loading = busy
            )
            PanelOutlinedButton(
                text = "Тест",
                onClick = {
                    testing = true
                    message = null
                    onTest { ok, error ->
                        testing = false
                        success = ok
                        message = if (ok) "Тестовое сообщение отправлено" else (error ?: "Telegram не ответил")
                    }
                },
                modifier = Modifier.weight(1f),
                loading = testing,
                enabled = status?.hasToken == true || token.isNotBlank()
            )
        }
    }
}
