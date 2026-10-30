package ru.servpanel.mobile.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.servpanel.mobile.data.ServerProfile
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH-туннель до панели, слушающей только `127.0.0.1`.
 *
 * На сервере панель привязана к loopback, поэтому снаружи её порт закрыт.
 * Приложение поднимает SSH-сессию отдельным пользователем (только проброс
 * портов, без шелла) и получает локальный порт, через который все запросы
 * идут в панель.
 *
 * Сессия одна на сервер и переиспользуется всеми запросами: SSH не добавляет
 * сетевой RTT, только единицы миллисекунд на шифрование. Через 5 минут
 * простоя туннель закрывается, чтобы не тратить батарею.
 */
object SshTunnels {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val KEEPALIVE_MS = 15_000
    private const val IDLE_CLOSE_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Entry(var port: Int) {
        val mutex = Mutex()
        var session: Session? = null
        var lastUsed: Long = 0L
        var closeJob: Job? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Локальный порт туннеля для профиля (выделяется один раз за процесс). */
    fun portFor(serverId: String): Int =
        entries.computeIfAbsent(serverId) { Entry(allocatePort()) }.port

    fun isConnected(serverId: String): Boolean =
        entries[serverId]?.session?.isConnected == true

    /**
     * Гарантирует живой туннель и возвращает локальный порт.
     *
     * Параллельные вызовы входят по очереди, поэтому при одновременном
     * обновлении карточек SSH-сессия открывается один раз.
     */
    suspend fun ensure(server: ServerProfile): Int = withContext(Dispatchers.IO) {
        val entry = entries.computeIfAbsent(server.id) { Entry(allocatePort()) }
        entry.mutex.withLock {
            val existing = entry.session
            if (existing != null && existing.isConnected) {
                touch(entry)
                return@withLock entry.port
            }
            if (existing != null) runCatching { existing.disconnect() }
            entry.session = null
            entry.session = open(server, entry)
            touch(entry)
            entry.port
        }
    }

    /** Закрыть туннель (например, после смены ключа или профиля). */
    fun invalidate(serverId: String) {
        val entry = entries.remove(serverId) ?: return
        entry.closeJob?.cancel()
        runCatching { entry.session?.disconnect() }
        entry.session = null
    }

    fun closeAll() {
        for (id in entries.keys.toList()) invalidate(id)
    }

    // ------------------------------------------------------------------
    // Подключение
    // ------------------------------------------------------------------

    private fun open(server: ServerProfile, entry: Entry): Session {
        val host = server.sshHost.trim().ifBlank {
            throw PanelApiException(0, "Укажите SSH-хост в настройках сервера")
        }
        if (server.sshKey.isBlank()) {
            throw PanelApiException(0, "В профиле нет SSH-ключа. Добавьте его в настройках сервера.")
        }
        if (server.sshHostKey.isBlank()) {
            throw PanelApiException(
                0,
                "Отпечаток SSH-сервера не подтверждён. Откройте настройки сервера " +
                    "и нажмите «Проверить SSH»."
            )
        }

        val jsch = JSch()
        val identity = "servpanel-${server.id}"
        try {
            val key = server.sshKey.toByteArray(Charsets.UTF_8)
            val passphrase = server.sshPassphrase
                .takeIf { it.isNotBlank() }
                ?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity(identity, key, null, passphrase)
        } catch (e: Exception) {
            throw PanelApiException(
                0,
                "Не удалось прочитать приватный ключ. Нужен ключ в формате PEM или OpenSSH."
            )
        }

        val session = jsch.getSession(server.sshUser, host, server.sshPort)
        // Отпечаток проверяет SshHostKey.Pinned: при подмене JSch не пустит.
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        session.hostKeyRepository = SshHostKey.Pinned(server.sshHostKey)
        session.setServerAliveInterval(KEEPALIVE_MS)
        session.setServerAliveCountMax(4)

        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw PanelApiException(0, describe(host, server.sshPort, e))
        }

        try {
            session.setPortForwardingL("127.0.0.1", entry.port, "127.0.0.1", server.remotePanelPort)
        } catch (e: Exception) {
            // Порт мог занять другой процесс — пробуем ещё раз на свободном.
            runCatching { session.disconnect() }
            val retryPort = allocatePort()
            entry.port = retryPort
            val retry = jsch.getSession(server.sshUser, host, server.sshPort)
            retry.setConfig("StrictHostKeyChecking", "no")
            retry.setConfig("PreferredAuthentications", "publickey")
            retry.hostKeyRepository = SshHostKey.Pinned(server.sshHostKey)
            retry.setServerAliveInterval(KEEPALIVE_MS)
            retry.setServerAliveCountMax(4)
            try {
                retry.connect(CONNECT_TIMEOUT_MS)
                retry.setPortForwardingL(
                    "127.0.0.1", entry.port, "127.0.0.1", server.remotePanelPort
                )
                return retry
            } catch (e2: Exception) {
                runCatching { retry.disconnect() }
                throw PanelApiException(0, describe(host, server.sshPort, e2))
            }
        }
        return session
    }

    private fun touch(entry: Entry) {
        entry.lastUsed = System.currentTimeMillis()
        entry.closeJob?.cancel()
        entry.closeJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            if (System.currentTimeMillis() - entry.lastUsed >= IDLE_CLOSE_MS) {
                runCatching { entry.session?.disconnect() }
                entry.session = null
            }
        }
    }

    private fun allocatePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun describe(host: String, port: Int, error: Throwable): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("Algorithm negotiation fail", ignoreCase = true) ->
                "Сервер предлагает только ed25519-ключ хоста, который приложение " +
                    "не поддерживает. Выполните на сервере: ssh-keygen -A && " +
                    "systemctl reload ssh — появится RSA/ECDSA-ключ."

            text.contains("HostKey has been changed", ignoreCase = true) ->
                "Отпечаток ключа SSH-сервера изменился. Проверьте отпечаток заново " +
                    "в настройках сервера."

            text.contains("Auth fail", ignoreCase = true) ||
                text.contains("auth cancel", ignoreCase = true) ->
                "SSH не принял ключ. Проверьте пользователя и ключ в настройках сервера."

            text.contains("invalid privatekey", ignoreCase = true) ||
                text.contains("invalid key", ignoreCase = true) ->
                "Не удалось прочитать приватный ключ: нужен ключ в формате PEM или OpenSSH."

            text.contains("administratively prohibited", ignoreCase = true) ||
                text.contains("forwarding failed", ignoreCase = true) ->
                "Сервер запретил проброс портов (AllowTcpForwarding no в sshd_config)."

            text.contains("Connection refused", ignoreCase = true) ->
                "SSH-порт $port закрыт или SSH не запущен на $host."

            text.contains("timeout", ignoreCase = true) ->
                "SSH-сервер $host не ответил за ${CONNECT_TIMEOUT_MS / 1000} секунд."

            text.contains("UnknownHost", ignoreCase = true) ->
                "Не удалось найти хост $host."

            else -> text.ifBlank { "Не удалось открыть SSH-туннель до $host" }
        }
    }
}
