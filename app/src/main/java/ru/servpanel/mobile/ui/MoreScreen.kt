package ru.servpanel.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.BuildConfig
import ru.servpanel.mobile.MoreScreen
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

private data class MoreEntry(
    val target: MoreScreen,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private val moreEntries = listOf(
    MoreEntry(MoreScreen.Obfuscation, "Обфускация", "Параметры AmneziaWG и профили", Icons.Filled.Tune),
    MoreEntry(MoreScreen.Backups, "Бэкапы", "Архивы, автобэкап и пароль", Icons.Filled.Backup),
    MoreEntry(MoreScreen.Ssl, "Домены и SSL", "Let's Encrypt, Nginx и сертификаты", Icons.Filled.Language),
    MoreEntry(MoreScreen.Updates, "Обновления", "GitHub, архив и откат панели", Icons.Filled.SystemUpdate),
    MoreEntry(MoreScreen.Install, "Установка", "AmneziaWG, AdGuard, MTProto, WARP", Icons.Filled.Download),
    MoreEntry(MoreScreen.Proxy, "Прокси", "MTProxyL, Telemt: пользователи и лимиты", Icons.Filled.Send),
    MoreEntry(MoreScreen.Diagnostics, "Диагностика", "Ping, traceroute и журналы", Icons.Filled.NetworkCheck),
    MoreEntry(MoreScreen.Services, "Службы", "Перезапуск системных сервисов", Icons.Filled.Settings),
    MoreEntry(MoreScreen.Users, "Пользователи", "Доступы, роли и 2FA", Icons.Filled.Group),
    MoreEntry(MoreScreen.ServerInfo, "Информация", "IP, домен и сертификат сервера", Icons.Filled.Info)
)

@Composable
fun MoreScreenList(vm: AppViewModel, server: ServerProfile) {
    val auth = vm.authOf(server.id)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ScreenHeader(
                title = "Ещё",
                subtitle = "Все разделы панели ${server.name}"
            )
        }

        items(moreEntries, key = { it.target.name }) { entry ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PanelSurface,
                border = BorderStroke(1.dp, PanelBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.openMore(entry.target) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PanelAccent.copy(alpha = 0.12f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            entry.icon,
                            contentDescription = null,
                            tint = PanelAccent,
                            modifier = Modifier.padding(9.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = PanelText
                        )
                        Text(
                            entry.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = PanelMuted
                    )
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
                Column(Modifier.padding(14.dp)) {
                    InfoRow("Пользователь", auth.user.ifBlank { "—" })
                    InfoRow("Роль", if (auth.isAdmin) "Администратор" else "Только просмотр")
                    InfoRow("Адрес панели", server.displayHost)
                    if (!auth.isAdmin) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Роль «только просмотр»: изменения в панели недоступны.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PanelSurface,
                border = BorderStroke(1.dp, PanelBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.logout(server) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null, tint = PanelRed)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Выйти из панели",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PanelRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        item {
            val uriHandler = LocalUriHandler.current
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PanelSurface,
                border = BorderStroke(1.dp, PanelBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        uriHandler.openUri("https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest")
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = PanelAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Версия ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = PanelText
                        )
                        Text(
                            "Скачать обновление на GitHub",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = PanelMuted
                    )
                }
            }
        }

        item {
            Text(
                text = "Servpanel Mobile — нативный клиент панели Servpanel.",
                style = MaterialTheme.typography.labelSmall,
                color = PanelMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
