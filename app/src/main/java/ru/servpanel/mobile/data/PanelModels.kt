package ru.servpanel.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------------------------------------------------------------------------
// Вспомогательные чтения JSON. Сервер иногда присылает числа строками
// ("45.2"), поэтому все числовые поля читаем через optDouble/optString.
// ---------------------------------------------------------------------------

// org.json превращает JSON null в строку "null" (optString → toString),
// поэтому явно отсекаем JSONObject.NULL: для модели это пустое значение.
internal fun JSONObject.str(key: String): String {
    val value = opt(key) ?: return ""
    if (value === JSONObject.NULL) return ""
    return value.toString().trim()
}

internal fun JSONObject.num(key: String): Int =
    optDouble(key, 0.0).takeIf { !it.isNaN() }?.toInt() ?: 0

internal fun JSONObject.float(key: String): Float =
    optDouble(key, 0.0).takeIf { !it.isNaN() }?.toFloat() ?: 0f

internal fun JSONObject.long(key: String): Long =
    optDouble(key, 0.0).takeIf { !it.isNaN() }?.toLong() ?: 0L

internal fun JSONObject.flag(key: String): Boolean = optBoolean(key, false)

internal fun JSONObject.objOrNull(key: String): JSONObject? = optJSONObject(key)

internal fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let(::add)
}

internal fun JSONArray.stringList(): List<String> = buildList {
    for (i in 0 until length()) {
        val v = opt(i)
        if (v == null || v === JSONObject.NULL) continue
        val s = v.toString()
        if (s.isNotEmpty()) add(s)
    }
}

private fun JSONObject.mapOfStrings(): Map<String, String> = buildMap {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = opt(k)
        if (v != null && v !== JSONObject.NULL) put(k, v.toString())
    }
}

// ---------------------------------------------------------------------------
// Метрики и история трафика
// ---------------------------------------------------------------------------

data class SysInfo(
    val cpu: Float = 0f,
    val ram: Float = 0f,
    val load: String = "",
    val cores: Int = 0,
    val uptime: String = "",
    val diskTotal: Long = 0L,
    val diskUsed: Long = 0L,
    val diskFree: Long = 0L,
    val diskPercent: String = ""
) {
    companion object {
        fun from(json: JSONObject): SysInfo {
            val disk = json.objOrNull("disk") ?: JSONObject()
            return SysInfo(
                cpu = json.float("cpu"),
                ram = json.float("ram"),
                load = json.str("load"),
                cores = json.num("cores"),
                uptime = json.str("uptime"),
                diskTotal = disk.long("total"),
                diskUsed = disk.long("used"),
                diskFree = disk.long("free"),
                diskPercent = disk.str("percent")
            )
        }
    }
}

/**
 * Компактный снимок с отдельного metrics-сервера панели (`/metrics`).
 *
 * Сервер отдаёт его, если включён `PANEL_METRICS_ENABLED` (или задан
 * `PANEL_METRICS_PORT`). Порт и токен задаются в настройках сервера вручную.
 */
data class MetricsSnapshot(
    val cpuPercent: Float = 0f,
    val cores: Int = 0,
    val load1: Float = 0f,
    val ramPercent: Float = 0f,
    val ramTotal: Long = 0L,
    val ramUsed: Long = 0L,
    val diskTotal: Long = 0L,
    val diskUsed: Long = 0L,
    val diskFree: Long = 0L,
    val diskPercent: String = "",
    val uptimeSeconds: Long = 0L,
    val node: String = "",
    val clientsOnline: Int = 0,
    val clientsTotal: Int = 0,
    val panelPort: Int = 0,
    val tls: Boolean = false,
    val version: String = ""
) {
    fun toSysInfo(): SysInfo = SysInfo(
        cpu = cpuPercent,
        ram = ramPercent,
        load = String.format(Locale.US, "%.2f", load1),
        cores = cores,
        uptime = formatUptime(uptimeSeconds),
        diskTotal = diskTotal,
        diskUsed = diskUsed,
        diskFree = diskFree,
        diskPercent = diskPercent.ifBlank {
            if (diskTotal > 0) "${diskUsed * 100 / diskTotal}%" else ""
        }
    )

    companion object {
        fun from(json: JSONObject): MetricsSnapshot {
            val cpu = json.objOrNull("cpu") ?: JSONObject()
            val mem = json.objOrNull("memory") ?: JSONObject()
            val disk = json.objOrNull("disk") ?: JSONObject()
            val clients = json.objOrNull("clients") ?: JSONObject()
            val panel = json.objOrNull("panel") ?: JSONObject()
            val host = json.objOrNull("host") ?: JSONObject()
            return MetricsSnapshot(
                cpuPercent = cpu.float("percent"),
                cores = cpu.num("cores"),
                load1 = cpu.float("load1"),
                ramPercent = mem.float("percent"),
                ramTotal = mem.long("total"),
                ramUsed = mem.long("used"),
                diskTotal = disk.long("total"),
                diskUsed = disk.long("used"),
                diskFree = disk.long("free"),
                diskPercent = disk.str("percent"),
                uptimeSeconds = json.long("uptimeSeconds"),
                node = host.str("node"),
                clientsOnline = clients.num("online"),
                clientsTotal = clients.num("total"),
                panelPort = panel.num("publicPort").takeIf { it > 0 } ?: panel.num("internalPort"),
                tls = panel.optBoolean("tls", false),
                version = panel.str("version")
            )
        }
    }
}

internal fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return ""
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}д ${hours}ч ${minutes}м"
        hours > 0 -> "${hours}ч ${minutes}м"
        else -> "${minutes}м"
    }
}

data class HistoryData(
    val range: String = "1h",
    val view: String = "global",
    val labels: List<String> = emptyList(),
    val rx: List<Long> = emptyList(),
    val tx: List<Long> = emptyList(),
    val views: List<String> = emptyList()
) {
    companion object {
        fun from(json: JSONObject): HistoryData {
            fun numbers(key: String): List<Long> {
                val arr = json.array(key)
                return buildList { for (i in 0 until arr.length()) add(arr.optLong(i, 0L)) }
            }
            return HistoryData(
                range = json.str("range"),
                view = json.str("view"),
                labels = json.array("labels").stringList(),
                rx = numbers("rx"),
                tx = numbers("tx"),
                views = json.array("views").stringList()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Клиенты
// ---------------------------------------------------------------------------

data class ClientInfo(
    val name: String,
    val ip: String,
    val ipv6: String,
    val status: String,
    val statusCode: String,
    val expiresAt: Long?,
    val rx: Long,
    val tx: Long,
    val lastHandshake: Long,
    val hasVpnUri: Boolean
) {
    val active: Boolean
        get() = statusCode == "active" || status.lowercase().contains("актив")

    companion object {
        fun from(json: JSONObject): ClientInfo = ClientInfo(
            name = json.str("name"),
            ip = json.str("ip"),
            ipv6 = json.str("client_ipv6"),
            status = json.str("status"),
            statusCode = json.str("status_code").ifEmpty { "inactive" },
            expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L },
            rx = json.long("rx"),
            tx = json.long("tx"),
            lastHandshake = json.long("last_handshake"),
            hasVpnUri = json.flag("has_vpnuri")
        )
    }
}

data class ClientKey(val name: String, val uri: String, val qr: String) {
    companion object {
        fun from(json: JSONObject): ClientKey =
            ClientKey(json.str("name"), json.str("uri"), json.str("qr"))
    }
}

data class BulkConfig(val name: String, val content: String) {
    companion object {
        fun from(json: JSONObject): BulkConfig = BulkConfig(json.str("name"), json.str("content"))
    }
}

data class BulkRenameResult(
    val renamed: List<Pair<String, String>>,
    val skipped: List<Pair<String, String>>
) {
    companion object {
        fun from(json: JSONObject): BulkRenameResult {
            val renamed = json.array("renamed").objects()
                .map { it.str("from") to it.str("to") }
            val skipped = json.array("skipped").objects()
                .map { it.str("name") to it.str("reason") }
            return BulkRenameResult(renamed, skipped)
        }
    }
}

// ---------------------------------------------------------------------------
// Службы
// ---------------------------------------------------------------------------

data class ServiceInfo(
    val name: String,
    val desc: String,
    val status: String,
    val state: String,
    val enabled: Boolean
) {
    val running: Boolean get() = status == "running" || state == "active"

    companion object {
        fun from(json: JSONObject): ServiceInfo = ServiceInfo(
            name = json.str("name"),
            desc = json.str("desc"),
            status = json.str("status"),
            state = json.str("state"),
            enabled = json.flag("enabled")
        )
    }
}

// ---------------------------------------------------------------------------
// Безопасность: статус, пользователи, 2FA, аудит, fail2ban, уведомления
// ---------------------------------------------------------------------------

data class SecurityStatus(
    val user: String,
    val role: String,
    val https: Boolean,
    val node: String,
    val panelPort: String,
    val panelPublicPort: String,
    val users: Int,
    val totpEnabled: Boolean,
    val dataDir: String,
    val defaultPassword: Boolean,
    val passFileExists: Boolean
) {
    val isAdmin: Boolean get() = role != "viewer"

    companion object {
        fun from(json: JSONObject): SecurityStatus = SecurityStatus(
            user = json.str("user").ifEmpty { "admin" },
            role = json.str("role").ifEmpty { "admin" },
            https = json.flag("https"),
            node = json.str("node"),
            panelPort = json.optString("panelPort", ""),
            panelPublicPort = json.optString("panelPublicPort", ""),
            users = json.num("users"),
            totpEnabled = json.flag("totpEnabled"),
            dataDir = json.str("dataDir"),
            defaultPassword = json.flag("defaultPassword"),
            passFileExists = json.flag("passFileExists")
        )
    }
}

data class UserInfo(
    val username: String,
    val role: String,
    val createdAt: String,
    val totpEnabled: Boolean
) {
    companion object {
        fun from(json: JSONObject): UserInfo = UserInfo(
            username = json.str("username"),
            role = json.str("role"),
            createdAt = json.str("createdAt"),
            totpEnabled = json.flag("totpEnabled")
        )
    }
}

data class UsersPayload(val users: List<UserInfo>, val current: String) {
    companion object {
        fun from(json: JSONObject): UsersPayload = UsersPayload(
            users = json.array("users").objects().map(UserInfo::from),
            current = json.str("current")
        )
    }
}

data class TwoFaStatus(val user: String, val enabled: Boolean, val pending: Boolean) {
    companion object {
        fun from(json: JSONObject): TwoFaStatus =
            TwoFaStatus(json.str("user"), json.flag("enabled"), json.flag("pending"))
    }
}

data class TwoFaSetup(val secret: String, val otpauth: String, val qr: String) {
    companion object {
        fun from(json: JSONObject): TwoFaSetup =
            TwoFaSetup(json.str("secret"), json.str("otpauth"), json.str("qr"))
    }
}

data class AuditEntry(val time: String, val ip: String, val user: String, val action: String) {
    companion object {
        fun from(json: JSONObject): AuditEntry =
            AuditEntry(json.str("time"), json.str("ip"), json.str("user"), json.str("action"))
    }
}

data class Fail2banStatus(
    val installed: Boolean,
    val jail: Boolean,
    val banned: List<String>,
    val currentlyFailed: Int,
    val totalFailed: Int,
    val log: String
) {
    companion object {
        fun from(json: JSONObject): Fail2banStatus = Fail2banStatus(
            installed = json.flag("installed"),
            jail = json.flag("jail"),
            banned = json.array("banned").stringList(),
            currentlyFailed = json.num("currentlyFailed"),
            totalFailed = json.num("totalFailed"),
            log = json.str("log")
        )
    }
}

data class NotifyStatus(
    val enabled: Boolean,
    val chatId: String,
    val hasToken: Boolean,
    val lastAlertAt: Long,
    val lastState: String
) {
    companion object {
        fun from(json: JSONObject): NotifyStatus = NotifyStatus(
            enabled = json.flag("enabled"),
            chatId = json.str("chatId"),
            hasToken = json.flag("hasToken"),
            lastAlertAt = json.long("lastAlertAt"),
            lastState = json.str("lastState")
        )
    }
}

// ---------------------------------------------------------------------------
// Сеть: UFW, каскад, инструменты, WARP
// ---------------------------------------------------------------------------

data class UfwRule(val id: String, val to: String, val action: String, val from: String) {
    companion object {
        fun from(json: JSONObject): UfwRule =
            UfwRule(json.str("id"), json.str("to"), json.str("action"), json.str("from"))
    }
}

data class UfwPort(val proto: String, val addr: String, val proc: String) {
    companion object {
        fun from(json: JSONObject): UfwPort =
            UfwPort(json.str("proto"), json.str("addr"), json.str("proc"))
    }
}

data class UfwData(val rules: List<UfwRule>, val ports: List<UfwPort>) {
    companion object {
        fun from(json: JSONObject): UfwData = UfwData(
            rules = json.array("rules").objects().map(UfwRule::from),
            ports = json.array("ports").objects().map(UfwPort::from)
        )
    }
}

data class CascadeStatus(
    val active: Boolean,
    val text: String,
    val color: String,
    val configured: Boolean,
    val tunnelActive: Boolean,
    val timerActive: Boolean,
    val endpoint: String,
    val ifaces: List<String>,
    val ipsetCount: Int,
    val ruleExists: Boolean,
    val tableRoute: String,
    val handshakeAgo: Long?,
    val lastRun: String,
    val confPath: String
) {
    companion object {
        fun from(json: JSONObject): CascadeStatus = CascadeStatus(
            active = json.flag("active"),
            text = json.str("text"),
            color = json.str("color"),
            configured = json.flag("configured"),
            tunnelActive = json.flag("tunnelActive"),
            timerActive = json.flag("timerActive"),
            endpoint = json.str("endpoint"),
            ifaces = json.array("ifaces").stringList(),
            ipsetCount = json.num("ipsetCount"),
            ruleExists = json.flag("ruleExists"),
            tableRoute = json.str("tableRoute"),
            handshakeAgo = json.optLong("handshakeAgo", -1L).takeIf { it >= 0L },
            lastRun = json.str("lastRun"),
            confPath = json.str("confPath")
        )
    }
}

data class CascadeHealth(
    val ok: Boolean,
    val checks: Map<String, Boolean>,
    val endpoint: String,
    val handshakeAgo: Long?,
    val ifaces: List<String>,
    val ipsetCount: Int,
    val tableRoute: String,
    val lastRun: String,
    val checkedAt: String
) {
    companion object {
        fun from(json: JSONObject): CascadeHealth {
            val checks = buildMap {
                json.objOrNull("checks")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optBoolean(k, false))
                    }
                }
            }
            val details = json.objOrNull("details") ?: JSONObject()
            return CascadeHealth(
                ok = json.flag("ok"),
                checks = checks,
                endpoint = details.str("endpoint"),
                handshakeAgo = details.optLong("handshakeAgo", -1L).takeIf { it >= 0L },
                ifaces = details.array("ifaces").stringList(),
                ipsetCount = details.num("ipsetCount"),
                tableRoute = details.str("tableRoute"),
                lastRun = details.str("lastRun"),
                checkedAt = json.str("checkedAt")
            )
        }
    }
}

data class RuDropState(
    val active: Boolean,
    val count: Int,
    val ifaces: List<String>,
    val allPorts: Boolean
) {
    companion object {
        fun from(json: JSONObject): RuDropState = RuDropState(
            active = json.flag("active"),
            count = json.num("count"),
            ifaces = json.array("ifaces").stringList(),
            allPorts = json.flag("allPorts")
        )
    }
}

data class CascadeToolState(val active: Boolean, val ifaces: List<String>) {
    companion object {
        fun from(json: JSONObject): CascadeToolState =
            CascadeToolState(json.flag("active"), json.array("ifaces").stringList())
    }
}

data class DnsToolState(val active: Boolean, val ifaces: List<String>, val target: String) {
    companion object {
        fun from(json: JSONObject): DnsToolState =
            DnsToolState(json.flag("active"), json.array("ifaces").stringList(), json.str("target"))
    }
}

data class ToolsStatus(
    val ruDrop: RuDropState,
    val cascade: CascadeToolState,
    val dns: DnsToolState,
    val conflictIfaces: List<String>
) {
    companion object {
        fun from(json: JSONObject): ToolsStatus = ToolsStatus(
            ruDrop = RuDropState.from(json.objOrNull("ruDrop") ?: JSONObject()),
            cascade = CascadeToolState.from(json.objOrNull("cascade") ?: JSONObject()),
            dns = DnsToolState.from(json.objOrNull("dns") ?: JSONObject()),
            conflictIfaces = json.array("conflictIfaces").stringList()
        )
    }
}

data class WarpStatus(
    val installed: Boolean,
    val warpActive: Boolean,
    val xrayActive: Boolean,
    val connected: Boolean,
    val warpStatus: String,
    val domains: Int,
    val listenIp: String,
    val listenPort: String,
    val socksPort: String
) {
    val active: Boolean get() = warpActive || connected

    companion object {
        fun from(json: JSONObject): WarpStatus = WarpStatus(
            installed = json.flag("installed"),
            warpActive = json.flag("warpActive"),
            xrayActive = json.flag("xrayActive"),
            connected = json.flag("connected"),
            warpStatus = json.str("warpStatus"),
            domains = json.num("domains"),
            listenIp = json.str("listenIp"),
            listenPort = json.optString("listenPort", ""),
            socksPort = json.optString("socksPort", "")
        )
    }
}

data class WarpTestResult(
    val ok: Boolean,
    val ip: String,
    val org: String,
    val country: String,
    val city: String,
    val error: String
) {
    companion object {
        fun from(json: JSONObject): WarpTestResult = WarpTestResult(
            ok = json.flag("ok"),
            ip = json.str("ip"),
            org = json.str("org"),
            country = json.str("country"),
            city = json.str("city"),
            error = json.str("error")
        )
    }
}

// ---------------------------------------------------------------------------
// Бэкапы
// ---------------------------------------------------------------------------

data class ArchiveInfo(val name: String, val size: Long, val mtime: String) {
    companion object {
        fun from(json: JSONObject): ArchiveInfo =
            ArchiveInfo(json.str("name"), json.long("size"), json.str("mtime"))
    }
}

data class BackupAuto(
    val enabled: Boolean,
    val schedule: String,
    val time: String,
    val keep: Int,
    val encrypt: Boolean,
    val timerActive: Boolean,
    val passwordSet: Boolean,
    val archives: List<ArchiveInfo>,
    val dir: String
) {
    companion object {
        fun from(json: JSONObject): BackupAuto {
            val cfg = json.objOrNull("config") ?: JSONObject()
            return BackupAuto(
                enabled = cfg.flag("enabled"),
                schedule = cfg.str("schedule").ifEmpty { "daily" },
                time = cfg.str("time").ifEmpty { "04:30" },
                keep = cfg.num("keep").coerceAtLeast(1),
                encrypt = cfg.optBoolean("encrypt", true),
                timerActive = json.flag("timerActive"),
                passwordSet = json.flag("passwordSet"),
                archives = json.array("archives").objects().map(ArchiveInfo::from),
                dir = json.str("dir")
            )
        }
    }
}

data class BackupRunResult(val output: String, val archives: List<ArchiveInfo>) {
    companion object {
        fun from(json: JSONObject): BackupRunResult = BackupRunResult(
            output = json.str("output"),
            archives = json.array("archives").objects().map(ArchiveInfo::from)
        )
    }
}

// ---------------------------------------------------------------------------
// SSL и домены
// ---------------------------------------------------------------------------

data class SslCert(
    val name: String,
    val domains: List<String>,
    val fullchain: String,
    val privkey: String,
    val chain: String,
    val daysLeft: Int?,
    val validTo: String
) {
    companion object {
        fun from(json: JSONObject): SslCert = SslCert(
            name = json.str("name"),
            domains = json.array("domains").stringList(),
            fullchain = json.str("fullchain"),
            privkey = json.str("privkey"),
            chain = json.str("chain"),
            daysLeft = json.optInt("daysLeft", -1).takeIf { it >= 0 },
            validTo = json.str("validTo")
        )
    }
}

data class SslSite(
    val name: String,
    val domains: List<String>,
    val enabled: Boolean,
    val ssl: Boolean,
    val mode: String,
    val certPath: String,
    val keyPath: String,
    val proxyPort: Int?,
    val root: String,
    val managedByPanel: Boolean,
    val httpsPort: Int?
) {
    companion object {
        fun from(json: JSONObject): SslSite = SslSite(
            name = json.str("name"),
            domains = json.array("domains").stringList(),
            enabled = json.flag("enabled"),
            ssl = json.flag("ssl"),
            mode = json.str("mode"),
            certPath = json.str("certPath"),
            keyPath = json.str("keyPath"),
            proxyPort = json.optInt("proxyPort", 0).takeIf { it > 0 },
            root = json.str("root"),
            managedByPanel = json.flag("managedByPanel"),
            httpsPort = json.optInt("httpsPort", 0).takeIf { it > 0 }
        )
    }
}

data class SslTool(val installed: Boolean, val version: String, val running: Boolean) {
    companion object {
        fun from(json: JSONObject?): SslTool = SslTool(
            installed = json?.flag("installed") ?: false,
            version = json?.str("version") ?: "",
            running = json?.flag("running") ?: false
        )
    }
}

data class SslStatus(
    val demo: Boolean,
    val panelDomain: String,
    val panelInternalPort: String,
    val panelPublicPort: String,
    val panelHttpsPort: String,
    val panelScheme: String,
    val panelUrl: String,
    val nginx: SslTool,
    val certbot: SslTool,
    val opensslInstalled: Boolean,
    val timerActive: Boolean,
    val timerEnabled: Boolean,
    val email: String,
    val certificates: List<SslCert>,
    val sites: List<SslSite>,
    val warnings: List<String>,
    val paths: Map<String, String>
) {
    companion object {
        fun from(json: JSONObject): SslStatus {
            val panel = json.objOrNull("panel") ?: JSONObject()
            val tools = json.objOrNull("tools") ?: JSONObject()
            val timer = json.objOrNull("timer") ?: JSONObject()
            val paths = buildMap {
                json.objOrNull("paths")?.let { obj ->
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optString(k, ""))
                    }
                }
            }
            return SslStatus(
                demo = json.flag("demo"),
                panelDomain = panel.str("domain"),
                panelInternalPort = panel.optString("internalPort", ""),
                panelPublicPort = panel.optString("publicPort", ""),
                panelHttpsPort = panel.optString("httpsPort", ""),
                panelScheme = panel.str("scheme"),
                panelUrl = panel.str("url"),
                nginx = SslTool.from(tools.objOrNull("nginx")),
                certbot = SslTool.from(tools.objOrNull("certbot")),
                opensslInstalled = tools.objOrNull("openssl")?.flag("installed") ?: false,
                timerActive = timer.flag("active"),
                timerEnabled = timer.flag("enabled"),
                email = json.str("email"),
                certificates = json.array("certificates").objects().map(SslCert::from),
                sites = json.array("sites").objects().map(SslSite::from),
                warnings = json.array("warnings").stringList(),
                paths = paths
            )
        }
    }
}

data class SslSiteResult(
    val domain: String,
    val mode: String,
    val url: String,
    val siteName: String,
    val httpsPort: String,
    val dns: List<String>,
    val warnings: List<String>,
    val demo: Boolean
) {
    companion object {
        fun from(json: JSONObject): SslSiteResult = SslSiteResult(
            domain = json.str("domain"),
            mode = json.str("mode"),
            url = json.str("url"),
            siteName = json.str("siteName"),
            httpsPort = json.optString("httpsPort", ""),
            dns = json.array("dns").stringList(),
            warnings = json.array("warnings").stringList(),
            demo = json.flag("demo")
        )
    }
}

// ---------------------------------------------------------------------------
// Обновления
// ---------------------------------------------------------------------------

data class UpdateBackup(val name: String, val path: String, val size: Long, val mtime: String) {
    companion object {
        fun from(json: JSONObject): UpdateBackup =
            UpdateBackup(json.str("name"), json.str("path"), json.long("size"), json.str("mtime"))
    }
}

data class UpdateStatus(
    val running: Boolean,
    val ok: Boolean,
    val step: String,
    val message: String,
    val log: List<String>,
    val logTail: String,
    val backups: List<UpdateBackup>,
    val version: String,
    val stale: Boolean
) {
    companion object {
        fun from(json: JSONObject): UpdateStatus = UpdateStatus(
            running = json.flag("running"),
            ok = json.flag("ok"),
            step = json.str("step"),
            message = json.str("message"),
            log = json.array("log").stringList(),
            logTail = json.str("logTail"),
            backups = json.array("backups").objects().map(UpdateBackup::from),
            version = json.str("version"),
            stale = json.flag("stale")
        )
    }
}

data class GitLocal(
    val available: Boolean,
    val branch: String,
    val remote: String,
    val head: String,
    val subject: String,
    val dirty: List<String>,
    val behind: Int?
) {
    companion object {
        fun from(json: JSONObject?): GitLocal {
            if (json == null) return GitLocal(false, "", "", "", "", emptyList(), null)
            return GitLocal(
                available = json.flag("available"),
                branch = json.str("branch"),
                remote = json.str("remote"),
                head = json.str("head"),
                subject = json.str("subject"),
                dirty = json.array("dirty").stringList(),
                behind = json.optInt("behind", -1).takeIf { it >= 0 }
            )
        }
    }
}

data class UpdateInfo(
    val version: String,
    val appDir: String,
    val dataDir: String,
    val port: String,
    val pm2Name: String,
    val backupDir: String,
    val node: String,
    val git: GitLocal,
    val status: UpdateStatus,
    val backups: List<UpdateBackup>,
    val logTail: String,
    val cascadeIfaces: List<String>,
    val cascadeConf: Boolean
) {
    companion object {
        fun from(json: JSONObject): UpdateInfo = UpdateInfo(
            version = json.str("version"),
            appDir = json.str("appDir"),
            dataDir = json.str("dataDir"),
            port = json.optString("port", ""),
            pm2Name = json.str("pm2Name"),
            backupDir = json.str("backupDir"),
            node = json.str("node"),
            git = GitLocal.from(json.objOrNull("git")),
            status = UpdateStatus.from(json.objOrNull("status") ?: JSONObject()),
            backups = json.array("backups").objects().map(UpdateBackup::from),
            logTail = json.str("logTail"),
            cascadeIfaces = json.array("cascadeIfaces").stringList(),
            cascadeConf = json.flag("cascadeConf")
        )
    }
}

data class GitCheck(
    val ok: Boolean,
    val branch: String,
    val current: String,
    val latest: String,
    val behind: Int,
    val upToDate: Boolean,
    val dirty: List<String>,
    val commit: String,
    val error: String
) {
    companion object {
        fun from(json: JSONObject): GitCheck = GitCheck(
            ok = json.flag("ok"),
            branch = json.str("branch"),
            current = json.str("current"),
            latest = json.str("latest"),
            behind = json.num("behind"),
            upToDate = json.flag("upToDate"),
            dirty = json.array("dirty").stringList(),
            commit = json.str("commit"),
            error = json.str("error")
        )
    }
}

// ---------------------------------------------------------------------------
// Установка модулей
// ---------------------------------------------------------------------------

data class InstallStep(val key: String, val title: String, val done: Boolean) {
    companion object {
        fun from(json: JSONObject): InstallStep =
            InstallStep(json.str("key"), json.str("title"), json.flag("done"))
    }
}

data class InstallState(
    val active: Boolean,
    val module: String,
    val title: String,
    val logFile: String,
    val startedAt: String,
    val status: String,
    val steps: List<InstallStep>,
    val progress: Int
) {
    companion object {
        fun from(json: JSONObject): InstallState = InstallState(
            active = json.flag("active"),
            module = json.str("module"),
            title = json.str("title"),
            logFile = json.str("logFile"),
            startedAt = json.str("startedAt"),
            status = json.str("status"),
            steps = json.array("steps").objects().map(InstallStep::from),
            progress = json.num("progress")
        )
    }
}

data class InstallProgress(
    val text: String,
    val status: String,
    val steps: List<InstallStep>,
    val done: Boolean,
    val error: Boolean,
    val progress: Int
) {
    companion object {
        fun from(json: JSONObject): InstallProgress = InstallProgress(
            text = json.str("text"),
            status = json.str("status"),
            steps = json.array("steps").objects().map(InstallStep::from),
            done = json.flag("done"),
            error = json.flag("error"),
            progress = json.num("progress")
        )
    }
}

// ---------------------------------------------------------------------------
// Обфускация
// ---------------------------------------------------------------------------

data class ObfFinding(
    val level: String,
    val id: String,
    val title: String,
    val detail: String,
    val fix: String
) {
    companion object {
        fun from(json: JSONObject): ObfFinding = ObfFinding(
            level = json.str("level"),
            id = json.str("id"),
            title = json.str("title"),
            detail = json.str("detail"),
            fix = json.str("fix")
        )
    }
}

data class ObfAudit(
    val score: Int,
    val level: String,
    val version: String,
    val findings: List<ObfFinding>,
    val shared: String,
    val client: String,
    val cps: Int,
    val hpk: Boolean
) {
    val levelLabel: String
        get() = when (level) {
            "strong" -> "Устойчивый"
            "good" -> "Хороший"
            "weak" -> "Слабый"
            "critical" -> "Критичный"
            else -> level
        }

    companion object {
        fun from(json: JSONObject?): ObfAudit {
            if (json == null) return ObfAudit(0, "", "", emptyList(), "", "", 0, false)
            val summary = json.objOrNull("summary") ?: JSONObject()
            return ObfAudit(
                score = json.num("score"),
                level = json.str("level"),
                version = json.str("version"),
                findings = json.array("findings").objects().map(ObfFinding::from),
                shared = summary.str("shared"),
                client = summary.str("client"),
                cps = summary.num("cps"),
                hpk = summary.flag("hpk")
            )
        }
    }
}

data class ObfCurrent(
    val file: String,
    val params: Map<String, String>,
    val version: String,
    val audit: ObfAudit
) {
    companion object {
        fun from(json: JSONObject): ObfCurrent = ObfCurrent(
            file = json.str("file"),
            params = (json.objOrNull("params") ?: JSONObject()).mapOfStrings(),
            version = json.str("version"),
            audit = ObfAudit.from(json.objOrNull("audit"))
        )
    }
}

data class ObfProfile(
    val name: String,
    val title: String,
    val params: Map<String, String>,
    val builtin: Boolean,
    val level: String,
    val score: Int,
    val note: String
) {
    companion object {
        fun from(json: JSONObject): ObfProfile = ObfProfile(
            name = json.str("name"),
            title = json.str("title").ifEmpty { json.str("name") },
            params = (json.objOrNull("params") ?: JSONObject()).mapOfStrings(),
            builtin = json.flag("builtin"),
            level = json.str("level"),
            score = json.num("score"),
            note = json.str("note")
        )
    }
}

data class ObfProfiles(
    val builtin: List<ObfProfile>,
    val custom: List<ObfProfile>,
    val current: Map<String, String>,
    val currentAudit: ObfAudit
) {
    companion object {
        fun from(json: JSONObject): ObfProfiles = ObfProfiles(
            builtin = json.array("builtin").objects().map(ObfProfile::from),
            custom = json.array("custom").objects().map(ObfProfile::from),
            current = (json.objOrNull("current") ?: JSONObject()).mapOfStrings(),
            currentAudit = ObfAudit.from(json.objOrNull("currentAudit"))
        )
    }
}

data class ObfGenerated(
    val params: Map<String, String>,
    val version: String,
    val audit: ObfAudit
) {
    companion object {
        fun from(json: JSONObject): ObfGenerated = ObfGenerated(
            params = (json.objOrNull("params") ?: JSONObject()).mapOfStrings(),
            version = json.str("version"),
            audit = ObfAudit.from(json.objOrNull("audit"))
        )
    }
}

data class ObfApplyResult(
    val applied: Int,
    val removed: List<String>,
    val backup: String,
    val regenerated: Int?,
    val version: String,
    val audit: ObfAudit,
    val simulated: Boolean
) {
    companion object {
        fun from(json: JSONObject): ObfApplyResult = ObfApplyResult(
            applied = json.num("applied"),
            removed = json.array("removed").stringList(),
            backup = json.str("backup"),
            regenerated = json.optInt("regenerated", -1).takeIf { it >= 0 },
            version = json.str("version"),
            audit = ObfAudit.from(json.objOrNull("audit")),
            simulated = json.flag("simulated")
        )
    }
}

// ---------------------------------------------------------------------------
// Сервер и диагностика
// ---------------------------------------------------------------------------

data class CertInfo(
    val name: String,
    val fullchain: String,
    val privkey: String,
    val chain: String,
    val daysLeft: Int?,
    val validTo: String
) {
    companion object {
        fun from(json: JSONObject): CertInfo = CertInfo(
            name = json.str("name"),
            fullchain = json.str("fullchain"),
            privkey = json.str("privkey"),
            chain = json.str("chain"),
            daysLeft = json.optInt("daysLeft", -1).takeIf { it >= 0 },
            validTo = json.str("validTo")
        )
    }
}

data class ServerInfo(
    val ip: String,
    val domain: String,
    val country: String,
    val cert: CertInfo?
) {
    companion object {
        fun from(json: JSONObject): ServerInfo = ServerInfo(
            ip = json.str("ip"),
            domain = json.str("domain"),
            country = json.str("country"),
            cert = json.objOrNull("cert")?.let(CertInfo::from)
        )
    }
}

data class DiagResult(val ok: Boolean, val host: String, val output: String) {
    companion object {
        fun from(json: JSONObject): DiagResult =
            DiagResult(json.flag("ok"), json.str("host"), json.str("output"))
    }
}

data class AwgConfig(val content: String, val file: String) {
    companion object {
        fun from(json: JSONObject): AwgConfig =
            AwgConfig(json.str("content"), json.str("file"))
    }
}

// ---------------------------------------------------------------------------
// MTProto-прокси: MTProxyL / Telemt / MTProto.zig (панель 2.8+)
// ---------------------------------------------------------------------------

private fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

data class ProxyCapabilities(
    val users: Boolean = false,
    val limits: Boolean = false,
    val quota: Boolean = false,
    val rate: Boolean = false,
    val uniqueIps: Boolean = false,
    val connections: Boolean = false,
    val traffic: Boolean = false,
    val notes: Boolean = false,
    val expiry: Boolean = false,
    val adTag: Boolean = false,
    val web: Boolean = false,
    val restart: Boolean = false
) {
    companion object {
        fun from(json: JSONObject?): ProxyCapabilities {
            val src = json ?: JSONObject()
            return ProxyCapabilities(
                users = src.flag("users"),
                limits = src.flag("limits"),
                quota = src.flag("quota"),
                rate = src.flag("rate"),
                uniqueIps = src.flag("uniqueIps"),
                connections = src.flag("connections"),
                traffic = src.flag("traffic"),
                notes = src.flag("notes"),
                expiry = src.flag("expiry"),
                adTag = src.flag("adTag"),
                web = src.flag("web"),
                restart = src.flag("restart")
            )
        }
    }
}

/** Состояние движка MTProto-прокси (`/api/proxy/status`). */
data class ProxyStatus(
    val backend: String = "none",
    val limited: Boolean = false,
    val needsKey: Boolean = false,
    val capabilities: ProxyCapabilities = ProxyCapabilities(),
    val reason: String = "",
    val version: String = "",
    val mode: String = "",
    val running: Boolean = false,
    val port: Int? = null,
    val domain: String = "",
    val uptime: Long = 0L,
    val connections: Int = 0,
    val uniqueIps: Int = 0,
    val trafficTotal: Long = 0L,
    val trafficIn: Long = 0L,
    val trafficOut: Long = 0L,
    val usersCount: Int = 0,
    val configPath: String = "",
    val error: String = ""
) {
    val title: String
        get() = when (backend) {
            "mtproxyl" -> "MTProxyL"
            "telemt" -> "Telemt"
            "zig" -> "MTProto.zig"
            "demo" -> "Демо-прокси"
            else -> "Не найден"
        }

    val available: Boolean get() = backend != "none" && backend != "zig"

    companion object {
        fun from(json: JSONObject): ProxyStatus {
            return ProxyStatus(
                backend = json.str("backend").ifBlank { "none" },
                limited = json.flag("limited"),
                needsKey = json.flag("needsKey"),
                capabilities = ProxyCapabilities.from(json.objOrNull("capabilities")),
                reason = json.str("reason"),
                version = json.str("version"),
                mode = json.str("mode"),
                running = json.flag("running"),
                port = json.intOrNull("port")?.takeIf { it > 0 },
                domain = json.str("domain"),
                uptime = json.long("uptime"),
                connections = json.num("connections"),
                uniqueIps = json.num("uniqueIps"),
                trafficTotal = json.long("trafficTotal"),
                trafficIn = json.long("trafficIn"),
                trafficOut = json.long("trafficOut"),
                usersCount = json.num("users"),
                configPath = json.str("configPath"),
                error = json.str("error")
            )
        }
    }
}

data class ProxyTraffic(
    val inBytes: Long = 0L,
    val outBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    companion object {
        fun from(json: JSONObject?): ProxyTraffic {
            val src = json ?: JSONObject()
            return ProxyTraffic(
                inBytes = src.long("in_bytes"),
                outBytes = src.long("out_bytes"),
                totalBytes = src.long("total_bytes")
            )
        }
    }
}

data class ProxyLinks(
    val classic: List<String> = emptyList(),
    val secure: List<String> = emptyList(),
    val tls: List<String> = emptyList(),
    val web: List<String> = emptyList(),
    val tlsDomains: List<String> = emptyList()
) {
    /** Все ссылки в одном списке без повторов. */
    fun flat(): List<String> {
        val all = classic + secure + tls + web + tlsDomains
        return all.filter { it.isNotBlank() }.distinct()
    }

    companion object {
        fun from(json: JSONObject?): ProxyLinks {
            val src = json ?: JSONObject()
            return ProxyLinks(
                classic = src.array("classic").stringList(),
                secure = src.array("secure").stringList(),
                tls = src.array("tls").stringList(),
                web = src.array("web").stringList(),
                tlsDomains = src.array("tls_domains").objects().mapNotNull { it.str("link").ifBlank { null } }
            )
        }
    }
}

/** Пользователь прокси (`/api/proxy/users`). */
data class ProxyUser(
    val username: String,
    val enabled: Boolean = true,
    val inRuntime: Boolean = true,
    val adTag: String = "",
    val maxTcpConns: Int? = null,
    val expiration: String = "",
    val quotaBytes: Long? = null,
    val rateUpBps: Long? = null,
    val rateDownBps: Long? = null,
    val maxUniqueIps: Int? = null,
    val currentConnections: Int = 0,
    val activeUniqueIps: Int = 0,
    val totalOctets: Long = 0L,
    val notes: String = "",
    val created: Long = 0L,
    val traffic: ProxyTraffic = ProxyTraffic(),
    val secret: String = "",
    val links: ProxyLinks = ProxyLinks()
) {
    val quotaFraction: Float?
        get() {
            val quota = quotaBytes ?: return null
            if (quota <= 0L) return null
            val used = traffic.totalBytes.takeIf { it > 0 } ?: totalOctets
            return (used.toDouble() / quota.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val expired: Boolean
        get() {
            if (expiration.isBlank()) return false
            val time = runCatching { java.time.OffsetDateTime.parse(expiration).toInstant().toEpochMilli() }
                .getOrNull() ?: return false
            return time < System.currentTimeMillis()
        }

    companion object {
        fun from(json: JSONObject): ProxyUser = ProxyUser(
            username = json.str("username"),
            enabled = json.optBoolean("enabled", true),
            inRuntime = json.optBoolean("in_runtime", true),
            adTag = json.str("user_ad_tag"),
            maxTcpConns = json.intOrNull("max_tcp_conns"),
            expiration = json.str("expiration_rfc3339"),
            quotaBytes = json.longOrNull("data_quota_bytes"),
            rateUpBps = json.longOrNull("rate_limit_up_bps"),
            rateDownBps = json.longOrNull("rate_limit_down_bps"),
            maxUniqueIps = json.intOrNull("max_unique_ips"),
            currentConnections = json.num("current_connections"),
            activeUniqueIps = json.num("active_unique_ips"),
            totalOctets = json.long("total_octets"),
            notes = json.str("notes"),
            created = json.long("created"),
            traffic = ProxyTraffic.from(json.objOrNull("traffic")),
            secret = json.str("secret"),
            links = ProxyLinks.from(json.objOrNull("links"))
        )
    }
}

data class ProxyUserLinks(
    val username: String,
    val links: List<String>,
    val qr: String
) {
    companion object {
        fun from(json: JSONObject): ProxyUserLinks = ProxyUserLinks(
            username = json.str("username"),
            links = json.array("links").stringList(),
            qr = json.str("qr")
        )
    }
}

data class ProxyBotStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val tokenSet: Boolean = false,
    val token: String = "",
    val admins: List<String> = emptyList(),
    val lastUpdateId: Long = 0L,
    val configFile: String = ""
) {
    companion object {
        fun from(json: JSONObject): ProxyBotStatus = ProxyBotStatus(
            enabled = json.flag("enabled"),
            running = json.flag("running"),
            tokenSet = json.flag("tokenSet") || json.str("token").isNotBlank(),
            token = json.str("token"),
            admins = json.array("admins").stringList(),
            lastUpdateId = json.long("lastUpdateId"),
            configFile = json.str("configFile")
        )
    }
}
