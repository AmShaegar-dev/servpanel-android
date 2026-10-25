package ru.servpanel.mobile.data

import android.content.Context
import org.json.JSONArray

/** Простое локальное хранилище списка серверов (SharedPreferences + JSON). */
class ServersStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("servpanel_servers", Context.MODE_PRIVATE)

    fun read(): List<ServerProfile> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
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
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
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
