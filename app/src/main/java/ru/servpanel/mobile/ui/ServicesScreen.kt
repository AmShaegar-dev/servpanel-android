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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ServiceInfo
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

@Composable
fun ServicesScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var services by remember(server.id) { mutableStateOf<List<ServiceInfo>?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var restarting by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmTarget by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (services == null) loading = true
        error = null
        try {
            services = vm.call(server) { services() }
        } catch (e: Exception) {
            if (services == null) error = e.message ?: "Не удалось загрузить службы"
        } finally {
            loading = false
        }
    }

    if (loading && services == null) {
        LoadingState("Читаем состояние служб…")
        return
    }

    val list = services
    if (list == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { reload++ })
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ScreenHeader(
                title = "Службы",
                subtitle = "Активны ${list.count { it.running }} из ${list.size}",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = PanelGreen)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.name }) { service ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PanelSurface,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(if (service.running) PanelGreen else PanelRed, 9.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                service.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = PanelText
                            )
                            Text(
                                service.desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted
                            )
                            Text(
                                buildString {
                                    append(if (service.running) "работает" else "остановлена")
                                    append(if (service.enabled) " · автозапуск" else " · без автозапуска")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (service.running) PanelGreen else PanelRed
                            )
                        }
                        if (auth.isAdmin) {
                            IconButton(
                                onClick = { confirmTarget = service.name },
                                enabled = service.name !in restarting
                            ) {
                                Icon(
                                    Icons.Filled.RestartAlt,
                                    contentDescription = "Перезапустить",
                                    tint = if (service.name in restarting) PanelMuted else PanelAccent
                                )
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PanelSurface,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Settings, contentDescription = null, tint = PanelMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Перезапуск может прервать активные подключения на несколько секунд.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                }
            }
        }
    }

    confirmTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            containerColor = PanelElevated,
            title = { Text("Перезапустить $name?") },
            text = { Text("Служба будет перезапущена через systemctl. Подключения могут прерваться на несколько секунд.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = name
                    confirmTarget = null
                    restarting = restarting + target
                    scope.launch {
                        try {
                            vm.call(server) { restartService(target) }
                            message = "$target перезапущена"
                        } catch (e: Exception) {
                            message = e.message ?: "Не удалось перезапустить $target"
                        } finally {
                            restarting = restarting - target
                            reload++
                        }
                    }
                }) { Text("Перезапустить", color = PanelAccent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Отмена") }
            }
        )
    }
}
