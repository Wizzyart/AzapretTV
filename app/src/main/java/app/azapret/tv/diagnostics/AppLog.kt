package app.azapret.tv.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE_NAME = "azapret-tv-events.log"
    private const val MAX_BYTES = 96 * 1024
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun append(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(tail)
            }
            file.appendText("${dateFormat.format(Date())} $message\n")
        }
    }

    fun readTail(context: Context, maxLines: Int = 80): String {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return "Журнал пока пуст"
            file.readLines().takeLast(maxLines).joinToString("\n")
        }.getOrElse { "Ошибка чтения журнала: ${it.message ?: "без сообщения"}" }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
