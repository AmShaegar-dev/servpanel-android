package ru.servpanel.mobile.net

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Base64

/** Параметры SSH-подключения к серверу, на который ставим панель. */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val password: String
)

/** Что удалось узнать о сервере до установки. */
data class HostInfo(
    /** Отпечаток ключа хоста в формате OpenSSH: SHA256:… */
    val fingerprint: String,
    val osName: String,
    val arch: String,
    val isRoot: Boolean
)

/** Задание на установку панели. */
data class InstallRequest(
    val target: SshTarget,
    /** Отпечаток, подтверждённый пользователем на шаге проверки. */
    val fingerprint: String,
    /** 1 — доступ по IP, 2 — по домену с Let's Encrypt. */
    val accessType: Int,
    val domain: String,
    val panelPort: Int,
    val adminPassword: String,
    /** После установки закрыть панель на loopback и оставить только SSH-туннель. */
    val sshOnly: Boolean = false,
    /** Приватный ключ, сгенерированный на телефоне (в память, не на сервер). */
    val sshKey: String = "",
    /** Публичная часть того же ключа — её и ставим в authorized_keys. */
    val sshPublicKey: String = "",
    val sshUser: String = SshAccess.DEFAULT_USER
)

sealed interface InstallEvent {
    data class Stage(val text: String, val progress: Float) : InstallEvent
    data class Log(val line: String) : InstallEvent
    data class Done(val panelUrl: String, val sshReady: Boolean = false) : InstallEvent
}

/**
 * Установка панели Servpanel на сервер по SSH.
 *
 * Схема: сначала [probe] — подключаемся, запоминаем отпечаток ключа хоста и
 * показываем его пользователю. Потом [install] — подключаемся уже с
 * закреплённым отпечатком и стримим журнал установки.
 */
class SshInstaller {

    // ------------------------------------------------------------------
    // Проверка сервера
    // ------------------------------------------------------------------

    suspend fun probe(target: SshTarget): HostInfo = withContext(Dispatchers.IO) {
        val capture = CapturingHostKeys()
        val session = connect(target, capture)
        try {
            val key = capture.key
                ?: throw IllegalStateException("Сервер не прислал ключ хоста")

            val osName = runCommand(
                session,
                "sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '\"' | head -1"
            ).trim()
            val arch = runCommand(session, "uname -m").trim()
            val uid = runCommand(session, "id -u").trim()

            HostInfo(
                fingerprint = sha256(key),
                osName = osName.ifBlank { "неизвестна" },
                arch = arch.ifBlank { "—" },
                isRoot = uid == "0"
            )
        } finally {
            session.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Установка
    // ------------------------------------------------------------------

    fun install(request: InstallRequest): Flow<InstallEvent> = flow {
        emit(InstallEvent.Stage("Подключаемся к ${request.target.host}…", 0.03f))
        val session = connect(request.target, PinnedHostKeys(request.fingerprint))
        try {
            emit(InstallEvent.Log("SSH-соединение установлено, отпечаток сервера совпал."))

            emit(InstallEvent.Stage("Проверяем систему…", 0.10f))
            val uid = runCommand(session, "id -u").trim()
            if (uid != "0") {
                throw IllegalStateException(
                    "Нужен пользователь root: установщик ставит пакеты, nginx и systemd-службы."
                )
            }

            val osName = runCommand(
                session,
                "sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null | tr -d '\"'"
            ).trim()
            emit(InstallEvent.Log("Система: ${osName.ifBlank { "неизвестна" }}"))

            val hasApt = runCommand(
                session,
                "command -v apt-get >/dev/null 2>&1 && echo yes || echo no"
            ).contains("yes")
            if (!hasApt) {
                throw IllegalStateException(
                    "Поддерживаются Debian и Ubuntu: на сервере нет apt-get."
                )
            }

            emit(InstallEvent.Stage("Готовим зависимости…", 0.18f))
            withTimeout(INSTALL_TIMEOUT_MS) {
                execStreaming(
                    session,
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "command -v curl >/dev/null 2>&1 || { " +
                        "apt-get update -qq && apt-get install -y -qq curl ca-certificates; }"
                ) { line -> emit(InstallEvent.Log(line)) }

                emit(InstallEvent.Stage("Скачиваем установщик…", 0.25f))
                val downloadCode = execStreaming(
                    session,
                    "curl -fsSL --retry 3 --connect-timeout 20 " +
                        "-o /tmp/servpanel-install.sh $INSTALL_SCRIPT_URL && echo 'скрипт загружен'"
                ) { line -> emit(InstallEvent.Log(line)) }
                if (downloadCode != 0) {
                    throw IllegalStateException(
                        "Не удалось скачать установщик (код $downloadCode). " +
                            "Проверьте, что серверу доступен интернет и GitHub."
                    )
                }

                emit(InstallEvent.Stage("Устанавливаем панель. Это займёт 2–5 минут…", 0.35f))
                var panelUrl: String? = null
                val command = buildString {
                    append("export DEBIAN_FRONTEND=noninteractive; ")
                    append("ACCESS_TYPE=").append(shellQuote(request.accessType.toString())).append(' ')
                    if (request.domain.isNotBlank()) {
                        append("DOMAIN_NAME=").append(shellQuote(request.domain)).append(' ')
                    }
                    append("PANEL_PORT=").append(shellQuote(request.panelPort.toString())).append(' ')
                    append("ADMIN_PASS=").append(shellQuote(request.adminPassword)).append(' ')
                    append("bash /tmp/servpanel-install.sh")
                }
                val installCode = execStreaming(session, command) { line ->
                    emit(InstallEvent.Log(line))
                    panelUrlOf(line)?.let { panelUrl = it }
                }
                if (installCode != 0) {
                    throw IllegalStateException(
                        "Установщик завершился с кодом $installCode. " +
                            "Подробности — в журнале выше."
                    )
                }

                var sshReady = false
                if (request.sshOnly) {
                    emit(InstallEvent.Stage("Настраиваем доступ по SSH…", 0.9f))
                    sshReady = provisionSshAccess(session, request) { line ->
                        emit(InstallEvent.Log(line))
                    }
                }

                emit(InstallEvent.Stage("Готово", 1f))
                emit(InstallEvent.Done(panelUrl ?: expectedUrl(request), sshReady))
            }
        } finally {
            session.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Доступ по SSH после установки
    // ------------------------------------------------------------------

    /**
     * Создаёт отдельного пользователя с ключом, переводит панель на loopback
     * и закрывает внешний порт. Проверяет туннель этой же сессией; при сбое
     * возвращает панель наружу, чтобы не потерять доступ.
     */
    private suspend fun provisionSshAccess(
        session: Session,
        request: InstallRequest,
        log: suspend (String) -> Unit
    ): Boolean {
        if (request.sshPublicKey.isBlank()) {
            throw IllegalStateException("Не сгенерирован SSH-ключ для доступа")
        }
        val internal = if (request.accessType == 2) 3000 else request.panelPort
        val publicPort = if (request.accessType == 2) 443 else request.panelPort
        val line = SshAccess.authorizedKeyLine(request.sshPublicKey, internal)

        log("Создаём пользователя ${request.sshUser} (без шелла) и ставим ключ…")
        val script = SshAccess.provisionScript(
            user = request.sshUser,
            authorizedKey = line,
            remotePort = internal,
            bindLoopback = true,
            publicPort = publicPort
        )
        val out = runCommand(session, "bash -s <<'SERVpanel_EOF'\n$script\nSERVpanel_EOF")
        if (!out.contains("SERVpanel_ACCESS_OK")) {
            log(out.trim().takeLast(400))
            throw IllegalStateException(
                "Не удалось настроить SSH-доступ. Панель оставлена доступной как раньше."
            )
        }

        log("Проверяем панель через туннель…")
        val port = SshAccess.freeLocalPort()
        session.setPortForwardingL("127.0.0.1", port, "127.0.0.1", internal)
        if (!SshAccess.panelAnswers(port)) {
            log("Туннель не открылся — возвращаем панель наружу.")
            val rollback = SshAccess.provisionScript(
                user = request.sshUser,
                authorizedKey = line,
                remotePort = internal,
                bindLoopback = false,
                publicPort = publicPort,
                rollback = true
            )
            runCommand(session, "bash -s <<'SERVpanel_EOF'\n$rollback\nSERVpanel_EOF")
            throw IllegalStateException(
                "Панель не ответила через SSH-туннель, поэтому внешний доступ оставлен."
            )
        }
        log("Готово: панель слушает только 127.0.0.1:$internal, порт $publicPort закрыт.")
        return true
    }

    // ------------------------------------------------------------------
    // SSH
    // ------------------------------------------------------------------

    private fun connect(target: SshTarget, repository: HostKeyRepository): Session {
        val jsch = JSch()
        val session = jsch.getSession(target.user, target.host, target.port)
        session.setPassword(target.password)
        // Отпечаток проверяем сами: PinnedHostKeys вернёт CHANGED при подмене,
        // а CHANGED JSch не пропускает никогда.
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.hostKeyRepository = repository
        session.setServerAliveInterval(20_000)
        session.setServerAliveCountMax(6)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            throw IllegalStateException(describe(e, target), e)
        }
        return session
    }

    private fun describe(e: Exception, target: SshTarget): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("Algorithm negotiation fail", ignoreCase = true) ->
                "Сервер предлагает только ed25519-ключ хоста, который установщик " +
                    "не поддерживает. Выполните: ssh-keygen -A && systemctl reload ssh"

            text.contains("HostKey has been changed", ignoreCase = true) ->
                "Отпечаток ключа сервера изменился. Возможно, это другой сервер " +
                    "или ключ перевыпущен. Проверьте отпечаток заново."

            text.contains("Auth fail", ignoreCase = true) ||
                text.contains("auth cancel", ignoreCase = true) ->
                "Неверный пароль или вход по паролю запрещён " +
                    "(в sshd_config стоит PasswordAuthentication no)."

            text.contains("Connection refused", ignoreCase = true) ->
                "Порт ${target.port} закрыт или SSH не запущен на сервере."

            text.contains("timeout", ignoreCase = true) ->
                "Сервер ${target.host} не ответил за ${CONNECT_TIMEOUT_MS / 1000} секунд."

            text.contains("UnknownHost", ignoreCase = true) ->
                "Не удалось найти сервер ${target.host}."

            else -> text.ifBlank { "Не удалось подключиться по SSH" }
        }
    }

    /** Выполняет команду и возвращает весь вывод (stdout + stderr). */
    private fun runCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val err = ByteArrayOutputStream()
        channel.setErrStream(err)
        val input = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        val out = input.bufferedReader(Charsets.UTF_8).readText()
        while (!channel.isClosed) Thread.sleep(40)
        channel.disconnect()
        return out + err.toString("UTF-8")
    }

    /**
     * Выполняет команду, отдавая каждую строку вывода в [onLine].
     * PTY включает построчную буферизацию и сливает stderr в stdout.
     */
    private suspend fun execStreaming(
        session: Session,
        command: String,
        onLine: suspend (String) -> Unit
    ): Int = coroutineScope {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setPty(true)
        channel.setInputStream(null)
        val input = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)

        val lines = Channel<String>(Channel.UNLIMITED)
        val reader = launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        lines.send(line)
                    }
                }
            } finally {
                lines.close()
            }
        }

        for (line in lines) {
            val clean = cleanLine(line)
            if (clean.isNotBlank()) onLine(clean)
        }
        reader.join()
        while (!channel.isClosed) delay(60)
        val status = channel.exitStatus
        channel.disconnect()
        status
    }

    // ------------------------------------------------------------------
    // Ключи хоста
    // ------------------------------------------------------------------

    /** Запоминает ключ хоста, чтобы посчитать отпечаток. */
    private class CapturingHostKeys : HostKeyRepository {
        @Volatile
        var key: ByteArray? = null

        override fun check(host: String?, key: ByteArray?): Int {
            this.key = key
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "servpanel-capture"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Пропускает только заранее подтверждённый отпечаток. */
    private class PinnedHostKeys(private val fingerprint: String) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.CHANGED
            return if (sha256(key) == fingerprint) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "servpanel-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val INSTALL_TIMEOUT_MS = 20 * 60 * 1000L
        private const val INSTALL_SCRIPT_URL =
            "https://raw.githubusercontent.com/AmShaegar-dev/servpanel/main/install.sh"

        /** Отпечаток в формате, который показывает `ssh-keygen -lf`. */
        fun sha256(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        fun expectedUrl(request: InstallRequest): String {
            val host = if (request.accessType == 2) request.domain else request.target.host
            val scheme = if (request.accessType == 2) "https" else "http"
            val defaultPort = if (scheme == "https") 443 else 80
            return if (request.panelPort == defaultPort) {
                "$scheme://$host"
            } else {
                "$scheme://$host:${request.panelPort}"
            }
        }

        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        private fun cleanLine(line: String): String =
            line.replace(ANSI, "").replace("\r", "").trimEnd()

        /** Достаёт адрес панели из строки установщика «Адрес: …». */
        private fun panelUrlOf(line: String): String? {
            val clean = cleanLine(line)
            if (!clean.contains("Адрес", ignoreCase = true)) return null
            return clean.split(Regex("\\s+"))
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                ?.trimEnd('.', ',', ';')
        }

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
