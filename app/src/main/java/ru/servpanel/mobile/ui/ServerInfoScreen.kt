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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.AwgConfig
import ru.servpanel.mobile.data.ServerInfo
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

@Composable
fun ServerInfoScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var info by remember(server.id) { mutableStateOf<ServerInfo?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var config by remember(server.id) { mutableStateOf<AwgConfig?>(null) }
    var configLoading by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (info == null) loading = true
        error = null
        try {
            info = vm.call(server) { serverInfo() }
        } catch (e: Exception) {
            if (info == null) error = e.message ?: "Не удалось получить информацию о сервере"
        } finally {
            loading = false
        }
    }

    if (loading && info == null) {
        LoadingState("Читаем информацию о сервере…")
        return
    }

    val current = info
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
                title = "Информация",
                subtitle = "Сервер, домен и конфигурация",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
        }

        item {
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PanelAccent.copy(alpha = 0.12f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = null,
                            tint = PanelAccent,
                            modifier = Modifier.padding(11.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.domain.ifBlank { server.name },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PanelText
                        )
                        Text(
                            current.ip,
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelMuted
                        )
                    }
                    Badge(
                        current.country.ifBlank { "—" },
                        if (current.country == "RU") PanelAmber else PanelGreen
                    )
                }

                Spacer(Modifier.height(14.dp))

                InfoRow("IP-адрес", current.ip)
                InfoRow("Домен", current.domain)
                InfoRow("Страна", current.country)
                InfoRow("Адрес панели", server.displayHost)

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Копировать IP",
                        onClick = { copyToClipboard(context, current.ip, "IP скопирован") },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ContentCopy,
                        enabled = current.ip.isNotBlank()
                    )
                    PanelOutlinedButton(
                        text = "Копировать домен",
                        onClick = { copyToClipboard(context, current.domain, "Домен скопирован") },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ContentCopy,
                        enabled = current.domain.isNotBlank()
                    )
                }
            }
        }

        current.cert?.let { cert ->
            item {
                PanelCard {
                    SectionHeader(
                        title = "Сертификат панели",
                        subtitle = cert.validTo.ifBlank { "Let's Encrypt" },
                        trailing = {
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
                    )
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Имя", cert.name)
                    InfoRow("Действует до", cert.validTo)
                    Spacer(Modifier.height(8.dp))
                    Text("Fullchain", style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                    Text(
                        cert.fullchain,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Private key", style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                    Text(
                        cert.privkey,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = PanelMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Пути можно передать в клиентские конфиги вручную.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Конфиг AmneziaWG",
                    subtitle = config?.file.orEmpty().ifBlank { "Серверный конфиг" },
                    trailing = {
                        IconButton(
                            onClick = {
                                configLoading = true
                                message = null
                                scope.launch {
                                    try {
                                        config = vm.call(server) { awgConfig() }
                                    } catch (e: Exception) {
                                        messageOk = false
                                        message = e.message ?: "Не удалось прочитать конфиг"
                                    } finally {
                                        configLoading = false
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Загрузить конфиг", tint = PanelMuted)
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))

                if (configLoading) {
                    LoadingState("Читаем конфиг…")
                } else {
                    val conf = config
                    if (conf == null) {
                        Text(
                            "Нажмите на карандаш, чтобы загрузить серверный конфиг.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelMuted
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PanelSurface,
                            border = BorderStroke(1.dp, PanelBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        ) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    conf.content,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelText
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelOutlinedButton(
                                text = "Поделиться",
                                onClick = { shareFile(context, "awg0.conf", conf.content) },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Share
                            )
                            PanelButton(
                                text = "Редактировать",
                                onClick = { editing = true },
                                modifier = Modifier.weight(1f),
                                enabled = auth.isAdmin,
                                icon = Icons.Filled.Save
                            )
                        }
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

    if (editing) {
        val conf = config
        if (conf != null) {
            ConfigEditorDialog(
                initial = conf.content,
                onDismiss = { editing = false },
                onSave = { content ->
                    scope.launch {
                        try {
                            vm.call(server) { saveAwgConfig(conf.file, content) }
                            messageOk = true
                            message = "Конфиг сохранён. Интерфейс может потребовать перезапуска."
                            editing = false
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось сохранить конфиг"
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ConfigEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }

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
                    "Редактирование конфига",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Spacer(Modifier.height(10.dp))
                PanelTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "awg0.conf",
                    singleLine = false,
                    minLines = 12
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = "Сохранить",
                        onClick = { onSave(text) },
                        modifier = Modifier.weight(1f),
                        enabled = text.isNotBlank()
                    )
                }
            }
        }
    }
}
