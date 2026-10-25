package ru.servpanel.mobile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.servpanel.mobile.ui.EditorScreen
import ru.servpanel.mobile.ui.InstallPanelScreen
import ru.servpanel.mobile.ui.ServersScreen
import ru.servpanel.mobile.ui.WorkspaceScreen

@Composable
fun AppRoot(vm: AppViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.startPolling()
                Lifecycle.Event.ON_STOP -> vm.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            vm.startPolling()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopPolling()
        }
    }

    when (val screen = vm.screen) {
        is Screen.Servers -> ServersScreen(
            servers = vm.servers,
            health = vm.health,
            refreshing = vm.refreshing,
            lastRefreshAt = vm.lastRefreshAt,
            onOpen = vm::openServer,
            onAdd = { vm.openEditor(null) },
            onEdit = { vm.openEditor(it) },
            onDelete = vm::deleteServer,
            onRefreshAll = vm::refreshAll,
            onRefreshOne = vm::refreshOne,
            onOpenExternal = { openExternal(vm, it) },
            onInstall = vm::openInstall
        )

        Screen.InstallPanel -> InstallPanelScreen(
            vm = vm,
            onBack = vm::closeInstall,
            onInstalled = { profile ->
                vm.saveServer(profile)
                vm.openServer(profile.id)
            }
        )

        is Screen.Workspace -> {
            val server = vm.serverById(screen.serverId)
            if (server == null) {
                LaunchedEffect(screen.serverId) { vm.openServers() }
            } else {
                WorkspaceScreen(
                    vm = vm,
                    server = server,
                    onBack = vm::openServers
                )
            }
        }

        is Screen.Editor -> {
            val existing = screen.serverId?.let(vm::serverById)
            EditorScreen(
                existing = existing,
                servers = vm.servers,
                onBack = vm::closeEditor,
                onSave = vm::saveServer,
                onDelete = vm::deleteServer,
                onProbe = vm::probe
            )
        }
    }
}

private fun openExternal(vm: AppViewModel, url: String) {
    try {
        val context = vm.getApplication<android.app.Application>()
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        val context = vm.getApplication<android.app.Application>()
        Toast.makeText(context, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
    }
}
