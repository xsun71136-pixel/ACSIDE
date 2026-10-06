package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.BuildInfo
import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.mcp.McpServer
import io.github.xsun71136.plugins.mcp.util.Exec
import java.io.File

/** ide group: what the IDE is, where things live, which device we run on. */
object IdeTools {

    private const val TAG = "ide"
    private val SECRET_HINTS = listOf("token", "secret", "password", "passwd", "apikey", "api_key", "credential", "private_key")

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_ide_status",
            group = ToolGroups.IDE,
            description = "Android Code Studio 与 MCP 服务器的总体状态：环境是否初始化、当前打开的项目、各类目录、SDK/Flutter 路径、rootfs JAVA_HOME、编辑器/LSP/UI 可用性、语言服务器状态、MCP 监听端口与会话统计、已启用工具数量。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(statusMap()) },
        ),
        ToolSpec(
            name = "acs_env_get",
            group = ToolGroups.IDE,
            description = "读取 IDE 用于子进程的环境变量表（JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / PATH 等）。含 token、secret、password 的键会被自动打码。可用 filter 只返回名字包含该子串的键。",
            inputSchema = Json.schema(
                listOf(
                    Triple("filter", "string", "可选：只返回键名包含该子串的环境变量"),
                    Triple("redact", "boolean", "默认 true：对疑似密钥的值打码。仅在明确需要时设为 false。"),
                ),
            ),
            handler = { args ->
                val filter = Json.strOf(args, "filter")?.lowercase()
                val redact = Json.boolOf(args, "redact", true)
                val env = Exec.ideEnvironment().toSortedMap()
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in env) {
                    if (filter != null && !k.lowercase().contains(filter)) continue
                    out[k] = if (redact && isSecret(k)) redactValue(v) else v
                }
                ToolOutput.ok(Json.obj("count" to out.size, "filter" to filter, "redacted" to redact, "env" to out))
            },
        ),
        ToolSpec(
            name = "acs_device_info",
            group = ToolGroups.IDE,
            description = "设备与运行时信息：Android 版本/SDK、ABI、型号、可用内存、IDE 进程信息，以及 getprop 关键属性。",
            inputSchema = Json.schema(emptyList()),
            handler = {
                val info = LinkedHashMap<String, Any?>()
                info["androidRelease"] = android.os.Build.VERSION.RELEASE
                info["sdkInt"] = android.os.Build.VERSION.SDK_INT
                info["manufacturer"] = android.os.Build.MANUFACTURER
                info["model"] = android.os.Build.MODEL
                info["device"] = android.os.Build.DEVICE
                info["abis"] = android.os.Build.SUPPORTED_ABIS.toList()
                info["availableProcessors"] = Runtime.getRuntime().availableProcessors()
                info["jvmMaxMemory"] = Runtime.getRuntime().maxMemory()
                info["jvmTotalMemory"] = Runtime.getRuntime().totalMemory()
                info["jvmFreeMemory"] = Runtime.getRuntime().freeMemory()
                info["javaVersion"] = System.getProperty("java.version")
                info["userDir"] = System.getProperty("user.dir")
                val props = Exec.hostShell(
                    "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; " +
                        "getprop ro.build.display.id; getprop ro.product.model; getprop persist.sys.timezone",
                    null,
                    20,
                )
                info["getprop"] = props.stdout.trim()
                ToolOutput.ok(info)
            },
        ),
        ToolSpec(
            name = "acs_paths",
            group = ToolGroups.IDE,
            description = "列出 IDE 的全部关键路径（projectsDir / acsRootProjects / androidSdkDir / flutterDir / filesDir / homeDir / localDir / tmpDir / rootfs 路径 / acs-mcp 配置目录），便于其它工具构造绝对路径。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(pathsMap()) },
        ),
    )

    fun isSecret(key: String): Boolean {
        val k = key.lowercase()
        for (h in SECRET_HINTS) if (k.contains(h)) return true
        return false
    }

    fun redactValue(v: String): String =
        if (v.length <= 4) "****" else v.substring(0, 2) + "****" + v.substring(v.length - 2)

    fun pathsMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        runCatching {
            val env = com.nullij.androidcodestudio.plugins.api.PluginApi.environment
            m["openProjectDir"] = env.openProjectDir?.absolutePath
            m["projectsDir"] = env.projectsDir.absolutePath
            m["acsRootProjects"] = env.acsRootProjects.absolutePath
            m["androidSdkDir"] = env.androidSdkDir.absolutePath
            m["flutterDir"] = env.flutterDir.absolutePath
            m["filesDir"] = env.filesDir.absolutePath
            m["homeDir"] = env.homeDir.absolutePath
            m["localDir"] = env.localDir.absolutePath
            m["tmpDir"] = env.tmpDir.absolutePath
            m["rootfsAndroidSdkPath"] = env.rootfsAndroidSdkPath
            m["rootfsJavaHome"] = env.rootfsJavaHome
            m["environmentInitialized"] = env.isInitialized()
        }.onFailure { t ->
            m["error"] = "environment unavailable: ${t.message}"
            McpLog.warn(TAG, "paths unavailable: ${t.message}")
        }
        m["mcpDir"] = McpSettingsStore.dir()?.absolutePath
        m["mcpSettingsFile"] = McpSettingsStore.file()?.absolutePath
        return m
    }

    fun statusMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["plugin"] = Json.obj(
            "name" to BuildInfo.NAME,
            "version" to BuildInfo.VERSION,
            "protocolVersions" to BuildInfo.PROTOCOL_VERSIONS,
        )
        runCatching {
            val env = com.nullij.androidcodestudio.plugins.api.PluginApi.environment
            m["environmentInitialized"] = env.isInitialized()
            m["openProjectDir"] = env.openProjectDir?.absolutePath
            m["projectsDir"] = env.projectsDir.absolutePath
            m["androidSdkDir"] = env.androidSdkDir.absolutePath
        }.onFailure { t -> m["environmentError"] = t.message }
        runCatching {
            m["editorAvailable"] = com.nullij.androidcodestudio.plugins.api.PluginApi.editor.isAvailable()
        }.onFailure { t -> m["editorError"] = t.message }
        runCatching {
            val lsp = com.nullij.androidcodestudio.plugins.api.PluginApi.lsp
            m["lspAvailable"] = (lsp != null)
            if (lsp != null) {
                m["lspServers"] = lsp.getAvailableServers().sorted()
                m["lspRunning"] = lsp.getRunningServers().sorted()
                m["lspExtensions"] = lsp.getRegisteredExtensions().toSortedMap()
            }
        }.onFailure { t -> m["lspError"] = t.message }
        m["uiAvailable"] = runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.ui != null }.getOrDefault(false)
        m["mcpServer"] = McpServer.status()
        val s = McpSettingsStore.current
        m["settings"] = s.toPublicMap()
        val grouped = ToolRegistry.byGroup()
        m["toolGroups"] = grouped.mapValues { e ->
            Json.obj(
                "enabled" to s.groupEnabled(e.key),
                "description" to (ToolGroups.DESCRIPTIONS[e.key] ?: ""),
                "tools" to e.value,
                "count" to e.value.size,
            )
        }
        m["enabledToolCount"] = ToolRegistry.enabledSpecs().size
        m["registeredToolCount"] = ToolRegistry.size()
        m["callStats"] = CallHistory.stats()
        return m
    }

    /** Resolve a project directory: explicit argument, else the currently open project. */
    fun resolveProjectDir(args: Map<String, Any?>): File? {
        val explicit = Json.strOf(args, "projectDir")?.trim()
        if (!explicit.isNullOrEmpty()) {
            val f = File(explicit)
            return if (f.isAbsolute) f else File(Exec.projectsDirs().firstOrNull() ?: File("."), explicit)
        }
        return runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.environment.openProjectDir }.getOrNull()
    }
}
