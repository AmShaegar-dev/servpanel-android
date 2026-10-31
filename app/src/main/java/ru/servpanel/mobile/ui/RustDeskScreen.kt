package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.RustDeskClient
import ru.servpanel.mobile.data.RustDeskJob
import ru.servpanel.mobile.data.RustDeskStatus
import ru.servpanel.mobile.data.ServerProfile
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
 * RustDesk: собственный ID/relay-сервер.
 *
 * Панель ставит официальные `hbbs`/`hbbr`, открывает порты и хранит ключ.
 * Приложение показывает состояние, чек-лист, настройки и раздаёт установщики
 * клиентов — всё через API панели 3.0.50+, без SSH и ручных команд.
 */
@Composable
fun RustDeskScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var status by remember(server.id) { mutableStateOf<RustDeskStatus?>(null) }
    var job by remember(server.id) { mutableStateOf<RustDeskJob?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var downloadingClient by remember { mutableStateOf<String?>(null) }
    var instructions by remember { mutableStateOf<String?>(null) }
    var instructionsLoading by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (status == null) loading = true
        error = null
        try {
            val fresh = vm.call(server) { rustdeskStatus(refresh = reload > 0) }
            status = fresh
            if (job == null) {
                job = runCatching { vm.call(server) { rustdeskJob() } }.getOrNull()
            }
        } catch (e: Exception) {
            if (status == null) error = e.message ?: "Не удалось прочитать состояние RustDesk"
        } finally {
            loading = false
        }
    }

    // Пока задание выполняется, панель пишет журнал — опрашиваем его.
    LaunchedEffect(server.id, job?.running, job?.startedAt) {
        if (job?.running != true) return@LaunchedEffect
        while (isActive) {
            delay(2000)
            val next = runCatching { vm.call(server) { rustdeskJob() } }.getOrNull() ?: break
            job = next
            if (next.running != true) {
                messageOk = next.ok
                message = when {
                    next.ok && next.action == "uninstall" -> "RustDesk удалён с сервера"
                    next.ok && next.toVersion.isNotBlank() -> "Готово: версия ${next.toVersion}"
                    next.ok -> "Готово"
                    next.error.isNotBlank() -> next.error
                    else -> "Операция завершилась с ошибкой"
                }
                reload++
                break
            }
        }
    }

    fun runAction(action: String) {
        busy = true
        message = null
        scope.launch {
            try {
                val started = vm.call(server) { rustdeskAction(action) }
                job = started
                if (started == null) {
                    messageOk = true
                    message = "Панель не вернула задание — проверьте состояние через обновление"
                    reload++
                } else if (!started.running) {
                    messageOk = started.ok
                    message = if (started.ok) "Готово" else started.error
                    reload++
                }
            } catch (e: Exception) {
                messageOk = false
                message = e.message ?: "Не удалось запустить операцию"
            } finally {
                busy = false
            }
        }
    }

    if (loading && status == null) {
        LoadingState("Читаем состояние RustDesk…")
        return
    }

    val current = status
    if (current == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { reload++ })
        }
        return
    }

    val running = job?.running == true
    val canEdit = auth.isAdmin
    val badgeColor = when {
        current.running -> PanelGreen
        current.installed -> PanelAmber
        else -> PanelMuted
    }
    val badgeText = when {
        current.running -> "работает"
        current.installed -> "остановлен"
        else -> "не установлен"
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
                title = "RustDesk",
                subtitle = "Свой ID/relay-сервер",
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
                    title = "Сервер",
                    subtitle = if (current.installed) "Версия ${current.version}" else "Не установлен",
                    trailing = { Badge(badgeText, badgeColor) }
                )
                Spacer(Modifier.height(10.dp))
                InfoRow("Версия сервера", current.version.ifBlank { "не установлен" })
                InfoRow("Последняя версия", current.latestVersion.ifBlank { "—" })
                InfoRow(
                    "Службы",
                    "hbbs: ${current.services["hbbs"]?.state ?: "—"} · " +
                        "hbbr: ${current.services["hbbr"]?.state ?: "—"}"
                )
                InfoRow("Адрес", current.host.ifBlank { "—" })
                InfoRow("Relay", current.relay.ifBlank { "—" })
                InfoRow(
                    "Порты",
                    current.openPorts.joinToString(", ") { it.caption }.ifBlank { "не открыты" }
                )
                if (current.cacheBytes > 0L) {
                    InfoRow("Кэш клиентов", formatBytes(current.cacheBytes))
                }
                if (current.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        current.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelAmber
                    )
                }
                if (current.updateAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PanelAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PanelAccent.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Доступна версия ${current.latestVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelText,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        job?.let { task ->
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task.running) {
                            CircularProgressIndicator(
                                color = PanelAccent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                if (task.ok) Icons.Filled.Check else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (task.ok) PanelGreen else PanelRed,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = PanelText
                            )
                            Text(
                                when {
                                    task.running -> "Выполняется…"
                                    task.ok -> "Готово"
                                    else -> task.error.ifBlank { "Ошибка" }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!task.running && !task.ok) PanelRed else PanelMuted
                            )
                        }
                    }

                    if (task.log.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PanelSurface,
                            border = BorderStroke(1.dp, PanelBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    task.log.takeLast(160).joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        if (current.host.isNotBlank() || current.publicKey.isNotBlank()) {
            item {
                PanelCard {
                    SectionHeader("Подключение", "Введите в клиенте RustDesk")
                    Spacer(Modifier.height(10.dp))
                    InfoRow("ID-сервер", current.host.ifBlank { "—" }, mono = true)
                    InfoRow("Relay", current.relay.ifBlank { "—" }, mono = true)
                    InfoRow(
                        "Порты",
                        current.ports
                            .filter { it.port <= 21117 || current.settings.webClient }
                            .joinToString(", ") { it.caption }
                            .ifBlank { "—" },
                        mono = true
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Публичный ключ сервера",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PanelSurface,
                        border = BorderStroke(1.dp, PanelBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            current.publicKey.ifBlank { "Ключ ещё не сгенерирован" },
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelText,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PanelOutlinedButton(
                        text = "Скопировать данные",
                        onClick = {
                            copyToClipboard(
                                context,
                                listOf(
                                    "ID-сервер: ${current.host}",
                                    "Relay: ${current.relay}",
                                    "Ключ: ${current.publicKey}"
                                ).joinToString("\n"),
                                "Данные RustDesk скопированы"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.ContentCopy
                    )
                }
            }
        }

        if (current.checklist.isNotEmpty()) {
            item {
                val okCount = current.checklist.count { it.ok }
                PanelCard {
                    SectionHeader(
                        title = "Чек-лист",
                        subtitle = "Проверка установки",
                        trailing = {
                            Badge(
                                "$okCount/${current.checklist.size}",
                                if (okCount == current.checklist.size) PanelGreen else PanelAmber
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    current.checklist.forEach { check ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (check.ok) PanelGreen.copy(alpha = 0.2f) else PanelRed.copy(alpha = 0.2f),
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    if (check.ok) Icons.Filled.Check else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (check.ok) PanelGreen else PanelRed,
                                    modifier = Modifier.padding(3.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    check.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (check.ok) PanelText else PanelAmber
                                )
                                if (check.detail.isNotBlank()) {
                                    Text(
                                        check.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PanelMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (canEdit) {
            item {
                PanelCard {
                    SectionHeader("Управление", "Установка и обслуживание сервера")
                    Spacer(Modifier.height(12.dp))
                    PanelButton(
                        text = if (current.installed) "Переустановить" else "Установить",
                        onClick = { runAction("install") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                        loading = busy,
                        icon = Icons.Filled.Download
                    )
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = if (current.updateAvailable) {
                            "Обновить до ${current.latestVersion}"
                        } else {
                            "Обновить с GitHub"
                        },
                        onClick = { runAction("update") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = current.installed && !running,
                        icon = Icons.Filled.SystemUpdate
                    )
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = "Открыть порты",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    vm.call(server) { rustdeskOpenPorts() }
                                    messageOk = true
                                    message = "Порты открыты"
                                    reload++
                                } catch (e: Exception) {
                                    messageOk = false
                                    message = e.message ?: "Не удалось открыть порты"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                        icon = Icons.Filled.Refresh
                    )
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = "Удалить полностью",
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = current.installed && !running,
                        color = PanelRed,
                        icon = Icons.Filled.DeleteForever
                    )
                    Text(
                        "Удаление стирает пакеты, ключи, базу клиентов, кэш и правила UFW.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            item {
                RustDeskSettingsCard(
                    status = current,
                    enabled = !running && !busy,
                    onSave = { hostType, domain, openPorts, webClient ->
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                vm.call(server) {
                                    rustdeskSettings(hostType, domain, openPorts, webClient)
                                }
                                messageOk = true
                                message = "Настройки RustDesk сохранены"
                                reload++
                            } catch (e: Exception) {
                                messageOk = false
                                message = e.message ?: "Не удалось сохранить настройки"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
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

        if (current.clients.isNotEmpty()) {
            item {
                PanelCard {
                    SectionHeader(
                        title = "Клиенты",
                        subtitle = "Версия ${current.clientVersion.ifBlank { "—" }} · установщики с GitHub",
                        trailing = {
                            if (downloadingClient != null) {
                                CircularProgressIndicator(
                                    color = PanelAccent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    current.clients.groupBy { it.group }.forEach { (group, list) ->
                        Text(
                            group,
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        list.forEach { client ->
                            RustDeskClientRow(
                                client = client,
                                downloading = downloadingClient == client.id,
                                enabled = downloadingClient == null,
                                onDownload = {
                                    downloadingClient = client.id
                                    message = null
                                    scope.launch {
                                        try {
                                            val bytes = vm.call(server) { rustdeskClient(client.id) }
                                            shareBytes(
                                                context,
                                                client.name.ifBlank { "rustdesk-client" },
                                                bytes,
                                                "application/octet-stream"
                                            )
                                        } catch (e: Exception) {
                                            messageOk = false
                                            message = e.message ?: "Не удалось скачать клиент"
                                        } finally {
                                            downloadingClient = null
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Как подключиться",
                    subtitle = "Инструкция и адреса сервера"
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "1. Скачайте клиент для Windows, Android или Linux.\n" +
                        "2. Откройте RustDesk → Настройки → Сеть → ID/Relay Server.\n" +
                        "3. Укажите ID-сервер, Relay и ключ из карточки «Подключение».",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
                Spacer(Modifier.height(12.dp))
                PanelOutlinedButton(
                    text = "Открыть инструкцию",
                    onClick = {
                        showInstructions = true
                        if (instructions == null) {
                            instructionsLoading = true
                            scope.launch {
                                try {
                                    instructions = vm.call(server) { rustdeskInstructions() }
                                } catch (e: Exception) {
                                    instructions = null
                                    messageOk = false
                                    message = e.message ?: "Не удалось получить инструкцию"
                                } finally {
                                    instructionsLoading = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Share
                )
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            containerColor = PanelElevated,
            title = { Text("Удалить RustDesk?") },
            text = {
                Text(
                    "Будут удалены пакеты hbbs/hbbr, ключи, база клиентов, кэш и правила UFW " +
                        "для портов 21115–21119. Это необратимо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    runAction("uninstall")
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Отмена") }
            }
        )
    }

    if (showInstructions) {
        AlertDialog(
            onDismissRequest = { showInstructions = false },
            containerColor = PanelElevated,
            title = { Text("RustDesk — подключение") },
            text = {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PanelSurface,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    if (instructionsLoading) {
                        Column(Modifier.padding(12.dp)) {
                            CircularProgressIndicator(
                                color = PanelAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Text(
                            instructions ?: "Инструкция недоступна",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelText,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        instructions?.let {
                            copyToClipboard(context, it, "Инструкция скопирована")
                        }
                    },
                    enabled = instructions != null
                ) { Text("Копировать") }
            },
            dismissButton = {
                TextButton(onClick = { showInstructions = false }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun RustDeskSettingsCard(
    status: RustDeskStatus,
    enabled: Boolean,
    onSave: (String, String, Boolean, Boolean) -> Unit
) {
    var hostType by remember(status.hostType) { mutableStateOf(status.hostType.ifBlank { "ip" }) }
    var domain by remember(status.domain, status.suggestedDomain) {
        mutableStateOf(status.domain.ifBlank { status.suggestedDomain })
    }
    var openPorts by remember(status.settings) { mutableStateOf(status.settings.openPorts) }
    var webClient by remember(status.settings) { mutableStateOf(status.settings.webClient) }

    PanelCard {
        SectionHeader("Адрес и порты", "Что попадёт в клиенты")
        Spacer(Modifier.height(12.dp))

        PanelDropdown(
            label = "Адрес",
            options = listOf("IP сервера", "Домен"),
            selected = if (hostType == "domain") "Домен" else "IP сервера",
            onSelect = { hostType = if (it == "Домен") "domain" else "ip" }
        )

        if (hostType == "domain") {
            Spacer(Modifier.height(10.dp))
            PanelTextField(
                value = domain,
                onValueChange = { domain = it },
                label = "Домен для RustDesk",
                placeholder = "rd.example.com",
                enabled = enabled
            )
            if (status.suggestedDomain.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Домен из настроек: ${status.suggestedDomain}" +
                        when (status.suggestedSource) {
                            "awg" -> " (AmneziaWG)"
                            "panel" -> " (панель)"
                            else -> ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "Открывать порты в UFW",
            subtitle = "21115/tcp, 21116/tcp+udp, 21117/tcp",
            checked = openPorts,
            onCheckedChange = { openPorts = it },
            enabled = enabled
        )
        ToggleRow(
            title = "Веб-клиент",
            subtitle = "Дополнительно 21118/tcp и 21119/tcp",
            checked = webClient,
            onCheckedChange = { webClient = it },
            enabled = enabled
        )

        Spacer(Modifier.height(12.dp))
        PanelButton(
            text = "Сохранить",
            onClick = { onSave(hostType, domain.trim(), openPorts, webClient) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && (hostType != "domain" || domain.isNotBlank())
        )
    }
}

@Composable
private fun RustDeskClientRow(
    client: RustDeskClient,
    downloading: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PanelSurface,
        border = BorderStroke(1.dp, PanelBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    client.label.ifBlank { client.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelText
                )
                if (client.size > 0L) {
                    Text(
                        formatBytes(client.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
            }
            if (downloading) {
                CircularProgressIndicator(
                    color = PanelAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                TextButton(onClick = onDownload, enabled = enabled) {
                    Text("Скачать", color = PanelAccent)
                }
            }
        }
    }
}
