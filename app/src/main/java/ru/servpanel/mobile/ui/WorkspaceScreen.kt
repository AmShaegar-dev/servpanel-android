package ru.servpanel.mobile.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.servpanel.mobile.AppViewModel
import ru.servpanel.mobile.AuthStatus
import ru.servpanel.mobile.MoreScreen
import ru.servpanel.mobile.WorkspaceTab
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.net.LoginResult
import ru.servpanel.mobile.ui.theme.PanelAccent
import ru.servpanel.mobile.ui.theme.PanelBg
import ru.servpanel.mobile.ui.theme.PanelBorder
import ru.servpanel.mobile.ui.theme.PanelElevated
import ru.servpanel.mobile.ui.theme.PanelMuted
import ru.servpanel.mobile.ui.theme.PanelRed
import ru.servpanel.mobile.ui.theme.PanelSurface
import ru.servpanel.mobile.ui.theme.PanelText

@Composable
fun WorkspaceScreen(
    vm: AppViewModel,
    server: ServerProfile,
    onBack: () -> Unit
) {
    val auth = vm.authOf(server.id)
    val context = LocalContext.current

    when (auth.status) {
        AuthStatus.Unknown, AuthStatus.Checking -> FullScreenLoading("Проверяем сессию панели…")

        AuthStatus.LoggedOut, AuthStatus.Error -> LoginScreen(
            server = server,
            auth = auth,
            onLogin = { username, password, code, callback ->
                vm.login(server, username, password, code, callback)
            },
            onBack = onBack,
            onEditServer = { vm.openEditor(server.id) }
        )

        AuthStatus.LoggedIn -> WorkspaceContent(
            vm = vm,
            server = server,
            onBack = onBack,
            onOpenExternal = { openUrl(context, server.url) }
        )
    }
}

@Composable
private fun WorkspaceContent(
    vm: AppViewModel,
    server: ServerProfile,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val more = vm.moreScreen
    val title = if (more != MoreScreen.None) more.title else vm.workspaceTab.title

    BackHandler {
        when {
            more != MoreScreen.None -> vm.closeMore()
            vm.workspaceTab != WorkspaceTab.Overview -> vm.selectTab(WorkspaceTab.Overview)
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = PanelBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelBg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        when {
                            more != MoreScreen.None -> vm.closeMore()
                            vm.workspaceTab != WorkspaceTab.Overview -> vm.selectTab(WorkspaceTab.Overview)
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = PanelText)
                    }

                    ServerAvatar(server.name, server.color, 36.dp)

                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (more != MoreScreen.None) server.name else title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PanelText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (more != MoreScreen.None) more.title else server.displayHost,
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { vm.refreshWorkspace() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = PanelText)
                    }

                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = PanelText)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = PanelElevated
                        ) {
                            DropdownMenuItem(
                                text = { Text("Настройки сервера") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.openEditor(server.id)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Открыть панель в браузере") },
                                leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenExternal()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Выйти из панели") },
                                leadingIcon = { Icon(Icons.Filled.Logout, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmLogout = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить сервер", color = PanelRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = PanelRed) },
                                onClick = {
                                    menuOpen = false
                                    vm.deleteServer(server)
                                }
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(PanelBorder)
                )
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(PanelBorder)
                )
                Surface(color = PanelSurface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BottomItem(
                            icon = Icons.Filled.Dashboard,
                            label = "Обзор",
                            selected = vm.workspaceTab == WorkspaceTab.Overview && more == MoreScreen.None,
                            onClick = { vm.selectTab(WorkspaceTab.Overview) }
                        )
                        BottomItem(
                            icon = Icons.Filled.People,
                            label = "Клиенты",
                            selected = vm.workspaceTab == WorkspaceTab.Clients && more == MoreScreen.None,
                            onClick = { vm.selectTab(WorkspaceTab.Clients) }
                        )
                        BottomItem(
                            icon = Icons.Filled.Lan,
                            label = "Сеть",
                            selected = vm.workspaceTab == WorkspaceTab.Network && more == MoreScreen.None,
                            onClick = { vm.selectTab(WorkspaceTab.Network) }
                        )
                        BottomItem(
                            icon = Icons.Filled.Shield,
                            label = "Защита",
                            selected = vm.workspaceTab == WorkspaceTab.Security && more == MoreScreen.None,
                            onClick = { vm.selectTab(WorkspaceTab.Security) }
                        )
                        BottomItem(
                            icon = Icons.Filled.MoreHoriz,
                            label = "Ещё",
                            selected = vm.workspaceTab == WorkspaceTab.More,
                            onClick = { vm.selectTab(WorkspaceTab.More) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (vm.workspaceTab) {
                WorkspaceTab.Overview -> OverviewScreen(vm, server)
                WorkspaceTab.Clients -> ClientsScreen(vm, server)
                WorkspaceTab.Network -> NetworkScreen(vm, server)
                WorkspaceTab.Security -> SecurityScreen(vm, server)
                WorkspaceTab.More -> {
                    when (more) {
                        MoreScreen.None -> MoreScreenList(vm, server)
                        MoreScreen.Obfuscation -> ObfuscationScreen(vm, server)
                        MoreScreen.Backups -> BackupsScreen(vm, server)
                        MoreScreen.Ssl -> SslScreen(vm, server)
                        MoreScreen.Updates -> UpdatesScreen(vm, server)
                        MoreScreen.Install -> InstallScreen(vm, server)
                        MoreScreen.Diagnostics -> DiagnosticsScreen(vm, server)
                        MoreScreen.Services -> ServicesScreen(vm, server)
                        MoreScreen.Users -> UsersScreen(vm, server)
                        MoreScreen.ServerInfo -> ServerInfoScreen(vm, server)
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = PanelElevated,
            title = { Text("Выйти из панели?") },
            text = { Text("Сессия на сервере будет завершена. Чтобы вернуться, потребуется ввести пароль снова.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout(server)
                }) { Text("Выйти", color = PanelRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun BottomItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) PanelAccent else PanelMuted
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (selected) PanelAccent.copy(alpha = 0.12f) else Color.Transparent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
