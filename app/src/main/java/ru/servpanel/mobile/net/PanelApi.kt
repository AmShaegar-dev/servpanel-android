package ru.servpanel.mobile.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.servpanel.mobile.data.MetricsSnapshot
import ru.servpanel.mobile.data.PanelUrl
import ru.servpanel.mobile.data.ServerProfile
import ru.servpanel.mobile.data.SysInfo
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Проверки серверов для главного экрана и редактора.
 *
 * Рабочие запросы к панели выполняет [PanelClient] — здесь только сводка
 * состояния и проверка адреса при добавлении сервера.
 */
class PanelApi(private val context: Context) {

    suspend fun check(server: ServerProfile): ServerHealth = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val client = PanelClient(server, context)
        val started = System.currentTimeMillis()

        try {
            // Метрики и sysinfo запрашиваем параллельно и «Пинг» считаем по
            // одному запросу к API. Раньше запросы шли последовательно, а если
            // порт метрик закрыт файрволом, ожидание connect-timeout (6 с)
            // попадало в задержку на карточке, хотя панель отвечала быстро.
            val parts = coroutineScope {
                val metricsJob = async { timedMetrics(client, server) }
                val sysStarted = System.currentTimeMillis()
                val sys = runCatching { client.sysinfo() }
                // Важно: время sysinfo фиксируем до await(), иначе в него
                // попадёт ожидание метрик.
                val sysMs = System.currentTimeMillis() - sysStarted
                CheckParts(metrics = metricsJob.await(), sys = sys, sysMs = sysMs)
            }
            val metrics = parts.metrics.value
            val sys = parts.sys.getOrNull()
            val latency = if (sys != null) parts.sysMs else parts.metrics.ms

            if (sys == null && metrics == null) {
                val error = parts.sys.exceptionOrNull()
                return@withContext when {
                    error is PanelApiException && (error.status == 401 || error.status == 403) ->
                        ServerHealth(
                            online = true,
                            needsLogin = true,
                            latencyMs = latency,
                            checkedAt = now
                        )

                    error is PanelApiException ->
                        ServerHealth(online = false, error = error.message, checkedAt = now)

                    error is Exception ->
                        ServerHealth(online = false, error = describe(error), checkedAt = now)

                    else ->
                        ServerHealth(online = false, error = "Нет данных", checkedAt = now)
                }
            }

            val metricsSys = metrics?.toSysInfo()
            var health = ServerHealth(
                online = true,
                latencyMs = latency,
                checkedAt = now,
                cpuPercent = metrics?.cpuPercent ?: sys?.cpu,
                ramPercent = metrics?.ramPercent ?: sys?.ram,
                diskPercent = metricsSys?.diskPercent?.ifBlank { null }
                    ?: sys?.diskPercent?.ifBlank { null },
                load = metricsSys?.load?.ifBlank { null } ?: sys?.load?.ifBlank { null },
                cores = (metrics?.cores ?: sys?.cores ?: 0).takeIf { it > 0 },
                uptime = metricsSys?.uptime?.ifBlank { null } ?: sys?.uptime?.ifBlank { null },
                clientsTotal = metrics?.clientsTotal,
                clientsOnline = metrics?.clientsOnline,
                panelPort = metrics?.panelPort?.takeIf { it > 0 }?.toString(),
                https = metrics?.tls ?: false
            )

            // Список клиентов нужен только без метрик, данные сессии — всегда.
            // Оба запроса независимы, поэтому идём в них одновременно.
            val extras = coroutineScope {
                val clientsJob = if (metrics == null) {
                    async { runCatching { client.clients() }.getOrNull() }
                } else {
                    null
                }
                val securityJob = async { runCatching { client.securityStatus() }.getOrNull() }
                clientsJob?.await() to securityJob.await()
            }

            extras.first?.let { clients ->
                health = health.copy(
                    clientsTotal = clients.size,
                    clientsOnline = clients.count {
                        it.statusCode == "active" || it.statusCode == "recent"
                    }
                )
            }

            extras.second?.let { security ->
                health = health.copy(
                    user = security.user.ifBlank { null },
                    role = security.role.ifBlank { null },
                    node = security.node.ifBlank { null },
                    https = security.https,
                    panelPort = security.panelPublicPort.ifBlank { null }
                )
            }

            // API ответил 401/403, но метрики доступны: сервер на связи,
            // просто нужно войти.
            if (sys == null) {
                val error = parts.sys.exceptionOrNull()
                if (error is PanelApiException && (error.status == 401 || error.status == 403)) {
                    health = health.copy(needsLogin = true)
                }
            }

            health
        } catch (e: PanelApiException) {
            when (e.status) {
                401 -> ServerHealth(
                    online = true,
                    needsLogin = true,
                    latencyMs = System.currentTimeMillis() - started,
                    checkedAt = now
                )

                403 -> ServerHealth(
                    online = true,
                    needsLogin = true,
                    latencyMs = System.currentTimeMillis() - started,
                    checkedAt = now
                )

                else -> ServerHealth(online = false, error = e.message, checkedAt = now)
            }
        } catch (e: Exception) {
            ServerHealth(online = false, error = describe(e), checkedAt = now)
        }
    }

    private data class Timed<T>(val value: T, val ms: Long)

    private data class CheckParts(
        val metrics: Timed<MetricsSnapshot?>,
        val sys: Result<SysInfo>,
        val sysMs: Long
    )

    /**
     * Метрики — необязательный источник: если порт закрыт, не ждём полный
     * connect-timeout, чтобы карточка сервера обновлялась быстрее.
     *
     * Недоступный порт запоминаем на [METRICS_COOLDOWN_MS], иначе каждая
     * проверка снова висела бы на нём до таймаута.
     */
    private suspend fun timedMetrics(
        client: PanelClient,
        server: ServerProfile
    ): Timed<MetricsSnapshot?> {
        if (server.metricsPort <= 0) return Timed(null, 0L)
        val key = "${server.id}:${server.metricsPort}"
        val until = metricsCooldown[key]
        if (until != null) {
            if (until > System.currentTimeMillis()) return Timed(null, 0L)
            metricsCooldown.remove(key)
        }

        val started = System.currentTimeMillis()
        val result = runCatching { client.metrics() }
        val value = result.getOrNull()
        val error = result.exceptionOrNull()
        if (value == null && error is PanelApiException && error.status == 0) {
            metricsCooldown[key] = System.currentTimeMillis() + METRICS_COOLDOWN_MS
        }
        return Timed(value, System.currentTimeMillis() - started)
    }

    /** Проверка адреса из редактора: при отсутствии схемы пробует https, затем http. */
    suspend fun probe(raw: String, allowInsecure: Boolean): ProbeResult = withContext(Dispatchers.IO) {
        val explicitScheme = PanelUrl.hasScheme(raw)
        val candidates = if (explicitScheme) {
            listOfNotNull(PanelUrl.normalize(raw))
        } else {
            listOfNotNull(
                PanelUrl.normalize("https://$raw"),
                PanelUrl.normalize("http://$raw")
            ).distinct()
        }

        if (candidates.isEmpty()) {
            return@withContext ProbeResult(false, message = "Некорректный адрес сервера")
        }

        val client = HttpClients.forServer(allowInsecure)
        var lastError = "Не удалось подключиться"

        for (candidate in candidates) {
            val started = System.currentTimeMillis()
            try {
                val response = fetch(client, "$candidate/api/security/status")
                val latency = System.currentTimeMillis() - started
                when (response.code) {
                    200 -> return@withContext ProbeResult(
                        ok = true,
                        resolvedUrl = baseUrlOf(response.finalUrl) ?: candidate,
                        message = "Панель на связи",
                        latencyMs = latency
                    )

                    401 -> return@withContext ProbeResult(
                        ok = true,
                        resolvedUrl = baseUrlOf(response.finalUrl) ?: candidate,
                        message = "Панель найдена, потребуется вход",
                        latencyMs = latency,
                        needsLogin = true
                    )

                    else -> lastError = "Сервер ответил HTTP ${response.code}"
                }
            } catch (e: Exception) {
                lastError = describe(e)
            }
        }

        ProbeResult(false, message = lastError)
    }

    suspend fun logout(server: ServerProfile) = withContext(Dispatchers.IO) {
        runCatching { PanelClient(server, context).logout() }
        Unit
    }

    private data class Response(val code: Int, val body: String, val finalUrl: String)

    private fun fetch(client: OkHttpClient, url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            return Response(
                code = response.code,
                body = response.body?.string().orEmpty(),
                finalUrl = response.request.url.toString()
            )
        }
    }

    /**
     * Адрес панели из финального URL после редиректов: отрезает
     * `/api/security/status`. Так HTTP-редирект на HTTPS сохраняется
     * в профиль уже как https-адрес.
     */
    private fun baseUrlOf(finalUrl: String): String? {
        val cut = finalUrl.indexOf("/api/")
        val base = if (cut > 0) finalUrl.substring(0, cut) else finalUrl
        return PanelUrl.normalize(base)
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Таймаут подключения"
        is UnknownHostException -> "Сервер не найден (DNS)"
        is ConnectException -> "Нет соединения с сервером"
        is SSLException -> "Ошибка сертификата — включите «самоподписанный сертификат»"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Ошибка сети"
    }

    private companion object {
        const val UA = "ServpanelMobile/1.0 (Android)"

        /** Сколько не дёргать порт метрик после сетевой ошибки. */
        const val METRICS_COOLDOWN_MS = 5 * 60 * 1000L

        /** Ключ — `id сервера:порт метрик`; проверки идут параллельно. */
        val metricsCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}

/** Быстрый разбор массива клиентов без полной модели — для сводки. */
internal fun JSONArray.activeClients(): Int {
    var count = 0
    for (i in 0 until length()) {
        val code = optJSONObject(i)?.optString("status_code").orEmpty()
        if (code == "active" || code == "recent") count++
    }
    return count
}

/** Пустой объект-заглушка, чтобы не ловить NPE при отсутствии секции. */
internal fun JSONObject.section(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
