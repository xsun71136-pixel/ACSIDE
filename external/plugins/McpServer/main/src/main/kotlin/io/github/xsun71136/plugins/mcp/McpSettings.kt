package io.github.xsun71136.plugins.mcp

import io.github.xsun71136.plugins.mcp.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/** Tool groups exposed by the MCP server. Every group can be switched on/off separately. */
object ToolGroups {
    const val IDE = "ide"
    const val PROJECT = "project"
    const val FILE = "file"
    const val BUILD = "build"
    const val DEPLOY = "deploy"
    const val SHELL = "shell"
    const val EDITOR = "editor"
    const val LSP = "lsp"
    const val MCP = "mcp"

    val ALL: List<String> = listOf(IDE, PROJECT, FILE, BUILD, DEPLOY, SHELL, EDITOR, LSP, MCP)

    val DESCRIPTIONS: Map<String, String> = mapOf(
        IDE to "IDE 状态 / 环境变量 / 设备信息",
        PROJECT to "项目列表、项目信息、目录树",
        FILE to "文件读写、搜索、删除、统计",
        BUILD to "Gradle 构建、任务列表、APK 产物定位",
        DEPLOY to "安装 / 卸载 / 启动 / 停止 / logcat",
        SHELL to "在 IDE 环境内执行命令（proot/acsenv）与宿主 shell",
        EDITOR to "当前编辑器文本、光标、选区、撤销、格式化",
        LSP to "语言服务器状态、启停、文档事件",
        MCP to "MCP 服务器自身的状态、配置、重启、日志",
    )

    /** Tools that mutate state; refused automatically while readOnly is on. */
    val MUTATING: Set<String> = setOf(
        // file
        "acs_file_write", "acs_file_delete", "acs_file_mkdir",
        // build
        "acs_gradle_run", "acs_build_apk",
        // deploy
        "acs_install_apk", "acs_uninstall_app", "acs_launch_app", "acs_stop_app", "acs_logcat_clear",
        // shell
        "acs_shell_exec", "acs_shell_argv", "acs_host_exec", "acs_adb",
        // editor
        "acs_editor_write", "acs_editor_insert", "acs_editor_replace", "acs_editor_command",
        // lsp
        "acs_lsp_start", "acs_lsp_stop", "acs_lsp_stop_all", "acs_lsp_extensions", "acs_lsp_document",
        // mcp self management
        "acs_mcp_config_set", "acs_mcp_start", "acs_mcp_stop", "acs_mcp_restart", "acs_mcp_log_clear",
    )
}

/**
 * Persisted, separately configurable MCP settings.
 *
 * Stored as plain JSON at <ide filesDir>/acs-mcp/settings.json so it can also be
 * edited by hand from inside the IDE editor.
 */
data class McpSettings(
    var enabled: Boolean = true,
    var autoStart: Boolean = true,
    var showConsoleOnLaunch: Boolean = true,
    /** false -> listen on 127.0.0.1 only, true -> listen on all interfaces (LAN reachable). */
    var bindAll: Boolean = false,
    var port: Int = 8388,
    var requireAuth: Boolean = true,
    var authToken: String = "",
    /** Read-only safety mode: every mutating tool is refused. */
    var readOnly: Boolean = false,
    var maxBodyBytes: Int = 4 * 1024 * 1024,
    var requestTimeoutSec: Int = 600,
    var logLimit: Int = 500,
    var minLogLevel: String = McpLog.INFO,
    var serverName: String = "acs-mcp",
    var serverVersion: String = BuildInfo.VERSION,
    /** Preferred MCP protocol version advertised by the server. */
    var protocolVersion: String = "2025-06-18",
    /** Interpreter used for acs_shell_exec / acs_gradle_run inside the IDE environment. */
    var shellCommand: String = "/bin/bash",
    var shellArgs: String = "-lc",
    /** auto -> use ./gradlew when present, otherwise gradle. Or an explicit path/name. */
    var gradleCommand: String = "auto",
    var gradleJvmArgs: String = "-Xmx2048m -Dfile.encoding=UTF-8",
    /** Host side command runner (Android). Used by acs_host_exec / acs_adb / acs_logcat_*. */
    var hostShell: String = "/system/bin/sh",
    var adbCommand: String = "adb",
    /** Extra environment merged into every IDE-environment process. */
    var extraEnv: MutableMap<String, String> = LinkedHashMap(),
    /** Per-group switches; missing keys default to true. */
    var toolGroups: MutableMap<String, Boolean> = LinkedHashMap(),
    /** Explicit allow list; empty means "every enabled tool". */
    var allowedTools: MutableList<String> = mutableListOf(),
    /** Explicit deny list; wins over allowedTools. */
    var deniedTools: MutableList<String> = mutableListOf(),
) {

    fun groupEnabled(group: String): Boolean = toolGroups[group] ?: true

    fun isToolEnabled(name: String, group: String): Boolean {
        if (!enabled) return false
        if (deniedTools.contains(name)) return false
        if (allowedTools.isNotEmpty() && !allowedTools.contains(name)) return false
        if (!groupEnabled(group)) return false
        if (readOnly && ToolGroups.MUTATING.contains(name)) return false
        return true
    }

    fun toMap(): Map<String, Any?> = Json.obj(
        "enabled" to enabled,
        "autoStart" to autoStart,
        "showConsoleOnLaunch" to showConsoleOnLaunch,
        "bindAll" to bindAll,
        "port" to port,
        "requireAuth" to requireAuth,
        "authToken" to authToken,
        "readOnly" to readOnly,
        "maxBodyBytes" to maxBodyBytes,
        "requestTimeoutSec" to requestTimeoutSec,
        "logLimit" to logLimit,
        "minLogLevel" to minLogLevel,
        "serverName" to serverName,
        "serverVersion" to serverVersion,
        "protocolVersion" to protocolVersion,
        "shellCommand" to shellCommand,
        "shellArgs" to shellArgs,
        "gradleCommand" to gradleCommand,
        "gradleJvmArgs" to gradleJvmArgs,
        "hostShell" to hostShell,
        "adbCommand" to adbCommand,
        "extraEnv" to extraEnv,
        "toolGroups" to toolGroups,
        "allowedTools" to allowedTools,
        "deniedTools" to deniedTools,
    )

    /** Settings without the secret, safe to return from MCP tools and to log. */
    fun toPublicMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(toMap())
        m["authToken"] = if (authToken.isEmpty()) "" else "********(${authToken.length} chars)"
        return m
    }

    companion object {
        fun fromMap(src: Map<String, Any?>): McpSettings {
            val d = McpSettings()
            val s = McpSettings(
                enabled = Json.boolOf(src, "enabled", d.enabled),
                autoStart = Json.boolOf(src, "autoStart", d.autoStart),
                showConsoleOnLaunch = Json.boolOf(src, "showConsoleOnLaunch", d.showConsoleOnLaunch),
                bindAll = Json.boolOf(src, "bindAll", d.bindAll),
                port = Json.intOf(src, "port", d.port).coerceIn(1024, 65535),
                requireAuth = Json.boolOf(src, "requireAuth", d.requireAuth),
                authToken = Json.strOf(src, "authToken", d.authToken) ?: "",
                readOnly = Json.boolOf(src, "readOnly", d.readOnly),
                maxBodyBytes = Json.intOf(src, "maxBodyBytes", d.maxBodyBytes).coerceIn(4096, 64 * 1024 * 1024),
                requestTimeoutSec = Json.intOf(src, "requestTimeoutSec", d.requestTimeoutSec).coerceIn(5, 7200),
                logLimit = Json.intOf(src, "logLimit", d.logLimit).coerceIn(50, 20000),
                minLogLevel = Json.strOf(src, "minLogLevel", d.minLogLevel) ?: d.minLogLevel,
                serverName = Json.strOf(src, "serverName", d.serverName) ?: d.serverName,
                serverVersion = Json.strOf(src, "serverVersion", d.serverVersion) ?: d.serverVersion,
                protocolVersion = Json.strOf(src, "protocolVersion", d.protocolVersion) ?: d.protocolVersion,
                shellCommand = Json.strOf(src, "shellCommand", d.shellCommand) ?: d.shellCommand,
                shellArgs = Json.strOf(src, "shellArgs", d.shellArgs) ?: d.shellArgs,
                gradleCommand = Json.strOf(src, "gradleCommand", d.gradleCommand) ?: d.gradleCommand,
                gradleJvmArgs = Json.strOf(src, "gradleJvmArgs", d.gradleJvmArgs) ?: d.gradleJvmArgs,
                hostShell = Json.strOf(src, "hostShell", d.hostShell) ?: d.hostShell,
                adbCommand = Json.strOf(src, "adbCommand", d.adbCommand) ?: d.adbCommand,
            )
            Json.asMap(src["extraEnv"]).forEach { (k, v) ->
                Json.str(v)?.let { s.extraEnv[k] = it }
            }
            Json.asMap(src["toolGroups"]).forEach { (k, v) ->
                s.toolGroups[k] = Json.boolOf(mapOf("v" to v), "v", true)
            }
            s.allowedTools.addAll(Json.strList(src, "allowedTools"))
            s.deniedTools.addAll(Json.strList(src, "deniedTools"))
            return s
        }

        fun randomToken(bytes: Int = 24): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val sb = StringBuilder(bytes * 2)
            for (b in buf) sb.append(alphabet[(b.toInt() and 0xFF) % alphabet.length])
            return sb.toString()
        }
    }
}

/** Version metadata reported by initialize/serverInfo and by acs_ide_status. */
object BuildInfo {
    const val VERSION = "1.0"
    const val NAME = "acs-mcp"
    const val PROTOCOL_VERSIONS = "2025-06-18,2025-03-26,2024-11-05,2024-10-07"
}

/**
 * Owns the settings file, the cached instance and change notifications.
 * The console UI and the server both read through here, so "可以单独设置"
 * takes effect immediately without restarting the IDE.
 */
object McpSettingsStore {

    const val DIR_NAME = "acs-mcp"
    const val FILE_NAME = "settings.json"

    @Volatile
    var baseDir: File? = null
        private set

    private val listeners = CopyOnWriteArrayList<(McpSettings) -> Unit>()

    @Volatile
    private var cached: McpSettings? = null

    val current: McpSettings
        get() = cached ?: McpSettings().also { cached = it }

    fun file(): File? = baseDir?.let { File(it, FILE_NAME) }

    fun dir(): File? = baseDir

    fun addListener(l: (McpSettings) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (McpSettings) -> Unit) {
        listeners.remove(l)
    }

    /** Resolve and load settings. [dir] is normally PluginApi.environment.filesDir. */
    @Synchronized
    fun init(dir: File?): McpSettings {
        val target = dir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", DIR_NAME)
        val d = File(target, DIR_NAME)
        try {
            if (!d.exists()) d.mkdirs()
        } catch (t: Throwable) {
            McpLog.warn("settings", "cannot create ${d.absolutePath}: ${t.message}")
        }
        baseDir = d
        val s = read(d)
        cached = s
        McpLog.setLimit(s.logLimit)
        McpLog.minLevel = s.minLogLevel
        return s
    }

    @Synchronized
    fun reload(): McpSettings {
        val d = baseDir ?: return current
        val s = read(d)
        cached = s
        McpLog.setLimit(s.logLimit)
        McpLog.minLevel = s.minLogLevel
        return s
    }

    @Synchronized
    fun update(block: (McpSettings) -> Unit): McpSettings {
        val s = current
        block(s)
        s.port = s.port.coerceIn(1024, 65535)
        s.logLimit = s.logLimit.coerceIn(50, 20000)
        s.requestTimeoutSec = s.requestTimeoutSec.coerceIn(5, 7200)
        McpLog.setLimit(s.logLimit)
        McpLog.minLevel = s.minLogLevel
        cached = s
        write(s)
        for (l in listeners) {
            try {
                l(s)
            } catch (t: Throwable) {
                McpLog.error("settings", "listener failed", t)
            }
        }
        return s
    }

    @Synchronized
    fun resetToDefaults(keepToken: Boolean = true): McpSettings {
        val old = current
        val fresh = McpSettings()
        if (keepToken) fresh.authToken = old.authToken.ifEmpty { McpSettings.randomToken() }
        cached = fresh
        write(fresh)
        McpLog.setLimit(fresh.logLimit)
        McpLog.minLevel = fresh.minLogLevel
        for (l in listeners) runCatching { l(fresh) }
        return fresh
    }

    private fun read(dir: File): McpSettings {
        val f = File(dir, FILE_NAME)
        if (!f.exists()) {
            val s = McpSettings(authToken = McpSettings.randomToken())
            write(s, f)
            McpLog.info("settings", "created default settings at ${f.absolutePath}")
            return s
        }
        return try {
            val parsed = Json.parse(f.readText())
            val s = McpSettings.fromMap(Json.asMap(parsed))
            if (s.authToken.isEmpty()) {
                s.authToken = McpSettings.randomToken()
                write(s, f)
            }
            s
        } catch (t: Throwable) {
            McpLog.error("settings", "cannot parse ${f.absolutePath}, falling back to defaults", t)
            McpSettings(authToken = McpSettings.randomToken())
        }
    }

    private fun write(s: McpSettings, f: File? = null) {
        val target = f ?: file() ?: return
        try {
            target.parentFile?.mkdirs()
            target.writeText(Json.pretty(s.toMap()))
        } catch (t: Throwable) {
            McpLog.error("settings", "cannot write ${target.absolutePath}", t)
        }
    }
}
