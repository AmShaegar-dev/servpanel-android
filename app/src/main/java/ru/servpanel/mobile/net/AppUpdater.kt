package ru.servpanel.mobile.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.servpanel.mobile.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/** Последний релиз приложения на GitHub. */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val pageUrl: String,
    val notes: String,
    val sizeBytes: Long,
    val publishedAt: String
)

/**
 * Проверка и установка обновлений приложения.
 *
 * APK скачивается с GitHub в кэш и открывается системным установщиком:
 * подпись релиза совпадает с установленной, поэтому обновление ставится
 * поверх без потери серверов.
 */
class AppUpdater(private val context: Context) {

    private val client: OkHttpClient = HttpClients.standard.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Последний опубликованный релиз (без черновиков и пререлизов). */
    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ServpanelMobile/${BuildConfig.VERSION_NAME} (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PanelApiException(response.code, "GitHub ответил ${response.code}")
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim().removePrefix("v")
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            val apk = list.firstOrNull {
                it.optString("name").equals("servpanel-mobile.apk", ignoreCase = true)
            } ?: list.firstOrNull {
                it.optString("name").endsWith(".apk", ignoreCase = true)
            } ?: throw PanelApiException(0, "В релизе нет APK")

            AppRelease(
                versionName = tag.ifBlank { json.optString("name").removePrefix("v") },
                versionCode = versionCodeOf(tag),
                apkUrl = apk.optString("browser_download_url"),
                pageUrl = json.optString("html_url"),
                notes = json.optString("body").trim(),
                sizeBytes = apk.optLong("size"),
                publishedAt = json.optString("published_at")
            )
        }
    }

    /** Скачивает APK в кэш и возвращает файл. */
    suspend fun download(
        release: AppRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "servpanel-${release.versionName}.apk")
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "ServpanelMobile/${BuildConfig.VERSION_NAME} (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PanelApiException(response.code, "Скачивание: ${response.code}")
            }
            val body = response.body ?: throw PanelApiException(0, "Пустой ответ GitHub")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var reported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - reported >= 256 * 1024) {
                            reported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        }
        target
    }

    /** Разрешена ли установка APK из этого приложения. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Системные настройки «Установка неизвестных приложений». */
    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${BuildConfig.APPLICATION_ID}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Открывает системный установщик для скачанного APK. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.files",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** v1.4.2 → 10402 (та же схема, что в build.gradle.kts). */
    private fun versionCodeOf(name: String): Int {
        val parts = name.trim().removePrefix("v").split('.', '-', '+')
        val numbers = parts.mapNotNull { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull()
        }
        if (numbers.isEmpty()) return 0
        val major = numbers.getOrElse(0) { 0 }
        val minor = numbers.getOrElse(1) { 0 }
        val patch = numbers.getOrElse(2) { 0 }
        return major * 10_000 + minor * 100 + patch
    }
}
