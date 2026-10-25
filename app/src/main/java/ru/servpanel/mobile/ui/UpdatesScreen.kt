package ru.servpanel.mobile.ui

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.GitCheck
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.UpdateInfo
import ru.servpanel.mobile.data.UpdateStatus
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
fun UpdatesScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var info by remember(server.id) { mutableStateOf<UpdateInfo?>(null) }
    var status by remember(server.id) { mutableStateOf<UpdateStatus?>(null) }
    var git by remember(server.id) { mutableStateOf<GitCheck?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var rollbackTarget by remember { mutableStateOf<String?>(null) }
    var confirmForce by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (info == null) loading = true
        error = null
        try {
            val loaded = vm.call(server) { updateInfo() }
            info = loaded
            status = loaded.status
        } catch (e: Exception) {
            if (info == null) error = e.message ?: "Не удалось загрузить состояние обновлений"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(server.id, status?.running) {
        if (status?.running != true) return@LaunchedEffect
        while (isActive) {
            delay(4000)
            val next = runCatching { vm.call(server) { updateStatus() } }.getOrNull()
            if (next != null) {
                status = next
                if (!next.running) {
                    reload++
                    break
                }
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        messageOk = false
                        message = "Не удалось прочитать архив"
                    } else if (bytes.size > 10 * 1024 * 1024) {
                        messageOk = false
                        message = "Архив больше 10 МБ"
                    } else {
                        val name = "servpanel-release.tar.gz"
                        vm.call(server) {
                            updateUpload(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                        messageOk = true
                        message = "Обновление запущено"
                        reload++
                    }
                } catch (e: Exception) {
                    messageOk = false
                    message = e.message ?: "Не удалось загрузить архив"
                } finally {
                    busy = false
                }
            }
        }
    }

    if (loading && info == null) {
        LoadingState("Читаем состояние обновлений…")
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
                title = "Обновления",
                subtitle = "Версия ${current.version}",
                trailing = {
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
        }

        val running = status?.running == true
        if (running) {
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = PanelAccent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Обновление выполняется",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = PanelText
                            )
                            Text(
                                status?.message.orEmpty().ifBlank { status?.step.orEmpty() },
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted
                            )
                        }
                    }
                    val log = status?.log.orEmpty().takeLast(12)
                    if (log.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PanelSurface,
                            border = BorderStroke(1.dp, PanelBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                        ) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                log.forEach { line ->
                                    Text(
                                        line,
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

        item {
            PanelCard {
                SectionHeader(
                    title = "GitHub",
                    subtitle = if (current.git.available) {
                        "Ветка ${current.git.branch} · ${current.git.head}"
                    } else {
                        "Репозиторий не обнаружен"
                    },
                    trailing = {
                        Icon(Icons.Filled.Code, contentDescription = null, tint = PanelMuted)
                    }
                )

                Spacer(Modifier.height(10.dp))

                InfoRow("Версия", current.version)
                InfoRow("Коммит", current.git.head)
                InfoRow("Описание", current.git.subject)
                InfoRow("Remote", current.git.remote)
                InfoRow(
                    "Отставание",
                    current.git.behind?.let { "$it коммитов" } ?: "—"
                )
                InfoRow(
                    "Локальные изменения",
                    if (current.git.dirty.isEmpty()) "нет" else "${current.git.dirty.size} файлов",
                    valueColor = if (current.git.dirty.isEmpty()) PanelGreen else PanelAmber
                )

                git?.let { check ->
                    Spacer(Modifier.height(10.dp))
                    PanelDivider()
                    Spacer(Modifier.height(10.dp))
                    if (!check.ok) {
                        Text(
                            check.error.ifBlank { "Не удалось проверить обновления" },
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelRed
                        )
                    } else {
                        InfoRow(
                            "Доступно",
                            if (check.upToDate) "Актуальная версия" else "новый коммит",
                            valueColor = if (check.upToDate) PanelGreen else PanelAmber
                        )
                        if (check.commit.isNotBlank()) {
                            InfoRow("Последний", check.commit)
                        }
                    }
                }

                if (auth.isAdmin) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelOutlinedButton(
                            text = "Проверить",
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        git = vm.call(server) { checkGit() }
                                    } catch (e: Exception) {
                                        messageOk = false
                                        message = e.message ?: "Ошибка проверки"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            loading = busy,
                            enabled = current.git.available
                        )
                        PanelButton(
                            text = "Обновить",
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    try {
                                        vm.call(server) { updateFromGit(false) }
                                        messageOk = true
                                        message = "Обновление запущено"
                                        reload++
                                    } catch (e: Exception) {
                                        messageOk = false
                                        message = e.message ?: "Не удалось запустить обновление"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            loading = busy,
                            enabled = current.git.available
                        )
                    }

                    if (current.git.dirty.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        PanelOutlinedButton(
                            text = "Принудительно (сброс кода)",
                            onClick = { confirmForce = true },
                            modifier = Modifier.fillMaxWidth(),
                            color = PanelAmber
                        )
                    }
                }
            }
        }

        if (auth.isAdmin) {
            item {
                PanelCard {
                    SectionHeader("Другие способы", "Архив релиза или прямой URL")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelOutlinedButton(
                            text = "По URL",
                            onClick = { showUrl = true },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.Link,
                            enabled = !running
                        )
                        PanelOutlinedButton(
                            text = "Архив .tar.gz",
                            onClick = {
                                uploadLauncher.launch(
                                    arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*")
                                )
                            },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.CloudUpload,
                            enabled = !running
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Архив до 10 МБ. Данные панели и конфиги AWG при обновлении не затрагиваются.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Резервные копии перед обновлением",
                    subtitle = "${current.backups.size} архивов"
                )
                Spacer(Modifier.height(8.dp))
                if (current.backups.isEmpty()) {
                    Text(
                        "Резервных копий нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                } else {
                    current.backups.forEach { backup ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = PanelMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    backup.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelText
                                )
                                Text(
                                    "${formatBytes(backup.size)} · ${formatMtimeShort(backup.mtime)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                            if (auth.isAdmin) {
                                TextButton(onClick = { rollbackTarget = backup.path }) {
                                    Text("Откатить", color = PanelAmber)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (current.logTail.isNotBlank()) {
            item {
                PanelCard {
                    SectionHeader("Журнал обновлений", "Последние строки")
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PanelSurface,
                        border = BorderStroke(1.dp, PanelBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Text(
                                current.logTail.takeLast(4000),
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted
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

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = PanelMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Панель может перезапуститься. Подключение восстановится автоматически.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }
    }

    if (showUrl) {
        UrlUpdateDialog(
            onDismiss = { showUrl = false },
            onSubmit = { url ->
                busy = true
                message = null
                scope.launch {
                    try {
                        vm.call(server) { updateFromUrl(url) }
                        messageOk = true
                        message = "Обновление по URL запущено"
                        showUrl = false
                        reload++
                    } catch (e: Exception) {
                        messageOk = false
                        message = e.message ?: "Не удалось запустить обновление"
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }

    rollbackTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { rollbackTarget = null },
            containerColor = PanelElevated,
            title = { Text("Откатить панель?") },
            text = { Text("Текущий код будет заменён резервной копией. Данные панели не изменятся.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            vm.call(server) { updateRollback(path) }
                            messageOk = true
                            message = "Откат запущен"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось запустить откат"
                        }
                        rollbackTarget = null
                    }
                }) { Text("Откатить", color = PanelAmber) }
            },
            dismissButton = {
                TextButton(onClick = { rollbackTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (confirmForce) {
        AlertDialog(
            onDismissRequest = { confirmForce = false },
            containerColor = PanelElevated,
            title = { Text("Принудительное обновление?") },
            text = {
                Text(
                    "Локальные изменения кода будут сброшены к origin/main. " +
                        "Данные панели и конфиги AWG не входят в git и сохранятся.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForce = false
                    busy = true
                    scope.launch {
                        try {
                            vm.call(server) { updateFromGit(true) }
                            messageOk = true
                            message = "Принудительное обновление запущено"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось запустить обновление"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Обновить", color = PanelAmber) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForce = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun UrlUpdateDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "Обновление по URL",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Spacer(Modifier.height(12.dp))
                PanelTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = "Ссылка на .tar.gz",
                    placeholder = "https://example.com/servpanel.tar.gz"
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Отмена",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PanelButton(
                        text = "Запустить",
                        onClick = { onSubmit(url) },
                        modifier = Modifier.weight(1f),
                        enabled = url.startsWith("http://") || url.startsWith("https://")
                    )
                }
            }
        }
    }
}

private fun formatMtimeShort(raw: String): String {
    if (raw.isBlank()) return "—"
    return raw.replace("T", " ").take(16)
}
