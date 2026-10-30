package ru.servpanel.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.ServerColors
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.net.GeneratedKey
import ru.servpanel.mobile.net.HostInfo
import ru.servpanel.mobile.net.InstallEvent
import ru.servpanel.mobile.net.InstallRequest
import ru.servpanel.mobile.net.SshAccess
import ru.servpanel.mobile.net.SshInstaller
import ru.servpanel.mobile.net.SshTarget
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelAmber
import ru.servpanel.mobile.ui.theme.PanelBg
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText
import java.util.UUID
import kotlin.random.Random

/**
 * Установка панели на чистый сервер по SSH.
 *
 * Два шага: сначала проверяем подключение и показываем отпечаток ключа хоста
 * (TOFU), потом запускаем install.sh и стримим журнал.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallPanelScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onInstalled: (ServerProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { SshInstaller() }

    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    var showSshPassword by remember { mutableStateOf(false) }

    var accessType by remember { mutableStateOf(1) }
    var domain by remember { mutableStateOf("") }
    var panelPort by remember { mutableStateOf("3000") }
    var adminPassword by remember { mutableStateOf(randomPassword()) }
    var showAdminPassword by remember { mutableStateOf(false) }

    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var hostInfo by remember { mutableStateOf<HostInfo?>(null) }

    var sshOnly by remember { mutableStateOf(false) }
    var generatedKey by remember { mutableStateOf<GeneratedKey?>(null) }
    var sshReady by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var installError by remember { mutableStateOf<String?>(null) }
    var resultUrl by remember { mutableStateOf<String?>(null) }

    val sshPortValue = sshPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val panelPortValue = panelPort.trim().toIntOrNull()?.takeIf { it in 1..65535 && it != 80 }
    val domainValid = accessType == 1 || (
        domain.trim().matches(Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")) &&
            domain.trim().contains(".")
        )
    val canProbe = host.isNotBlank() &&
        sshPortValue != null &&
        sshUser.isNotBlank() &&
        sshPassword.isNotEmpty()
    val canInstall = canProbe &&
        panelPortValue != null &&
        domainValid &&
        adminPassword.length >= 8

    fun resetProbe() {
        hostInfo = null
        probeError = null
    }

    fun target() = SshTarget(
        host = host.trim().removePrefix("http://").removePrefix("https://").substringBefore("/"),
        port = sshPortValue ?: 22,
        user = sshUser.trim(),
        password = sshPassword
    )

    fun startProbe() {
        resetProbe()
        probing = true
        scope.launch {
            try {
                hostInfo = installer.probe(target())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                probeError = e.message ?: "Не удалось подключиться по SSH"
            } finally {
                probing = false
            }
        }
    }

    fun startInstall(info: HostInfo) {
        installError = null
        resultUrl = null
        sshReady = false
        log = emptyList()
        stage = "Подключаемся…"
        progress = 0.02f
        running = true
        // Ключ создаётся до установки: на сервер уйдёт только публичная часть.
        val key = if (sshOnly) SshAccess.generateKey() else null
        generatedKey = key
        val request = InstallRequest(
            target = target(),
            fingerprint = info.fingerprint,
            accessType = accessType,
            domain = domain.trim(),
            panelPort = panelPortValue ?: 3000,
            adminPassword = adminPassword,
            sshOnly = sshOnly,
            sshKey = key?.privateKey.orEmpty(),
            sshPublicKey = key?.publicKey.orEmpty(),
            sshUser = SshAccess.DEFAULT_USER
        )
        job = scope.launch {
            try {
                installer.install(request).collect { event ->
                    when (event) {
                        is InstallEvent.Stage -> {
                            stage = event.text
                            progress = event.progress
                        }

                        is InstallEvent.Log -> log = (log + event.line).takeLast(800)

                        is InstallEvent.Done -> {
                            resultUrl = event.panelUrl
                            sshReady = event.sshReady
                            progress = 1f
                        }
                    }
                }
                if (resultUrl == null) {
                    throw IllegalStateException("Установка завершилась без адреса панели")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installError = e.message ?: "Не удалось установить панель"
            } finally {
                running = false
            }
        }
    }

    BackHandler(enabled = running) {
        job?.cancel()
        running = false
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
                        text = "Установка панели",
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
            val url = resultUrl
            when {
                url != null -> InstallDoneCard(
                    url = url,
                    adminPassword = adminPassword,
                    sshOnly = sshReady,
                    onCopyPassword = {
                        copyToClipboard(context, adminPassword, "Пароль скопирован")
                    },
                    onOpen = {
                        val hostValue = host.trim()
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .substringBefore("/")
                        val key = generatedKey
                        val profile = if (sshReady && key != null) {
                            ServerProfile(
                                id = UUID.randomUUID().toString(),
                                name = hostValue,
                                // Для режима SSH адрес панели — только подпись;
                                // реальный трафик идёт через локальный конец туннеля.
                                url = "http://$hostValue",
                                color = ServerColors.next(vm.servers),
                                createdAt = System.currentTimeMillis(),
                                sshTunnel = true,
                                sshHost = hostValue,
                                sshPort = sshPortValue ?: 22,
                                sshUser = SshAccess.DEFAULT_USER,
                                sshKey = key.privateKey,
                                sshHostKey = hostInfo?.fingerprint.orEmpty(),
                                remotePanelPort = if (accessType == 2) 3000 else panelPortValue ?: 3000
                            )
                        } else {
                            ServerProfile(
                                id = UUID.randomUUID().toString(),
                                name = if (accessType == 2) domain.trim() else hostValue,
                                url = url,
                                color = ServerColors.next(vm.servers),
                                createdAt = System.currentTimeMillis()
                            )
                        }
                        onInstalled(profile)
                    },
                    onAgain = {
                        resultUrl = null
                        sshReady = false
                        generatedKey = null
                        log = emptyList()
                        stage = ""
                        progress = 0f
                    }
                )

                running || log.isNotEmpty() || installError != null -> InstallLogCard(
                    stage = stage,
                    progress = progress,
                    log = log,
                    running = running,
                    error = installError,
                    onCancel = {
                        job?.cancel()
                        running = false
                    },
                    onBackToForm = {
                        log = emptyList()
                        installError = null
                        stage = ""
                        progress = 0f
                    }
                )

                else -> {
                    PanelCard {
                        SectionHeader(
                            title = "Сервер",
                            subtitle = "Чистый VPS с Debian или Ubuntu и доступом по SSH"
                        )
                        Spacer(Modifier.height(12.dp))
                        PanelTextField(
                            value = host,
                            onValueChange = { host = it; resetProbe() },
                            label = "IP или домен сервера",
                            placeholder = "203.0.113.10",
                            enabled = !probing,
                            keyboardType = KeyboardType.Uri
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PanelTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it; resetProbe() },
                                label = "Пользователь",
                                enabled = !probing,
                                modifier = Modifier.weight(1f)
                            )
                            PanelTextField(
                                value = sshPort,
                                onValueChange = { sshPort = it; resetProbe() },
                                label = "SSH-порт",
                                enabled = !probing,
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.7f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        PanelTextField(
                            value = sshPassword,
                            onValueChange = { sshPassword = it; resetProbe() },
                            label = "Пароль SSH",
                            enabled = !probing,
                            visualTransformation = if (showSshPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardType = KeyboardType.Password,
                            trailing = {
                                IconButton(onClick = { showSshPassword = !showSshPassword }) {
                                    Icon(
                                        if (showSshPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = null,
                                        tint = PanelMuted
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Пароль нужен только для установки и нигде не сохраняется.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    PanelCard {
                        SectionHeader(
                            title = "Доступ к панели",
                            subtitle = "Как вы будете открывать панель после установки"
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccessChip(
                                text = "По IP",
                                selected = accessType == 1,
                                enabled = !probing,
                                onClick = {
                                    if (accessType != 1) {
                                        accessType = 1
                                        if (panelPort == "443") panelPort = "3000"
                                    }
                                }
                            )
                            AccessChip(
                                text = "По домену",
                                selected = accessType == 2,
                                enabled = !probing,
                                onClick = {
                                    if (accessType != 2) {
                                        accessType = 2
                                        if (panelPort == "3000") panelPort = "443"
                                    }
                                }
                            )
                        }
                        if (accessType == 2) {
                            Spacer(Modifier.height(10.dp))
                            PanelTextField(
                                value = domain,
                                onValueChange = { domain = it; resetProbe() },
                                label = "Домен",
                                placeholder = "panel.example.com",
                                enabled = !probing,
                                isError = domain.isNotBlank() && !domainValid,
                                supportingText = if (domain.isNotBlank() && !domainValid) {
                                    "Домен без http:// — например panel.example.com"
                                } else {
                                    "A-запись домена должна указывать на этот сервер"
                                },
                                keyboardType = KeyboardType.Uri
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        PanelTextField(
                            value = panelPort,
                            onValueChange = { panelPort = it },
                            label = "Порт панели",
                            enabled = !probing,
                            keyboardType = KeyboardType.Number,
                            isError = panelPort.isNotBlank() && panelPortValue == null,
                            supportingText = if (accessType == 2) {
                                "443 — обычный HTTPS. Сертификат Let's Encrypt выпустится автоматически"
                            } else {
                                "По умолчанию 3000. Порт 80 занят редиректом на HTTPS"
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    PanelCard {
                        SectionHeader(
                            title = "Пароль администратора",
                            subtitle = "Логин admin — его создаст установщик"
                        )
                        Spacer(Modifier.height(12.dp))
                        PanelTextField(
                            value = adminPassword,
                            onValueChange = { adminPassword = it },
                            label = "Пароль",
                            enabled = !probing,
                            visualTransformation = if (showAdminPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardType = KeyboardType.Password,
                            isError = adminPassword.isNotEmpty() && adminPassword.length < 8,
                            supportingText = if (adminPassword.isNotEmpty() && adminPassword.length < 8) {
                                "Минимум 8 символов"
                            } else {
                                null
                            },
                            trailing = {
                                IconButton(onClick = { showAdminPassword = !showAdminPassword }) {
                                    Icon(
                                        if (showAdminPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = null,
                                        tint = PanelMuted
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PanelOutlinedButton(
                                text = "Другой пароль",
                                onClick = { adminPassword = randomPassword() },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Refresh
                            )
                            Spacer(Modifier.width(8.dp))
                            PanelOutlinedButton(
                                text = "Копировать",
                                onClick = {
                                    copyToClipboard(context, adminPassword, "Пароль скопирован")
                                },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.ContentCopy
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    PanelCard {
                        SettingSwitch(
                            title = "Доступ только из приложения",
                            subtitle = if (accessType == 2) {
                                "После установки панель закроется на 127.0.0.1:3000 и будет " +
                                    "доступна по SSH-ключу. Nginx на 443 останется."
                            } else {
                                "После установки панель закроется на 127.0.0.1:${panelPort.ifBlank { "3000" }}: " +
                                    "порт в UFW закрывается, доступ — по SSH-ключу из приложения."
                            },
                            checked = sshOnly,
                            onCheckedChange = { sshOnly = it }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    PanelButton(
                        text = if (probing) "Проверяем…" else "Проверить сервер",
                        onClick = { startProbe() },
                        enabled = canProbe,
                        loading = probing,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Terminal
                    )

                    probeError?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        PanelCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "!",
                                    color = PanelRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelMuted
                                )
                            }
                        }
                    }

                    hostInfo?.let { info ->
                        Spacer(Modifier.height(12.dp))
                        HostInfoCard(
                            info = info,
                            onCopyFingerprint = {
                                copyToClipboard(context, info.fingerprint, "Отпечаток скопирован")
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        if (!info.isRoot) {
                            Text(
                                "Пользователь ${sshUser.trim()} не root — установщику нужен root.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PanelAmber
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        PanelButton(
                            text = "Установить панель",
                            onClick = { startInstall(info) },
                            enabled = canInstall && info.isRoot,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.PlayArrow
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Установка займёт 2–5 минут и не прерывается, если свернуть приложение. " +
                                "Не закрывайте экран до завершения.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AccessChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) PanelAccent.copy(alpha = 0.16f) else PanelSurface,
        border = BorderStroke(
            1.dp,
            if (selected) PanelAccent.copy(alpha = 0.45f) else PanelBorder
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) PanelAccent else PanelMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun HostInfoCard(info: HostInfo, onCopyFingerprint: () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сервер отвечает",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PanelGreen,
                modifier = Modifier.weight(1f)
            )
            Badge(if (info.isRoot) "root" else "не root", if (info.isRoot) PanelGreen else PanelAmber)
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("Система", info.osName)
        InfoRow("Архитектура", info.arch)
        Spacer(Modifier.height(8.dp))
        Text(
            "Отпечаток ключа",
            style = MaterialTheme.typography.labelSmall,
            color = PanelMuted
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                info.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = PanelText
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сверьте с сервером: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopyFingerprint) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Скопировать отпечаток",
                    tint = PanelMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun InstallLogCard(
    stage: String,
    progress: Float,
    log: List<String>,
    running: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onBackToForm: () -> Unit
) {
    val scroll = rememberScrollState()

    androidx.compose.runtime.LaunchedEffect(log.size) {
        if (log.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    Column {
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(
                        color = PanelAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = if (running) stage else "Установка остановлена",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (error != null) PanelRed else PanelText,
                    modifier = Modifier.weight(1f)
                )
                if (running) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = PanelMuted
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = PanelAccent,
                trackColor = PanelBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = PanelRed)
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF05080D),
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .heightIn(min = 180.dp, max = 320.dp)
                    .verticalScroll(scroll)
                    .padding(12.dp)
            ) {
                if (log.isEmpty()) {
                    Text(
                        "Ждём вывод установщика…",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelMuted
                    )
                }
                log.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.contains("ошиб", ignoreCase = true) ||
                                line.contains("error", ignoreCase = true) ||
                                line.contains("failed", ignoreCase = true) -> PanelRed

                            line.contains("установлена", ignoreCase = true) -> PanelGreen
                            else -> PanelText.copy(alpha = 0.86f)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (running) {
            PanelOutlinedButton(
                text = "Прервать",
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                color = PanelRed
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Прерывается только соединение: установщик на сервере продолжит работу.",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
        } else {
            PanelButton(
                text = "Вернуться к настройкам",
                onClick = onBackToForm,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InstallDoneCard(
    url: String,
    adminPassword: String,
    sshOnly: Boolean,
    onCopyPassword: () -> Unit,
    onOpen: () -> Unit,
    onAgain: () -> Unit
) {
    Column {
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Панель установлена",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PanelGreen,
                    modifier = Modifier.weight(1f)
                )
                Badge("готово", PanelGreen)
            }
            Spacer(Modifier.height(12.dp))
            if (sshOnly) {
                InfoRow("Доступ", "только по SSH-ключу", mono = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Порт панели закрыт в UFW, панель слушает 127.0.0.1. " +
                        "Приложение подключится само — ключ уже сохранён на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted
                )
            } else {
                InfoRow("Адрес", url, mono = true)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Логин",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
            Text("admin", style = MaterialTheme.typography.bodyMedium, color = PanelText)
            Spacer(Modifier.height(10.dp))
            Text(
                "Пароль",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted
            )
            SelectionContainer {
                Text(
                    adminPassword,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = PanelText
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelOutlinedButton(
                    text = "Скопировать",
                    onClick = onCopyPassword,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.ContentCopy
                )
                PanelOutlinedButton(
                    text = "Ещё раз",
                    onClick = onAgain,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Refresh
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        PanelButton(
            text = "Добавить сервер и войти",
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Сервер появится в списке. Пароль администратора сохранён только в панели " +
                "(/root/servpanel/admin_pass.txt).",
            style = MaterialTheme.typography.labelSmall,
            color = PanelMuted
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun randomPassword(): String =
    (1..16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
