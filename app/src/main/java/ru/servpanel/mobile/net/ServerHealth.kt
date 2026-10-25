package ru.servpanel.mobile.net

/** Сводка состояния сервера для карточки на главном экране. */
data class ServerHealth(
    val online: Boolean = false,
    val needsLogin: Boolean = false,
    val latencyMs: Long? = null,
    val error: String? = null,
    val cpuPercent: Float? = null,
    val ramPercent: Float? = null,
    val diskPercent: String? = null,
    val load: String? = null,
    val cores: Int? = null,
    val uptime: String? = null,
    val clientsTotal: Int? = null,
    val clientsOnline: Int? = null,
    val user: String? = null,
    val role: String? = null,
    val node: String? = null,
    val panelPort: String? = null,
    val https: Boolean = false,
    val checkedAt: Long = 0L
)

data class ProbeResult(
    val ok: Boolean,
    val resolvedUrl: String? = null,
    val message: String = "",
    val latencyMs: Long? = null,
    val needsLogin: Boolean = false
)
