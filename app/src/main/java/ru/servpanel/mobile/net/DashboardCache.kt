package ru.servpanel.mobile.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.servpanel.mobile.data.ClientInfo
import ru.servpanel.mobile.data.SecurityStatus
import ru.servpanel.mobile.data.ServiceInfo
import ru.servpanel.mobile.data.SysInfo

/**
 * Последний удачный снимок «Обзора», чтобы экран открывался сразу.
 *
 * Данные лежат в SharedPreferences в зашифрованном виде (AES-256/GCM,
 * ключ в Android Keystore — см. [SecureStore]). В открытом виде на диск
 * ничего не пишется, поэтому вытащить список клиентов и адреса сервера
 * из резервной копии или при root-доступе к файлу настроек не получится.
 *
 * Снимок не содержит паролей и cookie — только то, что и так показывает
 * экран: нагрузка, клиенты, службы и состояние защиты.
 */
class DashboardCache(context: Context) {

    private val store = SecureStore(context.applicationContext, PREFS)

    fun load(serverId: String): DashboardSnapshot? {
        val raw = store.getString(key(serverId)) ?: return null
        if (raw.length > MAX_CHARS) {
            store.remove(key(serverId))
            return null
        }
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun save(serverId: String, snapshot: DashboardSnapshot) {
        val payload = runCatching { encode(snapshot).toString() }.getOrNull() ?: return
        // Очень большие списки клиентов не кэшируем: приватность важнее,
        // а экран всё равно обновится запросом.
        if (payload.length > MAX_CHARS) {
            store.remove(key(serverId))
            return
        }
        store.putString(key(serverId), payload)
    }

    fun clear(serverId: String) = store.remove(key(serverId))

    private fun key(serverId: String) = "overview_$serverId"

    private fun encode(snapshot: DashboardSnapshot): JSONObject {
        val json = JSONObject()
        json.put("at", snapshot.at)
        json.put(
            "sys",
            JSONObject()
                .put("cpu", snapshot.sys.cpu.toDouble())
                .put("ram", snapshot.sys.ram.toDouble())
                .put("load", snapshot.sys.load)
                .put("cores", snapshot.sys.cores)
                .put("uptime", snapshot.sys.uptime)
                .put("disk_total", snapshot.sys.diskTotal)
                .put("disk_used", snapshot.sys.diskUsed)
                .put("disk_free", snapshot.sys.diskFree)
                .put("disk_percent", snapshot.sys.diskPercent)
        )
        val clients = JSONArray()
        snapshot.clients.forEach { client ->
            clients.put(
                JSONObject()
                    .put("name", client.name)
                    .put("ip", client.ip)
                    .put("client_ipv6", client.ipv6)
                    .put("status", client.status)
                    .put("status_code", client.statusCode)
                    .put("expires_at", client.expiresAt ?: 0L)
                    .put("rx", client.rx)
                    .put("tx", client.tx)
                    .put("last_handshake", client.lastHandshake)
                    .put("has_vpnuri", client.hasVpnUri)
            )
        }
        json.put("clients", clients)

        val services = JSONArray()
        snapshot.services.forEach { service ->
            services.put(
                JSONObject()
                    .put("name", service.name)
                    .put("desc", service.desc)
                    .put("status", service.status)
                    .put("state", service.state)
                    .put("enabled", service.enabled)
            )
        }
        json.put("services", services)

        snapshot.security?.let { st ->
            json.put(
                "security",
                JSONObject()
                    .put("user", st.user)
                    .put("role", st.role)
                    .put("https", st.https)
                    .put("node", st.node)
                    .put("panelPort", st.panelPort)
                    .put("panelPublicPort", st.panelPublicPort)
                    .put("users", st.users)
                    .put("totpEnabled", st.totpEnabled)
                    .put("dataDir", st.dataDir)
                    .put("defaultPassword", st.defaultPassword)
                    .put("passFileExists", st.passFileExists)
                    .put("httpsConfigured", st.httpsOn)
                    .put("httpsDomain", st.httpsDomain)
                    .put("httpsPort", st.httpsPort)
            )
        }
        return json
    }

    private fun decode(json: JSONObject): DashboardSnapshot {
        val sys = json.optJSONObject("sys") ?: JSONObject()
        val services = json.optJSONArray("services") ?: JSONArray()
        val clients = json.optJSONArray("clients") ?: JSONArray()

        return DashboardSnapshot(
            at = json.optLong("at", 0L),
            sys = SysInfo.from(
                JSONObject()
                    .put("cpu", sys.optDouble("cpu", 0.0))
                    .put("ram", sys.optDouble("ram", 0.0))
                    .put("load", sys.optString("load", ""))
                    .put("cores", sys.optInt("cores", 0))
                    .put("uptime", sys.optString("uptime", ""))
                    .put(
                        "disk",
                        JSONObject()
                            .put("total", sys.optLong("disk_total", 0L))
                            .put("used", sys.optLong("disk_used", 0L))
                            .put("free", sys.optLong("disk_free", 0L))
                            .put("percent", sys.optString("disk_percent", ""))
                    )
            ),
            clients = (0 until clients.length()).mapNotNull { i ->
                clients.optJSONObject(i)?.let(ClientInfo::from)
            },
            services = (0 until services.length()).mapNotNull { i ->
                services.optJSONObject(i)?.let(ServiceInfo::from)
            },
            security = json.optJSONObject("security")?.let(SecurityStatus::from)
        )
    }

    private companion object {
        const val PREFS = "servpanel_overview_cache"
        const val MAX_CHARS = 240_000
    }
}

/** Расшифрованный снимок экрана «Обзор». */
data class DashboardSnapshot(
    val at: Long,
    val sys: SysInfo,
    val clients: List<ClientInfo>,
    val services: List<ServiceInfo>,
    val security: SecurityStatus?
)
