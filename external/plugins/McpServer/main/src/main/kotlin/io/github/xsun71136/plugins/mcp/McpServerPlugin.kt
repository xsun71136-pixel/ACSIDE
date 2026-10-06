package io.github.xsun71136.plugins.mcp

import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.mcp.McpServer
import io.github.xsun71136.plugins.mcp.tools.BuildTools
import io.github.xsun71136.plugins.mcp.tools.CallHistory
import io.github.xsun71136.plugins.mcp.tools.DeployTools
import io.github.xsun71136.plugins.mcp.tools.EditorTools
import io.github.xsun71136.plugins.mcp.tools.FileTools
import io.github.xsun71136.plugins.mcp.tools.IdeTools
import io.github.xsun71136.plugins.mcp.tools.LspTools
import io.github.xsun71136.plugins.mcp.tools.McpTools
import io.github.xsun71136.plugins.mcp.tools.ProjectTools
import io.github.xsun71136.plugins.mcp.tools.ShellTools
import io.github.xsun71136.plugins.mcp.tools.ToolRegistry
import java.io.File

/**
 * Plugin entry point declared in main/meta/actions.json.
 *
 * The IDE invokes a static method of this class when the editor activity is
 * launched (showIn = onEditorActivityLaunched). Both `setContext` and
 * `getContext` are exposed with and without a Context parameter so the plugin
 * keeps loading regardless of which dispatch signature the IDE uses.
 *
 * Everything here is defensive: a failure in the console UI must never stop the
 * MCP server, and a failure in the server must never break the IDE.
 */
object McpServerPlugin {

    private const val TAG = "plugin"

    @Volatile
    private var bootstrapped = false

    @Volatile
    private var hostContext: android.content.Context? = null

    @Volatile
    private var consoleVisible = false

    @JvmStatic
    fun setContext(context: android.content.Context): Map<String, Any?> = bootstrap(context, "setContext(Context)")

    @JvmStatic
    fun getContext(context: android.content.Context): Map<String, Any?> = bootstrap(context, "getContext(Context)")

    @JvmStatic
    fun setContext(): Map<String, Any?> = bootstrap(null, "setContext()")

    @JvmStatic
    fun getContext(): Map<String, Any?> = bootstrap(null, "getContext()")

    /** Manual entry used by the console overlay and by tests. */
    @JvmStatic
    fun bootstrap(context: android.content.Context?, via: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["via"] = via
        try {
            if (context != null) hostContext = context
            registerTools()
            val dir = resolveDir(context)
            val settings = McpSettingsStore.init(dir)
            result["settingsFile"] = McpSettingsStore.file()?.absolutePath
            result["registeredTools"] = ToolRegistry.size()
            result["enabled"] = settings.enabled

            McpSettingsStore.removeListener(settingsListener)
            McpSettingsStore.addListener(settingsListener)

            if (!settings.enabled) {
                McpLog.info(TAG, "plugin loaded but MCP server is disabled in settings")
                result["serverStarted"] = false
                result["reason"] = "disabled in settings"
            } else if (settings.autoStart) {
                val startResult = McpServer.start("autoStart/$via")
                result["serverStarted"] = Json.boolOf(startResult, "ok", false)
                result["startResult"] = startResult
            } else {
                result["serverStarted"] = McpServer.isRunning
                result["reason"] = "autoStart is off"
            }

            if (settings.showConsoleOnLaunch) {
                result["console"] = showConsole("auto")
            }
            bootstrapped = true
            McpLog.info(
                TAG,
                "bootstrap complete: tools=${ToolRegistry.size()} enabled=${settings.enabled} " +
                    "running=${McpServer.isRunning} endpoints=${McpServer.endpoints()}",
            )
        } catch (t: Throwable) {
            McpLog.error(TAG, "bootstrap failed", t)
            result["error"] = "${t.javaClass.name}: ${t.message}"
        }
        result["server"] = runCatching { McpServer.status() }.getOrElse { Json.obj("error" to (it.message ?: "?")) }
        return result
    }

    @JvmStatic
    fun isBootstrapped(): Boolean = bootstrapped

    /** Show (or re-show) the 输出与调试 console overlay from the UI thread. */
    @JvmStatic
    fun showConsole(reason: String): Map<String, Any?> {
        if (consoleVisible) return Json.obj("ok" to true, "alreadyVisible" to true)
        val ui = runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.ui }.getOrNull()
        if (ui == null) {
            McpLog.warn(TAG, "console unavailable: PluginApi.ui is null (not inside EditorActivity)")
            return Json.obj("ok" to false, "error" to "PluginApi.ui is null - open a file in the editor first")
        }
        return try {
            val runner = Runnable {
                try {
                    val handle = ui.showOverlay { h ->
                        io.github.xsun71136.plugins.mcp.ui.McpConsolePanel(
                            handle = h,
                            onClose = {
                                consoleVisible = false
                                h.dismiss()
                            },
                        )
                    }
                    consoleVisible = handle.isShowing
                    McpLog.info(TAG, "console overlay shown ($reason)")
                } catch (t: Throwable) {
                    consoleVisible = false
                    McpLog.error(TAG, "cannot render console overlay", t)
                }
            }
            // showOverlay() must run on the main thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post(runner)
            Json.obj("ok" to true, "reason" to reason)
        } catch (t: Throwable) {
            consoleVisible = false
            McpLog.error(TAG, "showConsole failed", t)
            Json.obj("ok" to false, "error" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun registerTools() {
        if (ToolRegistry.size() > 0) return
        val specs = ArrayList<io.github.xsun71136.plugins.mcp.tools.ToolSpec>()
        specs.addAll(IdeTools.all)
        specs.addAll(ProjectTools.all)
        specs.addAll(FileTools.all)
        specs.addAll(BuildTools.all)
        specs.addAll(DeployTools.all)
        specs.addAll(ShellTools.all)
        specs.addAll(EditorTools.all)
        specs.addAll(LspTools.all)
        specs.addAll(McpTools.all)
        ToolRegistry.registerAll(specs)
        McpLog.info(TAG, "registered ${specs.size} MCP tools in ${ToolGroups.ALL.size} groups")
    }

    private fun resolveDir(context: android.content.Context?): File? {
        runCatching {
            val f = com.nullij.androidcodestudio.plugins.api.PluginApi.environment.filesDir
            if (f != null) return f
        }
        runCatching {
            val f = context?.filesDir
            if (f != null) return f
        }
        return null
    }

    /** React to settings edits: restart the listener when the endpoint identity changed. */
    private val settingsListener: (McpSettings) -> Unit = { s ->
        try {
            McpLog.minLevel = s.minLogLevel
            McpLog.setLimit(s.logLimit)
            val running = McpServer.isRunning
            val status = McpServer.status()
            val port = status["port"] as? Int ?: -1
            val needRestart = running && (port != s.port)
            if (!s.enabled && running) {
                McpServer.stop("settings:disabled")
            } else if (s.enabled && s.autoStart && !running) {
                McpServer.start("settings:enabled")
            } else if (needRestart) {
                McpServer.restart("settings:port/bind changed")
            }
            McpServer.notifyToolsChanged()
        } catch (t: Throwable) {
            McpLog.error(TAG, "settings listener failed", t)
        }
    }

    /** Debug helper exposed for the console's 调试 tab. */
    @JvmStatic
    fun selfProbe(): Map<String, Any?> = Json.obj(
        "bootstrapped" to bootstrapped,
        "consoleVisible" to consoleVisible,
        "serverRunning" to McpServer.isRunning,
        "tools" to ToolRegistry.size(),
        "enabledTools" to ToolRegistry.enabledSpecs().size,
        "calls" to CallHistory.stats(),
        "editor" to EditorTools.probe(),
        "paths" to IdeTools.pathsMap(),
    )
}
