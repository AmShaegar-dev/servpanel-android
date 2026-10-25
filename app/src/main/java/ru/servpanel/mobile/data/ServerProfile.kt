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
    val metricsToken: String = ""
) {
    /** Адрес панели с учётом поля «Порт панели». */
    val apiUrl: String
        get() {
            val base = url.trimEnd('/')
            if (panelPort <= 0) return base
            val parsed = base.toHttpUrlOrNull() ?: return base
            return parsed.newBuilder().port(panelPort).build().toString().trimEnd('/')
        }

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
                metricsToken = o.optString("metricsToken").orEmpty()
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
