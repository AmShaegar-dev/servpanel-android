package ru.servpanel.mobile.data

import android.content.Context
import org.json.JSONArray
import ru.servpanel.mobile.net.SecureStore

/**
 * Простое локальное хранилище списка серверов.
 *
 * JSON шифруется ключом из Android Keystore: в профилях есть адрес панели
 * и Bearer-токен metrics-сервера, и они не должны лежать открытым текстом.
 * Старые значения читаются и перезаписываются в зашифрованном виде.
 */
class ServersStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("servpanel_servers", Context.MODE_PRIVATE)
    private val store = SecureStore(context, "servpanel_servers")

    fun read(): List<ServerProfile> {
        val raw = store.getString(KEY_SERVERS) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { obj ->
                        ServerProfile.fromJson(obj)?.let(::add)
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun write(servers: List<ServerProfile>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        store.putString(KEY_SERVERS, array.toString())
    }

    fun readLastOpenedId(): String? = prefs.getString(KEY_LAST, null)

    fun writeLastOpenedId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_LAST) else putString(KEY_LAST, id)
        }.apply()
    }

    private companion object {
        const val KEY_SERVERS = "servers_json"
        const val KEY_LAST = "last_server_id"
    }
}
