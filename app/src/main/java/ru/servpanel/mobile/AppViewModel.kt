package ru.servpanel.mobile

import android.app.Application
import android.content.Context
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
import org.json.JSONArray
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ServersStore
import ru.servpanel.mobile.net.AppRelease
import ru.servpanel.mobile.net.AppUpdater
import ru.servpanel.mobile.net.LoginResult
import ru.servpanel.mobile.net.PanelApi
import ru.servpanel.mobile.net.PanelApiException
import ru.servpanel.mobile.net.PanelClient
import ru.servpanel.mobile.net.ProbeResult
import ru.servpanel.mobile.net.ServerHealth
import ru.servpanel.mobile.net.SshAccess
import ru.servpanel.mobile.net.SshProbeResult
import ru.servpanel.mobile.net.SshTunnels
import java.io.File

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
    Proxy("Прокси"),
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

/** Состояние проверки и загрузки обновления приложения. */
data class AppUpdateState(
    val checking: Boolean = false,
    val release: AppRelease? = null,
    val downloading: Boolean = false,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val ready: File? = null,
    val error: String? = null,
    val prompt: Boolean = false
) {
    val hasUpdate: Boolean get() = release != null

    val progress: Float?
        get() = if (downloading && total > 0L) {
            (downloaded.toFloat() / total).coerceIn(0f, 1f)
        } else {
            null
        }
}

/** Ход настройки SSH-доступа через панель (без входа на сервер). */
data class SshSetupState(
    val running: Boolean = false,
    val step: String = "",
    val error: String? = null
)

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

    /** Какой модуль открыть в разделе «Установка» (awg, mtproto, mtproxyl…). */
    var installModule by mutableStateOf("awg")
        private set

    var refreshing by mutableStateOf(false)
        private set

    var lastRefreshAt by mutableStateOf<Long?>(null)
        private set

    /** Растёт при ручном обновлении и после входа — экраны перезагружают данные. */
    var refreshTick by mutableIntStateOf(0)
        private set

    /** Обновление приложения: проверка, загрузка APK и предложение установки. */
    var appUpdate by mutableStateOf(AppUpdateState())
        private set

    /** Настройка SSH-доступа через панель. */
    var sshSetup by mutableStateOf(SshSetupState())
        private set

    private val updater = AppUpdater(app)
    private val appPrefs = app.getSharedPreferences("servpanel_app", Context.MODE_PRIVATE)

    private var pollJob: Job? = null
    private var refreshJob: Job? = null
    private var updateJob: Job? = null
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

    fun openMore(target: MoreScreen, installModuleKey: String? = null) {
        if (!installModuleKey.isNullOrBlank()) installModule = installModuleKey
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

    fun saveServer(profile: ServerProfile, keepSession: Boolean = false) {
        val previous = servers.firstOrNull { it.id == profile.id }
        val list = servers.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)

        servers = list
        store.write(list)
        health = health - profile.id

        if (previous == null ||
            previous.url != profile.url ||
            previous.panelPort != profile.panelPort ||
            previous.allowInsecure != profile.allowInsecure ||
            previous.sshTunnel != profile.sshTunnel ||
            previous.sshHost != profile.sshHost ||
            previous.sshPort != profile.sshPort ||
            previous.sshUser != profile.sshUser ||
            previous.sshKey != profile.sshKey ||
            previous.sshPassphrase != profile.sshPassphrase ||
            previous.sshHostKey != profile.sshHostKey ||
            previous.remotePanelPort != profile.remotePanelPort
        ) {
            val cached = clients.remove(profile.id)
            // При переходе на туннель сессию переносим на 127.0.0.1,
            // поэтому здесь её не выкидываем.
            if (!keepSession) cached?.clearSession()
            SshTunnels.invalidate(profile.id)
            if (!keepSession) auth = auth - profile.id
        }

        screen = Screen.Workspace(profile.id)
        workspaceTab = WorkspaceTab.Overview
        moreScreen = MoreScreen.None
        checkSession(profile)
        refreshOne(profile)
    }

    fun deleteServer(profile: ServerProfile) {
        clients.remove(profile.id)?.clearSession()
        SshTunnels.invalidate(profile.id)
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
                    val secure = if (server.sshTunnel) null else server.httpsVariant
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

    /** Проверка SSH-доступа из редактора: ключ, отпечаток и панель через туннель. */
    fun probeSsh(server: ServerProfile, onResult: (Result<SshProbeResult>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { SshAccess.probe(server) })
        }
    }

    /**
     * Настраивает SSH-доступ целиком через панель: ключ ставит сама панель,
     * root-пароль и вход на сервер не нужны.
     *
     * Порядок безопасный: сначала панель добавляет ключ (внешний доступ ещё
     * работает), приложение проверяет туннель и только потом просит закрыть
     * порт на loopback. Если проверка не прошла — ключ удаляется.
     */
    fun setupSshAccess(server: ServerProfile) {
        val fresh = servers.firstOrNull { it.id == server.id } ?: server
        if (fresh.sshTunnel || sshSetup.running) return
        sshSetup = SshSetupState(running = true, step = "Создаём ключ на телефоне…")
        viewModelScope.launch {
            var keyAdded = false
            var restricted = false
            try {
                val key = SshAccess.generateKey()
                val status = call(fresh) { sshAccessStatus() }
                val internalPort = status.optInt("panelPort", 3000).takeIf { it > 0 } ?: 3000
                val sshPort = status.optInt("sshPort", 22).takeIf { it > 0 } ?: 22

                sshSetup = sshSetup.copy(step = "Панель ставит ключ…")
                val enabledJson = call(fresh) { sshAccessEnable(key.publicKey, internalPort) }
                keyAdded = true

                val hostKeys = enabledJson.optJSONArray("hostKeys") ?: JSONArray()
                val hostKey = (0 until hostKeys.length())
                    .mapNotNull { hostKeys.optJSONObject(it) }
                    .firstOrNull { it.optString("type") != "ssh-ed25519" }
                    ?.optString("fingerprint")
                    .orEmpty()
                if (hostKey.isBlank()) {
                    throw IllegalStateException(
                        "На сервере нет RSA/ECDSA ключа хоста — приложение не сможет " +
                            "подключиться. Обновите панель и повторите."
                    )
                }

                val draft = fresh.copy(
                    sshTunnel = true,
                    sshHost = fresh.host,
                    sshPort = sshPort,
                    sshUser = enabledJson.optString("user").ifBlank { SshAccess.DEFAULT_USER },
                    sshKey = key.privateKey,
                    sshPassphrase = "",
                    sshHostKey = hostKey,
                    remotePanelPort = internalPort,
                    panelPort = 0,
                    metricsPort = 0,
                    allowInsecure = false
                )

                sshSetup = sshSetup.copy(step = "Проверяем туннель…")
                val before = SshAccess.probe(draft)
                if (!before.panelReachable) throw IllegalStateException(before.message)

                sshSetup = sshSetup.copy(step = "Закрываем внешний порт…")
                val restrict = call(fresh) { sshAccessRestrict() }
                restricted = true
                val restart = restrict.optInt("restartInMs", 1500).coerceIn(0, 5000)
                delay(restart + 1500L)

                sshSetup = sshSetup.copy(step = "Проверяем панель через туннель…")
                var reachable = false
                for (i in 0 until 5) {
                    val probe = runCatching { SshAccess.probe(draft) }.getOrNull()
                    if (probe?.panelReachable == true) {
                        reachable = true
                        break
                    }
                    delay(1500)
                }
                if (!reachable) {
                    throw IllegalStateException(
                        "Панель переведена на 127.0.0.1, но туннель не открылся. " +
                            "Проверьте SSH-доступ к серверу и при необходимости верните " +
                            "внешний порт командой pm2 restart awg-admin."
                    )
                }

                sshSetup = sshSetup.copy(step = "Сохраняем профиль…")
                // Сессия panel.sid выдана для внешнего адреса — переносим её
                // на 127.0.0.1, чтобы не просить пароль заново.
                clients[fresh.id]?.rebindSession("127.0.0.1", SshTunnels.portFor(draft.id))
                saveServer(draft, keepSession = true)
                sshSetup = SshSetupState()
            } catch (e: Exception) {
                // Пока панель ещё смотрит наружу, убираем ключ и откатываемся.
                if (keyAdded && !restricted) {
                    runCatching { call(fresh) { sshAccessDisable() } }
                }
                val message = when {
                    e is PanelApiException && e.status == 404 ->
                        "Панель не умеет выдавать ключ. Обновите её до 3.0.48 или новее."
                    else -> e.message ?: "Не удалось настроить доступ"
                }
                sshSetup = SshSetupState(error = message)
            }
        }
    }

    // ------------------------------------------------------------------
    // Обновление приложения
    // ------------------------------------------------------------------

    /**
     * Проверяет последний релиз на GitHub.
     *
     * @param promptIfNew показать диалог, если версия новее установленной.
     */
    fun checkAppUpdate(promptIfNew: Boolean = false) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            appUpdate = appUpdate.copy(checking = true, error = null)
            try {
                val release = updater.latest()
                val newer = release.versionCode > BuildConfig.VERSION_CODE
                appUpdate = appUpdate.copy(
                    checking = false,
                    release = if (newer) release else null,
                    error = null,
                    prompt = promptIfNew && newer && shouldPrompt(release.versionName)
                )
            } catch (e: Exception) {
                appUpdate = appUpdate.copy(
                    checking = false,
                    error = e.message ?: "Не удалось проверить обновления"
                )
            }
        }
    }

    /** Скачивает APK последнего релиза и сразу открывает установщик. */
    fun downloadAppUpdate() {
        val release = appUpdate.release ?: return
        if (appUpdate.downloading) return
        viewModelScope.launch {
            appUpdate = appUpdate.copy(
                downloading = true,
                downloaded = 0L,
                total = release.sizeBytes,
                error = null,
                prompt = false
            )
            try {
                val file = updater.download(release) { done, total ->
                    appUpdate = appUpdate.copy(downloaded = done, total = total)
                }
                appUpdate = appUpdate.copy(downloading = false, ready = file)
                installAppUpdate()
            } catch (e: Exception) {
                appUpdate = appUpdate.copy(
                    downloading = false,
                    error = e.message ?: "Не удалось скачать обновление"
                )
            }
        }
    }

    /** Открывает системный установщик для уже скачанного APK. */
    fun installAppUpdate() {
        val file = appUpdate.ready ?: return
        if (!updater.canInstall()) {
            appUpdate = appUpdate.copy(
                error = "Разрешите установку из Servpanel и нажмите «Установить» ещё раз"
            )
            updater.openInstallSettings()
            return
        }
        try {
            updater.install(file)
            appUpdate = appUpdate.copy(prompt = false)
        } catch (e: Exception) {
            appUpdate = appUpdate.copy(error = e.message ?: "Не удалось открыть установщик")
        }
    }

    /** «Позже»: не предлагать эту версию сутки. */
    fun dismissAppUpdatePrompt() {
        appUpdate.release?.versionName?.let { version ->
            appPrefs.edit()
                .putString(KEY_SKIP_VERSION, version)
                .putLong(KEY_SKIP_AT, System.currentTimeMillis())
                .apply()
        }
        appUpdate = appUpdate.copy(prompt = false)
    }

    private fun shouldPrompt(version: String): Boolean {
        val skipped = appPrefs.getString(KEY_SKIP_VERSION, null)
        if (skipped != version) return true
        val at = appPrefs.getLong(KEY_SKIP_AT, 0L)
        return System.currentTimeMillis() - at > PROMPT_AGAIN_MS
    }

    override fun onCleared() {
        super.onCleared()
        clients.values.forEach { it.clearSession() }
        clients.clear()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** Через сколько снова предлагать отложенное обновление. */
        const val PROMPT_AGAIN_MS = 24 * 60 * 60 * 1000L

        const val KEY_SKIP_VERSION = "update_skip_version"
        const val KEY_SKIP_AT = "update_skip_at"
    }
}
