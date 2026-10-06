package io.github.xsun71136.plugins.mcp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** One line in the Output & Debugging console. */
data class LogEntry(
    val seq: Long,
    val time: Long,
    val level: String,
    val tag: String,
    val message: String,
) {
    fun format(): String = "${TF.format(Date(time))} ${level.padEnd(5)} [$tag] $message"

    private companion object {
        val TF = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Bounded in-memory log shared by the MCP server, the tool layer and the
 * "输出与调试" console. Also mirrored to logcat under the ACS-MCP tag so the
 * messages show up in the IDE's own log output.
 */
object McpLog {

    const val TRACE = "TRACE"
    const val DEBUG = "DEBUG"
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"

    private const val ANDROID_TAG = "ACS-MCP"

    private val levels = listOf(TRACE, DEBUG, INFO, WARN, ERROR)

    @Volatile
    var minLevel: String = INFO

    private val entries = ArrayDeque<LogEntry>()
    private val seq = AtomicLong(0)
    private var limit = 500
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private val tf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun setLimit(n: Int) {
        limit = n.coerceIn(50, 20000)
        trim()
    }

    fun addListener(l: (LogEntry) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (LogEntry) -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun tail(n: Int, level: String? = null): List<LogEntry> {
        val src = if (level == null) entries.toList() else entries.filter { rank(it.level) >= rank(level) }
        return if (src.size <= n) src else src.subList(src.size - n, src.size)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    fun dumpText(n: Int = 200, level: String? = null): String =
        tail(n, level).joinToString("\n") { it.format() }

    fun log(level: String, tag: String, message: String, error: Throwable? = null) {
        if (rank(level) < rank(minLevel)) return
        val full = if (error == null) message else "$message\n${error.javaClass.name}: ${error.message}"
        val entry = LogEntry(seq.incrementAndGet(), System.currentTimeMillis(), level, tag, full)
        synchronized(this) {
            entries.addLast(entry)
            trim()
        }
        mirrorToLogcat(level, tag, full, error)
        for (l in listeners) {
            try {
                l(entry)
            } catch (_: Throwable) {
            }
        }
    }

    fun trace(tag: String, msg: String) = log(TRACE, tag, msg)
    fun debug(tag: String, msg: String) = log(DEBUG, tag, msg)
    fun info(tag: String, msg: String) = log(INFO, tag, msg)
    fun warn(tag: String, msg: String) = log(WARN, tag, msg)
    fun error(tag: String, msg: String, t: Throwable? = null) = log(ERROR, tag, msg, t)

    /** Compact single-line rendering used by the console list. */
    fun stamp(time: Long): String = tf.format(Date(time))

    private fun trim() {
        while (entries.size > limit) entries.removeFirst()
    }

    private fun rank(level: String): Int {
        val idx = levels.indexOf(level.uppercase(Locale.US))
        return if (idx < 0) levels.indexOf(INFO) else idx
    }

    private fun mirrorToLogcat(level: String, tag: String, msg: String, error: Throwable?) {
        try {
            val text = "[$tag] $msg"
            when (level) {
                ERROR -> android.util.Log.e(ANDROID_TAG, text, error)
                WARN -> android.util.Log.w(ANDROID_TAG, text, error)
                DEBUG -> android.util.Log.d(ANDROID_TAG, text)
                TRACE -> android.util.Log.v(ANDROID_TAG, text)
                else -> android.util.Log.i(ANDROID_TAG, text)
            }
        } catch (_: Throwable) {
            // logcat is best effort only
        }
    }
}
