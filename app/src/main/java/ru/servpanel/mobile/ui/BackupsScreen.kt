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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.BackupAuto
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupsScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = vm.authOf(server.id)

    var data by remember(server.id) { mutableStateOf<BackupAuto?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showRestore by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (data == null) loading = true
        error = null
        try {
            data = vm.call(server) { backupAuto() }
        } catch (e: Exception) {
            if (data == null) error = e.message ?: "Не удалось загрузить настройки бэкапов"
        } finally {
            loading = false
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
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
                        message = "Не удалось прочитать файл"
                    } else {
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        vm.call(server) { restoreBackup(encoded) }
                        messageOk = true
                        message = "Архив восстановлен. Перезапустите панель при необходимости."
                        reload++
                    }
                } catch (e: Exception) {
                    messageOk = false
                    message = e.message ?: "Ошибка восстановления"
                } finally {
                    busy = false
                    showRestore = false
                }
            }
        }
    }

    if (loading && data == null) {
        LoadingState("Читаем настройки бэкапов…")
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
                title = "Бэкапы",
                subtitle = "Архивы конфигов и автобэкап"
            )
        }

        item {
            AutoBackupCard(
                data = current,
                isAdmin = auth.isAdmin,
                busy = busy,
                onSave = { enabled, schedule, time, keep, encrypt ->
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            vm.call(server) { saveBackupAuto(enabled, schedule, time, keep, encrypt) }
                            messageOk = true
                            message = "Настройки автобэкапа сохранены"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось сохранить"
                        } finally {
                            busy = false
                        }
                    }
                },
                onRun = {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            val result = vm.call(server) { runBackup() }
                            messageOk = true
                            message = result.output.ifBlank { "Бэкап создан" }
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Ошибка запуска бэкапа"
                        } finally {
                            busy = false
                        }
                    }
                }
            )
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Резервные копии",
                    subtitle = current.dir.ifBlank { "Каталог архивов" }
                )

                Spacer(Modifier.height(10.dp))

                if (current.archives.isEmpty()) {
                    Text(
                        "Архивов пока нет. Включите автобэкап или создайте копию вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                } else {
                    current.archives.forEach { archive ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Backup,
                                contentDescription = null,
                                tint = PanelAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    archive.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelText
                                )
                                Text(
                                    "${formatBytes(archive.size)} · ${formatMtime(archive.mtime)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                            TextButton(
                                onClick = {
                                    busy = true
                                    message = null
                                    scope.launch {
                                        try {
                                            val bytes = vm.call(server) { backupArchive(archive.name) }
                                            shareBytes(context, archive.name, bytes, "application/gzip")
                                        } catch (e: Exception) {
                                            messageOk = false
                                            message = e.message ?: "Не удалось скачать архив"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            ) { Text("Скачать", color = PanelAccent) }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Полный бэкап",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val bytes = vm.call(server) { fullBackup() }
                                    shareBytes(context, "awg-full-backup.tar.gz", bytes, "application/gzip")
                                } catch (e: Exception) {
                                    messageOk = false
                                    message = e.message ?: "Не удалось создать архив"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.CloudDownload,
                        enabled = auth.isAdmin && !busy
                    )
                    PanelOutlinedButton(
                        text = "Восстановить",
                        onClick = { showRestore = true },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Restore,
                        enabled = auth.isAdmin && !busy
                    )
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Пароль архивов",
                    subtitle = if (current.passwordSet) "Пароль задан" else "Пароль ещё не задан",
                    trailing = {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = PanelMuted)
                    }
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "Пароль используется для шифрования архивов. Храните его отдельно: " +
                        "без него зашифрованную копию не восстановить.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Показать пароль",
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    password = vm.call(server) { backupPassword() }
                                    showPassword = true
                                } catch (e: Exception) {
                                    messageOk = false
                                    message = e.message ?: "Не удалось получить пароль"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = auth.isAdmin && !busy
                    )
                    PanelOutlinedButton(
                        text = "Задать новый",
                        onClick = { showPassword = true },
                        modifier = Modifier.weight(1f),
                        enabled = auth.isAdmin && !busy
                    )
                }
            }
        }

        message?.let { text ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (messageOk) PanelGreen.copy(alpha = 0.12f) else PanelRed.copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        (if (messageOk) PanelGreen else PanelRed).copy(alpha = 0.35f)
                    ),
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

    if (showPassword) {
        BackupPasswordDialog(
            initial = password,
            onDismiss = {
                showPassword = false
                password = ""
            },
            onSave = { newPassword ->
                busy = true
                scope.launch {
                    try {
                        vm.call(server) { setBackupPassword(newPassword) }
                        messageOk = true
                        message = "Пароль архивов обновлён"
                        showPassword = false
                        password = ""
                        reload++
                    } catch (e: Exception) {
                        messageOk = false
                        message = e.message ?: "Не удалось сохранить пароль"
                    } finally {
                        busy = false
                    }
                }
            }
        )
    }

    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            containerColor = PanelElevated,
            title = { Text("Восстановить из архива?") },
            text = {
                Text(
                    "Текущие конфиги будут заменены содержимым архива. " +
                        "Панель может потребовать перезапуска. Продолжить?",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestore = false
                    restoreLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"))
                }) { Text("Выбрать файл") }
            },
            dismissButton = {
                TextButton(onClick = { showRestore = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun AutoBackupCard(
    data: BackupAuto,
    isAdmin: Boolean,
    busy: Boolean,
    onSave: (Boolean, String, String, Int, Boolean) -> Unit,
    onRun: () -> Unit
) {
    var enabled by remember(data.enabled) { mutableStateOf(data.enabled) }
    var schedule by remember(data.schedule) { mutableStateOf(data.schedule) }
    var time by remember(data.time) { mutableStateOf(data.time) }
    var keep by remember(data.keep) { mutableStateOf(data.keep.toString()) }
    var encrypt by remember(data.encrypt) { mutableStateOf(data.encrypt) }

    PanelCard {
        SectionHeader(
            title = "Автобэкап",
            subtitle = if (data.timerActive) "Таймер активен" else "Таймер выключен",
            trailing = {
                Badge(
                    if (data.enabled) "Включён" else "Выключен",
                    if (data.enabled) PanelGreen else PanelMuted
                )
            }
        )

        Spacer(Modifier.height(8.dp))

        ToggleRow(
            title = "Включить автобэкап",
            subtitle = "systemd-таймер на сервере",
            checked = enabled,
            onCheckedChange = { enabled = it },
            enabled = isAdmin && !busy
        )

        Spacer(Modifier.height(8.dp))

        PanelDropdown(
            label = "Расписание",
            options = listOf("Ежедневно", "Еженедельно"),
            selected = if (schedule == "weekly") "Еженедельно" else "Ежедневно",
            onSelect = { schedule = if (it == "Еженедельно") "weekly" else "daily" }
        )

        Spacer(Modifier.height(10.dp))

        PanelTextField(
            value = time,
            onValueChange = { time = it.take(5) },
            label = "Время (ЧЧ:ММ)",
            placeholder = "04:30",
            enabled = isAdmin && !busy
        )

        Spacer(Modifier.height(10.dp))

        PanelTextField(
            value = keep,
            onValueChange = { keep = it.filter(Char::isDigit).take(2) },
            label = "Хранить архивов (1–60)",
            keyboardType = KeyboardType.Number,
            enabled = isAdmin && !busy
        )

        Spacer(Modifier.height(4.dp))

        ToggleRow(
            title = "Шифровать архивы",
            subtitle = "AES-256 через openssl",
            checked = encrypt,
            onCheckedChange = { encrypt = it },
            enabled = isAdmin && !busy
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                text = "Сохранить",
                onClick = {
                    onSave(
                        enabled,
                        schedule,
                        time,
                        keep.toIntOrNull() ?: 7,
                        encrypt
                    )
                },
                modifier = Modifier.weight(1f),
                loading = busy,
                enabled = isAdmin
            )
            PanelOutlinedButton(
                text = "Запустить",
                onClick = onRun,
                modifier = Modifier.weight(1f),
                loading = busy,
                icon = Icons.Filled.PlayArrow,
                enabled = isAdmin
            )
        }
    }
}

@Composable
private fun BackupPasswordDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Пароль архивов") },
        text = {
            Column {
                PanelTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Пароль (минимум 12 символов)",
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = "Используется для шифрования и восстановления"
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = value.length >= 12
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private fun formatMtime(raw: String): String {
    if (raw.isBlank()) return "—"
    return try {
        val parsed = java.time.Instant.parse(raw)
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date.from(parsed))
    } catch (e: Exception) {
        raw
    }
}
