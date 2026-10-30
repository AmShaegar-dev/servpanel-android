package ru.servpanel.mobile.net

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/**
 * Проверка ключа SSH-сервера по отпечатку.
 *
 * Отпечаток считается так же, как `ssh-keygen -lf`: SHA256 в base64 без
 * выравнивающих `=`. Это позволяет сверить его вручную с выводом команды
 * на сервере.
 */
object SshHostKey {

    fun sha256(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Запоминает ключ хоста, чтобы показать отпечаток пользователю. */
    class Capture : HostKeyRepository {
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
    class Pinned(private val fingerprint: String) : HostKeyRepository {
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
}
