package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.InstallProgress
import ru.servpanel.mobile.data.InstallState
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

private data class ModuleInfo(val key: String, val title: String, val subtitle: String)

private val modules = listOf(
    ModuleInfo("awg", "AmneziaWG", "VPN-сервер: установка и настройка"),
    ModuleInfo("adguard", "AdGuard Home", "DNS-фильтр и блокировка рекламы"),
    ModuleInfo("mtproto", "MTProto", "Telegram-прокси"),
    ModuleInfo("warp", "Cloudflare WARP", "WARP + Xray для AdGuard")
)

@Composable
fun InstallScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var state by remember(server.id) { mutableStateOf<InstallState?>(null) }
    var progress by remember(server.id) { mutableStateOf<InstallProgress?>(null) }
    var logText by remember(server.id) { mutableStateOf("") }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("awg") }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (state == null) loading = true
        error = null
        try {
            state = vm.call(server) { installState() }
        } catch (e: Exception) {
            if (state == null) error = e.message ?: "Не удалось прочитать состояние установки"
        } finally {
            loading = false
        }
    }

    val activeLog = state?.logFile.orEmpty()
    LaunchedEffect(server.id, state?.active, activeLog) {
        if (state?.active != true || activeLog.isBlank()) return@LaunchedEffect
        while (isActive) {
            delay(3000)
            val next = runCatching { vm.call(server) { installProgress(activeLog) } }.getOrNull()
            if (next != null) {
                progress = next
                logText = next.text.takeLast(6000)
                if (next.done || next.error || next.status == "done" || next.status == "error") {
                    reload++
                    break
                }
            }
        }
    }

    if (loading && state == null) {
        LoadingState("Читаем состояние установки…")
        return
    }

    if (state == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { reload++ })
        }
        return
    }

    val current = state!!
    val running = current.active || progress?.status == "running"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "Установка",
                subtitle = "Модули панели",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
        }

        if (running || progress != null) {
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(
                                color = PanelAccent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                if (progress?.error == true) Icons.Filled.Warning else Icons.Filled.Check,
                                contentDescription = null,
                                tint = if (progress?.error == true) PanelRed else PanelGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                current.title.ifBlank { "Установка" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = PanelText
                            )
                            Text(
                                when {
                                    progress?.error == true -> "Ошибка установки"
                                    progress?.done == true -> "Установка завершена"
                                    running -> "Выполняется…"
                                    else -> "Готово"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (progress?.error == true) PanelRed else PanelMuted
                            )
                        }
                    }

                    val percent = (progress?.progress ?: 0) / 100f
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { percent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = PanelAccent,
                        trackColor = PanelBorder
                    )

                    val steps = progress?.steps ?: current.steps
                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        steps.forEach { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (step.done) PanelGreen.copy(alpha = 0.2f) else PanelBorder,
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    if (step.done) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = PanelGreen,
                                            modifier = Modifier.padding(3.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    step.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (step.done) PanelText else PanelMuted
                                )
                            }
                        }
                    }

                    if (logText.isNotBlank()) {
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
                                    logText,
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
            PanelCard {
                SectionHeader("Модуль", "Что установить на сервер")
                Spacer(Modifier.height(8.dp))
                modules.forEach { module ->
                    val isSelected = selected == module.key
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) PanelAccent.copy(alpha = 0.12f) else PanelSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) PanelAccent.copy(alpha = 0.45f) else PanelBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(enabled = !running) { selected = module.key }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    module.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = PanelText
                                )
                                Text(
                                    module.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = PanelAccent)
                            }
                        }
                    }
                }
            }
        }

        item {
            InstallOptionsCard(
                module = selected,
                running = running,
                enabled = auth.isAdmin,
                onInstall = { options ->
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val body = JSONObject().put("module", selected)
                            options.forEach { (key, value) -> body.put(key, value) }
                            val result = vm.call(server) { installModule(body) }
                            messageOk = true
                            message = "Установка запущена: ${result.optString("title", selected)}"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось запустить установку"
                        } finally {
                            busy = false
                        }
                    }
                }
            )
        }

        if (auth.isAdmin) {
            item {
                PanelCard {
                    SectionHeader(
                        title = "Удаление AmneziaWG",
                        subtitle = "Полностью удалить VPN и конфиги"
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Будут удалены пакеты AmneziaWG, интерфейсы, конфиги и таймеры каскада. " +
                            "Клиентские ключи перестанут работать. Действие необратимо.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    PanelOutlinedButton(
                        text = "Удалить AmneziaWG",
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.fillMaxWidth(),
                        color = PanelRed,
                        icon = Icons.Filled.DeleteForever
                    )
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

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            containerColor = PanelElevated,
            title = { Text("Удалить AmneziaWG?") },
            text = {
                Text(
                    "VPN будет полностью удалён с сервера. Это необратимо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    busy = true
                    scope.launch {
                        try {
                            vm.call(server) { uninstallAwg() }
                            messageOk = true
                            message = "Удаление запущено"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось запустить удаление"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun InstallOptionsCard(
    module: String,
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("2.0") }
    var preset by remember { mutableStateOf("default") }
    var route by remember { mutableStateOf("all") }
    var customRoute by remember { mutableStateOf("") }
    var ipv6 by remember { mutableStateOf("disallow") }
    var port by remember { mutableStateOf("") }
    var isolation by remember { mutableStateOf(true) }
    var keepPackages by remember { mutableStateOf(true) }
    var noTweaks by remember { mutableStateOf(false) }
    var awgMode by remember { mutableStateOf("single") }
    var cascadeRole by remember { mutableStateOf("ru") }
    var remoteIp by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("22") }
    var remotePass by remember { mutableStateOf("") }
    var listenIp by remember { mutableStateOf("10.8.1.1") }
    var listenPort by remember { mutableStateOf("443") }
    var socksPort by remember { mutableStateOf("40000") }

    PanelCard {
        SectionHeader(
            title = "Параметры",
            subtitle = when (module) {
                "awg" -> "Версия, маршрутизация и режим установки"
                "warp" -> "Адреса и порты WARP/Xray"
                else -> "Домен и общие параметры"
            }
        )

        Spacer(Modifier.height(12.dp))

        if (module != "warp") {
            PanelTextField(
                value = domain,
                onValueChange = { domain = it.trim() },
                label = "Домен (необязательно)",
                placeholder = "vpn.example.com",
                enabled = !running
            )
            Spacer(Modifier.height(10.dp))
        }

        if (module == "awg") {
            PanelDropdown(
                label = "Версия AmneziaWG",
                options = listOf("1.5", "2.0", "3.1"),
                selected = version,
                onSelect = { version = it }
            )
            Spacer(Modifier.height(10.dp))
            PanelDropdown(
                label = "Пресет",
                options = listOf("default", "mobile"),
                selected = preset,
                onSelect = { preset = it }
            )
            Spacer(Modifier.height(10.dp))
            PanelDropdown(
                label = "Маршрутизация",
                options = listOf("all", "amnezia", "custom"),
                selected = route,
                onSelect = { route = it }
            )
            if (route == "custom") {
                Spacer(Modifier.height(10.dp))
                PanelTextField(
                    value = customRoute,
                    onValueChange = { customRoute = it },
                    label = "Свои подсети",
                    placeholder = "10.0.0.0/8, 192.168.0.0/16",
                    enabled = !running
                )
            }
            Spacer(Modifier.height(10.dp))
            PanelDropdown(
                label = "IPv6",
                options = listOf("disallow", "allow", "tunnel"),
                selected = ipv6,
                onSelect = { ipv6 = it }
            )
            Spacer(Modifier.height(10.dp))
            PanelTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = "Порт (необязательно)",
                placeholder = "51820",
                keyboardType = KeyboardType.Number,
                enabled = !running
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Изоляция клиентов",
                subtitle = "Клиенты не видят друг друга",
                checked = isolation,
                onCheckedChange = { isolation = it },
                enabled = !running
            )
            ToggleRow(
                title = "Оставить пакеты",
                subtitle = "Не удалять зависимости после установки",
                checked = keepPackages,
                onCheckedChange = { keepPackages = it },
                enabled = !running
            )
            ToggleRow(
                title = "Без твиков",
                subtitle = "Не менять системные параметры",
                checked = noTweaks,
                onCheckedChange = { noTweaks = it },
                enabled = !running
            )
            Spacer(Modifier.height(10.dp))
            PanelDropdown(
                label = "Режим установки",
                options = listOf("single", "cascade"),
                selected = awgMode,
                onSelect = { awgMode = it }
            )
            if (awgMode == "cascade") {
                Spacer(Modifier.height(10.dp))
                PanelDropdown(
                    label = "Роль сервера",
                    options = listOf("ru", "eu"),
                    selected = cascadeRole,
                    onSelect = { cascadeRole = it }
                )
                if (cascadeRole == "ru") {
                    Spacer(Modifier.height(10.dp))
                    PanelTextField(
                        value = remoteIp,
                        onValueChange = { remoteIp = it.trim() },
                        label = "IP EU-сервера",
                        placeholder = "203.0.113.10",
                        enabled = !running
                    )
                    Spacer(Modifier.height(10.dp))
                    PanelTextField(
                        value = remotePort,
                        onValueChange = { remotePort = it.filter(Char::isDigit).take(5) },
                        label = "SSH-порт",
                        keyboardType = KeyboardType.Number,
                        enabled = !running
                    )
                    Spacer(Modifier.height(10.dp))
                    PanelTextField(
                        value = remotePass,
                        onValueChange = { remotePass = it },
                        label = "Пароль SSH",
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !running
                    )
                }
            }
        }

        if (module == "warp") {
            PanelTextField(
                value = listenIp,
                onValueChange = { listenIp = it.trim() },
                label = "IP туннеля",
                placeholder = "10.8.1.1",
                enabled = !running
            )
            Spacer(Modifier.height(10.dp))
            PanelTextField(
                value = listenPort,
                onValueChange = { listenPort = it.filter(Char::isDigit).take(5) },
                label = "Порт Xray",
                keyboardType = KeyboardType.Number,
                enabled = !running
            )
            Spacer(Modifier.height(10.dp))
            PanelTextField(
                value = socksPort,
                onValueChange = { socksPort = it.filter(Char::isDigit).take(5) },
                label = "SOCKS-порт",
                keyboardType = KeyboardType.Number,
                enabled = !running
            )
        }

        Spacer(Modifier.height(16.dp))

        PanelButton(
            text = "Установить",
            onClick = {
                val options = mutableMapOf<String, Any>()
                if (domain.isNotBlank()) options["domain"] = domain
                if (module == "awg") {
                    options["version"] = version
                    options["preset"] = preset
                    options["route"] = route
                    if (route == "custom" && customRoute.isNotBlank()) options["customRoute"] = customRoute
                    options["ipv6"] = ipv6
                    if (port.isNotBlank()) options["port"] = port.toIntOrNull() ?: 0
                    options["isolation"] = isolation
                    options["keepPackages"] = keepPackages
                    options["noTweaks"] = noTweaks
                    options["awgMode"] = awgMode
                    options["cascadeRole"] = cascadeRole
                    if (awgMode == "cascade" && cascadeRole == "ru") {
                        if (remoteIp.isNotBlank()) options["remoteIp"] = remoteIp
                        if (remotePort.isNotBlank()) options["remotePort"] = remotePort.toIntOrNull() ?: 22
                        if (remotePass.isNotBlank()) options["remotePass"] = remotePass
                    }
                }
                if (module == "warp") {
                    options["listenIp"] = listenIp
                    options["listenPort"] = listenPort.toIntOrNull() ?: 443
                    options["socksPort"] = socksPort.toIntOrNull() ?: 40000
                }
                onInstall(options)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !running,
            loading = false,
            icon = Icons.Filled.Download
        )

        if (!enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Роль «только просмотр»: установка недоступна.",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        }
    }
}
