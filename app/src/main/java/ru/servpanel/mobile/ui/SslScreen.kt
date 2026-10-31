package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.SslRenewState
import ru.servpanel.mobile.data.SslStatus
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

@Composable
fun SslScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var data by remember(server.id) { mutableStateOf<SslStatus?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteSite by remember { mutableStateOf<String?>(null) }
    var renewCert by remember { mutableStateOf<String?>(null) }
    var renewing by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (data == null) loading = true
        error = null
        try {
            data = vm.call(server) { sslStatus() }
        } catch (e: Exception) {
            if (data == null) error = e.message ?: "Не удалось загрузить состояние SSL"
        } finally {
            loading = false
        }
    }

    if (loading && data == null) {
        LoadingState("Проверяем сертификаты и Nginx…")
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
                title = "Домены и SSL",
                subtitle = "Let's Encrypt, Nginx и сертификаты",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Панель",
                    subtitle = current.panelUrl.ifBlank { current.panelDomain },
                    trailing = {
                        Badge(
                            if (current.panelScheme == "https") "HTTPS" else "HTTP",
                            if (current.panelScheme == "https") PanelGreen else PanelAmber
                        )
                    }
                )
                Spacer(Modifier.height(10.dp))
                InfoRow("Домен", current.panelDomain)
                InfoRow("Схема", current.panelScheme)
                InfoRow("Внутренний порт", current.panelInternalPort)
                InfoRow("Публичный порт", current.panelPublicPort)
                InfoRow("HTTPS-порт", current.panelHttpsPort)
                if (current.panelUrl.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = "Скопировать URL",
                        onClick = { copyToClipboard(context, current.panelUrl, "URL скопирован") },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.ContentCopy
                    )
                }
            }
        }

        if (current.warnings.isNotEmpty()) {
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = PanelAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Предупреждения",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = PanelAmber
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    current.warnings.forEach { warning ->
                        Text(
                            "• $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelMuted,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        item {
            PanelCard {
                SectionHeader("Инструменты", "Nginx, certbot и openssl")
                Spacer(Modifier.height(10.dp))
                ToolRow("Nginx", current.nginx.installed, current.nginx.version, current.nginx.running)
                ToolRow("Certbot", current.certbot.installed, current.certbot.version, false)
                ToolRow("OpenSSL", current.opensslInstalled, "", false)
                Spacer(Modifier.height(10.dp))
                InfoRow(
                    "Таймер продления",
                    if (current.timerActive) "Активен" else if (current.timerEnabled) "Включён" else "Выключен",
                    valueColor = if (current.timerActive) PanelGreen else PanelMuted
                )
                InfoRow("Email Let's Encrypt", current.email)

                if (auth.isAdmin && (!current.nginx.installed || !current.certbot.installed)) {
                    Spacer(Modifier.height(12.dp))
                    PanelButton(
                        text = "Установить nginx и certbot",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    vm.call(server) { sslInstallDeps() }
                                    messageOk = true
                                    message = "Пакеты установлены"
                                    reload++
                                } catch (e: Exception) {
                                    messageOk = false
                                    message = e.message ?: "Не удалось установить пакеты"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        loading = busy
                    )
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Домены",
                    subtitle = pluralRu(current.sites.size, "сайт", "сайта", "сайтов"),
                    trailing = {
                        if (auth.isAdmin) {
                            IconButton(onClick = { showCreate = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Добавить домен", tint = PanelAccent)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                if (current.sites.isEmpty()) {
                    Text(
                        "Домены не настроены. Привяжите домен к панели или создайте сайт-заглушку.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                } else {
                    current.sites.forEach { site ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Language, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    site.domains.joinToString(", ").ifBlank { site.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelText
                                )
                                Text(
                                    buildString {
                                        append(if (site.mode == "panel") "панель" else "сайт")
                                        append(" · ")
                                        append(if (site.ssl) "SSL" else "без SSL")
                                        if (!site.enabled) append(" · выключен")
                                        site.httpsPort?.let { append(" · :$it") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                            if (auth.isAdmin) {
                                IconButton(onClick = { deleteSite = site.name }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Удалить",
                                        tint = PanelMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    "Сертификаты",
                    pluralRu(current.certificates.size, "сертификат", "сертификата", "сертификатов")
                )
                Spacer(Modifier.height(8.dp))
                if (current.certificates.isEmpty()) {
                    Text(
                        "Сертификатов нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                } else {
                    current.certificates.forEach { cert ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = PanelGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    cert.domains.joinToString(", ").ifBlank { cert.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelText,
                                    modifier = Modifier.weight(1f)
                                )
                                val days = cert.daysLeft
                                Badge(
                                    if (days == null) "—" else "$days дн.",
                                    when {
                                        days == null -> PanelMuted
                                        days < 14 -> PanelRed
                                        days < 30 -> PanelAmber
                                        else -> PanelGreen
                                    }
                                )
                            }
                            Text(
                                "до ${cert.validTo.ifBlank { "—" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted,
                                modifier = Modifier.padding(start = 24.dp)
                            )
                            if (auth.isAdmin) {
                                TextButton(onClick = { renewCert = cert.name }) {
                                    Text("Продлить", color = PanelAccent)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (current.paths.isNotEmpty()) {
            item {
                PanelCard {
                    SectionHeader("Пути", "Файлы сертификатов и конфигов")
                    Spacer(Modifier.height(8.dp))
                    current.paths.forEach { (key, value) ->
                        InfoRow(key, value, mono = true)
                    }
                }
            }
        }

        message?.let { text ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (messageOk) PanelGreen.copy(alpha = 0.12f) else PanelRed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, (if (messageOk) PanelGreen else PanelRed).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageOk) PanelGreen else PanelRed,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    if (showCreate) {
        SslSiteDialog(
            defaultEmail = current.email,
            onDismiss = { showCreate = false },
            onCreate = { domain, mode, www, issue, email, useForVpn ->
                busy = true
                message = null
                scope.launch {
                    try {
                        val result = vm.call(server) {
                            sslCreateSite(domain, mode, www, issue, email, useForVpn)
                        }
                        messageOk = true
                        message = buildString {
                            append("Домен ${result.domain} настроен")
                            if (result.url.isNotBlank()) append(": ${result.url}")
                            if (result.warnings.isNotEmpty()) append("\n${result.warnings.joinToString("; ")}")
                        }
                        showCreate = false
                        reload++
                    } catch (e: Exception) {
                        messageOk = false
                        message = e.message ?: "Не удалось настроить домен"
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }

    deleteSite?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteSite = null },
            containerColor = PanelElevated,
            title = { Text("Удалить сайт?") },
            text = { Text("Конфиг Nginx для «$name» будет удалён. Сертификат останется в /etc/letsencrypt.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            vm.call(server) { sslDeleteSite(name) }
                            messageOk = true
                            message = "Сайт удалён"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось удалить сайт"
                        }
                        deleteSite = null
                    }
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSite = null }) { Text("Отмена") }
            }
        )
    }

    renewCert?.let { name ->
        AlertDialog(
            onDismissRequest = { renewCert = null },
            containerColor = PanelElevated,
            title = { Text("Продлить сертификат?") },
            text = { Text("Certbot попробует продлить сертификат «$name». Это может занять до минуты.") },
            confirmButton = {
                TextButton(onClick = {
                    renewCert = null
                    renewing = true
                    message = "Продлеваем сертификат «$name»…"
                    messageOk = true
                    scope.launch {
                        try {
                            // Панель 3.0.50+ запускает certbot в фоне: запрос
                            // возвращается сразу, результат читаем отдельно.
                            var state: SslRenewState = vm.call(server) { sslRenew(name) }
                            val deadline = System.currentTimeMillis() + 6 * 60 * 1000
                            while (state.running && System.currentTimeMillis() < deadline) {
                                delay(2500)
                                state = runCatching {
                                    vm.call(server) { sslRenewStatus(name) }
                                }.getOrNull() ?: break
                                if (state.none) break
                            }
                            messageOk = state.error.isBlank() && (state.ok || state.notDue)
                            message = when {
                                state.error.isNotBlank() -> state.error
                                state.notDue -> "Продление не требуется: сертификат ещё действует"
                                state.ok -> "Сертификат продлён"
                                else -> "Продление запущено на сервере — обновите список позже"
                            }
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось продлить сертификат"
                        } finally {
                            renewing = false
                        }
                    }
                }, enabled = !renewing) { Text("Продлить") }
            },
            dismissButton = {
                TextButton(onClick = { renewCert = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ToolRow(name: String, installed: Boolean, version: String, running: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(if (installed) PanelGreen else PanelRed, 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = PanelText,
            modifier = Modifier.width(80.dp)
        )
        Text(
            when {
                !installed -> "не установлен"
                running -> "работает"
                else -> "установлен"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (installed) PanelMuted else PanelRed,
            modifier = Modifier.weight(1f)
        )
        if (version.isNotBlank()) {
            Text(
                version.take(28),
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        }
    }
}

@Composable
private fun SslSiteDialog(
    defaultEmail: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Boolean, String, Boolean) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("panel") }
    var www by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf(defaultEmail) }
    var useForVpn by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Новый домен",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "A-запись домена должна указывать на этот сервер.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(14.dp))

                PanelTextField(
                    value = domain,
                    onValueChange = { domain = it.trim() },
                    label = "Домен",
                    placeholder = "panel.example.com"
                )

                Spacer(Modifier.height(10.dp))

                PanelDropdown(
                    label = "Режим",
                    options = listOf("Домен панели", "Отдельный сайт"),
                    selected = if (mode == "panel") "Домен панели" else "Отдельный сайт",
                    onSelect = { mode = if (it == "Домен панели") "panel" else "site" }
                )

                Spacer(Modifier.height(4.dp))

                ToggleRow(
                    title = "Добавить www",
                    subtitle = "Выпустить сертификат и на www-поддомен",
                    checked = www,
                    onCheckedChange = { www = it }
                )

                ToggleRow(
                    title = "Выпустить сертификат",
                    subtitle = "Let's Encrypt через certbot",
                    checked = issue,
                    onCheckedChange = { issue = it }
                )

                if (mode == "panel") {
                    ToggleRow(
                        title = "Использовать для VPN",
                        subtitle = "Клиентские конфиги будут ссылаться на домен",
                        checked = useForVpn,
                        onCheckedChange = { useForVpn = it }
                    )
                }

                Spacer(Modifier.height(10.dp))

                PanelTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = "Email для Let's Encrypt",
                    placeholder = "admin@example.com",
                    enabled = issue
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = "Настроить",
                        onClick = { onCreate(domain, mode, www, issue, email, useForVpn) },
                        modifier = Modifier.weight(1f),
                        enabled = domain.contains(".") && (!issue || email.contains("@"))
                    )
                }
            }
        }
    }
}
