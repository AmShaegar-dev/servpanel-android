package ru.servpanel.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.servpanel.mobile.AuthState
import ru.servpanel.mobile.AuthStatus
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.net.LoginResult
import ru.servpanel.mobile.net.PanelApiException
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelBg
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

@Composable
fun LoginScreen(
    server: ServerProfile,
    auth: AuthState,
    onLogin: (String, String, String, (Result<LoginResult>) -> Unit) -> Unit,
    onBack: () -> Unit,
    onEditServer: () -> Unit
) {
    BackHandler { onBack() }

    var username by remember(server.id) { mutableStateOf("admin") }
    var password by remember(server.id) { mutableStateOf("") }
    var code by remember(server.id) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var need2fa by remember(server.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(server.id) { mutableStateOf<String?>(auth.error) }

    fun submit() {
        if (password.isBlank() || busy) return
        busy = true
        error = null
        onLogin(username, password, code) { result ->
            busy = false
            result.fold(
                onSuccess = { },
                onFailure = { throwable ->
                    val api = throwable as? PanelApiException
                    if (api?.need2fa == true) need2fa = true
                    error = throwable.message ?: "Не удалось войти"
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = PanelText)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEditServer) { Text("Настройки сервера", color = PanelMuted) }
        }

        Spacer(Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = PanelAccent.copy(alpha = 0.13f),
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelAccent.copy(alpha = 0.35f)),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = PanelAccent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Вход в панель",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PanelText
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = server.name,
            style = MaterialTheme.typography.titleSmall,
            color = PanelText
        )
        Text(
            text = server.displayHost,
            style = MaterialTheme.typography.bodySmall,
            color = PanelMuted
        )

        Spacer(Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = PanelSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                PanelTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Пользователь",
                    placeholder = "admin",
                    enabled = !busy
                )

                Spacer(Modifier.height(12.dp))

                PanelTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Пароль",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    enabled = !busy,
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = PanelMuted
                            )
                        }
                    }
                )

                if (need2fa) {
                    Spacer(Modifier.height(12.dp))
                    PanelTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = "Код 2FA",
                        placeholder = "6 цифр",
                        keyboardType = KeyboardType.NumberPassword,
                        enabled = !busy
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Введите код из приложения-аутентификатора",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PanelRed.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PanelRed.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelRed,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                PanelButton(
                    text = "Войти",
                    onClick = { submit() },
                    enabled = password.isNotBlank(),
                    loading = busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = PanelMuted,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Пароль не сохраняется на устройстве — только сессия панели.",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        }

        if (auth.status == AuthStatus.Error) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = auth.error ?: "Сервер недоступен",
                style = MaterialTheme.typography.labelSmall,
                color = PanelRed
            )
        }

        Spacer(Modifier.height(24.dp))

        if (auth.defaultPassword) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "В панели используется стандартный пароль. Смените его в разделе «Защита».",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}
