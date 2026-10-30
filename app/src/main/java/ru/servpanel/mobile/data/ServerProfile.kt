package ru.servpanel.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Один сервер (панель Servpanel), сохранённый на устройстве.
 *
 * Пароли здесь не хранятся: вход выполняется через API панели,
 * а сессия живёт в cookie-хранилище OkHttp своего сервера.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val color: Int,
    val allowInsecure: Boolean = false,
    val createdAt: Long = 0L,
    /** Отдельный порт панели; 0 — брать из [url]. */
    val panelPort: Int = 0,
    /** Порт metrics-сервера панели (`PANEL_METRICS_PORT`); 0 — метрики через API. */
    val metricsPort: Int = 0,
    /** Bearer-токен metrics-сервера, если он включён на панели. */
    val metricsToken: String = "",
    // --- Доступ через SSH-туннель -------------------------------------
    /** Ходить в панель не напрямую, а через SSH-туннель до loopback сервера. */
    val sshTunnel: Boolean = false,
    /** Хост SSH (обычно тот же, что и у панели). */
    val sshHost: String = "",
    val sshPort: Int = 22,
    /** Отдельный пользователь без шелла, только для проброса порта. */
    val sshUser: String = "servpanel-app",
    /** Приватный ключ в PEM/OpenSSH; хранится в зашифрованном JSON профиля. */
    val sshKey: String = "",
    val sshPassphrase: String = "",
    /** Закреплённый отпечаток ключа хоста в формате SHA256:… */
    val sshHostKey: String = "",
    /** Порт панели на самом сервере (127.0.0.1), а не внешний. */
    val remotePanelPort: Int = 3000
) {
    /** Адрес панели с учётом поля «Порт панели». */
    val apiUrl: String
        get() {
            val base = url.trimEnd('/')
            if (panelPort <= 0) return base
            val parsed = base.toHttpUrlOrNull() ?: return base
            return parsed.newBuilder().port(panelPort).build().toString().trimEnd('/')
        }

    /** Копия профиля с адресом локального конца туннеля. */
    fun viaLocalPort(port: Int): ServerProfile =
        copy(url = "http://127.0.0.1:$port", panelPort = 0, metricsPort = 0, allowInsecure = false)

    /** Короткая подпись режима доступа для карточки сервера. */
    val accessLabel: String
        get() = if (sshTunnel) "SSH" else ""

    /** Адрес metrics-сервера; null, если отдельный порт не задан. */
    val metricsUrl: String?
        get() {
            if (metricsPort <= 0) return null
            val parsed = url.toHttpUrlOrNull() ?: return null
            return parsed.newBuilder()
                .port(metricsPort)
                .encodedPath("/metrics")
                .query(null)
                .build()
                .toString()
        }

    /** Хост и порт для подписей в интерфейсе. */
    val displayHost: String
        get() {
            if (sshTunnel) {
                val host = sshHost.ifBlank {
                    url.toHttpUrlOrNull()?.host ?: url
                }
                return if (sshPort == 22) host else "$host:$sshPort"
            }
            val parsed = apiUrl.toHttpUrlOrNull() ?: return apiUrl
            val defaultPort = if (parsed.scheme == "https") 443 else 80
            return if (parsed.port == defaultPort) parsed.host else "${parsed.host}:${parsed.port}"
        }

    val host: String
        get() = apiUrl.toHttpUrlOrNull()?.host ?: displayHost

    /**
     * Тот же адрес, но по HTTPS. Нужен, когда сервер отвечает на HTTP
     * редиректом 301: POST при переходе превращается в GET, и /api/login
     * отвечает 404. Возвращает null, если адрес уже HTTPS.
     */
    val httpsVariant: ServerProfile?
        get() {
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.scheme == "https") return null
            val secure = parsed.newBuilder().scheme("https").build().toString().trimEnd('/')
            return copy(url = secure)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("color", color)
        put("allowInsecure", allowInsecure)
        put("createdAt", createdAt)
        put("panelPort", panelPort)
        put("metricsPort", metricsPort)
        put("metricsToken", metricsToken)
        put("sshTunnel", sshTunnel)
        put("sshHost", sshHost)
        put("sshPort", sshPort)
        put("sshUser", sshUser)
        put("sshKey", sshKey)
        put("sshPassphrase", sshPassphrase)
        put("sshHostKey", sshHostKey)
        put("remotePanelPort", remotePanelPort)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerProfile? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
            return ServerProfile(
                id = id,
                name = o.optString("name").ifBlank { url },
                url = url,
                color = o.optInt("color", ServerColors.palette.first()),
                allowInsecure = o.optBoolean("allowInsecure", false),
                createdAt = o.optLong("createdAt", 0L),
                panelPort = o.optInt("panelPort", 0).takeIf { it in 1..65535 } ?: 0,
                metricsPort = o.optInt("metricsPort", 0).takeIf { it in 1..65535 } ?: 0,
                metricsToken = o.optString("metricsToken").orEmpty(),
                sshTunnel = o.optBoolean("sshTunnel", false),
                sshHost = o.optString("sshHost").orEmpty(),
                sshPort = o.optInt("sshPort", 22).takeIf { it in 1..65535 } ?: 22,
                sshUser = o.optString("sshUser").ifBlank { "servpanel-app" },
                sshKey = o.optString("sshKey").orEmpty(),
                sshPassphrase = o.optString("sshPassphrase").orEmpty(),
                sshHostKey = o.optString("sshHostKey").orEmpty(),
                remotePanelPort = o.optInt("remotePanelPort", 3000).takeIf { it in 1..65535 } ?: 3000
            )
        }
    }
}

object ServerColors {
    val palette: List<Int> = listOf(
        0xFF5B8CFF.toInt(),
        0xFF8B5CF6.toInt(),
        0xFF2DD4A7.toInt(),
        0xFFF5B84B.toInt(),
        0xFFFF6B6B.toInt(),
        0xFF38BDF8.toInt(),
        0xFFF472B6.toInt(),
        0xFFA3E635.toInt()
    )

    fun next(existing: List<ServerProfile>): Int = palette[existing.size % palette.size]
}

/** Разбор и нормализация адреса панели. */
object PanelUrl {

    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        val parsed = value.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        return parsed.toString().substringBefore('?').substringBefore('#').trimEnd('/')
    }

    fun hasScheme(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }

    /**
     * Адрес для кнопки «Проверить подключение».
     *
     * Если схему не ввели, она намеренно НЕ подставляется: `PanelApi.probe`
     * сам попробует https, а затем http. Иначе явный `http://` на https-порту
     * гарантирует ошибку `unexpected end of stream`.
     */
    fun probeTarget(raw: String, port: Int): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        if (hasScheme(value)) return withPort(normalize(value) ?: value, port)
        val parsed = "https://$value".toHttpUrlOrNull() ?: return value
        val targetPort = if (port > 0) port else parsed.port.takeIf { it != 443 }
        val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
        val path = parsed.encodedPath.trimEnd('/')
        return if (targetPort != null) "$host:$targetPort$path" else "$host$path"
    }

    /** Подставляет порт в адрес (0 — вернуть адрес как есть). */
    fun withPort(url: String, port: Int): String {
        if (port <= 0) return url.trimEnd('/')
        val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
        return parsed.newBuilder().port(port).build().toString().trimEnd('/')
    }

    /** Убирает явный порт, оставляя схему, хост и путь. */
    fun withoutPort(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
        val defaultPort = if (parsed.scheme == "https") 443 else 80
        return parsed.newBuilder().port(defaultPort).build().toString().trimEnd('/')
    }
}
