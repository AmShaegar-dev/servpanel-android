package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.AuthStatus
import ru.servpanel.mobile.WorkspaceTab
import ru.servpanel.mobile.data.CascadeStatus
import ru.servpanel.mobile.data.ClientInfo
import ru.servpanel.mobile.data.HistoryData
import ru.servpanel.mobile.data.SecurityStatus
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ServiceInfo
import ru.servpanel.mobile.data.SysInfo
import ru.servpanel.mobile.data.ToolsStatus
import ru.servpanel.mobile.net.DashboardCache
import ru.servpanel.mobile.net.DashboardSnapshot
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText
import ru.servpanel.mobile.ui.theme.PanelViolet
import kotlin.math.max

private data class OverviewData(
    val sys: SysInfo,
    val clients: List<ClientInfo>,
    val services: List<ServiceInfo> = emptyList(),
    val security: SecurityStatus? = null,
    val cascade: CascadeStatus? = null,
    val tools: ToolsStatus? = null
)

private val ranges = listOf("5m" to "5 мин", "1h" to "1 ч", "3h" to "3 ч", "24h" to "24 ч", "7d" to "7 дн")

@Composable
fun OverviewScreen(vm: AppViewModel, server: ServerProfile) {
    var data by remember(server.id) { mutableStateOf<OverviewData?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var range by remember(server.id) { mutableStateOf("1h") }
    var autoTick by remember(server.id) { mutableIntStateOf(0) }
    // Тяжёлые разделы (службы, защита, каскад, инструменты) обновляются
    // реже: каждый такой запрос — это несколько systemctl/ipset на сервере.
    var slowTick by remember(server.id) { mutableIntStateOf(0) }
    var restarting by remember { mutableStateOf<Set<String>>(emptySet()) }
    // История трафика грузится отдельно: переключение периода не должно
    // перезапрашивать клиентов, службы и статус защиты.
    var history by remember(server.id) { mutableStateOf<HistoryData?>(null) }
    var historyLoading by remember(server.id) { mutableStateOf(true) }
    var fromCache by remember(server.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val cache = remember(context) { DashboardCache(context) }

    LaunchedEffect(server.id) {
        while (isActive) {
            delay(20_000)
            autoTick++
        }
    }

    LaunchedEffect(server.id) {
        while (isActive) {
            delay(90_000)
            slowTick++
        }
    }

    // Снимок с прошлого раза показываем сразу, не дожидаясь сети.
    LaunchedEffect(server.id) {
        if (data != null) return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) { cache.load(server.id) } ?: return@LaunchedEffect
        if (data == null) {
            data = OverviewData(
                sys = cached.sys,
                clients = cached.clients,
                services = cached.services,
                security = cached.security
            )
            fromCache = true
            loading = false
        }
    }

    // Фаза 1: то, без чего экран бесполезен — нагрузка и клиенты.
    LaunchedEffect(
        server.id,
        server.apiUrl,
        server.metricsPort,
        server.metricsToken,
        vm.refreshTick,
        autoTick
    ) {
        if (data == null) loading = true
        val sys = runCatching {
            withTimeout(12_000) {
                // Если задан отдельный порт метрик — берём цифры оттуда,
                // иначе используем обычный /api/sysinfo панели.
                val fromMetrics = if (server.metricsPort > 0) {
                    runCatching { vm.call(server) { metrics() } }
                        .onFailure {
                            android.util.Log.w(
                                "ServpanelOverview",
                                "Метрики (порт ${server.metricsPort}) недоступны: ${it.message}",
                                it
                            )
                        }
                        .getOrNull()
                } else {
                    null
                }
                fromMetrics?.toSysInfo() ?: vm.call(server) { sysinfo() }
            }
        }.onFailure { android.util.Log.w("ServpanelOverview", "sysinfo: ${it.message}") }.getOrNull()

        val clients = runCatching {
            withTimeout(10_000) { vm.call(server) { clients() } }
        }.onFailure { android.util.Log.w("ServpanelOverview", "clients: ${it.message}") }.getOrNull()

        if (sys != null) {
            val base = data
            error = null
            data = OverviewData(
                sys = sys,
                clients = clients ?: base?.clients ?: emptyList(),
                services = base?.services ?: emptyList(),
                security = base?.security,
                cascade = base?.cascade,
                tools = base?.tools
            )
            fromCache = false
            withContext(Dispatchers.IO) {
                cache.save(
                    server.id,
                    DashboardSnapshot(
                        at = System.currentTimeMillis(),
                        sys = sys,
                        clients = clients ?: base?.clients ?: emptyList(),
                        services = base?.services ?: emptyList(),
                        security = base?.security
                    )
                )
            }
        } else if (data == null) {
            error = "Панель не ответила — проверьте связь"
        }
        loading = false
    }

    // Фаза 2: тяжёлые данные догружаются следом и не задерживают экран.
    LaunchedEffect(server.id, vm.refreshTick, slowTick) {
        val base = data ?: return@LaunchedEffect
        val services = runCatching {
            withTimeout(15_000) { vm.call(server) { services() } }
        }.getOrNull()
        val security = runCatching {
            withTimeout(15_000) { vm.call(server) { securityStatus() } }
        }.getOrNull()
        val cascade = runCatching {
            withTimeout(12_000) { vm.call(server) { cascadeStatus() } }
        }.getOrNull()
        val tools = runCatching {
            withTimeout(12_000) { vm.call(server) { toolsStatus() } }
        }.getOrNull()

        val merged = OverviewData(
            sys = base.sys,
            clients = base.clients,
            services = services ?: base.services,
            security = security ?: base.security,
            cascade = cascade ?: base.cascade,
            tools = tools ?: base.tools
        )
        data = merged
        if (services != null || security != null) {
            withContext(Dispatchers.IO) {
                cache.save(
                    server.id,
                    DashboardSnapshot(
                        at = System.currentTimeMillis(),
                        sys = merged.sys,
                        clients = merged.clients,
                        services = merged.services,
                        security = merged.security
                    )
                )
            }
        }
    }

    // История трафика живёт отдельно от остального «Обзора»: смена периода
    // перезапрашивает только график, а не клиентов и службы.
    LaunchedEffect(
        server.id,
        server.apiUrl,
        server.metricsPort,
        server.metricsToken,
        vm.refreshTick,
        range,
        autoTick
    ) {
        if (history?.range != range) historyLoading = true
        try {
            history = withTimeout(12_000) { vm.call(server) { history(range, "global") } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ServpanelOverview", "Не удалось загрузить историю трафика", e)
            if (history?.range != range) history = HistoryData(range = range)
        } finally {
            historyLoading = false
        }
    }

    if (loading && data == null) {
        LoadingState("Читаем метрики сервера…")
        return
    }

    if (data == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { vm.refreshWorkspace() })
        }
        return
    }

    val current = data!!

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StatusOverviewCard(current, server) }

        if (!server.sshTunnel &&
            vm.authOf(server.id).status == AuthStatus.LoggedIn &&
            vm.authOf(server.id).isAdmin
        ) {
            item { SshNudgeCard(vm, server) }
        }

        item {
            PanelCard {
                SectionHeader("Нагрузка", if (fromCache) "Из кэша · обновляем…" else "Текущее состояние сервера")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetricCell(
                        label = "CPU",
                        value = "${current.sys.cpu.toInt()}%",
                        fraction = current.sys.cpu / 100f,
                        color = PanelAccent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "RAM",
                        value = "${current.sys.ram.toInt()}%",
                        fraction = current.sys.ram / 100f,
                        color = PanelAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "Диск",
                        value = current.sys.diskPercent.ifBlank { "—" },
                        fraction = diskPercentValue(current.sys.diskPercent),
                        color = PanelViolet,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                PanelDivider()
                Spacer(Modifier.height(10.dp))
                InfoRow("Диск", "${formatBytes(current.sys.diskUsed)} из ${formatBytes(current.sys.diskTotal)}")
                InfoRow("Свободно", formatBytes(current.sys.diskFree))
            }
        }

        item {
            val chart = history?.takeIf { it.range == range }
            val periodLabel = ranges.firstOrNull { it.first == range }?.second ?: range
            // Как в панели: сумма rx/tx по всем клиентам — «сколько всего прошло».
            val totalRx = current.clients.sumOf { it.rx }
            val totalTx = current.clients.sumOf { it.tx }
            PanelCard {
                SectionHeader(
                    title = "Трафик",
                    subtitle = "Суммарно по всем клиентам · $periodLabel",
                    trailing = {
                        IconButton(onClick = { vm.refreshWorkspace() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryStat(
                        label = "принято за всё время",
                        value = "↓ ${formatBytes(totalRx)}",
                        accent = PanelAccent,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStat(
                        label = "отдано за всё время",
                        value = "↑ ${formatBytes(totalTx)}",
                        accent = PanelViolet,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                PanelDivider()
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ranges.forEach { (key, label) ->
                        RangeChip(
                            label = label,
                            selected = range == key,
                            onClick = { if (range != key) range = key }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    chart == null || historyLoading -> ChartLoading()
                    else -> TrafficChart(chart)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(PanelAccent)
                    Text(
                        "RX ${formatSpeed(chart?.rx?.lastOrNull() ?: 0L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.width(14.dp))
                    LegendDot(PanelViolet)
                    Text(
                        "TX ${formatSpeed(chart?.tx?.lastOrNull() ?: 0L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "пик ${formatSpeed(max(chart?.rx?.maxOrNull() ?: 0L, chart?.tx?.maxOrNull() ?: 0L))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
            }
        }

        item {
            QuickClientsCard(
                clients = current.clients,
                onAll = { vm.selectTab(WorkspaceTab.Clients) }
            )
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Службы",
                    subtitle = "Системные сервисы панели"
                )
                Spacer(Modifier.height(8.dp))
                if (current.services.isEmpty()) {
                    Text(
                        "Читаем службы…",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                current.services.forEach { service ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(if (service.running) PanelGreen else PanelRed, 8.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                service.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PanelText
                            )
                            Text(
                                service.desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted
                            )
                        }
                        if (restarting.contains(service.name)) {
                            Text("…", color = PanelMuted)
                        } else {
                            IconButton(
                                onClick = {
                                    restarting = restarting + service.name
                                    scope.launch {
                                        runCatching {
                                            vm.call(server) { restartService(service.name) }
                                        }
                                        restarting = restarting - service.name
                                        vm.refreshWorkspace()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Bolt,
                                    contentDescription = "Перезапустить",
                                    tint = PanelMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        current.security?.let { security ->
            item {
                SecurityOverviewCard(
                    security = security,
                    cascade = current.cascade,
                    tools = current.tools,
                    onOpen = { vm.selectTab(WorkspaceTab.Security) }
                )
            }
        }
    }
}

@Composable
private fun SshNudgeCard(vm: AppViewModel, server: ServerProfile) {
    val state = vm.sshSetup

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelAccent.copy(alpha = 0.12f),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = PanelAccent,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Доступ по SSH через панель",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    text = when {
                        state.running -> state.step.ifBlank { "Настраиваем…" }
                        state.error != null -> state.error
                        else -> "Панель сама поставит ключ и закроет порт — " +
                            "без команд на сервере."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) PanelRed else PanelMuted
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        PanelButton(
            text = if (state.running) "Настраиваем…" else "Настроить через панель",
            onClick = { vm.setupSshAccess(server) },
            enabled = !state.running,
            loading = state.running,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Filled.Shield
        )
    }
}

@Composable
private fun StatusOverviewCard(data: OverviewData, server: ServerProfile) {
    val security = data.security
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = PanelGreen.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, PanelGreen.copy(alpha = 0.3f)),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Dns, contentDescription = null, tint = PanelGreen, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Сервер на связи",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "Аптайм ${data.sys.uptime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
            if (security != null) {
                Badge(
                    if (security.isAdmin) "Админ" else "Просмотр",
                    if (security.isAdmin) PanelAccent else PanelAmber
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        KeyValueGrid(
            rows = listOf(
                "Пользователь" to (security?.user ?: "…"),
                "Load Average" to data.sys.load,
                "Ядра" to data.sys.cores.toString(),
                "Node.js" to (security?.node ?: "…"),
                "Порт панели" to (security?.panelPublicPort?.ifBlank { security.panelPort } ?: "…"),
                "HTTPS" to when {
                    security == null -> "…"
                    security.httpsOn && security.httpsDomain.isNotBlank() -> security.httpsDomain
                    security.httpsOn -> "включён"
                    else -> "нет"
                },
                "Метрики" to if (server.metricsPort > 0) "порт ${server.metricsPort}" else "через API"
            )
        )
    }
}

@Composable
private fun QuickClientsCard(clients: List<ClientInfo>, onAll: () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Клиенты",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "Активны ${clients.count { it.active }} из ${clients.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            }
            Text(
                "Все",
                style = MaterialTheme.typography.labelMedium,
                color = PanelAccent,
                modifier = Modifier.clickable(onClick = onAll)
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(8.dp))

        if (clients.isEmpty()) {
            Text(
                "Клиентов пока нет",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        } else {
            clients.take(4).forEach { client ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(if (client.active) PanelGreen else PanelMuted, 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            client.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PanelText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            client.ip.ifBlank { client.status },
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                    Text(
                        "↓${formatBytes(client.rx)}  ↑${formatBytes(client.tx)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityOverviewCard(
    security: SecurityStatus,
    cascade: CascadeStatus?,
    tools: ToolsStatus?,
    onOpen: () -> Unit
) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Защита",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PanelText
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Подробнее",
                style = MaterialTheme.typography.labelMedium,
                color = PanelAccent,
                modifier = Modifier.clickable(onClick = onOpen)
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PanelAccent, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.height(10.dp))

        InfoRow(
            "HTTPS",
            if (security.httpsOn) "Включён" else "Не настроен",
            valueColor = if (security.httpsOn) PanelGreen else PanelAmber
        )
        InfoRow(
            "2FA",
            if (security.totpEnabled) "Включена" else "Выключена",
            valueColor = if (security.totpEnabled) PanelGreen else PanelMuted
        )
        InfoRow(
            "Пароль",
            if (security.defaultPassword) "Стандартный — смените" else "Изменён",
            valueColor = if (security.defaultPassword) PanelRed else PanelGreen
        )
        if (cascade != null) {
            InfoRow(
                "Каскад",
                cascade.text.ifBlank { if (cascade.configured) "Настроен" else "Не настроен" },
                valueColor = cascade.color.toColorOrNull() ?: PanelMuted
            )
        }
        if (tools != null) {
            InfoRow(
                "GeoIP-блокировка РФ",
                if (tools.ruDrop.active) "Активна (${tools.ruDrop.count} адресов)" else "Выключена",
                valueColor = if (tools.ruDrop.active) PanelGreen else PanelMuted
            )
            InfoRow(
                "DNS-перехват",
                if (tools.dns.active) tools.dns.target.ifBlank { "Активен" } else "Выключен",
                valueColor = if (tools.dns.active) PanelGreen else PanelMuted
            )
        }

        if (security.defaultPassword) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = PanelAmber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Смените стандартный пароль администратора",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelAmber
                )
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) PanelAccent.copy(alpha = 0.16f) else PanelSurface,
        border = BorderStroke(
            1.dp,
            if (selected) PanelAccent.copy(alpha = 0.45f) else PanelBorder
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) PanelAccent else PanelMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
    Spacer(Modifier.width(5.dp))
}

@Composable
private fun ChartLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = PanelAccent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun TrafficChart(history: HistoryData) {
    val maxValue = max(
        history.rx.maxOrNull() ?: 0L,
        history.tx.maxOrNull() ?: 0L
    ).coerceAtLeast(1L)

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val width = size.width
            val height = size.height

            for (i in 0..3) {
                val y = height * i / 3f
                drawLine(
                    color = PanelBorder.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            fun build(values: List<Long>): Path? {
                if (values.size < 2) return null
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = width * index / (values.size - 1).toFloat()
                    val y = height - (value.toFloat() / maxValue) * height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }

            build(history.rx)?.let {
                drawPath(it, color = PanelAccent, style = Stroke(width = 2.2f))
            }
            build(history.tx)?.let {
                drawPath(it, color = PanelViolet, style = Stroke(width = 2.2f))
            }
        }

        if (history.labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    history.labels.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
                Spacer(Modifier.weight(1f))
                Text(
                    history.labels.last(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = PanelMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Нет данных за выбранный период",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }
    }
}

private fun String.toColorOrNull(): Color? = try {
    if (startsWith("#") && (length == 7 || length == 4)) Color(android.graphics.Color.parseColor(this)) else null
} catch (e: Exception) {
    null
}
