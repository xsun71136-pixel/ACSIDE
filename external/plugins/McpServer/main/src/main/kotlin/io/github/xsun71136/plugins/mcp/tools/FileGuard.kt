package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpLog
import java.io.File

/**
 * Confines file tools to directories the IDE actually owns, so a malformed
 * argument can never touch an unrelated part of the device.
 */
object FileGuard {

    private const val TAG = "fileguard"

    fun roots(): List<File> {
        val out = ArrayList<File>()
        runCatching {
            val env = com.nullij.androidcodestudio.plugins.api.PluginApi.environment
            out.add(env.projectsDir)
            out.add(env.acsRootProjects)
            out.add(env.filesDir)
            out.add(env.homeDir)
            out.add(env.localDir)
            out.add(env.tmpDir)
            out.add(env.androidSdkDir)
            out.add(env.flutterDir)
            env.openProjectDir?.let { out.add(it) }
        }.onFailure { t -> McpLog.warn(TAG, "environment roots unavailable: ${t.message}") }
        out.add(File("/storage/emulated/0"))
        out.add(File("/data/local/tmp"))
        return out.distinctBy { it.absolutePath }
    }

    /** Turn a user supplied path into an absolute File, resolving relative paths sensibly. */
    fun resolve(path: String): File {
        val trimmed = path.trim()
        val f = File(trimmed)
        if (f.isAbsolute) return f
        val base = runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.environment.openProjectDir }.getOrNull()
            ?: runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.environment.projectsDir }.getOrNull()
            ?: File(".")
        return File(base, trimmed)
    }

    /** Returns null when allowed, otherwise a human readable refusal reason. */
    fun check(file: File): String? {
        val roots = roots()
        if (roots.isEmpty()) return null
        val target = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        for (r in roots) {
            val rp = runCatching { r.canonicalPath }.getOrElse { r.absolutePath }
            if (target == rp || target.startsWith(rp + File.separator)) return null
        }
        return "path '$target' is outside every IDE-owned directory (projectsDir, acsRootProjects, filesDir, homeDir, localDir, tmpDir, androidSdkDir, flutterDir, /storage/emulated/0, /data/local/tmp)"
    }

    fun resolveChecked(path: String): Pair<File?, String?> {
        val f = resolve(path)
        val err = check(f)
        return if (err == null) f to null else null to err
    }
}
