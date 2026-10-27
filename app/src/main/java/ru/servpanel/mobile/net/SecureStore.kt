package ru.servpanel.mobile.net

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрованное хранилище небольших секретов (cookie панели).
 *
 * Ключ AES-256 живёт в Android Keystore и не покидает устройство, поэтому
 * вытащить cookie сессии из резервной копии SharedPreferences или через
 * root-доступ к файлу настроек уже не получится. Формат значения:
 * `v1:<base64(iv + ciphertext)>`.
 *
 * Старые незашифрованные значения читаются и сразу перезаписываются в
 * зашифрованном виде, поэтому обновление проходит без повторного входа.
 */
class SecureStore(context: Context, prefsName: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(PREFIX)) {
            // Значение от старой версии приложения — мигрируем.
            runCatching { putString(key, raw) }
            return raw
        }
        return runCatching { decrypt(raw.removePrefix(PREFIX)) }
            .getOrElse {
                // Ключ в Keystore сброшен (сброс настроек, смена блокировки).
                // Битое значение удаляем: пользователь просто войдёт заново.
                prefs.edit().remove(key).apply()
                null
            }
    }

    fun putString(key: String, value: String) {
        val encoded = runCatching { encrypt(value) }.getOrNull() ?: return
        prefs.edit().putString(key, PREFIX + encoded).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, out, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, out, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Слишком короткое значение" }
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val data = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "servpanel_cookie_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val PREFIX = "v1:"
    }
}
