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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.UserInfo
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
fun UsersScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val auth = vm.authOf(server.id)

    var users by remember(server.id) { mutableStateOf<List<UserInfo>?>(null) }
    var current by remember(server.id) { mutableStateOf("") }
    var error by remember(server.id) { mutableStateOf<String?>(null) }
    var loading by remember(server.id) { mutableStateOf(true) }
    var reload by remember(server.id) { mutableIntStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UserInfo?>(null) }
    var passwordTarget by remember { mutableStateOf<UserInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(false) }

    LaunchedEffect(server.id, vm.refreshTick, reload) {
        if (users == null) loading = true
        error = null
        try {
            val payload = vm.call(server) { users() }
            users = payload.users
            current = payload.current
        } catch (e: Exception) {
            if (users == null) error = e.message ?: "Не удалось загрузить пользователей"
        } finally {
            loading = false
        }
    }

    if (loading && users == null) {
        LoadingState("Читаем список пользователей…")
        return
    }

    val list = users
    if (list == null) {
        Column(Modifier.padding(16.dp)) {
            ErrorState(error ?: "Нет данных", onRetry = { reload++ })
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            ScreenHeader(
                title = "Пользователи",
                subtitle = "Всего ${list.size}",
                trailing = {
                    if (auth.isAdmin) {
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Добавить", tint = PanelAccent)
                        }
                    }
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelMuted)
                    }
                }
            )
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (messageOk) PanelGreen else PanelRed
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.username }) { user ->
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = PanelAccent.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = null,
                                tint = PanelAccent,
                                modifier = Modifier.padding(9.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    user.username,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = PanelText
                                )
                                if (user.username == current) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge("это вы", PanelAccent)
                                }
                            }
                            Text(
                                buildString {
                                    append(if (user.role == "admin") "администратор" else "только просмотр")
                                    if (user.totpEnabled) append(" · 2FA")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelMuted
                            )
                            if (user.createdAt.isNotBlank()) {
                                Text(
                                    "создан ${user.createdAt.take(10)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PanelMuted
                                )
                            }
                        }
                        if (auth.isAdmin) {
                            TextButton(onClick = { passwordTarget = user }) {
                                Text("Пароль", color = PanelAccent)
                            }
                            if (user.username != "admin" && user.username != current) {
                                IconButton(onClick = { deleteTarget = user }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Удалить",
                                        tint = PanelMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                PanelCard {
                    SectionHeader("Роли", null)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Администратор управляет клиентами, сетью и обновлениями. " +
                            "Роль «только просмотр» видит данные панели, но не может ничего менять.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelMuted
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateUserDialog(
            onDismiss = { showCreate = false },
            onCreate = { username, password, role ->
                scope.launch {
                    try {
                        vm.call(server) { createUser(username, password, role) }
                        messageOk = true
                        message = "Пользователь $username создан"
                        showCreate = false
                        reload++
                    } catch (e: Exception) {
                        messageOk = false
                        message = e.message ?: "Не удалось создать пользователя"
                    }
                }
            }
        )
    }

    deleteTarget?.let { user ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = PanelElevated,
            title = { Text("Удалить пользователя?") },
            text = { Text("Доступ «${user.username}» будет отозван. Сессия пользователя завершится.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            vm.call(server) { deleteUser(user.username) }
                            messageOk = true
                            message = "Пользователь удалён"
                            reload++
                        } catch (e: Exception) {
                            messageOk = false
                            message = e.message ?: "Не удалось удалить пользователя"
                        }
                        deleteTarget = null
                    }
                }) { Text("Удалить", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    passwordTarget?.let { user ->
        ChangeUserPasswordDialog(
            user = user,
            onDismiss = { passwordTarget = null },
            onSave = { password ->
                scope.launch {
                    try {
                        vm.call(server) { setUserPassword(user.username, password) }
                        messageOk = true
                        message = "Пароль ${user.username} изменён"
                        passwordTarget = null
                    } catch (e: Exception) {
                        messageOk = false
                        message = e.message ?: "Не удалось сменить пароль"
                    }
                }
            }
        )
    }
}

@Composable
private fun CreateUserDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("admin") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Новый пользователь") },
        text = {
            Column {
                PanelTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = "Имя",
                    supportingText = "3–32 символа: буквы, цифры, . _ -"
                )
                Spacer(Modifier.height(10.dp))
                PanelTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Пароль",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = "Минимум 10 символов"
                )
                Spacer(Modifier.height(10.dp))
                PanelDropdown(
                    label = "Роль",
                    options = listOf("Администратор", "Только просмотр"),
                    selected = if (role == "viewer") "Только просмотр" else "Администратор",
                    onSelect = { role = if (it == "Только просмотр") "viewer" else "admin" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(username, password, role) },
                enabled = username.length >= 3 && password.length >= 10
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun ChangeUserPasswordDialog(
    user: UserInfo,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelElevated,
        title = { Text("Пароль ${user.username}") },
        text = {
            PanelTextField(
                value = password,
                onValueChange = { password = it },
                label = "Новый пароль",
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = "Минимум 10 символов"
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(password) },
                enabled = password.length >= 10
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
