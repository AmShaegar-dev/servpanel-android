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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.CascadeHealth
import ru.servpanel.mobile.data.CascadeStatus
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ToolsStatus
import ru.servpanel.mobile.data.UfwData
import ru.servpanel.mobile.data.WarpStatus
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText
import ru.servpanel.mobile.ui.theme.PanelViolet

private enum class NetworkSection(val title: String) {
    Firewall("Файрвол"),
    Cascade("Каскад"),
    GeoIp("GeoIP"),
    Dns("DNS"),
    Warp("WARP")
}

private data class NetworkData(
    val interfaces: List<String>,
    val ufw: UfwData?,
    val cascade: CascadeStatus?,
    val health: CascadeHealth?,
    val tools: ToolsStatus?,
    val warp: WarpStatus?
)

@Composable
fun NetworkScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var section by remember(server.id) { mutableStateOf(NetworkSection.Firewall) }
    var data by remember(server.id) { mutableStateOf<NetworkData?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }

    fun refresh() {
        reload++
    }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (data == null) loading = true
        error = null
        try {
            data = coroutineScope {
                val ifaces = async { runCatching { vm.call(server) { interfaces() } }.getOrDefault(emptyList()) }
                val ufw = async { runCatching { vm.call(server) { ufw() } }.getOrNull() }
                val cascade = async { runCatching { vm.call(server) { cascadeStatus() } }.getOrNull() }
                val health = async { runCatching { vm.call(server) { cascadeHealth() } }.getOrNull() }
                val tools = async { runCatching { vm.call(server) { toolsStatus() } }.getOrNull() }
                val warp = async { runCatching { vm.call(server) { warpStatus() } }.getOrNull() }
                NetworkData(
                    interfaces = ifaces.await(),
                    ufw = ufw.await(),
                    cascade = cascade.await(),
                    health = health.await(),
                    tools = tools.await(),
                    warp = warp.await()
                )
            }
        } catch (e: Exception) {
            if (data == null) error = e.message ?: "Не удалось загрузить сетевые настройки"
        } finally {
            loading = false
        }
    }

    if (loading && data == null) {
        LoadingState("Читаем сетевые настройки…")
        return
    }

    val current = data
    if (current == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { refresh() })
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ScreenHeader(
                title = "Сеть",
                subtitle = "Файрвол, каскад и туннели",
                trailing = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkSection.entries.forEach { item ->
                    SectionChip(
                        label = item.title,
                        selected = section == item,
                        onClick = { section = item }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                NetworkSection.Firewall -> firewallItems(current, auth.isAdmin, scope, server, vm) { refresh() }
                NetworkSection.Cascade -> cascadeItems(current, auth.isAdmin, scope, server, vm) { refresh() }
                NetworkSection.GeoIp -> geoIpItems(current, auth.isAdmin, scope, server, vm) { refresh() }
                NetworkSection.Dns -> dnsItems(current, auth.isAdmin, scope, server, vm) { refresh() }
                NetworkSection.Warp -> warpItems(current, auth.isAdmin, scope, server, vm) { refresh() }
            }
        }
    }
}

@Composable
private fun SectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) PanelAccent.copy(alpha = 0.16f) else PanelSurface,
        border = BorderStroke(1.dp, if (selected) PanelAccent.copy(alpha = 0.5f) else PanelBorder),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) PanelAccent else PanelMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Файрвол
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.firewallItems(
    data: NetworkData,
    isAdmin: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    server: ServerProfile,
    vm: AppViewModel,
    onChanged: () -> Unit
) {
    item {
        var showAdd by remember { mutableStateOf(false) }
        var deleteTarget by remember { mutableStateOf<String?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current

        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Правила UFW",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelText
                    )
                    Text(
                        "${data.ufw?.rules?.size ?: 0} правил · ${data.ufw?.ports?.size ?: 0} слушающих портов",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
                if (isAdmin) {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Открыть порт", tint = PanelAccent)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val rules = data.ufw?.rules.orEmpty()
            if (rules.isEmpty()) {
                Text(
                    "Правил нет или UFW недоступен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "#${rule.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted,
                            modifier = Modifier.width(38.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${rule.to} → ${rule.from}",
                                style = MaterialTheme.typography.bodySmall,
                                color = PanelText
                            )
                            Text(
                                rule.action,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule.action.contains("ALLOW")) PanelGreen else PanelAmber
                            )
                        }
                        if (isAdmin) {
                            IconButton(onClick = { deleteTarget = rule.id }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Удалить правило",
                                    tint = PanelMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (showAdd) {
                AddUfwDialog(
                    interfaces = data.interfaces,
                    onDismiss = { showAdd = false },
                    onAdd = { port, proto, iface ->
                        scope.launch {
                            try {
                                vm.call(server) { addUfwRule(port, proto, iface) }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    e.message ?: "Не удалось добавить правило",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            showAdd = false
                            onChanged()
                        }
                    }
                )
            }

            deleteTarget?.let { id ->
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    containerColor = PanelElevated,
                    title = { Text("Удалить правило #$id?") },
                    text = { Text("Порт закроется сразу после применения правил UFW.") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    vm.call(server) { deleteUfwRule(id) }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        e.message ?: "Не удалось удалить правило",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                                deleteTarget = null
                                onChanged()
                            }
                        }) { Text("Удалить", color = PanelRed) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
                    }
                )
            }
        }
    }

    item {
        PanelCard {
            SectionHeader("Слушающие порты", "Что сейчас принимает подключения")
            Spacer(Modifier.height(8.dp))
            val ports = data.ufw?.ports.orEmpty()
            if (ports.isEmpty()) {
                Text("Нет данных", style = MaterialTheme.typography.bodySmall, color = PanelMuted)
            } else {
                ports.take(40).forEach { port ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Badge(port.proto.uppercase(), PanelViolet)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            port.addr,
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelText,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            port.proc.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddUfwDialog(
    interfaces: List<String>,
    onDismiss: () -> Unit,
    onAdd: (Int, String, String) -> Unit
) {
    var port by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf("tcp") }
    var iface by remember { mutableStateOf("any") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Открыть порт") },
        text = {
            Column {
                PanelTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = "Порт",
                    placeholder = "51820",
                    keyboardType = KeyboardType.Number
                )
                Spacer(Modifier.height(10.dp))
                PanelDropdown(
                    label = "Протокол",
                    options = listOf("tcp", "udp", "any"),
                    selected = proto,
                    onSelect = { proto = it }
                )
                Spacer(Modifier.height(10.dp))
                PanelDropdown(
                    label = "Интерфейс",
                    options = listOf("any") + interfaces,
                    selected = iface,
                    onSelect = { iface = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(port.toIntOrNull() ?: 0, proto, iface) },
                enabled = (port.toIntOrNull() ?: 0) in 1..65535
            ) { Text("Открыть") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ---------------------------------------------------------------------------
// Каскад
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.cascadeItems(
    data: NetworkData,
    isAdmin: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    server: ServerProfile,
    vm: AppViewModel,
    onChanged: () -> Unit
) {
    item {
        var editing by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val cascade = data.cascade
        val color = cascade?.color?.toColorOrNull() ?: PanelMuted

        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Sync, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Каскад RU → EU",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelText
                    )
                    Text(
                        cascade?.text ?: "Статус недоступен",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                Badge(
                    if (cascade?.active == true) "Работает" else if (cascade?.configured == true) "Неактивен" else "Не настроен",
                    if (cascade?.active == true) PanelGreen else if (cascade?.configured == true) PanelAmber else PanelMuted
                )
            }

            Spacer(Modifier.height(12.dp))

            InfoRow("Endpoint", cascade?.endpoint.orEmpty())
            InfoRow("Интерфейсы", cascade?.ifaces?.joinToString(", ").orEmpty())
            InfoRow("Маршрут таблицы 100", cascade?.tableRoute.orEmpty())
            InfoRow(
                "Рукопожатие",
                cascade?.handshakeAgo?.let { "$it с назад" } ?: "нет"
            )
            InfoRow("Последний запуск", cascade?.lastRun.orEmpty())
            InfoRow("IP в ipset", cascade?.ipsetCount?.toString().orEmpty())

            Spacer(Modifier.height(12.dp))

            val health = data.health
            if (health != null) {
                PanelDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "Проверка состояния",
                    style = MaterialTheme.typography.labelMedium,
                    color = PanelMuted
                )
                Spacer(Modifier.height(6.dp))
                health.checks.forEach { (key, ok) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(if (ok) PanelGreen else PanelRed, 7.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (key) {
                                "tunnel" -> "Туннель awg1"
                                "handshake" -> "Рукопожатие с EU"
                                "route" -> "Маршрут по умолчанию"
                                "ipset" -> "Список RU-адресов"
                                "table" -> "Таблица маршрутов"
                                else -> key
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelText,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (ok) "ок" else "нет",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) PanelGreen else PanelRed
                        )
                    }
                }
                if (health.checkedAt.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Проверено: ${health.checkedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
            }

            if (isAdmin) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = if (cascade?.configured == true) "Изменить" else "Настроить",
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f)
                    )
                    PanelOutlinedButton(
                        text = "Обновить",
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { vm.call(server) { cascadeRefresh() } }
                                busy = false
                                onChanged()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = busy,
                        enabled = cascade?.configured == true
                    )
                }
                if (cascade?.configured == true) {
                    Spacer(Modifier.height(8.dp))
                    PanelOutlinedButton(
                        text = "Удалить каскад",
                        onClick = { confirmRemove = true },
                        modifier = Modifier.fillMaxWidth(),
                        color = PanelRed
                    )
                }
            }
        }

        if (editing) {
            CascadeDialog(
                interfaces = data.interfaces,
                onDismiss = { editing = false },
                onLoadConfig = { callback ->
                    scope.launch {
                        val conf = runCatching { vm.call(server) { cascadeConfig() } }.getOrNull()
                        callback(conf.orEmpty())
                    }
                },
                onSave = { conf, ifaces, extra ->
                    scope.launch {
                        runCatching { vm.call(server) { cascadeSetup(conf, ifaces, extra) } }
                        editing = false
                        onChanged()
                    }
                }
            )
        }

        if (confirmRemove) {
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                containerColor = PanelElevated,
                title = { Text("Удалить каскад?") },
                text = {
                    Text(
                        "Маршруты RU → EU будут сняты, туннель awg1 и клиент ru_host " +
                            "удалены на этом сервере. Вторую сторону почистите из её панели " +
                            "(или в 3.0 панель сделает это сама, если указать её адрес и пароль)."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { vm.call(server) { cascadeRemove() } }
                            confirmRemove = false
                            onChanged()
                        }
                    }) { Text("Удалить", color = PanelRed) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = false }) { Text("Отмена") }
                }
            )
        }
    }
}

@Composable
private fun CascadeDialog(
    interfaces: List<String>,
    onDismiss: () -> Unit,
    onLoadConfig: (((String) -> Unit)) -> Unit,
    onSave: (String, List<String>, String) -> Unit
) {
    var config by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onLoadConfig { content ->
            if (config.isBlank()) config = content
            loaded = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Конфиг каскада",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "Вставьте конфиг клиента с EU-сервера (WireGuard/AmneziaWG).",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(12.dp))

                PanelTextField(
                    value = config,
                    onValueChange = { config = it },
                    label = "Конфиг [Interface]/[Peer]",
                    singleLine = false,
                    minLines = 9,
                    placeholder = "[Interface]\nPrivateKey = …\n[Peer]\nPublicKey = …\nEndpoint = eu.example.com:51820"
                )

                Spacer(Modifier.height(12.dp))

                MultiChoiceChips(
                    title = "Клиентские интерфейсы",
                    subtitle = "Трафик с этих интерфейсов пойдёт через EU",
                    options = interfaces,
                    selected = selected,
                    onToggle = { iface ->
                        selected = if (iface in selected) selected - iface else selected + iface
                    }
                )

                Spacer(Modifier.height(12.dp))

                PanelTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    label = "Дополнительные RU-подсети",
                    placeholder = "192.168.0.0/16, 10.0.0.0/8"
                )

                if (!loaded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Загружаем текущий конфиг…",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = "Сохранить",
                        onClick = { onSave(config, selected.toList(), extra) },
                        modifier = Modifier.weight(1f),
                        enabled = config.isNotBlank() && selected.isNotEmpty()
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// GeoIP
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.geoIpItems(
    data: NetworkData,
    isAdmin: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    server: ServerProfile,
    vm: AppViewModel,
    onChanged: () -> Unit
) {
    item {
        val state = data.tools?.ruDrop
        var enabled by remember(state?.active) { mutableStateOf(state?.active ?: false) }
        var schedule by remember(state?.active) { mutableStateOf(if (state?.active == true) "weekly" else "weekly") }
        var ports by remember { mutableStateOf("") }
        var selected by remember(state?.ifaces) { mutableStateOf(state?.ifaces?.toSet() ?: emptySet()) }
        var busy by remember { mutableStateOf(false) }

        PanelCard {
            SectionHeader(
                title = "GeoIP-блокировка РФ",
                subtitle = "Не пускать клиентов на российские адреса"
            )

            Spacer(Modifier.height(10.dp))

            InfoRow(
                "Состояние",
                if (state?.active == true) "Активна" else "Выключена",
                valueColor = if (state?.active == true) PanelGreen else PanelMuted
            )
            InfoRow("Адресов в списке", state?.count?.toString().orEmpty())
            InfoRow("Режим", if (state?.allPorts == true) "Все порты" else "Выбранные порты")
            if (data.tools?.conflictIfaces?.isNotEmpty() == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Конфликт: GeoIP и каскад включены на ${data.tools.conflictIfaces.joinToString(", ")}. " +
                        "Оставьте что-то одно — иначе RU-адреса блокируются на каскаде.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelAmber
                )
            }

            if (isAdmin) {
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Включить GeoIP-блокировку",
                    subtitle = "Правила iptables + ipset ru_block",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )

                Spacer(Modifier.height(8.dp))

                PanelDropdown(
                    label = "Обновление списка",
                    options = listOf("Ежедневно", "Еженедельно"),
                    selected = if (schedule == "daily") "Ежедневно" else "Еженедельно",
                    onSelect = { schedule = if (it == "Ежедневно") "daily" else "weekly" }
                )

                Spacer(Modifier.height(10.dp))

                PanelTextField(
                    value = ports,
                    onValueChange = { ports = it },
                    label = "Только порты (необязательно)",
                    placeholder = "443, 51820 — пусто = все порты"
                )

                Spacer(Modifier.height(12.dp))

                MultiChoiceChips(
                    title = "Интерфейсы",
                    options = data.interfaces,
                    selected = selected,
                    onToggle = { iface ->
                        selected = if (iface in selected) selected - iface else selected + iface
                    }
                )

                Spacer(Modifier.height(14.dp))

                PanelButton(
                    text = applyLabel(enabled, state?.active == true, "GeoIP-блокировку"),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching {
                                vm.call(server) { setRuDrop(selected.toList(), schedule, enabled) }
                            }
                            busy = false
                            onChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = busy,
                    enabled = !enabled || selected.isNotEmpty()
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DNS
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.dnsItems(
    data: NetworkData,
    isAdmin: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    server: ServerProfile,
    vm: AppViewModel,
    onChanged: () -> Unit
) {
    item {
        val state = data.tools?.dns
        var enabled by remember(state?.active) { mutableStateOf(state?.active ?: false) }
        var target by remember(state?.target) { mutableStateOf(state?.target.orEmpty().ifBlank { "10.8.1.1:53" }) }
        var selected by remember(state?.ifaces) { mutableStateOf(state?.ifaces?.toSet() ?: emptySet()) }
        var busy by remember { mutableStateOf(false) }

        PanelCard {
            SectionHeader(
                title = "DNS-перехват",
                subtitle = "Отправлять DNS клиентов на AdGuard"
            )

            Spacer(Modifier.height(10.dp))

            InfoRow(
                "Состояние",
                if (state?.active == true) "Активен" else "Выключен",
                valueColor = if (state?.active == true) PanelGreen else PanelMuted
            )
            InfoRow("Цель", state?.target.orEmpty())

            if (isAdmin) {
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Включить DNS-перехват",
                    subtitle = "DNAT правил для порта 53",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )

                Spacer(Modifier.height(10.dp))

                PanelTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = "Адрес DNS",
                    placeholder = "10.8.1.1:53",
                    supportingText = "IP или IP:порт AdGuard Home"
                )

                Spacer(Modifier.height(12.dp))

                MultiChoiceChips(
                    title = "Интерфейсы",
                    options = data.interfaces,
                    selected = selected,
                    onToggle = { iface ->
                        selected = if (iface in selected) selected - iface else selected + iface
                    }
                )

                Spacer(Modifier.height(14.dp))

                PanelButton(
                    text = applyLabel(enabled, state?.active == true, "DNS-перехват"),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching {
                                vm.call(server) { setDns(selected.toList(), target, enabled) }
                            }
                            busy = false
                            onChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = busy,
                    enabled = !enabled || (selected.isNotEmpty() && target.isNotBlank())
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// WARP
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.warpItems(
    data: NetworkData,
    isAdmin: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    server: ServerProfile,
    vm: AppViewModel,
    onChanged: () -> Unit
) {
    item {
        val warp = data.warp
        var busy by remember { mutableStateOf(false) }
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }

        PanelCard {
            SectionHeader(
                title = "Cloudflare WARP",
                subtitle = "Выход в интернет через WARP для AdGuard"
            )

            Spacer(Modifier.height(10.dp))

            InfoRow(
                "Установлен",
                if (warp?.installed == true) "Да" else "Нет",
                valueColor = if (warp?.installed == true) PanelGreen else PanelMuted
            )
            InfoRow(
                "WARP",
                if (warp?.warpActive == true) "Активен" else "Выключен",
                valueColor = if (warp?.warpActive == true) PanelGreen else PanelMuted
            )
            InfoRow(
                "Xray",
                if (warp?.xrayActive == true) "Активен" else "Выключен",
                valueColor = if (warp?.xrayActive == true) PanelGreen else PanelMuted
            )
            InfoRow("Доменов", warp?.domains?.toString().orEmpty())
            InfoRow("SOCKS-порт", warp?.socksPort.orEmpty())
            if (warp?.warpStatus?.isNotBlank() == true) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PanelSurface,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        warp.warpStatus.take(500),
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (isAdmin) {
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "WARP / Xray",
                    subtitle = "Включение принимает TOS Cloudflare",
                    checked = warp?.active == true,
                    onCheckedChange = { value ->
                        busy = true
                        scope.launch {
                            runCatching { vm.call(server) { warpPower(value) } }
                            busy = false
                            onChanged()
                        }
                    },
                    enabled = !busy && warp?.installed == true
                )

                Spacer(Modifier.height(10.dp))

                PanelOutlinedButton(
                    text = "Проверить выходной IP",
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = runCatching { vm.call(server) { warpTest() } }.getOrNull()
                            testResult = when {
                                result == null -> "Не удалось выполнить проверку"
                                result.ok -> buildString {
                                    append(result.ip.ifBlank { "—" })
                                    if (result.org.isNotBlank()) append(" · ${result.org}")
                                    if (result.country.isNotBlank()) append(" · ${result.country}")
                                    if (result.city.isNotBlank()) append(" · ${result.city}")
                                }
                                else -> result.error.ifBlank { "WARP не ответил" }
                            }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = testing,
                    enabled = warp?.installed == true
                )

                testResult?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                }
            }
        }
    }

    item {
        PanelCard {
            SectionHeader("Как это работает", null)
            Spacer(Modifier.height(8.dp))
            Text(
                "WARP поднимает туннель Cloudflare и отдаёт его локальному Xray. " +
                    "AdGuard Home использует этот прокси как upstream, поэтому запросы уходят " +
                    "не с IP сервера. Включение и выключение не удаляет пакеты.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
        }
    }
}

/** Подпись кнопки применения: отражает, что именно изменится на сервере. */
private fun applyLabel(enabled: Boolean, active: Boolean, what: String): String = when {
    enabled == active -> "Применить"
    enabled -> "Включить $what"
    else -> "Выключить $what"
}

private fun String.toColorOrNull(): androidx.compose.ui.graphics.Color? = try {
    if (startsWith("#") && (length == 7 || length == 4)) {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(this))
    } else {
        null
    }
} catch (e: Exception) {
    null
}
