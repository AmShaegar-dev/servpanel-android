package ru.servpanel.mobile

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    // При запуске проверяем последний релиз и предлагаем обновление.
    // Состояние дублируем в лог: видно, дошёл ли запрос до GitHub.
    val updateState = vm.appUpdate
    LaunchedEffect(Unit) {
        Log.i("ServpanelUpdate", "start check, current=${BuildConfig.VERSION_NAME}")
        vm.checkAppUpdate(promptIfNew = true)
    }
    LaunchedEffect(updateState) {
        Log.i(
            "ServpanelUpdate",
            "checking=${updateState.checking} release=${updateState.release?.versionName} " +
                "current=${BuildConfig.VERSION_NAME} error=${updateState.error} " +
                "prompt=${updateState.prompt}"
        )
    }

    when (val screen = vm.screen) {
        is Screen.Servers -> ServersScreen(
            servers = vm.servers,
            health = vm.health,
            refreshing = vm.refreshing,
            lastRefreshAt = vm.lastRefreshAt,
            appUpdate = vm.appUpdate,
            onOpen = vm::openServer,
            onAdd = { vm.openEditor(null) },
            onEdit = { vm.openEditor(it) },
            onDelete = vm::deleteServer,
            onRefreshAll = vm::refreshAll,
            onRefreshOne = vm::refreshOne,
            onOpenExternal = { openExternal(vm, it) },
            onInstall = vm::openInstall,
            onUpdateApp = vm::downloadAppUpdate,
            onLaterUpdate = vm::dismissAppUpdatePrompt
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
                sshSetup = vm.sshSetup,
                onBack = vm::closeEditor,
                onSave = vm::saveServer,
                onDelete = vm::deleteServer,
                onProbe = vm::probe,
                onProbeSsh = vm::probeSsh,
                onSetupSsh = { existing?.let(vm::setupSshAccess) }
            )
        }
    }

    if (vm.appUpdate.prompt) {
        val release = vm.appUpdate.release
        if (release != null) {
            AlertDialog(
                onDismissRequest = vm::dismissAppUpdatePrompt,
                title = { Text("Доступно обновление ${release.versionName}") },
                text = {
                    Text(
                        "Скачать новую версию с GitHub и установить поверх текущей? " +
                            "Серверы и настройки сохранятся."
                    )
                },
                confirmButton = {
                    TextButton(onClick = vm::downloadAppUpdate) { Text("Обновить") }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissAppUpdatePrompt) { Text("Позже") }
                }
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
