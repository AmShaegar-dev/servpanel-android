package ru.servpanel.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ServersStore
import ru.servpanel.mobile.net.LoginResult
import ru.servpanel.mobile.net.PanelApi
import ru.servpanel.mobile.net.PanelApiException
import ru.servpanel.mobile.net.PanelClient
import ru.servpanel.mobile.net.ProbeResult
import ru.servpanel.mobile.net.ServerHealth

sealed interface Screen {
    data object Servers : Screen
    data class Workspace(val serverId: String) : Screen
    data class Editor(val serverId: String?) : Screen
    data object InstallPanel : Screen
}

enum class WorkspaceTab(val title: String) {
    Overview("Обзор"),
    Clients("Клиенты"),
    Network("Сеть"),
    Security("Защита"),
    More("Ещё")
}

enum class MoreScreen(val title: String) {
    None(""),
    Obfuscation("Обфускация"),
    Backups("Бэкапы"),
    Ssl("Домены и SSL"),
    Updates("Обновления"),
    Install("Установка"),
    Diagnostics("Диагностика"),
    Services("Службы"),
    Users("Пользователи"),
    ServerInfo("Информация")
}

enum class AuthStatus { Unknown, Checking, LoggedOut, LoggedIn, Error }

data class AuthState(
    val status: AuthStatus = AuthStatus.Unknown,
    val user: String = "",
    val role: String = "admin",
    val defaultPassword: Boolean = false,
    val error: String? = null
) {
    val isAdmin: Boolean get() = role != "viewer"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ServersStore(app)
    private val api = PanelApi(app)
    private val clients = mutableMapOf<String, PanelClient>()

    var servers by mutableStateOf(store.read())
        private set

    var health by mutableStateOf<Map<String, ServerHealth>>(emptyMap())
        private set

    var auth by mutableStateOf<Map<String, AuthState>>(emptyMap())
        private set

    var screen by mutableStateOf<Screen>(Screen.Servers)
        private set

    var workspaceTab by mutableStateOf(WorkspaceTab.Overview)
        private set

    var moreScreen by mutableStateOf(MoreScreen.None)
        private set

    var refreshing by mutableStateOf(false)
        private set

    var lastRefreshAt by mutableStateOf<Long?>(null)
        private set

    /** Растёт при ручном обновлении и после входа — экраны перезагружают данные. */
    var refreshTick by mutableIntStateOf(0)
        private set

    private var pollJob: Job? = null
    private var refreshJob: Job? = null
    private var editorReturn: Screen = Screen.Servers

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    fun openServer(id: String) {
        val server = serverById(id) ?: return
        workspaceTab = WorkspaceTab.Overview
        moreScreen = MoreScreen.None
        screen = Screen.Workspace(id)
        checkSession(server)
        refreshOne(server)
    }

    fun openServers() {
        screen = Screen.Servers
    }

    fun openEditor(serverId: String?) {
        if (screen !is Screen.Editor) editorReturn = screen
        screen = Screen.Editor(serverId)
    }

    fun closeEditor() {
        screen = editorReturn
    }

    fun openInstall() {
        screen = Screen.InstallPanel
    }

    fun closeInstall() {
        screen = Screen.Servers
    }

    fun selectTab(tab: WorkspaceTab) {
        workspaceTab = tab
        moreScreen = MoreScreen.None
    }

    fun openMore(target: MoreScreen) {
        moreScreen = target
    }

    fun closeMore() {
        moreScreen = MoreScreen.None
    }

    fun serverById(id: String?): ServerProfile? =
        id?.let { target -> servers.firstOrNull { it.id == target } }

    fun client(server: ServerProfile): PanelClient =
        clients.getOrPut(server.id) { PanelClient(server, getApplication()) }

    fun authOf(id: String): AuthState = auth[id] ?: AuthState()

    // ------------------------------------------------------------------
    // Серверы
    // ------------------------------------------------------------------

    fun saveServer(profile: ServerProfile) {
        val previous = servers.firstOrNull { it.id == profile.id }
        val list = servers.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)

        servers = list
        store.write(list)
        health = health - profile.id

        if (previous == null ||
            previous.url != profile.url ||
            previous.allowInsecure != profile.allowInsecure
        ) {
            clients.remove(profile.id)?.clearSession()
            auth = auth - profile.id
        }

        screen = Screen.Workspace(profile.id)
        workspaceTab = WorkspaceTab.Overview
        moreScreen = MoreScreen.None
        checkSession(profile)
        refreshOne(profile)
    }

    fun deleteServer(profile: ServerProfile) {
        clients.remove(profile.id)?.clearSession()
        auth = auth - profile.id
        health = health - profile.id

        val list = servers.filterNot { it.id == profile.id }
        servers = list
        store.write(list)

        if ((screen as? Screen.Workspace)?.serverId == profile.id) screen = Screen.Servers
        if ((screen as? Screen.Editor)?.serverId == profile.id) screen = Screen.Servers
    }

    fun rememberTrust(serverId: String) {
        val updated = servers.map {
            if (it.id == serverId) it.copy(allowInsecure = true) else it
        }
        servers = updated
        store.write(updated)
        clients.remove(serverId)?.clearSession()
        auth = auth - serverId
        updated.firstOrNull { it.id == serverId }?.let { profile ->
            health = health - serverId
            checkSession(profile)
            refreshOne(profile)
        }
    }

    // ------------------------------------------------------------------
    // Состояние серверов
    // ------------------------------------------------------------------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshAll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshAll() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { performRefresh() }
    }

    private suspend fun performRefresh() {
        val targets = servers
        if (targets.isEmpty()) {
            lastRefreshAt = System.currentTimeMillis()
            return
        }
        refreshing = true
        try {
            coroutineScope {
                targets.forEach { server ->
                    launch {
                        val result = api.check(server)
                        health = health + (server.id to result)
                    }
                }
            }
        } finally {
            refreshing = false
            lastRefreshAt = System.currentTimeMillis()
        }
    }

    fun refreshOne(server: ServerProfile) {
        viewModelScope.launch {
            val result = api.check(server)
            health = health + (server.id to result)
        }
    }

    fun refreshWorkspace() {
        refreshTick++
        serverById((screen as? Screen.Workspace)?.serverId)?.let(::refreshOne)
    }

    // ------------------------------------------------------------------
    // Вход и сессия
    // ------------------------------------------------------------------

    fun checkSession(server: ServerProfile) {
        auth = auth + (server.id to AuthState(status = AuthStatus.Checking))
        viewModelScope.launch {
            try {
                val status = client(server).session()
                auth = auth + (
                    server.id to if (status == null) {
                        AuthState(status = AuthStatus.LoggedOut)
                    } else {
                        AuthState(
                            status = AuthStatus.LoggedIn,
                            user = status.user,
                            role = status.role
                        )
                    }
                    )
                if (status != null) refreshTick++
            } catch (e: Exception) {
                auth = auth + (
                    server.id to AuthState(
                        status = AuthStatus.Error,
                        error = e.message ?: "Не удалось подключиться"
                    )
                    )
            }
        }
    }

    fun login(
        server: ServerProfile,
        username: String,
        password: String,
        code: String,
        onResult: (Result<LoginResult>) -> Unit
    ) {
        auth = auth + (server.id to authOf(server.id).copy(status = AuthStatus.Checking, error = null))
        viewModelScope.launch {
            try {
                var used = server
                val result = try {
                    client(used).login(username, password, code)
                } catch (e: PanelApiException) {
                    // Панель за nginx: HTTP отвечает 301 на HTTPS, OkHttp
                    // превращает POST в GET, и /api/login отдаёт 404.
                    // Пробуем тот же адрес по HTTPS и запоминаем его.
                    val secure = server.httpsVariant
                    if (e.status != 404 || secure == null) throw e
                    val secureClient = PanelClient(secure, getApplication())
                    val upgraded = secureClient.login(username, password, code)
                    clients[server.id] = secureClient
                    applyUrlUpgrade(secure)
                    used = secure
                    upgraded
                }
                auth = auth + (
                    server.id to AuthState(
                        status = AuthStatus.LoggedIn,
                        user = result.user,
                        role = result.role,
                        defaultPassword = result.defaultPassword
                    )
                    )
                refreshTick++
                refreshOne(used)
                onResult(Result.success(result))
            } catch (e: Exception) {
                val message = e.message ?: "Не удалось войти"
                auth = auth + (
                    server.id to AuthState(
                        status = AuthStatus.LoggedOut,
                        error = message
                    )
                    )
                onResult(Result.failure(e))
            }
        }
    }

    /** Тихая замена адреса сервера (например, http → https) без сброса сессии. */
    private fun applyUrlUpgrade(profile: ServerProfile) {
        val list = servers.map { if (it.id == profile.id) profile else it }
        servers = list
        store.write(list)
    }

    fun logout(server: ServerProfile) {
        viewModelScope.launch {
            runCatching { client(server).logout() }
            auth = auth + (server.id to AuthState(status = AuthStatus.LoggedOut))
            health = health - server.id
            refreshOne(server)
        }
    }

    /** Единая точка вызова API: при 401 переключает экран на форму входа. */
    suspend fun <T> call(server: ServerProfile, block: suspend PanelClient.() -> T): T {
        return try {
            client(server).block()
        } catch (e: PanelApiException) {
            if (e.status == 401) {
                auth = auth + (server.id to AuthState(status = AuthStatus.LoggedOut))
            }
            throw e
        }
    }

    fun probe(raw: String, allowInsecure: Boolean, onResult: (ProbeResult) -> Unit) {
        viewModelScope.launch {
            val result = api.probe(raw, allowInsecure)
            onResult(result)
        }
    }

    override fun onCleared() {
        super.onCleared()
        clients.values.forEach { it.clearSession() }
        clients.clear()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
