package ru.servpanel.mobile.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.servpanel.mobile.data.ServerProfile
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Сгенерированная на телефоне пара ключей. */
data class GeneratedKey(
    /** Приватный ключ в PEM; на сервер не отправляется. */
    val privateKey: String,
    /** Строка `ssh-rsa AAAA… servpanel-android` для authorized_keys. */
    val publicKey: String
)

/** Результат проверки SSH-доступа из редактора. */
data class SshProbeResult(
    val fingerprint: String,
    val panelReachable: Boolean,
    val message: String,
    val latencyMs: Long
)

/**
 * Настройка доступа к панели по SSH.
 *
 * Ключ генерируется на телефоне, на сервер уходит только публичная часть.
 * Пользователь для приложения не имеет шелла и может открыть туннель только
 * к порту панели (`permitopen`).
 */
object SshAccess {

    const val DEFAULT_USER = "servpanel-app"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val COMMENT = "servpanel-android"

    /** RSA-3072: поддерживается любым OpenSSH, в отличие от Ed25519 на старых Android. */
    fun generateKey(): GeneratedKey {
        val jsch = JSch()
        val pair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        val privateOut = ByteArrayOutputStream()
        pair.writePrivateKey(privateOut)
        val publicOut = ByteArrayOutputStream()
        pair.writePublicKey(publicOut, COMMENT)
        pair.dispose()
        return GeneratedKey(
            privateKey = privateOut.toString("UTF-8"),
            publicKey = publicOut.toString("UTF-8").trim()
        )
    }

    /** Публичная часть уже существующего ключа (для ручной настройки). */
    fun publicKeyOf(privateKey: String, passphrase: String): String? = runCatching {
        val jsch = JSch()
        val pair = KeyPair.load(
            jsch,
            privateKey.toByteArray(Charsets.UTF_8),
            passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
        )
        val out = ByteArrayOutputStream()
        pair.writePublicKey(out, COMMENT)
        pair.dispose()
        out.toString("UTF-8").trim()
    }.getOrNull()

    /** Строка для authorized_keys с ограничениями. */
    fun authorizedKeyLine(publicKey: String, remotePort: Int): String {
        val options = listOf(
            "permitopen=\"127.0.0.1:$remotePort\"",
            "no-pty",
            "no-agent-forwarding",
            "no-X11-forwarding",
            "no-user-rc"
        ).joinToString(",")
        return "$options $publicKey"
    }

    /** Короткий отпечаток для показа пользователю. */
    fun shortFingerprint(fingerprint: String): String =
        fingerprint.take(16) + "…" + fingerprint.takeLast(4)

    /**
     * Подключается по ключу, при необходимости запоминает отпечаток и
     * проверяет, что через туннель открывается панель.
     */
    suspend fun probe(server: ServerProfile): SshProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val capture = SshHostKey.Capture()
        val repository = if (server.sshHostKey.isNotBlank()) {
            SshHostKey.Pinned(server.sshHostKey)
        } else {
            capture
        }
        val session = connect(server, repository)
        try {
            val fingerprint = server.sshHostKey.ifBlank {
                val key = capture.key
                    ?: throw PanelApiException(0, "Сервер не прислал ключ хоста")
                SshHostKey.sha256(key)
            }

            val localPort = freePort()
            try {
                session.setPortForwardingL("127.0.0.1", localPort, "127.0.0.1", server.remotePanelPort)
            } catch (e: Exception) {
                throw PanelApiException(
                    0,
                    "Ключ принят, но сервер запретил проброс портов. " +
                        "Включите AllowTcpForwarding yes в sshd_config."
                )
            }

            val reachable = panelReachable(localPort)
            SshProbeResult(
                fingerprint = fingerprint,
                panelReachable = reachable,
                message = if (reachable) {
                    "SSH работает, панель отвечает через туннель"
                } else {
                    "SSH работает, но панель на 127.0.0.1:${server.remotePanelPort} не отвечает"
                },
                latencyMs = System.currentTimeMillis() - started
            )
        } finally {
            runCatching { session.disconnect() }
        }
    }

    /** Проверяет, что на сервере есть пользователь и ключ (для повторной настройки). */
    fun provisionScript(
        user: String,
        authorizedKey: String,
        remotePort: Int,
        bindLoopback: Boolean,
        publicPort: Int,
        rollback: Boolean = false
    ): String {
        val home = if (user == "root") "/root" else "/home/$user"
        val quotedKey = shellQuote(authorizedKey)
        val dir = listOf(
            "set -e",
            "PANEL_DIR=/root/servpanel",
            "cd \"\$PANEL_DIR\"",
            if (user == "root") {
                "true"
            } else {
                "id -u $user >/dev/null 2>&1 || useradd -r -m -s /usr/sbin/nologin $user"
            },
            "install -d -m 700 -o $user -g $user $home/.ssh",
            "touch $home/.ssh/authorized_keys",
            "chown $user:$user $home/.ssh/authorized_keys",
            "chmod 600 $home/.ssh/authorized_keys",
            "grep -qF ${shellQuote(keyBlob(authorizedKey))} $home/.ssh/authorized_keys || echo $quotedKey >> $home/.ssh/authorized_keys",
            // Проброс портов должен быть разрешён на уровне sshd.
            forwardingFix(),
            if (bindLoopback && !rollback) {
                "PANEL_HOST=127.0.0.1 pm2 restart awg-admin --update-env >/dev/null 2>&1 || true"
            } else if (rollback) {
                "PANEL_HOST=0.0.0.0 pm2 restart awg-admin --update-env >/dev/null 2>&1 || true"
            } else {
                "true"
            },
            "pm2 save >/dev/null 2>&1 || true",
            if (publicPort > 0 && publicPort != 443) {
                if (bindLoopback && !rollback) {
                    "ufw delete allow $publicPort/tcp >/dev/null 2>&1 || true"
                } else if (rollback) {
                    "ufw allow $publicPort/tcp >/dev/null 2>&1 || true"
                } else {
                    "true"
                }
            } else {
                "true"
            },
            "echo SERVpanel_ACCESS_OK"
        )
        return dir.joinToString("\n")
    }

    private fun keyBlob(authorizedKey: String): String =
        authorizedKey.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("AAAA") }
            ?: authorizedKey.substringAfterLast(' ')

    private fun forwardingFix(): String = listOf(
        // Часть серверов ставит только ed25519-ключ хоста, а JSch на Android
        // (без Java 15) его не понимает. ssh-keygen -A создаёт недостающие
        // типы (rsa/ecdsa), не трогая существующие.
        "if ! ls /etc/ssh/ssh_host_rsa_key.pub /etc/ssh/ssh_host_ecdsa_key.pub >/dev/null 2>&1; then " +
            "ssh-keygen -A >/dev/null 2>&1 || true; " +
            "(systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true); " +
            "fi",
        "if sshd -T 2>/dev/null | grep -qi '^allowtcpforwarding no'; then " +
            "mkdir -p /etc/ssh/sshd_config.d && " +
            "printf 'AllowTcpForwarding yes\\n' > /etc/ssh/sshd_config.d/99-servpanel-app.conf && " +
            "sshd -t && (systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true); " +
            "fi"
    ).joinToString("; ")

    // ------------------------------------------------------------------
    // JSch
    // ------------------------------------------------------------------

    private fun connect(server: ServerProfile, repository: com.jcraft.jsch.HostKeyRepository): Session {
        if (server.sshKey.isBlank()) {
            throw PanelApiException(0, "Вставьте приватный ключ (PEM или OpenSSH)")
        }
        val jsch = JSch()
        try {
            jsch.addIdentity(
                "probe-${server.id}",
                server.sshKey.toByteArray(Charsets.UTF_8),
                null,
                server.sshPassphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw PanelApiException(
                0,
                "Не удалось прочитать ключ. Нужен приватный ключ в формате PEM или OpenSSH."
            )
        }
        val host = server.sshHost.trim().ifBlank {
            throw PanelApiException(0, "Укажите SSH-хост")
        }
        val session = jsch.getSession(server.sshUser.ifBlank { DEFAULT_USER }, host, server.sshPort)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        session.hostKeyRepository = repository
        session.setServerAliveInterval(15_000)
        session.setServerAliveCountMax(4)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw PanelApiException(0, SshTunnels.describe(host, server.sshPort, e))
        }
        return session
    }

    private fun panelReachable(localPort: Int): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("http://127.0.0.1:$localPort/api/security/status")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.code == 200 || response.code == 401 || response.code == 403
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Свободный локальный порт для проверки туннеля. */
    fun freeLocalPort(): Int = freePort()

    /** Отвечает ли панель на локальном конце туннеля. */
    fun panelAnswers(localPort: Int): Boolean = panelReachable(localPort)

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
