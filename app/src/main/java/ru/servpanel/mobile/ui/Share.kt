package ru.servpanel.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Копирование в буфер обмена с коротким подтверждением. */
fun copyToClipboard(context: Context, text: String, label: String = "Скопировано") {
    try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Servpanel", text))
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось скопировать", Toast.LENGTH_SHORT).show()
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show()
    }
}

/** Сохраняет файл в кэш и открывает системный «Поделиться». */
fun shareFile(context: Context, fileName: String, content: String, mime: String = "text/plain") {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить файл"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось поделиться файлом", Toast.LENGTH_SHORT).show()
    }
}

fun shareText(context: Context, text: String, subject: String = "Servpanel") {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось поделиться", Toast.LENGTH_SHORT).show()
    }
}

/** Сохраняет бинарные данные в кэш и открывает системный «Поделиться». */
fun shareBytes(context: Context, fileName: String, bytes: ByteArray, mime: String = "application/octet-stream") {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить файл"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось поделиться файлом", Toast.LENGTH_SHORT).show()
    }
}

/** data:image/png;base64,... → Bitmap для QR-кода. */
fun decodeDataUrl(dataUrl: String?): Bitmap? {
    if (dataUrl.isNullOrBlank()) return null
    val marker = "base64,"
    val index = dataUrl.indexOf(marker)
    val payload = if (index >= 0) dataUrl.substring(index + marker.length) else dataUrl
    return try {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}
