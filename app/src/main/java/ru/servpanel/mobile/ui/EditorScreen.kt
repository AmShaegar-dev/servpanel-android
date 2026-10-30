package ru.servpanel.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.servpanel.mobile.data.PanelUrl
import ru.servpanel.mobile.data.ServerColors
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.SshSetupState
import ru.servpanel.mobile.net.ProbeResult
import ru.servpanel.mobile.net.SshAccess
import ru.servpanel.mobile.net.SshProbeResult
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
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    existing: ServerProfile?,
    servers: List<ServerProfile>,
    sshSetup: SshSetupState,
    onBack: () -> Unit,
    onSave: (ServerProfile) -> Unit,
    onDelete: (ServerProfile) -> Unit,
    onProbe: (String, Boolean, (ProbeResult) -> Unit) -> Unit,
    onProbeSsh: (ServerProfile, (Result<SshProbeResult>) -> Unit) -> Unit,
    onSetupSsh: () -> Unit
) {
    BackHandler { onBack() }

    val key = existing?.id
    var name by remember(key) { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember(key) { mutableStateOf(existing?.url.orEmpty()) }
    var panelPort by remember(key) {
        mutableStateOf(existing?.panelPort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var metricsPort by remember(key) {
        mutableStateOf(existing?.metricsPort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var metricsToken by remember(key) { mutableStateOf(existing?.metricsToken.orEmpty()) }
    var allowInsecure by remember(key) { mutableStateOf(existing?.allowInsecure ?: false) }
    var color by remember(key) { mutableIntStateOf(existing?.color ?: ServerColors.next(servers)) }
    var nameTouched by remember(key) { mutableStateOf(existing != null) }

    var sshTunnel by remember(key) { mutableStateOf(existing?.sshTunnel ?: false) }
    var sshHost by remember(key) { mutableStateOf(existing?.sshHost.orEmpty()) }
    var sshPort by remember(key) { mutableStateOf((existing?.sshPort ?: 22).toString()) }
    var sshUser by remember(key) { mutableStateOf(existing?.sshUser ?: SshAccess.DEFAULT_USER) }
    var remotePanelPort by remember(key) {
        mutableStateOf((existing?.remotePanelPort ?: 3000).toString())
    }
    var sshKeyText by remember(key) { mutableStateOf(existing?.sshKey.orEmpty()) }
    var sshPassphrase by remember(key) { mutableStateOf(existing?.sshPassphrase.orEmpty()) }
    var sshHostKey by remember(key) { mutableStateOf(existing?.sshHostKey.orEmpty()) }
    var sshResult by remember { mutableStateOf<SshProbeResult?>(null) }
    var showKey by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun parsePort(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    val normalizedUrl = PanelUrl.normalize(url)
    val panelPortValue = parsePort(panelPort)
    val metricsPortValue = parsePort(metricsPort)
    val panelPortInvalid = panelPort.isNotBlank() && panelPortValue == null
    val metricsPortInvalid = metricsPort.isNotBlank() && metricsPortValue == null
    val sshPortValue = parsePort(sshPort) ?: 22
    val remotePanelPortValue = parsePort(remotePanelPort)
    val sshHostInvalid = sshTunnel && sshHost.isBlank()

    fun draftSshProfile(): ServerProfile {
        val base = existing ?: ServerProfile(
            id = "probe",
            name = name.ifBlank { "probe" },
            url = "http://${sshHost.trim()}",
            color = color,
            createdAt = 0L
        )
        return base.copy(
            name = name.ifBlank { base.name },
            sshTunnel = true,
            sshHost = sshHost.trim(),
            sshPort = sshPortValue,
            sshUser = sshUser.trim().ifBlank { SshAccess.DEFAULT_USER },
            sshKey = sshKeyText,
            sshPassphrase = sshPassphrase,
            sshHostKey = sshHostKey,
            remotePanelPort = remotePanelPortValue ?: 3000
        )
    }

    val canSave = !panelPortInvalid &&
        !metricsPortInvalid &&
        if (sshTunnel) {
            sshHost.isNotBlank() && sshKeyText.isNotBlank() && remotePanelPortValue != null
        } else {
            name.isNotBlank() && normalizedUrl != null
        }
    val finalUrl = probeResult?.takeIf { it.ok }?.resolvedUrl ?: normalizedUrl

    LaunchedEffect(url, nameTouched) {
        if (!nameTouched && existing == null) {
            val host = PanelUrl.normalize(url)
                ?.substringAfter("://")
                ?.substringBefore("/")
            if (!host.isNullOrBlank()) name = host
        }
    }

    Scaffold(
        containerColor = PanelBg,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = PanelText)
                    }
                },
                title = {
                    Text(
                        text = if (existing == null) "Новый сервер" else "Настройки сервера",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PanelText
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelBg)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameTouched = true
                },
                label = { Text("Название") },
                placeholder = { Text("Например, EU-сервер") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = panelFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            SettingSwitch(
                title = "Доступ через SSH-туннель",
                subtitle = "Панель слушает только 127.0.0.1, снаружи порт закрыт. " +
                    "Приложение открывает туннель отдельным ключом.",
                checked = sshTunnel,
                onCheckedChange = {
                    sshTunnel = it
                    probeResult = null
                    sshResult = null
                }
            )

            if (sshTunnel) {
                Spacer(Modifier.height(8.dp))
                SshAccessFields(
                    host = sshHost,
                    onHostChange = {
                        sshHost = it.trimStart()
                        sshResult = null
                    },
                    port = sshPort,
                    onPortChange = { sshPort = it.filter(Char::isDigit).take(5) },
                    user = sshUser,
                    onUserChange = { sshUser = it.trim() },
                    remotePort = remotePanelPort,
                    onRemotePortChange = {
                        remotePanelPort = it.filter(Char::isDigit).take(5)
                        sshResult = null
                    },
                    privateKey = sshKeyText,
                    onPrivateKeyChange = {
                        sshKeyText = it
                        sshResult = null
                    },
                    passphrase = sshPassphrase,
                    onPassphraseChange = { sshPassphrase = it },
                    showKey = showKey,
                    onToggleShowKey = { showKey = !showKey },
                    hostKey = sshHostKey,
                    onHostKeyChange = { sshHostKey = it.trim() },
                    onGenerate = {
                        val generated = SshAccess.generateKey()
                        sshKeyText = generated.privateKey
                        sshResult = null
                    },
                    hostInvalid = sshHostInvalid,
                    remotePortInvalid = remotePanelPortValue == null,
                    publicKey = SshAccess.publicKeyOf(sshKeyText, sshPassphrase),
                    authorizedKey = SshAccess.publicKeyOf(sshKeyText, sshPassphrase)?.let {
                        SshAccess.authorizedKeyLine(it, remotePanelPortValue ?: 3000)
                    }.orEmpty(),
                    userForKeys = sshUser.ifBlank { SshAccess.DEFAULT_USER },
                    remotePortForKeys = remotePanelPortValue ?: 3000
                )
            } else {
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                onValueChange = {
                    url = it
                    probeResult = null
                },
                label = { Text("Адрес панели") },
                placeholder = { Text("panel.example.com или 1.2.3.4:3000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                ),
                isError = url.isNotBlank() && normalizedUrl == null,
                supportingText = {
                    Text(
                        text = when {
                            url.isBlank() -> "Можно вводить без http:// — приложение подберёт схему само"
                            normalizedUrl == null -> "Проверьте адрес: нужен корректный домен или IP"
                            !PanelUrl.hasScheme(url) ->
                                "Без схемы: сначала пробуется https, затем http"
                            else -> "Будет сохранено как $normalizedUrl"
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = panelFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = panelPort,
                    onValueChange = {
                        panelPort = it.filter(Char::isDigit).take(5)
                        probeResult = null
                    },
                    label = { Text("Порт панели") },
                    placeholder = { Text("3000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    isError = panelPortInvalid,
                    supportingText = {
                        Text(
                            if (panelPortInvalid) "1–65535" else "если не указан в адресе",
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = panelFieldColors()
                )
                OutlinedTextField(
                    value = metricsPort,
                    onValueChange = {
                        metricsPort = it.filter(Char::isDigit).take(5)
                    },
                    label = { Text("Порт метрик") },
                    placeholder = { Text("9101") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    isError = metricsPortInvalid,
                    supportingText = {
                        Text(
                            if (metricsPortInvalid) "1–65535" else "PANEL_METRICS_PORT",
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = panelFieldColors()
                )
            }

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = metricsToken,
                onValueChange = { metricsToken = it },
                label = { Text("Токен метрик") },
                placeholder = { Text("если metrics-сервер требует токен") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                supportingText = {
                    Text(
                        "Необязательно. Хранится только на этом устройстве.",
                        maxLines = 2
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = panelFieldColors()
            )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    testing = true
                    probeResult = null
                    sshResult = null
                    if (sshTunnel) {
                        onProbeSsh(draftSshProfile()) { result ->
                            testing = false
                            result.onSuccess { probe ->
                                sshResult = probe
                                if (probe.fingerprint.isNotBlank()) sshHostKey = probe.fingerprint
                            }.onFailure { error ->
                                sshResult = SshProbeResult(
                                    fingerprint = "",
                                    panelReachable = false,
                                    message = error.message ?: "Не удалось проверить SSH",
                                    latencyMs = 0L
                                )
                            }
                        }
                    } else {
                        val probeTarget = PanelUrl.probeTarget(url, panelPortValue ?: 0)
                        onProbe(probeTarget, allowInsecure) { result ->
                            testing = false
                            probeResult = result
                        }
                    }
                },
                enabled = if (sshTunnel) {
                    sshHost.isNotBlank() && sshKeyText.isNotBlank() && !testing
                } else {
                    url.isNotBlank() && !testing
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                border = BorderStroke(1.dp, PanelBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelAccent)
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = PanelAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Проверяем…")
                } else {
                    Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить подключение")
                }
            }

            probeResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                ProbeResultCard(result)
            }

            sshResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                SshProbeCard(result)
            }

            if (!sshTunnel && existing != null) {
                Spacer(Modifier.height(16.dp))
                SshSetupCard(state = sshSetup, onStart = onSetupSsh)
            }

            if (!sshTunnel) {
                Spacer(Modifier.height(20.dp))

                SettingSwitch(
                    title = "Самоподписанный сертификат",
                    subtitle = "Разрешить подключение к панели с недоверенным TLS-сертификатом",
                    checked = allowInsecure,
                    onCheckedChange = {
                        allowInsecure = it
                        probeResult = null
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Цвет метки",
                style = MaterialTheme.typography.labelMedium,
                color = PanelMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ServerColors.palette.forEach { candidate ->
                    val selected = candidate == color
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(candidate))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) PanelText else PanelBorder,
                                shape = CircleShape
                            )
                            .clickable { color = candidate },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    val profile = if (sshTunnel) {
                        val hostValue = sshHost.trim()
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .substringBefore("/")
                        val baseUrl = PanelUrl.normalize(hostValue) ?: "http://$hostValue"
                        val title = name.trim().ifBlank { hostValue }
                        val sshProfile = ServerProfile(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = title,
                            url = baseUrl,
                            color = color,
                            allowInsecure = false,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            panelPort = 0,
                            metricsPort = 0,
                            metricsToken = "",
                            sshTunnel = true,
                            sshHost = hostValue,
                            sshPort = sshPortValue,
                            sshUser = sshUser.trim().ifBlank { SshAccess.DEFAULT_USER },
                            sshKey = sshKeyText,
                            sshPassphrase = sshPassphrase,
                            sshHostKey = sshHostKey,
                            remotePanelPort = remotePanelPortValue ?: 3000
                        )
                        sshProfile
                    } else {
                        val resolved = finalUrl ?: return@Button
                        // Если порт вынесен в отдельное поле, из адреса его убираем,
                        // чтобы не хранить одно и то же дважды.
                        val target = if (panelPortValue != null) {
                            PanelUrl.withoutPort(resolved)
                        } else {
                            resolved
                        }
                        val title = name.trim().ifBlank {
                            target.substringAfter("://").substringBefore("/")
                        }
                        ServerProfile(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = title,
                            url = target,
                            color = color,
                            allowInsecure = allowInsecure,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            panelPort = panelPortValue ?: 0,
                            metricsPort = metricsPortValue ?: 0,
                            metricsToken = metricsToken.trim(),
                            sshTunnel = false,
                            sshHost = "",
                            sshPort = 22,
                            sshUser = SshAccess.DEFAULT_USER,
                            sshKey = "",
                            sshPassphrase = "",
                            sshHostKey = "",
                            remotePanelPort = 3000
                        )
                    }
                    onSave(profile)
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (existing == null) "Добавить сервер" else "Сохранить",
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (existing != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    border = BorderStroke(1.dp, PanelRed.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelRed)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить сервер")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PanelElevated,
            title = { Text("Удалить сервер?") },
            text = {
                Text(
                    "«${existing.name}» будет удалён из приложения. " +
                        "Панель на самом сервере продолжит работать."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(existing)
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

@Composable
private fun ProbeResultCard(result: ProbeResult) {
    val accent = if (result.ok) PanelGreen else PanelRed
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result.ok) Icons.Filled.Check else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (result.ok) "Соединение установлено" else "Не удалось подключиться",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelText
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            result.latencyMs?.let {
                Text(
                    text = "Задержка: $it мс",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
            result.resolvedUrl?.let {
                Text(
                    text = "Адрес: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }
    }
}

@Composable
private fun SshSetupCard(state: SshSetupState, onStart: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PanelSurface,
        border = BorderStroke(1.dp, PanelAccent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Доступ по SSH без входа на сервер",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = PanelText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Панель сама создаст отдельного пользователя, поставит ключ " +
                    "и закроет свой порт. Нужны только права администратора панели " +
                    "(версия 3.0.48 или новее).",
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
            Spacer(Modifier.height(12.dp))

            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = PanelAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = state.step.ifBlank { "Настраиваем…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = PanelText
                    )
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PanelAccent,
                        contentColor = Color.White
                    )
                ) {
                    Text("Настроить через панель")
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelRed
                )
            }
        }
    }
}

@Composable
private fun SshAccessFields(
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    user: String,
    onUserChange: (String) -> Unit,
    remotePort: String,
    onRemotePortChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    showKey: Boolean,
    onToggleShowKey: () -> Unit,
    hostKey: String,
    onHostKeyChange: (String) -> Unit,
    onGenerate: () -> Unit,
    hostInvalid: Boolean,
    remotePortInvalid: Boolean,
    publicKey: String?,
    authorizedKey: String,
    userForKeys: String,
    remotePortForKeys: Int
) {
    val context = LocalContext.current

    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text("SSH-хост") },
        placeholder = { Text("1.2.3.4 или server.example.com") },
        singleLine = true,
        isError = hostInvalid,
        supportingText = {
            Text(if (hostInvalid) "Укажите адрес сервера" else "Тот же сервер, где стоит панель")
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = panelFieldColors()
    )

    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text("Порт SSH") },
            placeholder = { Text("22") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.weight(1f),
            colors = panelFieldColors()
        )
        OutlinedTextField(
            value = remotePort,
            onValueChange = onRemotePortChange,
            label = { Text("Порт панели") },
            placeholder = { Text("3000") },
            singleLine = true,
            isError = remotePortInvalid,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            supportingText = { Text(if (remotePortInvalid) "1–65535" else "на 127.0.0.1") },
            modifier = Modifier.weight(1f),
            colors = panelFieldColors()
        )
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = user,
        onValueChange = onUserChange,
        label = { Text("Пользователь SSH") },
        placeholder = { Text(SshAccess.DEFAULT_USER) },
        singleLine = true,
        supportingText = { Text("Без шелла, только проброс порта панели") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth(),
        colors = panelFieldColors()
    )

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = privateKey,
        onValueChange = onPrivateKeyChange,
        label = { Text("Приватный ключ") },
        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
        minLines = 4,
        maxLines = 8,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.None
        ),
        supportingText = {
            Text("Хранится в зашифрованном виде на этом устройстве")
        },
        modifier = Modifier.fillMaxWidth(),
        colors = panelFieldColors()
    )

    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onGenerate,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, PanelBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelAccent)
        ) {
            Text("Создать ключ")
        }
        OutlinedButton(
            onClick = onToggleShowKey,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, PanelBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelMuted)
        ) {
            Text(if (showKey) "Скрыть пароль" else "Пароль ключа")
        }
    }

    if (showKey) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = onPassphraseChange,
            label = { Text("Пароль ключа (если есть)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            colors = panelFieldColors()
        )
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = hostKey,
        onValueChange = onHostKeyChange,
        label = { Text("Отпечаток сервера") },
        placeholder = { Text("SHA256:… — заполнится после проверки") },
        singleLine = true,
        supportingText = {
            Text("Сверьте с `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` на сервере")
        },
        modifier = Modifier.fillMaxWidth(),
        colors = panelFieldColors()
    )

    if (publicKey != null && authorizedKey.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Что выполнить на сервере один раз (от root)",
            style = MaterialTheme.typography.labelMedium,
            color = PanelMuted
        )
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelSurface,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = setupCommands(userForKeys, authorizedKey, remotePortForKeys),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = PanelText
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                copyToClipboard(
                    context,
                    setupCommands(userForKeys, authorizedKey, remotePortForKeys),
                    "Команды скопированы"
                )
            },
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, PanelBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PanelAccent)
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Скопировать команды")
        }
    }
}

@Composable
private fun SshProbeCard(result: SshProbeResult) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (result.panelReachable) PanelGreen.copy(alpha = 0.10f) else PanelRed.copy(alpha = 0.10f),
        border = BorderStroke(
            1.dp,
            if (result.panelReachable) PanelGreen.copy(alpha = 0.5f) else PanelRed.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.panelReachable) PanelGreen else PanelRed
            )
            if (result.fingerprint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = result.fingerprint,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = PanelMuted
                )
            }
            if (result.latencyMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Проверка заняла ${result.latencyMs} мс",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )
            }
        }
    }
}

private fun setupCommands(user: String, authorizedKey: String, remotePort: Int): String = buildString {
    val home = if (user == "root") "/root" else "/home/$user"
    appendLine("useradd -r -m -s /usr/sbin/nologin $user 2>/dev/null || true")
    appendLine("install -d -m 700 -o $user -g $user $home/.ssh")
    appendLine("echo '${authorizedKey}' >> $home/.ssh/authorized_keys")
    appendLine("chown $user:$user $home/.ssh/authorized_keys")
    appendLine("chmod 600 $home/.ssh/authorized_keys")
    appendLine("cd /root/servpanel")
    appendLine("PANEL_HOST=127.0.0.1 pm2 restart awg-admin --update-env && pm2 save")
    append("ufw delete allow $remotePort/tcp 2>/dev/null || true")
}

@Composable
internal fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = PanelText
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PanelMuted
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PanelAccent,
                uncheckedThumbColor = PanelMuted,
                uncheckedTrackColor = PanelSurface,
                uncheckedBorderColor = PanelBorder
            )
        )
    }
}

@Composable
private fun panelFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = PanelSurface,
    unfocusedContainerColor = PanelSurface,
    focusedBorderColor = PanelAccent,
    unfocusedBorderColor = PanelBorder,
    focusedTextColor = PanelText,
    unfocusedTextColor = PanelText,
    cursorColor = PanelAccent,
    focusedLabelColor = PanelAccent,
    unfocusedLabelColor = PanelMuted,
    focusedPlaceholderColor = PanelMuted,
    unfocusedPlaceholderColor = PanelMuted,
    focusedSupportingTextColor = PanelMuted,
    unfocusedSupportingTextColor = PanelMuted,
    errorBorderColor = PanelRed,
    errorLabelColor = PanelRed,
    errorSupportingTextColor = PanelRed
)
