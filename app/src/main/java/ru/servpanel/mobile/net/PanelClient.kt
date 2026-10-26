package ru.servpanel.mobile.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import ru.servpanel.mobile.data.ArchiveInfo
import ru.servpanel.mobile.data.AuditEntry
import ru.servpanel.mobile.data.AwgConfig
import ru.servpanel.mobile.data.BackupAuto
import ru.servpanel.mobile.data.BackupRunResult
import ru.servpanel.mobile.data.BulkConfig
import ru.servpanel.mobile.data.BulkRenameResult
import ru.servpanel.mobile.data.CascadeHealth
import ru.servpanel.mobile.data.CascadeStatus
import ru.servpanel.mobile.data.ClientInfo
import ru.servpanel.mobile.data.ClientKey
import ru.servpanel.mobile.data.DiagResult
import ru.servpanel.mobile.data.Fail2banStatus
import ru.servpanel.mobile.data.GitCheck
import ru.servpanel.mobile.data.HistoryData
import ru.servpanel.mobile.data.InstallProgress
import ru.servpanel.mobile.data.InstallState
import ru.servpanel.mobile.data.MetricsSnapshot
import ru.servpanel.mobile.data.NotifyStatus
import ru.servpanel.mobile.data.ObfApplyResult
import ru.servpanel.mobile.data.ObfCurrent
import ru.servpanel.mobile.data.ObfGenerated
import ru.servpanel.mobile.data.ObfProfiles
import ru.servpanel.mobile.data.ProxyBotStatus
import ru.servpanel.mobile.data.ProxyStatus
import ru.servpanel.mobile.data.ProxyUser
import ru.servpanel.mobile.data.ProxyUserLinks
import ru.servpanel.mobile.data.SecurityStatus
import ru.servpanel.mobile.data.ServerInfo
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.ServiceInfo
import ru.servpanel.mobile.data.SslSiteResult
import ru.servpanel.mobile.data.SslStatus
import ru.servpanel.mobile.data.SysInfo
import ru.servpanel.mobile.data.ToolsStatus
import ru.servpanel.mobile.data.TwoFaSetup
import ru.servpanel.mobile.data.TwoFaStatus
import ru.servpanel.mobile.data.UfwData
import ru.servpanel.mobile.data.UpdateInfo
import ru.servpanel.mobile.data.UpdateStatus
import ru.servpanel.mobile.data.UsersPayload
import ru.servpanel.mobile.data.WarpStatus
import ru.servpanel.mobile.data.WarpTestResult
import ru.servpanel.mobile.data.objects
import ru.servpanel.mobile.data.stringList
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class PanelApiException(
    val status: Int,
    override val message: String,
    val need2fa: Boolean = false,
    val body: JSONObject? = null
) : Exception(message)

data class LoginResult(
    val user: String,
    val role: String,
    val defaultPassword: Boolean
)

/**
 * REST-клиент одной панели Servpanel.
 *
 * Все запросы идут через OkHttp с cookie-сессией panel.sid, привязанной
 * к профилю сервера. Никакого WebView: ответы разбираются в модели.
 */
class PanelClient(
    val server: ServerProfile,
    context: Context
) {

    private val cookieJar = PersistentCookieJar(context, server.id)
    private val baseUrl: String = server.apiUrl.trimEnd('/')

    private val client: OkHttpClient = HttpClients.forServer(server.allowInsecure)
        .newBuilder()
        .cookieJar(cookieJar)
        .build()

    fun clearSession() = cookieJar.clear()

    // ------------------------------------------------------------------
    // Базовые запросы
    // ------------------------------------------------------------------

    private fun urlFor(path: String, query: Map<String, String>): HttpUrl? {
        val base = (baseUrl + path).toHttpUrlOrNull() ?: return null
        if (query.isEmpty()) return base
        val builder = base.newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        timeoutSeconds: Long = 45
    ): Response = withContext(Dispatchers.IO) {
        val url = urlFor(path, query)
            ?: throw PanelApiException(0, "Некорректный адрес панели")
        // OkHttp требует тело для POST/PUT/PATCH. Панельные ручки вроде
        // /api/2fa/setup тела не ждут, поэтому отправляем пустой JSON.
        val requestBody = body ?: when (method) {
            "POST", "PUT", "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "ServpanelMobile")
            .build()
        val callClient = client.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        try {
            callClient.newCall(request).execute()
        } catch (e: UnknownHostException) {
            throw PanelApiException(0, "Сервер не найден. Проверьте адрес панели.")
        } catch (e: ConnectException) {
            throw PanelApiException(0, "Не удалось подключиться к панели.")
        } catch (e: SocketTimeoutException) {
            throw PanelApiException(0, "Панель не ответила вовремя.")
        } catch (e: SSLException) {
            throw PanelApiException(
                0,
                "Ошибка TLS-сертификата. Если сертификат самоподписанный — включите это в настройках сервера."
            )
        } catch (e: IOException) {
            throw PanelApiException(0, e.message ?: "Ошибка сети")
        }
    }

    private fun errorFrom(status: Int, text: String): PanelApiException {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val message = json?.optString("error")?.takeIf { it.isNotBlank() }
            ?: when (status) {
                401 -> "Нужно войти в панель"
                403 -> "Недостаточно прав (роль «только просмотр»)"
                404 -> "Раздел недоступен на этом сервере"
                429 -> "Слишком много попыток. Подождите."
                else -> "Панель вернула ошибку HTTP $status"
            }
        return PanelApiException(
            status = status,
            message = message,
            need2fa = json?.optBoolean("need2fa", false) ?: false,
            body = json
        )
    }

    private suspend fun text(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        json: JSONObject? = null,
        timeoutSeconds: Long = 45
    ): String = withContext(Dispatchers.IO) {
        val body = json?.toString()?.toRequestBody(JSON)
        execute(method, path, query, body, timeoutSeconds).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, raw)
            raw
        }
    }

    private suspend fun json(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        timeoutSeconds: Long = 45
    ): JSONObject {
        val raw = text(method, path, query, body, timeoutSeconds)
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw PanelApiException(0, "Панель вернула неожиданный ответ")
        }
    }

    private suspend fun array(
        path: String,
        query: Map<String, String> = emptyMap()
    ): JSONArray {
        val raw = text("GET", path, query)
        if (raw.isBlank()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            throw PanelApiException(0, "Панель вернула неожиданный ответ")
        }
    }

    suspend fun rawText(
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 60
    ): String = text("GET", path, query, null, timeoutSeconds)

    suspend fun rawBytes(path: String, timeoutSeconds: Long = 120): ByteArray =
        withContext(Dispatchers.IO) {
            execute("GET", path, timeoutSeconds = timeoutSeconds).use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val raw = bytes.toString(Charsets.UTF_8)
                    throw errorFrom(response.code, raw)
                }
                bytes
            }
        }

    // ------------------------------------------------------------------
    // Вход / сессия
    // ------------------------------------------------------------------

    suspend fun login(username: String, password: String, code: String): LoginResult {
        val body = JSONObject()
            .put("username", username.ifBlank { "admin" })
            .put("password", password)
        if (code.isNotBlank()) body.put("code", code.trim())
        val result = json("POST", "/api/login", body = body)
        if (!result.optBoolean("success", false)) {
            throw PanelApiException(
                200,
                result.optString("error", "Не удалось войти"),
                result.optBoolean("need2fa", false)
            )
        }
        return LoginResult(
            user = result.optString("user", username.ifBlank { "admin" }),
            role = result.optString("role", "admin"),
            defaultPassword = result.optBoolean("defaultPassword", false)
        )
    }

    suspend fun logout() {
        runCatching { json("POST", "/api/logout") }
        cookieJar.clear()
    }

    /** Проверка живой сессии. null — панель требует вход. */
    suspend fun session(): SecurityStatus? {
        return try {
            SecurityStatus.from(json("GET", "/api/security/status"))
        } catch (e: PanelApiException) {
            if (e.status == 401) null else throw e
        }
    }

    // ------------------------------------------------------------------
    // Метрики и история
    // ------------------------------------------------------------------

    suspend fun sysinfo(): SysInfo = SysInfo.from(json("GET", "/api/sysinfo"))

    /**
     * Снимок с отдельного metrics-сервера панели.
     *
     * Идёт не на порт API, а на `PANEL_METRICS_PORT`, поэтому не использует
     * cookie-сессию: доступ определяется портом и Bearer-токеном.
     */
    suspend fun metrics(): MetricsSnapshot = withContext(Dispatchers.IO) {
        val url = server.metricsUrl
            ?: throw PanelApiException(0, "Порт метрик не задан")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ServpanelMobile/1.1 (Android)")
            .apply {
                val token = server.metricsToken.trim()
                if (token.isNotEmpty()) header("Authorization", "Bearer $token")
            }
            .build()
        val callClient = client.newBuilder()
            // Метрики — необязательный источник для карточки сервера, поэтому
            // не ждём стандартные 6 с на закрытом порту.
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        try {
            callClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw PanelApiException(
                        response.code,
                        "Метрики: неверный или отсутствующий токен"
                    )
                }
                if (!response.isSuccessful) {
                    throw PanelApiException(
                        response.code,
                        "Метрики недоступны (HTTP ${response.code})"
                    )
                }
                val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    ?: throw PanelApiException(0, "Метрики вернули неожиданный ответ")
                MetricsSnapshot.from(parsed)
            }
        } catch (e: UnknownHostException) {
            throw PanelApiException(0, "Метрики: сервер не найден")
        } catch (e: ConnectException) {
            throw PanelApiException(0, "Метрики: нет соединения с портом ${server.metricsPort}")
        } catch (e: SocketTimeoutException) {
            throw PanelApiException(0, "Метрики: сервер не ответил вовремя")
        } catch (e: SSLException) {
            throw PanelApiException(
                0,
                "Метрики: ошибка TLS. Для самоподписанного сертификата включите эту опцию в настройках сервера."
            )
        } catch (e: IOException) {
            throw PanelApiException(0, e.message ?: "Метрики: ошибка сети")
        }
    }

    suspend fun history(range: String, view: String): HistoryData =
        HistoryData.from(json("GET", "/api/history", mapOf("range" to range, "view" to view)))

    suspend fun securityStatus(): SecurityStatus =
        SecurityStatus.from(json("GET", "/api/security/status"))

    suspend fun interfaces(): List<String> = array("/api/interfaces").stringList()

    // ------------------------------------------------------------------
    // Клиенты
    // ------------------------------------------------------------------

    suspend fun clients(): List<ClientInfo> =
        array("/api/clients").objects().map(ClientInfo::from)

    suspend fun clientKey(name: String): ClientKey =
        ClientKey.from(json("GET", "/api/clients/$name/key", timeoutSeconds = 90))

    suspend fun createClient(name: String, expires: String): String {
        val body = JSONObject().put("name", name)
        if (expires.isNotBlank()) body.put("expires", expires)
        return text("POST", "/api/create", json = body, timeoutSeconds = 120)
    }

    suspend fun createBulk(prefix: String, count: Int, expires: String): List<BulkConfig> {
        val body = JSONObject().put("prefix", prefix).put("count", count)
        if (expires.isNotBlank()) body.put("expires", expires)
        val result = json("POST", "/api/create-bulk", body = body, timeoutSeconds = 180)
        return result.optJSONArray("configs")?.objects()?.map(BulkConfig::from) ?: emptyList()
    }

    suspend fun renameClient(name: String, newName: String) {
        json("POST", "/api/clients/$name/rename", body = JSONObject().put("newName", newName))
    }

    suspend fun renameBulk(search: String, replace: String): BulkRenameResult {
        val body = JSONObject().put("search", search).put("replace", replace)
        return BulkRenameResult.from(json("POST", "/api/clients/rename-bulk", body = body, timeoutSeconds = 120))
    }

    suspend fun deleteClient(name: String) {
        json("DELETE", "/api/delete/$name", timeoutSeconds = 90)
    }

    suspend fun downloadClient(name: String): String =
        text("GET", "/api/download/$name", timeoutSeconds = 90)

    suspend fun clientExport(name: String): String =
        text("GET", "/api/clients/$name/export", mapOf("format" to "txt"), timeoutSeconds = 90)

    suspend fun setLimit(ip: String, rate: Int) {
        json("POST", "/api/limit", body = JSONObject().put("ip", ip).put("rate", rate))
    }

    // ------------------------------------------------------------------
    // Службы
    // ------------------------------------------------------------------

    suspend fun services(): List<ServiceInfo> =
        array("/api/services").objects().map(ServiceInfo::from)

    suspend fun restartService(name: String) {
        json("POST", "/api/services/restart", body = JSONObject().put("name", name), timeoutSeconds = 90)
    }

    // ------------------------------------------------------------------
    // Пользователи, 2FA, аудит, fail2ban, уведомления
    // ------------------------------------------------------------------

    suspend fun users(): UsersPayload = UsersPayload.from(json("GET", "/api/users"))

    suspend fun createUser(username: String, password: String, role: String) {
        json(
            "POST", "/api/users",
            body = JSONObject().put("username", username).put("password", password).put("role", role)
        )
    }

    suspend fun deleteUser(username: String) {
        json("DELETE", "/api/users/$username")
    }

    suspend fun setUserPassword(username: String, password: String) {
        json("POST", "/api/users/$username/password", body = JSONObject().put("password", password))
    }

    suspend fun changePassword(newPassword: String) {
        json("POST", "/api/change-password", body = JSONObject().put("newPassword", newPassword))
    }

    suspend fun twoFaStatus(): TwoFaStatus = TwoFaStatus.from(json("GET", "/api/2fa/status"))

    suspend fun twoFaSetup(): TwoFaSetup = TwoFaSetup.from(json("POST", "/api/2fa/setup"))

    suspend fun twoFaEnable(code: String): List<String> {
        val result = json("POST", "/api/2fa/enable", body = JSONObject().put("code", code))
        return result.optJSONArray("recoveryCodes")?.stringList() ?: emptyList()
    }

    suspend fun twoFaDisable(password: String) {
        json("POST", "/api/2fa/disable", body = JSONObject().put("password", password))
    }

    suspend fun audit(limit: Int = 300): List<AuditEntry> =
        json("GET", "/api/audit", mapOf("limit" to limit.toString()))
            .optJSONArray("entries")?.objects()?.map(AuditEntry::from) ?: emptyList()

    suspend fun fail2ban(): Fail2banStatus = Fail2banStatus.from(json("GET", "/api/fail2ban/status"))

    suspend fun fail2banUnban(ip: String) {
        json("POST", "/api/fail2ban/unban", body = JSONObject().put("ip", ip))
    }

    suspend fun notifyStatus(): NotifyStatus = NotifyStatus.from(json("GET", "/api/notify/status"))

    suspend fun saveNotify(enabled: Boolean, token: String?, chatId: String?) {
        val body = JSONObject().put("enabled", enabled)
        if (token != null) body.put("token", token)
        if (chatId != null) body.put("chatId", chatId)
        json("POST", "/api/notify/telegram", body = body)
    }

    suspend fun testNotify() {
        json("POST", "/api/notify/test", timeoutSeconds = 60)
    }

    // ------------------------------------------------------------------
    // UFW
    // ------------------------------------------------------------------

    suspend fun ufw(): UfwData = UfwData.from(json("GET", "/api/ufw"))

    suspend fun addUfwRule(port: Int, proto: String, iface: String) {
        val body = JSONObject().put("port", port)
        if (proto.isNotBlank()) body.put("proto", proto)
        if (iface.isNotBlank()) body.put("iface", iface)
        json("POST", "/api/ufw", body = body)
    }

    suspend fun deleteUfwRule(id: String) {
        json("DELETE", "/api/ufw/$id")
    }

    // ------------------------------------------------------------------
    // Каскад и сетевые инструменты
    // ------------------------------------------------------------------

    suspend fun cascadeStatus(): CascadeStatus = CascadeStatus.from(json("GET", "/api/cascade/status"))

    suspend fun cascadeHealth(): CascadeHealth = CascadeHealth.from(json("GET", "/api/cascade/health"))

    suspend fun cascadeConfig(): String? {
        return try {
            json("GET", "/api/cascade/config").optString("content")
        } catch (e: PanelApiException) {
            if (e.status == 404) null else throw e
        }
    }

    suspend fun cascadeSetup(config: String, ifaces: List<String>, extraRuNets: String) {
        json(
            "POST", "/api/cascade/setup",
            body = JSONObject()
                .put("config", config)
                .put("ifaces", JSONArray(ifaces))
                .put("extraRuNets", extraRuNets),
            timeoutSeconds = 180
        )
    }

    suspend fun cascadeRefresh() {
        json("POST", "/api/cascade/refresh", timeoutSeconds = 150)
    }

    suspend fun cascadeRemove() {
        json("POST", "/api/cascade/remove")
    }

    suspend fun toolsStatus(): ToolsStatus = ToolsStatus.from(json("GET", "/api/tools/status"))

    suspend fun setRuDrop(ifaces: List<String>, schedule: String, enable: Boolean) {
        json(
            "POST", "/api/tools/rudrop",
            body = JSONObject()
                .put("ifaces", JSONArray(ifaces))
                .put("schedule", schedule)
                .put("enable", enable),
            timeoutSeconds = 150
        )
    }

    suspend fun setDns(ifaces: List<String>, target: String, enable: Boolean) {
        json(
            "POST", "/api/tools/dns",
            body = JSONObject()
                .put("ifaces", JSONArray(ifaces))
                .put("target", target)
                .put("enable", enable),
            timeoutSeconds = 90
        )
    }

    suspend fun warpStatus(): WarpStatus = WarpStatus.from(json("GET", "/api/warp/status"))

    suspend fun warpPower(enable: Boolean) {
        json("POST", "/api/warp/power", body = JSONObject().put("enable", enable), timeoutSeconds = 120)
    }

    suspend fun warpTest(): WarpTestResult =
        WarpTestResult.from(json("POST", "/api/warp/test", timeoutSeconds = 60))

    // ------------------------------------------------------------------
    // Бэкапы
    // ------------------------------------------------------------------

    suspend fun backupAuto(): BackupAuto = BackupAuto.from(json("GET", "/api/backup/auto"))

    suspend fun saveBackupAuto(
        enabled: Boolean,
        schedule: String,
        time: String,
        keep: Int,
        encrypt: Boolean
    ) {
        json(
            "POST", "/api/backup/auto",
            body = JSONObject()
                .put("enabled", enabled)
                .put("schedule", schedule)
                .put("time", time)
                .put("keep", keep)
                .put("encrypt", encrypt),
            timeoutSeconds = 90
        )
    }

    suspend fun runBackup(): BackupRunResult =
        BackupRunResult.from(json("POST", "/api/backup/run", timeoutSeconds = 240))

    suspend fun backupPassword(): String = json("GET", "/api/backup/password").optString("password")

    suspend fun setBackupPassword(password: String) {
        json("POST", "/api/backup/password", body = JSONObject().put("password", password))
    }

    suspend fun backupArchive(name: String): ByteArray =
        rawBytes("/api/backup/archive/$name")

    suspend fun fullBackup(): ByteArray = rawBytes("/api/backup")

    suspend fun encryptedBackup(): ByteArray = rawBytes("/api/backup/encrypted", timeoutSeconds = 300)

    suspend fun restoreBackup(base64: String) {
        json(
            "POST", "/api/restore",
            body = JSONObject().put("fileData", base64),
            timeoutSeconds = 240
        )
    }

    // ------------------------------------------------------------------
    // SSL
    // ------------------------------------------------------------------

    suspend fun sslStatus(): SslStatus = SslStatus.from(json("GET", "/api/ssl/status"))

    suspend fun sslInstallDeps() {
        json("POST", "/api/ssl/install-deps", timeoutSeconds = 900)
    }

    suspend fun sslCreateSite(
        domain: String,
        mode: String,
        www: Boolean,
        issue: Boolean,
        email: String,
        useForVpn: Boolean
    ): SslSiteResult {
        val body = JSONObject()
            .put("domain", domain)
            .put("mode", mode)
            .put("www", www)
            .put("issue", issue)
            .put("useForVpn", useForVpn)
        if (email.isNotBlank()) body.put("email", email)
        return SslSiteResult.from(json("POST", "/api/ssl/site", body = body, timeoutSeconds = 420))
    }

    suspend fun sslRenew(name: String) {
        json("POST", "/api/ssl/renew", body = JSONObject().put("name", name), timeoutSeconds = 360)
    }

    suspend fun sslDeleteSite(name: String) {
        json("DELETE", "/api/ssl/site/$name", timeoutSeconds = 120)
    }

    // ------------------------------------------------------------------
    // Обновления
    // ------------------------------------------------------------------

    suspend fun updateInfo(): UpdateInfo = UpdateInfo.from(json("GET", "/api/update/info"))

    suspend fun updateStatus(): UpdateStatus = UpdateStatus.from(json("GET", "/api/update/status"))

    suspend fun checkGit(): GitCheck = GitCheck.from(json("GET", "/api/update/check-git", timeoutSeconds = 90))

    suspend fun updateFromGit(force: Boolean) {
        json("POST", if (force) "/api/update/git-force" else "/api/update/git", timeoutSeconds = 90)
    }

    suspend fun updateFromUrl(url: String) {
        json("POST", "/api/update/url", body = JSONObject().put("url", url), timeoutSeconds = 120)
    }

    suspend fun updateUpload(name: String, base64: String) {
        json(
            "POST", "/api/update/upload",
            body = JSONObject().put("name", name).put("data", base64),
            timeoutSeconds = 180
        )
    }

    suspend fun updateRollback(backup: String) {
        json("POST", "/api/update/rollback", body = JSONObject().put("backup", backup), timeoutSeconds = 90)
    }

    // ------------------------------------------------------------------
    // Установка модулей
    // ------------------------------------------------------------------

    suspend fun installState(): InstallState = InstallState.from(json("GET", "/api/install/state"))

    suspend fun installProgress(logFile: String): InstallProgress =
        InstallProgress.from(json("GET", "/api/install/progress", mapOf("file" to logFile)))

    suspend fun installLog(logFile: String): String =
        text("GET", "/api/install/log", mapOf("file" to logFile))

    suspend fun installModule(options: JSONObject): JSONObject =
        json("POST", "/api/install/module", body = options, timeoutSeconds = 60)

    suspend fun uninstallAwg() {
        json("POST", "/api/uninstall/awg", timeoutSeconds = 60)
    }

    // ------------------------------------------------------------------
    // MTProto-прокси: MTProxyL / Telemt (панель 2.8+)
    // ------------------------------------------------------------------

    suspend fun proxyStatus(): ProxyStatus =
        ProxyStatus.from(json("GET", "/api/proxy/status", timeoutSeconds = 30))

    suspend fun proxyUsers(): List<ProxyUser> {
        val result = json("GET", "/api/proxy/users", timeoutSeconds = 30)
        return result.optJSONArray("users")?.objects()?.map(ProxyUser::from) ?: emptyList()
    }

    suspend fun createProxyUser(body: JSONObject) {
        json("POST", "/api/proxy/users", body = body, timeoutSeconds = 60)
    }

    suspend fun patchProxyUser(username: String, body: JSONObject) {
        json("PATCH", "/api/proxy/users/$username", body = body, timeoutSeconds = 60)
    }

    suspend fun deleteProxyUser(username: String) {
        json("DELETE", "/api/proxy/users/$username", timeoutSeconds = 60)
    }

    /** enable, disable, rotate-secret, reset-quota или reset-traffic. */
    suspend fun proxyUserAction(username: String, action: String, secret: String? = null) {
        val body = if (secret.isNullOrBlank()) null else JSONObject().put("secret", secret.trim())
        json("POST", "/api/proxy/users/$username/$action", body = body, timeoutSeconds = 60)
    }

    suspend fun proxyUserLinks(username: String): ProxyUserLinks =
        ProxyUserLinks.from(json("GET", "/api/proxy/users/$username/links", timeoutSeconds = 30))

    suspend fun restartProxy() {
        json("POST", "/api/proxy/restart", timeoutSeconds = 90)
    }

    /** Выдать новый ключ API MTProxyL и сохранить его в панели. */
    suspend fun rotateProxyApiKey() {
        json("POST", "/api/proxy/api-key", timeoutSeconds = 120)
    }

    suspend fun proxyBot(): ProxyBotStatus =
        ProxyBotStatus.from(json("GET", "/api/proxy/bot", timeoutSeconds = 30))

    suspend fun saveProxyBot(
        enabled: Boolean,
        token: String?,
        admins: List<String>,
        clearToken: Boolean = false
    ): ProxyBotStatus {
        val body = JSONObject()
            .put("enabled", enabled)
            .put("admins", JSONArray(admins))
        if (token != null) body.put("token", token)
        if (clearToken) body.put("clearToken", true)
        return ProxyBotStatus.from(json("POST", "/api/proxy/bot", body = body, timeoutSeconds = 60))
    }

    suspend fun testProxyBot() {
        json("POST", "/api/proxy/bot/test", timeoutSeconds = 60)
    }

    /** Ссылки `tg://`, сохранённые установщиком MEKO. */
    suspend fun mtprotoLinks(): List<String> =
        json("GET", "/api/mtproto/links", timeoutSeconds = 30)
            .optJSONArray("links")?.stringList() ?: emptyList()

    // ------------------------------------------------------------------
    // Диагностика
    // ------------------------------------------------------------------

    suspend fun ping(host: String): DiagResult =
        DiagResult.from(json("POST", "/api/diag/ping", body = JSONObject().put("host", host), timeoutSeconds = 60))

    suspend fun traceroute(host: String): DiagResult =
        DiagResult.from(
            json("POST", "/api/diag/traceroute", body = JSONObject().put("host", host), timeoutSeconds = 90)
        )

    suspend fun diagLog(file: String): String =
        text("GET", "/api/diag/log", mapOf("file" to file), timeoutSeconds = 60)

    // ------------------------------------------------------------------
    // Обфускация
    // ------------------------------------------------------------------

    suspend fun obfCurrent(): ObfCurrent = ObfCurrent.from(json("GET", "/api/obf/current"))

    suspend fun obfProfiles(): ObfProfiles = ObfProfiles.from(json("GET", "/api/obf/profiles"))

    suspend fun obfGenerate(preset: String): ObfGenerated =
        ObfGenerated.from(json("POST", "/api/obf/generate", body = JSONObject().put("preset", preset)))

    suspend fun obfAnalyze(source: String): ObfGenerated {
        val result = json("POST", "/api/obf/analyze", body = JSONObject().put("source", source))
        return ObfGenerated(
            params = result.optJSONObject("params")?.let { obj ->
                buildMap {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optString(k, ""))
                    }
                }
            } ?: emptyMap(),
            version = result.optString("version", ""),
            audit = ru.servpanel.mobile.data.ObfAudit.from(result.optJSONObject("audit"))
        )
    }

    suspend fun obfApply(
        params: Map<String, String>,
        regenClients: Boolean,
        removeMissing: Boolean
    ): ObfApplyResult {
        val obj = JSONObject()
        params.forEach { (k, v) -> obj.put(k, v) }
        val body = JSONObject()
            .put("params", obj)
            .put("regenClients", regenClients)
            .put("removeMissing", removeMissing)
        return ObfApplyResult.from(json("POST", "/api/obf/apply", body = body, timeoutSeconds = 180))
    }

    suspend fun obfSaveProfile(name: String, params: Map<String, String>) {
        val obj = JSONObject()
        params.forEach { (k, v) -> obj.put(k, v) }
        json("POST", "/api/obf/profiles", body = JSONObject().put("name", name).put("params", obj))
    }

    suspend fun obfDeleteProfile(name: String) {
        json("DELETE", "/api/obf/profiles/$name")
    }

    suspend fun obfApplyProfile(
        name: String,
        regenClients: Boolean,
        removeMissing: Boolean
    ): ObfApplyResult {
        val body = JSONObject()
            .put("regenClients", regenClients)
            .put("removeMissing", removeMissing)
        return ObfApplyResult.from(
            json("POST", "/api/obf/profiles/$name/apply", body = body, timeoutSeconds = 180)
        )
    }

    suspend fun obfExport(format: String): String =
        text("GET", "/api/obf/export", mapOf("format" to format), timeoutSeconds = 60)

    // ------------------------------------------------------------------
    // Сервер и конфиг
    // ------------------------------------------------------------------

    suspend fun serverInfo(): ServerInfo = ServerInfo.from(json("GET", "/api/server-info", timeoutSeconds = 60))

    suspend fun awgConfig(): AwgConfig = AwgConfig.from(json("GET", "/api/awg/config"))

    suspend fun saveAwgConfig(file: String, content: String) {
        json("POST", "/api/awg/config", body = JSONObject().put("file", file).put("content", content), timeoutSeconds = 90)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Список архивов для экрана бэкапов (без полного конфига). */
fun archivesOf(backup: BackupAuto): List<ArchiveInfo> = backup.archives
