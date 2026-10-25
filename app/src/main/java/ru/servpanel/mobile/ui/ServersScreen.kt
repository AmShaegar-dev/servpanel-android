package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.net.ServerHealth
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBg
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    servers: List<ServerProfile>,
    health: Map<String, ServerHealth>,
    refreshing: Boolean,
    lastRefreshAt: Long?,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onRefreshAll: () -> Unit,
    onRefreshOne: (ServerProfile) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val onlineCount = servers.count { health[it.id]?.online == true }
    val clientsOnline = servers.sumOf { health[it.id]?.clientsOnline ?: 0 }

    LaunchedEffect(Unit) { onRefreshAll() }

    Scaffold(
        containerColor = PanelBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Servpanel",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PanelText
                        )
                        Text(
                            text = "Панели AmneziaWG на связи",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshAll) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelBg)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = PanelAccent,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SummaryCard(
                    serversCount = servers.size,
                    onlineCount = onlineCount,
                    clientsOnline = clientsOnline,
                    refreshing = refreshing,
                    lastRefreshAt = lastRefreshAt
                )
            }

            if (servers.isEmpty()) {
                item { EmptyServersCard(onAdd) }
            }

            items(items = servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    health = health[server.id],
                    onClick = { onOpen(server.id) },
                    onEdit = { onEdit(server.id) },
                    onDelete = { onDelete(server) },
                    onRefresh = { onRefreshOne(server) },
                    onOpenExternal = {
                        onOpenExternal(
                            server.url
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    serversCount: Int,
    onlineCount: Int,
    clientsOnline: Int,
    refreshing: Boolean,
    lastRefreshAt: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        border = BorderStroke(1.dp, PanelBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Обзор",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = PanelAccent
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = formatAgo(lastRefreshAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SummaryStat(
                    label = "серверов",
                    value = serversCount.toString(),
                    accent = PanelText,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(PanelBorder)
                )
                SummaryStat(
                    label = "онлайн",
                    value = onlineCount.toString(),
                    accent = if (onlineCount == serversCount && serversCount > 0) PanelGreen else PanelAmber,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(PanelBorder)
                )
                SummaryStat(
                    label = "клиентов",
                    value = clientsOnline.toString(),
                    accent = PanelAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmptyServersCard(onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PanelSurface),
        border = BorderStroke(1.dp, PanelBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = null,
                tint = PanelAccent,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Пока нет ни одного сервера",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PanelText
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Добавьте адрес панели Servpanel — приложение запомнит его " +
                    "и покажет состояние всех серверов на этом экране.",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Добавить сервер")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: ServerProfile,
    health: ServerHealth?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true }
                ),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = PanelSurface),
            border = BorderStroke(1.dp, PanelBorder)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerAvatar(server.name, server.color, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PanelText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = server.displayHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(health)
                }

                Spacer(Modifier.height(14.dp))

                when {
                    health == null -> Text(
                        text = "Проверяем соединение…",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )

                    health.needsLogin -> HintRow(
                        icon = Icons.Filled.Lock,
                        text = "Откройте панель и войдите — метрики появятся здесь"
                    )

                    health.online -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCell(
                                label = "CPU",
                                value = percentText(health.cpuPercent),
                                fraction = percentValue(health.cpuPercent),
                                color = PanelAccent,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCell(
                                label = "RAM",
                                value = percentText(health.ramPercent),
                                fraction = percentValue(health.ramPercent),
                                color = PanelAmber,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCell(
                                label = "Диск",
                                value = health.diskPercent ?: "—",
                                fraction = diskPercentValue(health.diskPercent),
                                color = PanelAccent,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCell(
                                label = "Клиенты",
                                value = "${health.clientsOnline ?: 0}/${health.clientsTotal ?: 0}",
                                fraction = health.clientsTotal?.takeIf { it > 0 }?.let {
                                    (health.clientsOnline ?: 0).toFloat() / it
                                },
                                color = PanelGreen,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = buildString {
                                health.uptime?.let { append("Аптайм ").append(it) }
                                health.load?.let {
                                    if (isNotEmpty()) append("  ·  ")
                                    append("LA ").append(it)
                                }
                                health.role?.let {
                                    if (isNotEmpty()) append("  ·  ")
                                    append(if (it == "viewer") "только просмотр" else "админ")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }

                    else -> HintRow(
                        icon = Icons.Filled.CloudOff,
                        text = health.error ?: "Сервер недоступен"
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PanelBorder.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Пинг: " + (health?.latencyMs?.let { "$it мс" } ?: "—"),
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Проверить") }
                    TextButton(onClick = onClick) { Text("Открыть") }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text("Открыть панель") },
                leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Изменить") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Открыть в браузере") },
                leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onOpenExternal()
                }
            )
            DropdownMenuItem(
                text = { Text("Удалить", color = PanelRed) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = PanelRed) },
                onClick = {
                    menuOpen = false
                    confirmDelete = true
                }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PanelElevated,
            title = { Text("Удалить сервер?") },
            text = {
                Text(
                    "«${server.name}» будет удалён из приложения. " +
                        "Сама панель на сервере продолжит работать."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Удалить", color = PanelRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}
