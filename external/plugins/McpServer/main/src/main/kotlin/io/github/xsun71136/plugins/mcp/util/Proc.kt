package io.github.xsun71136.plugins.mcp.util

import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one command execution, always bounded so a huge build log cannot OOM the IDE. */
data class ProcResult(
    val command: List<String>,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
    val truncated: Boolean,
    val cwd: String? = null,
    val mode: String = "ide",
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    fun toMap(maxChars: Int = 24000): Map<String, Any?> = Json.obj(
        "ok" to ok,
        "mode" to mode,
        "command" to command,
        "cwd" to cwd,
        "exitCode" to exitCode,
        "timedOut" to timedOut,
        "durationMs" to durationMs,
        "truncated" to truncated,
        "stdout" to clamp(stdout, maxChars),
        "stderr" to clamp(stderr, maxChars),
        "stdoutTail" to tail(stdout, 120),
        "stderrTail" to tail(stderr, 60),
    )

    fun text(maxChars: Int = 24000): String {
        val sb = StringBuilder()
        sb.append("$ ").append(shellQuote(command)).append('\n')
        if (cwd != null) sb.append("# cwd: ").append(cwd).append('\n')
        sb.append("# exit=").append(exitCode?.toString() ?: "n/a")
            .append(" time=").append(durationMs).append("ms")
        if (timedOut) sb.append(" TIMED_OUT")
        if (truncated) sb.append(" OUTPUT_TRUNCATED")
        sb.append('\n')
        if (stdout.isNotEmpty()) sb.append("---- stdout ----\n").append(clamp(stdout, maxChars)).append('\n')
        if (stderr.isNotEmpty()) sb.append("---- stderr ----\n").append(clamp(stderr, maxChars)).append('\n')
        return sb.toString()
    }

    companion object {
        fun clamp(s: String, max: Int): String =
            if (s.length <= max) s else s.substring(0, max) + "\n... [truncated ${s.length - max} chars]"

        fun tail(s: String, lines: Int): String {
            if (s.isEmpty()) return ""
            val parts = s.split('\n')
            return if (parts.size <= lines) s else parts.takeLast(lines).joinToString("\n")
        }

        fun shellQuote(parts: List<String>): String =
            parts.joinToString(" ") { if (it.contains(' ') || it.contains('"')) "\"" + it.replace("\"", "\\\"") + "\"" else it }
    }
}

/**
 * Command execution for MCP tools.
 *
 * Two distinct execution domains exist inside Android Code Studio:
 *
 *  - [ide]  : inside the IDE's proot/acsenv environment, via PluginApi.process.
 *             This is where the JDK, Gradle, the Android SDK and the terminal
 *             packages live, so all build work goes through here.
 *  - [host] : the Android application process itself, via Runtime.exec.
 *             Used for pm/am/logcat/getprop style commands. These need shell or
 *             Shizuku privileges; when they fail the tool result says so instead
 *             of pretending to work.
 */
object Exec {

    private const val TAG = "exec"
    private const val MAX_CAPTURE = 512 * 1024

    // ------------------------------------------------------------ ide (proot)

    fun ide(
        command: List<String>,
        cwd: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Int = 600,
        attachStorage: Boolean = true,
        attachSdk: Boolean = true,
        attachProjects: Boolean = true,
        viaShell: Boolean = true,
    ): ProcResult {
        val settings = McpSettingsStore.current
        val started = System.currentTimeMillis()
        val fullEnv = LinkedHashMap<String, String>()
        try {
            fullEnv.putAll(ideEnvironment())
        } catch (t: Throwable) {
            McpLog.warn(TAG, "environment unavailable: ${t.message}")
        }
        fullEnv.putAll(settings.extraEnv)
        fullEnv.putAll(extraEnv)

        val effective: List<String> = if (viaShell) {
            val script = buildString {
                if (cwd != null) {
                    append("cd ").append(q(cwd.absolutePath)).append(" || exit 127; ")
                }
                append(command.joinToString(" ") { q(it) })
            }
            listOf(settings.shellCommand, settings.shellArgs, script)
        } else command

        return runCatching {
            var launcher = processApi().builder()
            launcher = launcher.command(*effective.toTypedArray())
            if (attachStorage) launcher = launcher.attachStorage()
            if (attachSdk) launcher = launcher.attachAndroidSdk()
            if (attachProjects) {
                projectsDirs().forEach { dir ->
                    runCatching { if (dir.exists()) launcher.attachDir(dir, null) }
                }
            }
            if (cwd != null) runCatching { if (cwd.exists()) launcher.attachDir(cwd, null) }
            launcher = launcher.withEnv(fullEnv)
            val process = launcher.launch()
            collect(process, effective, timeoutSec, started, "ide", cwd?.absolutePath)
        }.getOrElse { t ->
            McpLog.error(TAG, "ide exec failed", t)
            ProcResult(
                command = effective,
                exitCode = null,
                stdout = "",
                stderr = "failed to launch in IDE environment: ${t.javaClass.simpleName}: ${t.message}\n" +
                    "hint: check settings.shellCommand (currently '${settings.shellCommand}') and that the IDE terminal environment is installed",
                timedOut = false,
                durationMs = System.currentTimeMillis() - started,
                truncated = false,
                cwd = cwd?.absolutePath,
                mode = "ide",
            )
        }
    }

    // --------------------------------------------------------------- host (android)

    fun host(
        command: List<String>,
        cwd: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Int = 120,
    ): ProcResult {
        val started = System.currentTimeMillis()
        val env = LinkedHashMap<String, String>()
        try {
            for ((k, v) in System.getenv()) if (k != null && v != null) env[k] = v
        } catch (_: Throwable) {
        }
        env.putAll(extraEnv)
        val envp = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        return try {
            val process = Runtime.getRuntime().exec(command.toTypedArray(), envp, cwd)
            collect(process, command, timeoutSec, started, "host", cwd?.absolutePath)
        } catch (t: Throwable) {
            ProcResult(
                command = command,
                exitCode = null,
                stdout = "",
                stderr = "host exec failed: ${t.javaClass.simpleName}: ${t.message}",
                timedOut = false,
                durationMs = System.currentTimeMillis() - started,
                truncated = false,
                cwd = cwd?.absolutePath,
                mode = "host",
            )
        }
    }

    /** Run a command through the Android host shell (settings.hostShell). */
    fun hostShell(script: String, cwd: File? = null, timeoutSec: Int = 120): ProcResult {
        val shell = McpSettingsStore.current.hostShell
        return host(listOf(shell, "-c", script), cwd, emptyMap(), timeoutSec)
    }

    /** Run an adb sub-command using settings.adbCommand (wireless / OTG adb). */
    fun adb(args: List<String>, timeoutSec: Int = 180): ProcResult {
        val adb = McpSettingsStore.current.adbCommand
        val parts = ArrayList<String>()
        parts.add(adb)
        parts.addAll(args)
        val direct = host(parts, null, emptyMap(), timeoutSec)
        if (direct.exitCode != null) return direct
        return hostShell(ProcResult.shellQuote(parts), null, timeoutSec)
    }

    // -------------------------------------------------------------- gradle

    /**
     * Resolve the Gradle launcher for a project: settings.gradleCommand "auto"
     * prefers the project wrapper, then a plain `gradle` on PATH.
     */
    fun gradleInvoker(projectDir: File): List<String> {
        val configured = McpSettingsStore.current.gradleCommand.trim()
        if (configured.isNotEmpty() && configured != "auto") return listOf(configured)
        val wrapper = File(projectDir, "gradlew")
        return if (wrapper.exists()) listOf("./gradlew") else listOf("gradle")
    }

    fun gradle(
        projectDir: File,
        tasks: List<String>,
        extraArgs: List<String> = emptyList(),
        timeoutSec: Int = 1800,
    ): ProcResult {
        val cmd = ArrayList<String>()
        cmd.addAll(gradleInvoker(projectDir))
        cmd.addAll(tasks)
        cmd.addAll(extraArgs)
        cmd.add("--console=plain")
        cmd.add("-Dfile.encoding=UTF-8")
        val jvmArgs = McpSettingsStore.current.gradleJvmArgs.trim()
        val env = LinkedHashMap<String, String>()
        if (jvmArgs.isNotEmpty()) env["GRADLE_OPTS"] = jvmArgs
        env["ANDROID_HOME"] = envPath("ANDROID_HOME")
        env["ANDROID_SDK_ROOT"] = envPath("ANDROID_SDK_ROOT")
        return ide(cmd, cwd = projectDir, extraEnv = env, timeoutSec = timeoutSec)
    }

    private fun envPath(key: String): String {
        try {
            val v = ideEnvironment()[key]
            if (!v.isNullOrEmpty()) return v
        } catch (_: Throwable) {
        }
        return System.getenv(key) ?: ""
    }

    // ------------------------------------------------------------- internals

    private fun processApi() = com.nullij.androidcodestudio.plugins.api.PluginApi.process

    fun ideEnvironment(): Map<String, String> = try {
        com.nullij.androidcodestudio.plugins.api.PluginApi.environment.getEnvironment(emptyMap())
    } catch (t: Throwable) {
        McpLog.warn(TAG, "getEnvironment failed: ${t.message}")
        emptyMap()
    }

    fun projectsDirs(): List<File> {
        val out = ArrayList<File>()
        try {
            val env = com.nullij.androidcodestudio.plugins.api.PluginApi.environment
            out.add(env.projectsDir)
            out.add(env.acsRootProjects)
        } catch (t: Throwable) {
            McpLog.warn(TAG, "projects dirs unavailable: ${t.message}")
        }
        return out
    }

    private fun collect(
        process: Process,
        command: List<String>,
        timeoutSec: Int,
        started: Long,
        mode: String,
        cwd: String?,
    ): ProcResult {
        val truncated = AtomicBoolean(false)
        val outSink = StringBuilder()
        val errSink = StringBuilder()
        val outThread = drain(process.inputStream, outSink, truncated)
        val errThread = drain(process.errorStream, errSink, truncated)
        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            Thread.sleep(200)
            runCatching { process.destroyForcibly() }
        }
        outThread.join(2000)
        errThread.join(2000)
        val exit = if (finished) process.exitValue() else null
        val duration = System.currentTimeMillis() - started
        McpLog.info(
            TAG,
            "[$mode] ${ProcResult.shellQuote(command)} -> exit=$exit time=${duration}ms" +
                if (!finished) " (TIMEOUT after ${timeoutSec}s)" else "",
        )
        return ProcResult(
            command = command,
            exitCode = exit,
            stdout = outSink.toString(),
            stderr = errSink.toString(),
            timedOut = !finished,
            durationMs = duration,
            truncated = truncated.get(),
            cwd = cwd,
            mode = mode,
        )
    }

    private fun drain(stream: InputStream, sink: StringBuilder, truncated: AtomicBoolean): Thread {
        val t = Thread({
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    synchronized(sink) {
                        if (sink.length < MAX_CAPTURE) {
                            val room = MAX_CAPTURE - sink.length
                            if (n <= room) sink.append(String(buf, 0, n, Charsets.UTF_8))
                            else {
                                sink.append(String(buf, 0, room, Charsets.UTF_8))
                                truncated.set(true)
                            }
                        } else truncated.set(true)
                    }
                }
            } catch (_: Throwable) {
            }
        }, "mcp-proc-drain")
        t.isDaemon = true
        t.start()
        return t
    }

    private fun q(s: String): String {
        if (s.isEmpty()) return "''"
        val safe = s.all { it.isLetterOrDigit() || it == '/' || it == '.' || it == '_' || it == '-' || it == ':' || it == '=' || it == ',' || it == '+' }
        return if (safe) s else "'" + s.replace("'", "'\\''") + "'"
    }
}

/** Small helpers shared by several tool groups. */
object Fs {

    fun exists(f: File) = f.exists()

    fun isWithin(root: File?, target: File): Boolean {
        if (root == null) return true
        val rp = root.canonicalPath
        val tp = target.canonicalPath
        return tp == rp || tp.startsWith(rp + File.separator)
    }

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format("%.1f %s", v, units[i])
    }

    fun listing(dir: File, maxEntries: Int): Map<String, Any?> {
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val items = ArrayList<Map<String, Any?>>()
        for (c in children.take(maxEntries)) {
            items.add(
                Json.obj(
                    "name" to c.name,
                    "path" to c.absolutePath,
                    "type" to if (c.isDirectory) "dir" else "file",
                    "size" to (if (c.isDirectory) 0L else c.length()),
                    "lastModified" to c.lastModified(),
                    "canRead" to c.canRead(),
                    "canWrite" to c.canWrite(),
                ),
            )
        }
        return Json.obj(
            "path" to dir.absolutePath,
            "exists" to dir.exists(),
            "isDirectory" to dir.isDirectory,
            "totalEntries" to children.size,
            "returnedEntries" to items.size,
            "truncated" to (children.size > items.size),
            "entries" to items,
        )
    }
}
