package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.BulkConfig
import ru.servpanel.mobile.data.ClientInfo
import ru.servpanel.mobile.data.ClientKey
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

private val expiryOptions = listOf(
    "Бессрочно" to "",
    "1 час" to "1h",
    "1 день" to "1d",
    "7 дней" to "7d",
    "30 дней" to "30d",
    "1 неделя" to "1w"
)

@Composable
fun ClientsScreen(vm: AppViewModel, server: ServerProfile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var clients by remember(server.id) { mutableStateOf<List<ClientInfo>?>(null) }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var query by remember(server.id) { mutableStateOf("") }

    var showCreate by remember { mutableStateOf(false) }
    var showBulk by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ClientInfo?>(null) }
    var limitTarget by remember { mutableStateOf<ClientInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ClientInfo?>(null) }
    var keyTarget by remember { mutableStateOf<ClientInfo?>(null) }
    var keyData by remember { mutableStateOf<ClientKey?>(null) }
    var keyLoading by remember { mutableStateOf(false) }
    var createdName by remember { mutableStateOf<String?>(null) }
    var createdConf by remember { mutableStateOf<String?>(null) }
    var bulkResult by remember { mutableStateOf<List<BulkConfig>?>(null) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (clients == null) loading = true
        error = null
        try {
            clients = vm.call(server) { clients() }
        } catch (e: Exception) {
            if (clients == null) error = e.message ?: "Не удалось загрузить клиентов"
        } finally {
            loading = false
        }
    }

    fun refresh() {
        reload++
        vm.refreshWorkspace()
    }

    val list = clients ?: emptyList()
    val filtered = if (query.isBlank()) list else list.filter {
        it.name.contains(query, ignoreCase = true) || it.ip.contains(query)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            ScreenHeader(
                title = "Клиенты",
                subtitle = "Активны ${list.count { it.active }} из ${list.size}",
                trailing = {
                    if (auth.isAdmin) {
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Создать клиента", tint = PanelAccent)
                        }
                        IconButton(onClick = { showBulk = true }) {
                            Icon(Icons.Filled.People, contentDescription = "Массовое создание", tint = PanelMuted)
                        }
                    }
                }
            )

            Spacer(Modifier.height(10.dp))

            PanelTextField(
                value = query,
                onValueChange = { query = it },
                label = "Поиск",
                placeholder = "Имя или IP",
                trailing = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = PanelMuted)
                }
            )

            Spacer(Modifier.height(10.dp))
        }

        when {
            loading && clients == null -> LoadingState("Читаем список клиентов…")

            clients == null -> Column(Modifier.padding(16.dp)) {
                ErrorState(error ?: "Нет данных", onRetry = { refresh() })
            }

            filtered.isEmpty() -> Column(Modifier.padding(16.dp)) {
                PanelCard {
                    EmptyState(
                        icon = Icons.Filled.People,
                        title = if (query.isBlank()) "Клиентов пока нет" else "Ничего не найдено",
                        text = if (query.isBlank()) {
                            "Создайте первого клиента — приложение покажет QR-код и конфигурацию."
                        } else {
                            "Попробуйте другое имя или IP."
                        }
                    )
                    if (query.isBlank() && auth.isAdmin) {
                        PanelButton(
                            text = "Создать клиента",
                            onClick = { showCreate = true },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.Add
                        )
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.name }) { client ->
                    ClientRow(
                        client = client,
                        canEdit = auth.isAdmin,
                        onKey = {
                            keyTarget = client
                            keyData = null
                            keyLoading = true
                            scope.launch {
                                try {
                                    keyData = vm.call(server) { clientKey(client.name) }
                                } catch (e: Exception) {
                                    keyData = null
                                } finally {
                                    keyLoading = false
                                }
                            }
                        },
                        onDownload = {
                            scope.launch {
                                try {
                                    val conf = vm.call(server) { downloadClient(client.name) }
                                    shareFile(context, "Serv_${client.name}.conf", conf)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        e.message ?: "Не удалось скачать конфиг",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onRename = { renameTarget = client },
                        onLimit = { limitTarget = client },
                        onDelete = { deleteTarget = client }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateClientDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, expires ->
                scope.launch {
                    try {
                        val conf = vm.call(server) { createClient(name, expires) }
                        createdName = name
                        createdConf = conf
                        showCreate = false
                        refresh()
                    } catch (e: Exception) {
                        error = e.message
                        showCreate = false
                    }
                }
            }
        )
    }

    if (showBulk) {
        BulkCreateDialog(
            onDismiss = { showBulk = false },
            onCreate = { prefix, count, expires ->
                scope.launch {
                    try {
                        bulkResult = vm.call(server) { createBulk(prefix, count, expires) }
                        showBulk = false
                        refresh()
                    } catch (e: Exception) {
                        error = e.message
                        showBulk = false
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        RenameClientDialog(
            client = target,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                scope.launch {
                    try {
                        vm.call(server) { renameClient(target.name, newName) }
                        refresh()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            e.message ?: "Не удалось переименовать клиента",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    renameTarget = null
                }
            }
        )
    }

    limitTarget?.let { target ->
        LimitDialog(
            client = target,
            onDismiss = { limitTarget = null },
            onApply = { rate ->
                scope.launch {
                    try {
                        vm.call(server) { setLimit(target.ip, rate) }
                        android.widget.Toast.makeText(context, "Лимит применён", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            e.message ?: "Не удалось применить лимит",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    limitTarget = null
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = PanelElevated,
            title = { Text("Удалить клиента?") },
            text = { Text("«${target.name}» будет удалён на сервере. Ключи перестанут работать, восстановить их нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            vm.call(server) { deleteClient(target.name) }
                            android.widget.Toast.makeText(
                                context,
                                "Клиент «${target.name}» удалён",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                e.message ?: "Не удалось удалить клиента",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        deleteTarget = null
                        refresh()
                    }
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (keyTarget != null) {
        KeyDialog(
            client = keyTarget!!,
            key = keyData,
            loading = keyLoading,
            onDismiss = {
                keyTarget = null
                keyData = null
            }
        )
    }

    if (createdConf != null) {
        ResultDialog(
            title = "Клиент создан",
            subtitle = createdName ?: "",
            content = createdConf!!,
            fileName = "Serv_${createdName ?: "client"}.conf",
            onDismiss = {
                createdConf = null
                createdName = null
            }
        )
    }

    bulkResult?.let { configs ->
        val text = configs.joinToString("\n\n") { "${it.name}\n${it.content}" }
        ResultDialog(
            title = "Создано клиентов: ${configs.size}",
            subtitle = configs.joinToString(", ") { it.name },
            content = text,
            fileName = "servpanel-clients.txt",
            onDismiss = { bulkResult = null }
        )
    }
}

@Composable
private fun ClientRow(
    client: ClientInfo,
    canEdit: Boolean,
    onKey: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onLimit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PanelSurface,
        border = BorderStroke(1.dp, PanelBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(
                            color = if (client.active) PanelGreen.copy(alpha = 0.13f) else PanelBorder.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        initialsOf(client.name),
                        color = if (client.active) PanelGreen else PanelMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        client.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        client.ip.ifBlank { "без IP" },
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
                Badge(
                    if (client.active) "Активен" else "Неактивен",
                    if (client.active) PanelGreen else PanelMuted
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = PanelMuted)
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = PanelElevated
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ключ и QR-код") },
                            leadingIcon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                            onClick = { menu = false; onKey() }
                        )
                        DropdownMenuItem(
                            text = { Text("Скачать .conf") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = { menu = false; onDownload() }
                        )
                        if (canEdit) {
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                onClick = { menu = false; onRename() }
                            )
                            DropdownMenuItem(
                                text = { Text("Лимит скорости") },
                                onClick = { menu = false; onLimit() }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить", color = PanelRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = PanelRed) },
                                onClick = { menu = false; onDelete() }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↓ ${formatBytes(client.rx)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "↑ ${formatBytes(client.tx)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (client.lastHandshake > 0) {
                        "рукопожатие ${formatAgo(client.lastHandshake * 1000)}"
                    } else {
                        "нет рукопожатий"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }

            client.expiresAt?.let { expires ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Истекает: ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(expires * 1000))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelAmber
                )
            }
        }
    }
}

@Composable
private fun CreateClientDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(expiryOptions.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Новый клиент") },
        text = {
            Column {
                PanelTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Имя клиента",
                    placeholder = "например, iphone-alice"
                )
                Spacer(Modifier.height(12.dp))
                PanelDropdown(
                    label = "Срок действия",
                    options = expiryOptions.map { it.first },
                    selected = expiry,
                    onSelect = { expiry = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val expires = expiryOptions.firstOrNull { it.first == expiry }?.second.orEmpty()
                    onCreate(name.trim(), expires)
                },
                enabled = name.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun BulkCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int, String) -> Unit
) {
    var prefix by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("5") }
    var expiry by remember { mutableStateOf(expiryOptions.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Массовое создание") },
        text = {
            Column {
                PanelTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = "Префикс",
                    placeholder = "например, office"
                )
                Spacer(Modifier.height(10.dp))
                PanelTextField(
                    value = count,
                    onValueChange = { count = it.filter(Char::isDigit).take(3) },
                    label = "Количество (1–100)",
                    keyboardType = KeyboardType.Number
                )
                Spacer(Modifier.height(10.dp))
                PanelDropdown(
                    label = "Срок действия",
                    options = expiryOptions.map { it.first },
                    selected = expiry,
                    onSelect = { expiry = it }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Имена: ${prefix.ifBlank { "имя" }}-1 … ${prefix.ifBlank { "имя" }}-${count.ifBlank { "N" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val expires = expiryOptions.firstOrNull { it.first == expiry }?.second.orEmpty()
                    onCreate(prefix.trim(), count.toIntOrNull() ?: 0, expires)
                },
                enabled = prefix.isNotBlank() && (count.toIntOrNull() ?: 0) in 1..100
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun RenameClientDialog(
    client: ClientInfo,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(client.name) { mutableStateOf(client.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Переименовать клиента") },
        text = {
            PanelTextField(
                value = name,
                onValueChange = { name = it },
                label = "Новое имя",
                supportingText = "A-Z, a-z, 0-9, _ и -; до 63 символов"
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != client.name
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun LimitDialog(
    client: ClientInfo,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var rate by remember(client.name) { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Лимит скорости") },
        text = {
            Column {
                Text(
                    "${client.name} · ${client.ip}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
                Spacer(Modifier.height(10.dp))
                PanelTextField(
                    value = rate,
                    onValueChange = { rate = it.filter(Char::isDigit).take(6) },
                    label = "Мбит/с (0 — без лимита)",
                    keyboardType = KeyboardType.Number
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(rate.toIntOrNull() ?: 0) },
                enabled = client.ip.isNotBlank()
            ) { Text("Применить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun KeyDialog(
    client: ClientInfo,
    key: ClientKey?,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(key?.qr) { decodeDataUrl(key?.qr) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    "Ключ клиента",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                Text(
                    "${client.name} · ${client.ip}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(16.dp))

                when {
                    loading -> LoadingState("Готовим ключ и QR-код…")

                    key == null -> Text(
                        "Не удалось получить ключ. Проверьте, что клиент существует и панель доступна.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelRed
                    )

                    else -> {
                        if (bitmap != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "QR-код подключения",
                                    modifier = Modifier.size(220.dp)
                                )
                            }
                        } else {
                            Text(
                                "QR-код недоступен — используйте ссылку ниже.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PanelMuted
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PanelSurface,
                            border = BorderStroke(1.dp, PanelBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = key.uri,
                                style = MaterialTheme.typography.bodySmall,
                                color = PanelText,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelOutlinedButton(
                                text = "Копировать",
                                onClick = { copyToClipboard(context, key.uri, "Ссылка скопирована") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.ContentCopy
                            )
                            PanelOutlinedButton(
                                text = "Поделиться",
                                onClick = { shareText(context, key.uri, "Ключ ${client.name}") },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Share
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                PanelButton(
                    text = "Закрыть",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ResultDialog(
    title: String,
    subtitle: String,
    content: String,
    fileName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PanelSurface,
                    border = BorderStroke(1.dp, PanelBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelOutlinedButton(
                        text = "Копировать",
                        onClick = { copyToClipboard(context, content, "Конфигурация скопирована") },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ContentCopy
                    )
                    PanelButton(
                        text = "Поделиться",
                        onClick = { shareFile(context, fileName, content) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Share
                    )
                }

                Spacer(Modifier.height(10.dp))

                PanelButton(
                    text = "Готово",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
