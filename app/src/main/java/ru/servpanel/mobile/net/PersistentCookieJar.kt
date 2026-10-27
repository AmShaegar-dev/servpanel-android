package ru.servpanel.mobile.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cookie-хранилище панели для одного сервера.
 *
 * Раньше сессия жила в общем CookieManager WebView: две панели на одном хосте
 * с разными портами затирали друг другу panel.sid. Здесь cookie привязаны
 * к профилю сервера, поэтому несколько панелей работают независимо.
 */
class PersistentCookieJar(context: Context, serverId: String) : CookieJar {

    // Cookie сессии шифруются ключом из Android Keystore: файл настроек
    // бесполезен без устройства, а старые значения мигрируют автоматически.
    private val store = SecureStore(context, "servpanel_cookies")
    private val storageKey = "cookies_$serverId"
    private val lock = Any()
    private val cookies = linkedMapOf<String, Pair<String, Cookie>>()

    init {
        runCatching { restore() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            for (cookie in cookies) {
                val key = keyOf(cookie)
                if (cookie.expiresAt <= System.currentTimeMillis()) {
                    this.cookies.remove(key)
                } else {
                    this.cookies[key] = url.toString() to cookie
                }
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val expired = mutableListOf<String>()
            val matched = mutableListOf<Pair<String, Cookie>>()
            for ((key, entry) in cookies) {
                val cookie = entry.second
                if (cookie.expiresAt <= now) {
                    expired += key
                    continue
                }
                if (cookie.matches(url)) matched += key to cookie
            }
            if (expired.isNotEmpty()) {
                expired.forEach { cookies.remove(it) }
                persist()
            }
            // Сессия панели скользящая, но OkHttp отдаёт в запрос копию cookie
            // с тем expiresAt, что пришёл при последнем ответе. Поэтому после
            // каждого ответа панели срок обновляется ещё раз (saveFromResponse),
            // и сессия живёт 30 дней после последнего запроса, а не с входа.
            return matched.map { it.second }
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            store.remove(storageKey)
        }
    }

    private fun keyOf(cookie: Cookie): String =
        "${cookie.name}|${cookie.domain}|${cookie.path}"

    private fun restore() {
        val raw = store.getString(storageKey) ?: return
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url", "")
            val header = item.optString("header", "")
            val httpUrl = url.toHttpUrlOrNull() ?: continue
            val cookie = Cookie.parse(httpUrl, header) ?: continue
            cookies[keyOf(cookie)] = url to cookie
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (entry in cookies.values) {
            array.put(
                JSONObject()
                    .put("url", entry.first)
                    .put("header", entry.second.toString())
            )
        }
        store.putString(storageKey, array.toString())
    }
}
