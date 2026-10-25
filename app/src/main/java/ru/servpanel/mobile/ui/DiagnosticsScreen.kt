package ru.servpanel.mobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelGreen
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

private val logFiles = listOf(
    "/var/log/awg_install.log" to "Установка AmneziaWG",
    "/var/log/awg_uninstall.log" to "Удаление AmneziaWG",
    "/var/log/awg_audit.log" to "Аудит AWG",
    "/var/log/ru-drop.log" to "GeoIP-блокировка",
    "/var/log/mtproto_install.log" to "Установка MTProto",
    "/var/log/adguard_install.log" to "Установка AdGuard",
    "/var/log/warp_install.log" to "Установка WARP",
    "/var/log/nginx/error.log" to "Nginx: ошибки",
    "/var/log/nginx/access.log" to "Nginx: доступ",
    "/var/log/auth.log" to "Авторизация",
    "/var/log/syslog" to "Syslog"
)

@Composable
fun DiagnosticsScreen(vm: AppViewModel, server: ServerProfile) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var host by remember { mutableStateOf("1.1.1.1") }
    var output by remember { mutableStateOf("") }
    var outputOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var logName by remember { mutableStateOf(logFiles.first().first) }
    var logText by remember { mutableStateOf("") }
    var logBusy by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "Диагностика",
                subtitle = "Ping, traceroute и журналы сервера"
            )
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Сеть",
                    subtitle = "Проверка доступности хоста с сервера"
                )

                Spacer(Modifier.height(10.dp))

                PanelTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = "IP или домен",
                    placeholder = "1.1.1.1",
                    enabled = !busy
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(
                        text = "Ping",
                        onClick = {
                            busy = true
                            output = ""
                            scope.launch {
                                try {
                                    val result = vm.call(server) { ping(host) }
                                    output = result.output.ifBlank { "Нет вывода" }
                                    outputOk = result.ok
                                } catch (e: Exception) {
                                    output = e.message ?: "Ошибка"
                                    outputOk = false
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = busy,
                        icon = Icons.Filled.NetworkCheck,
                        enabled = host.isNotBlank()
                    )
                    PanelOutlinedButton(
                        text = "Traceroute",
                        onClick = {
                            busy = true
                            output = ""
                            scope.launch {
                                try {
                                    val result = vm.call(server) { traceroute(host) }
                                    output = result.output.ifBlank { "Нет вывода" }
                                    outputOk = result.ok
                                } catch (e: Exception) {
                                    output = e.message ?: "Ошибка"
                                    outputOk = false
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = busy,
                        enabled = host.isNotBlank()
                    )
                }

                if (output.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    OutputBox(output, outputOk) {
                        copyToClipboard(context, output, "Вывод скопирован")
                    }
                }
            }
        }

        item {
            PanelCard {
                SectionHeader(
                    title = "Журналы",
                    subtitle = "Последние 300 строк",
                    trailing = {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = PanelMuted)
                    }
                )

                Spacer(Modifier.height(10.dp))

                PanelDropdown(
                    label = "Файл журнала",
                    options = logFiles.map { it.first },
                    selected = logName,
                    onSelect = { logName = it }
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    logFiles.firstOrNull { it.first == logName }?.second.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted
                )

                Spacer(Modifier.height(12.dp))

                PanelButton(
                    text = "Открыть журнал",
                    onClick = {
                        logBusy = true
                        logText = ""
                        scope.launch {
                            try {
                                logText = vm.call(server) { diagLog(logName) }
                            } catch (e: Exception) {
                                logText = e.message ?: "Не удалось прочитать журнал"
                            } finally {
                                logBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = logBusy,
                    icon = Icons.Filled.PlayArrow
                )

                if (logText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    OutputBox(logText.takeLast(20000), true) {
                        copyToClipboard(context, logText, "Журнал скопирован")
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputBox(text: String, ok: Boolean, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PanelSurface,
        border = BorderStroke(1.dp, PanelBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ok) "Результат" else "Ошибка",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ok) PanelGreen else PanelRed,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = PanelText
                )
            }
            Spacer(Modifier.height(8.dp))
            PanelOutlinedButton(
                text = "Скопировать",
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
