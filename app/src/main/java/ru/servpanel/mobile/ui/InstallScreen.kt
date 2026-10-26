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
import androidx.compose.material.icons.filled.Link
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
    ModuleInfo("mtproto", "MTProto (MEKO)", "Telemt, MTProto.zig или только фикс"),
    ModuleInfo("mtproxyl", "MTProxyL", "Пользователи, лимиты и Telegram-бот"),
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
    var selected by remember(server.id, vm.installModule) { mutableStateOf(vm.installModule) }

    var showMtLinks by remember { mutableStateOf(false) }
    var mtLinks by remember { mutableStateOf<List<String>?>(null) }
    var mtLinksLoading by remember { mutableStateOf(false) }
    var mtLinksError by remember { mutableStateOf<String?>(null) }

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

        if (selected == "mtproto" || selected == "mtproxyl") {
            item {
                PanelCard {
                    SectionHeader("Ссылки для подключения", "tg:// из /root/mtproto_links.txt")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "После установки панель сохраняет ссылки Telemt/MTProxyL. " +
                            "Их можно открыть в Telegram или отправить клиенту.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    PanelOutlinedButton(
                        text = "Показать ссылки",
                        onClick = { loadMtLinks() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Link
                    )
                }
            }
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
}

@Composable
private fun InstallOptionsCard(
    module: String,
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    PanelCard {
        SectionHeader(
            title = "Параметры",
            subtitle = when (module) {
                "awg" -> "Версия, маршрутизация и режим установки"
                "mtproto" -> "Мастер MEKO: Telemt, MTProto.zig или фикс"
                "mtproxyl" -> "Мастер MTProxyL: движок, WEB, API и бот"
                "warp" -> "Адреса и порты WARP/Xray"
                else -> "Домен и общие параметры"
            }
        )

        Spacer(Modifier.height(12.dp))

        when (module) {
            "awg" -> AwgInstallOptions(running, enabled, onInstall)
            "mtproto" -> MtprotoInstallOptions(running, enabled, onInstall)
            "mtproxyl" -> MtproxylInstallOptions(running, enabled, onInstall)
            "warp" -> WarpInstallOptions(running, enabled, onInstall)
            else -> AdGuardInstallOptions(running, enabled, onInstall)
        }

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

@Composable
private fun InstallButton(enabled: Boolean, running: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    PanelButton(
        text = "Установить",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && !running,
        icon = Icons.Filled.Download
    )
}

@Composable
private fun AdGuardInstallOptions(
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var domain by remember { mutableStateOf("") }

    PanelTextField(
        value = domain,
        onValueChange = { domain = it.trim() },
        label = "Домен (необязательно)",
        placeholder = "dns.example.com",
        enabled = !running
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "AdGuard Home ставится в неинтерактивном режиме: панель откроет DNS-порты " +
            "и покажет адрес веб-интерфейса после установки.",
        style = MaterialTheme.typography.bodySmall,
        color = PanelMuted
    )
    InstallButton(enabled, running) {
        val options = mutableMapOf<String, Any>()
        if (domain.isNotBlank()) options["domain"] = domain
        onInstall(options)
    }
}

@Composable
private fun AwgInstallOptions(
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("3.1") }
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

    PanelTextField(
        value = domain,
        onValueChange = { domain = it.trim() },
        label = "Домен (необязательно)",
        placeholder = "vpn.example.com",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelDropdown(
        label = "Версия AmneziaWG",
        options = listOf("1.5", "2.0", "3.1"),
        selected = version,
        onSelect = { version = it }
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
        placeholder = "443",
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

    Spacer(Modifier.height(8.dp))
    Text(
        "AWG ставится стандартно: весь трафик, IPv6 disallow, порт 443. " +
            "Пресетов default/mobile больше нет — параметры обфускации задаются " +
            "во вкладке «Обфускация» или в Архитекторе.",
        style = MaterialTheme.typography.bodySmall,
        color = PanelMuted
    )

    InstallButton(enabled, running) {
        val options = mutableMapOf<String, Any>()
        if (domain.isNotBlank()) options["domain"] = domain
        options["version"] = version
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
        onInstall(options)
    }
}

@Composable
private fun MtprotoInstallOptions(
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var kind by remember { mutableStateOf("telemt") }
    var sni by remember { mutableStateOf("ozon.ru") }
    var port by remember { mutableStateOf("443") }
    var telemtVersion by remember { mutableStateOf("") }
    var fixType by remember { mutableStateOf("v3") }
    var fixPort by remember { mutableStateOf("") }
    var noFix by remember { mutableStateOf(false) }
    var adTag by remember { mutableStateOf("") }
    var mtUser by remember { mutableStateOf("") }
    var mtUserSecret by remember { mutableStateOf("") }
    var publicHost by remember { mutableStateOf("") }
    var web by remember { mutableStateOf(false) }
    var webHost by remember { mutableStateOf("") }
    var nginx by remember { mutableStateOf(true) }

    PanelDropdown(
        label = "Что установить",
        options = listOf("Telemt", "MTProto.zig", "Только фикс MEKO"),
        selected = when (kind) {
            "zig" -> "MTProto.zig"
            "fix" -> "Только фикс MEKO"
            else -> "Telemt"
        },
        onSelect = {
            kind = when (it) {
                "MTProto.zig" -> "zig"
                "Только фикс MEKO" -> "fix"
                else -> "telemt"
            }
            if (kind != "telemt") web = false
        }
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = sni,
        onValueChange = { sni = it.trim() },
        label = "SNI-домен",
        placeholder = "ozon.ru",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit).take(5) },
        label = "Порт прокси",
        keyboardType = KeyboardType.Number,
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    if (kind == "telemt") {
        PanelTextField(
            value = telemtVersion,
            onValueChange = { telemtVersion = it.trim() },
            label = "Версия Telemt (необязательно)",
            placeholder = "последняя",
            enabled = !running
        )
        Spacer(Modifier.height(10.dp))
    }

    PanelDropdown(
        label = "Тип фикса MEKO",
        options = listOf("v2", "v3", "v4", "nft"),
        selected = fixType,
        onSelect = { fixType = it }
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = fixPort,
        onValueChange = { fixPort = it.filter(Char::isDigit).take(5) },
        label = "Порт фикса (необязательно)",
        placeholder = "как у прокси",
        keyboardType = KeyboardType.Number,
        enabled = !running
    )
    Spacer(Modifier.height(4.dp))

    ToggleRow(
        title = "Без фикса",
        subtitle = "Установить только прокси, без MEKO-фикса",
        checked = noFix,
        onCheckedChange = { noFix = it },
        enabled = !running
    )

    PanelTextField(
        value = adTag,
        onValueChange = { adTag = it.trim() },
        label = "ad_tag (необязательно)",
        supportingText = "32 hex-символа",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = mtUser,
        onValueChange = { mtUser = it.trim() },
        label = "Первый пользователь (необязательно)",
        placeholder = "user",
        supportingText = "Буквы, цифры, _ и -",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = mtUserSecret,
        onValueChange = { mtUserSecret = it.trim() },
        label = "Секрет пользователя (необязательно)",
        supportingText = "32 hex-символа; пусто — сгенерировать",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = publicHost,
        onValueChange = { publicHost = it.trim() },
        label = "public_host (необязательно)",
        placeholder = "proxy.example.com",
        enabled = !running
    )
    Spacer(Modifier.height(4.dp))

    if (kind == "telemt") {
        ToggleRow(
            title = "WEB-режим",
            subtitle = "Nginx + сертификат Let's Encrypt, маскировка под сайт",
            checked = web,
            onCheckedChange = { web = it },
            enabled = !running
        )
        if (web) {
            PanelTextField(
                value = webHost,
                onValueChange = { webHost = it.trim() },
                label = "Домен WEB-режима",
                placeholder = "web.example.com",
                enabled = !running
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Поставить Nginx и Certbot",
                subtitle = "Панель сама выпустит сертификат",
                checked = nginx,
                onCheckedChange = { nginx = it },
                enabled = !running
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "MTG в мастере недоступен: MEKO пока не ставит его автоматически. " +
            "Ссылки tg:// после установки появятся в /root/mtproto_links.txt.",
        style = MaterialTheme.typography.bodySmall,
        color = PanelMuted
    )

    InstallButton(enabled, running) {
        val options = mutableMapOf<String, Any>()
        options["kind"] = kind
        options["mtDomain"] = sni
        options["mtPort"] = port.toIntOrNull() ?: 443
        if (telemtVersion.isNotBlank()) options["mtVersion"] = telemtVersion
        options["fixType"] = fixType
        if (fixPort.isNotBlank()) options["fixPort"] = fixPort.toIntOrNull() ?: 0
        options["noFix"] = noFix
        if (adTag.isNotBlank()) options["adTag"] = adTag
        if (mtUser.isNotBlank()) options["mtUser"] = mtUser
        if (mtUserSecret.isNotBlank()) options["mtUserSecret"] = mtUserSecret
        if (publicHost.isNotBlank()) options["publicHost"] = publicHost
        options["web"] = web
        if (web) {
            options["webHost"] = webHost
            options["nginx"] = nginx
        }
        onInstall(options)
    }
}

@Composable
private fun MtproxylInstallOptions(
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var mode by remember { mutableStateOf("manager") }
    var engine by remember { mutableStateOf("binary") }
    var proxyMode by remember { mutableStateOf("mtproto") }
    var port by remember { mutableStateOf("443") }
    var sni by remember { mutableStateOf("autoscout24.ru") }
    var host by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var adTag by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var userSecret by remember { mutableStateOf("") }
    var zapret2 by remember { mutableStateOf(true) }
    var synLimiter by remember { mutableStateOf("meko") }
    var meko by remember { mutableStateOf(true) }
    var selfmask by remember { mutableStateOf("") }
    var selfmaskCert by remember { mutableStateOf("letsencrypt") }
    var selfmaskEmail by remember { mutableStateOf("") }
    var web by remember { mutableStateOf(false) }
    var webDomain by remember { mutableStateOf("") }
    var webCarrier by remember { mutableStateOf("https") }
    var api by remember { mutableStateOf(true) }
    var apiListen by remember { mutableStateOf("127.0.0.1:9190") }
    var tgbotToken by remember { mutableStateOf("") }
    var tgbotAdmin by remember { mutableStateOf("") }
    var force by remember { mutableStateOf(false) }

    PanelDropdown(
        label = "Режим MTProxyL",
        options = listOf("Manager", "Reanimator"),
        selected = if (mode == "reanimator") "Reanimator" else "Manager",
        onSelect = { mode = if (it == "Reanimator") "reanimator" else "manager" }
    )
    Spacer(Modifier.height(10.dp))

    PanelDropdown(
        label = "Движок",
        options = listOf("Binary", "Docker"),
        selected = if (engine == "docker") "Docker" else "Binary",
        onSelect = { engine = if (it == "Docker") "docker" else "binary" }
    )
    Spacer(Modifier.height(10.dp))

    PanelDropdown(
        label = "Режим прокси",
        options = listOf("MTProto", "WEB", "Combined"),
        selected = when (proxyMode) {
            "web" -> "WEB"
            "combined" -> "Combined"
            else -> "MTProto"
        },
        onSelect = {
            proxyMode = when (it) {
                "WEB" -> "web"
                "Combined" -> "combined"
                else -> "mtproto"
            }
        }
    )
    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = "Порт",
            keyboardType = KeyboardType.Number,
            enabled = !running,
            modifier = Modifier.weight(1f)
        )
        PanelTextField(
            value = sni,
            onValueChange = { sni = it.trim() },
            label = "SNI-домен",
            enabled = !running,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = host,
        onValueChange = { host = it.trim() },
        label = "Публичный адрес (необязательно)",
        placeholder = "proxy.example.com",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = secret,
        onValueChange = { secret = it.trim() },
        label = "Секрет прокси (необязательно)",
        supportingText = "32 hex-символа; пусто — сгенерировать",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = adTag,
        onValueChange = { adTag = it.trim() },
        label = "ad_tag (необязательно)",
        supportingText = "32 hex-символа",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = user,
        onValueChange = { user = it.trim() },
        label = "Первый пользователь (необязательно)",
        placeholder = "user",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = userSecret,
        onValueChange = { userSecret = it.trim() },
        label = "Секрет пользователя (необязательно)",
        supportingText = "32 hex-символа",
        enabled = !running
    )
    Spacer(Modifier.height(4.dp))

    ToggleRow(
        title = "Zapret2",
        subtitle = "Обход блокировок на уровне сети",
        checked = zapret2,
        onCheckedChange = { zapret2 = it },
        enabled = !running
    )
    ToggleRow(
        title = "Фикс MEKO",
        subtitle = "Установить MEKO-фикс вместе с MTProxyL",
        checked = meko,
        onCheckedChange = { meko = it },
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelDropdown(
        label = "NFT SYN limiter",
        options = listOf("MEKO", "Classic", "Off"),
        selected = when (synLimiter) {
            "classic" -> "Classic"
            "off" -> "Off"
            else -> "MEKO"
        },
        onSelect = {
            synLimiter = when (it) {
                "Classic" -> "classic"
                "Off" -> "off"
                else -> "meko"
            }
        }
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = selfmask,
        onValueChange = { selfmask = it.trim() },
        label = "Домен Selfmask (необязательно)",
        placeholder = "mask.example.com",
        enabled = !running
    )
    if (selfmask.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        PanelDropdown(
            label = "Сертификат Selfmask",
            options = listOf("Let's Encrypt", "Самоподписанный"),
            selected = if (selfmaskCert == "selfsigned") "Самоподписанный" else "Let's Encrypt",
            onSelect = { selfmaskCert = if (it == "Самоподписанный") "selfsigned" else "letsencrypt" }
        )
        if (selfmaskCert == "letsencrypt") {
            Spacer(Modifier.height(10.dp))
            PanelTextField(
                value = selfmaskEmail,
                onValueChange = { selfmaskEmail = it.trim() },
                label = "E-mail для Let's Encrypt",
                placeholder = "admin@example.com",
                enabled = !running
            )
        }
    }
    Spacer(Modifier.height(4.dp))

    ToggleRow(
        title = "WEB Proxy",
        subtitle = "Домен и carrier для маскировки",
        checked = web,
        onCheckedChange = { web = it },
        enabled = !running
    )
    if (web) {
        PanelTextField(
            value = webDomain,
            onValueChange = { webDomain = it.trim() },
            label = "Домен WEB Proxy",
            placeholder = "web.example.com",
            enabled = !running
        )
        Spacer(Modifier.height(10.dp))
        PanelDropdown(
            label = "Carrier",
            options = listOf("https", "https-lanes", "websocket", "websocket-lanes"),
            selected = webCarrier,
            onSelect = { webCarrier = it }
        )
    }
    Spacer(Modifier.height(4.dp))

    ToggleRow(
        title = "API панели",
        subtitle = "Нужен для управления пользователями из приложения",
        checked = api,
        onCheckedChange = { api = it },
        enabled = !running
    )
    if (api) {
        PanelTextField(
            value = apiListen,
            onValueChange = { apiListen = it.trim() },
            label = "Адрес API",
            placeholder = "127.0.0.1:9190",
            enabled = !running
        )
    }
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = tgbotToken,
        onValueChange = { tgbotToken = it.trim() },
        label = "Токен Telegram-бота (необязательно)",
        placeholder = "123456:ABC…",
        enabled = !running
    )
    Spacer(Modifier.height(10.dp))

    PanelTextField(
        value = tgbotAdmin,
        onValueChange = { tgbotAdmin = it.filter(Char::isDigit).take(20) },
        label = "ID администратора бота (необязательно)",
        keyboardType = KeyboardType.Number,
        enabled = !running
    )
    Spacer(Modifier.height(4.dp))

    ToggleRow(
        title = "Переустановить поверх",
        subtitle = "--force: заменить существующую установку",
        checked = force,
        onCheckedChange = { force = it },
        enabled = !running
    )

    Spacer(Modifier.height(8.dp))
    Text(
        "MTProxyL ставится неинтерактивно, открывает порт прокси в UFW и сохраняет " +
            "ключ API в /etc/mtproxyl-api/servpanel.key. Пользователями можно управлять " +
            "во вкладке «Прокси».",
        style = MaterialTheme.typography.bodySmall,
        color = PanelMuted
    )

    InstallButton(enabled, running) {
        val options = mutableMapOf<String, Any>()
        options["mplMode"] = mode
        options["mplEngine"] = engine
        options["mplProxyMode"] = proxyMode
        options["mplPort"] = port.toIntOrNull() ?: 443
        options["mplSni"] = sni
        if (host.isNotBlank()) options["mplHost"] = host
        if (secret.isNotBlank()) options["mplSecret"] = secret
        if (adTag.isNotBlank()) options["mplAdTag"] = adTag
        if (user.isNotBlank()) options["mplUser"] = user
        if (userSecret.isNotBlank()) options["mplUserSecret"] = userSecret
        options["mplZapret2"] = zapret2
        options["mplSynLimiter"] = synLimiter
        options["mplMeko"] = meko
        if (selfmask.isNotBlank()) {
            options["mplSelfmask"] = selfmask
            options["mplSelfmaskCert"] = selfmaskCert
            if (selfmaskEmail.isNotBlank()) options["mplSelfmaskEmail"] = selfmaskEmail
        }
        options["mplWeb"] = web
        if (web) {
            options["mplWebDomain"] = webDomain
            options["mplWebCarrier"] = webCarrier
        }
        options["mplApi"] = api
        if (api && apiListen.isNotBlank()) options["mplApiListen"] = apiListen
        if (tgbotToken.isNotBlank()) options["mplTgbotToken"] = tgbotToken
        if (tgbotAdmin.isNotBlank()) options["mplTgbotAdmin"] = tgbotAdmin
        options["mplForce"] = force
        onInstall(options)
    }
}

@Composable
private fun WarpInstallOptions(
    running: Boolean,
    enabled: Boolean,
    onInstall: (Map<String, Any>) -> Unit
) {
    var listenIp by remember { mutableStateOf("10.8.1.1") }
    var listenPort by remember { mutableStateOf("443") }
    var socksPort by remember { mutableStateOf("40000") }

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

    InstallButton(enabled, running) {
        val options = mutableMapOf<String, Any>()
        options["listenIp"] = listenIp
        options["listenPort"] = listenPort.toIntOrNull() ?: 443
        options["socksPort"] = socksPort.toIntOrNull() ?: 40000
        onInstall(options)
    }
}
