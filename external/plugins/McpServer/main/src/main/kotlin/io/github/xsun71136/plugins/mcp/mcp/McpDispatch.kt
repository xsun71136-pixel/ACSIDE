package io.github.xsun71136.plugins.mcp.mcp

import io.github.xsun71136.plugins.mcp.BuildInfo
import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.tools.CallHistory
import io.github.xsun71136.plugins.mcp.tools.IdeTools
import io.github.xsun71136.plugins.mcp.tools.ToolRegistry

/** JSON-RPC 2.0 error with an MCP-compatible code. */
class RpcError(val code: Int, message: String, val data: Any? = null) : Exception(message) {
    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["code"] = code
        m["message"] = (message ?: "error")
        if (data != null) m["data"] = data
        return m
    }

    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/**
 * Protocol layer: turns a decoded JSON-RPC request map into a result map.
 *
 * Implements the server side of the Model Context Protocol:
 *   initialize / notifications/initialized / ping
 *   tools/list / tools/call
 *   resources/list / resources/read / resources/templates/list
 *   prompts/list / prompts/get
 *   logging/setLevel
 *   completion/complete
 */
object McpDispatch {

    private const val TAG = "mcp"

    private val SUPPORTED_PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07")

    /** @return null when the request is a notification and must not be answered. */
    fun handle(request: Map<String, Any?>, session: McpSession): Any? {
        val method = Json.strOf(request, "method") ?: throw RpcError(RpcError.INVALID_REQUEST, "missing 'method'")
        val id = request["id"]
        val params = Json.asMap(request["params"])

        if (method.startsWith("notifications/")) {
            onNotification(method, params, session)
            return null
        }

        return when (method) {
            "initialize" -> initialize(params, session)
            "ping" -> Json.obj()
            "tools/list" -> toolsList(params)
            "tools/call" -> toolsCall(params, session)
            "resources/list" -> resourcesList()
            "resources/templates/list" -> Json.obj("resourceTemplates" to Json.arr())
            "resources/read" -> resourcesRead(params)
            "prompts/list" -> promptsList()
            "prompts/get" -> promptsGet(params)
            "logging/setLevel" -> loggingSetLevel(params)
            "completion/complete" -> Json.obj("completion" to Json.obj("values" to Json.arr(), "hasMore" to false, "total" to 0))
            else -> {
                if (id == null) null
                else throw RpcError(RpcError.METHOD_NOT_FOUND, "method not found: $method")
            }
        }
    }

    private fun onNotification(method: String, params: Map<String, Any?>, session: McpSession) {
        when (method) {
            "notifications/initialized" -> {
                session.initialized = true
                McpLog.info(TAG, "session ${session.shortId()} initialized (protocol=${session.protocolVersion})")
            }
            "notifications/cancelled" -> McpLog.debug(TAG, "client cancelled request ${params["requestId"]}")
            "notifications/roots/list_changed" -> McpLog.debug(TAG, "client roots changed")
            else -> McpLog.debug(TAG, "notification $method")
        }
    }

    private fun initialize(params: Map<String, Any?>, session: McpSession): Map<String, Any?> {
        val requested = Json.strOf(params, "protocolVersion")
        val negotiated = when {
            requested == null -> McpSettingsStore.current.protocolVersion
            SUPPORTED_PROTOCOLS.contains(requested) -> requested
            else -> SUPPORTED_PROTOCOLS.first()
        }
        session.protocolVersion = negotiated
        session.clientInfo = Json.asMap(params["clientInfo"])
        session.initialized = false
        val s = McpSettingsStore.current
        McpLog.info(
            TAG,
            "initialize from ${session.remote} client=${Json.strOf(session.clientInfo, "name")} " +
                "protocol=$negotiated (requested=$requested)",
        )
        return Json.obj(
            "protocolVersion" to negotiated,
            "capabilities" to Json.obj(
                "tools" to Json.obj("listChanged" to true),
                "resources" to Json.obj("subscribe" to false, "listChanged" to false),
                "prompts" to Json.obj("listChanged" to false),
                "logging" to Json.obj(),
            ),
            "serverInfo" to Json.obj(
                "name" to s.serverName,
                "title" to "Android Code Studio MCP Server",
                "version" to s.serverVersion,
            ),
            "instructions" to INSTRUCTIONS,
        )
    }

    private fun toolsList(params: Map<String, Any?>): Map<String, Any?> {
        val defs = ToolRegistry.definitions()
        McpLog.debug(TAG, "tools/list -> ${defs.size} tools")
        return Json.obj("tools" to defs)
    }

    private fun toolsCall(params: Map<String, Any?>, session: McpSession): Map<String, Any?> {
        val name = Json.strOf(params, "name") ?: throw RpcError(RpcError.INVALID_PARAMS, "tools/call requires 'name'")
        val args = Json.asMap(params["arguments"])
        session.toolCalls.incrementAndGet()
        McpLog.info(TAG, "tools/call $name from ${session.shortId()} args=${clamp(Json.stringify(args), 400)}")
        val out = ToolRegistry.invoke(name, args, origin = "mcp:${session.shortId()}")
        return out.toResultMap()
    }

    private fun resourcesList(): Map<String, Any?> = Json.obj(
        "resources" to Json.arr(
            Json.obj("uri" to "acs://status", "name" to "IDE & MCP status", "description" to "Same payload as the acs_ide_status tool", "mimeType" to "application/json"),
            Json.obj("uri" to "acs://settings", "name" to "MCP settings", "description" to "Current MCP configuration (token masked)", "mimeType" to "application/json"),
            Json.obj("uri" to "acs://project", "name" to "Open project", "description" to "Build configuration of the currently open project", "mimeType" to "application/json"),
            Json.obj("uri" to "acs://paths", "name" to "IDE paths", "description" to "All IDE-owned directories", "mimeType" to "application/json"),
            Json.obj("uri" to "acs://tools", "name" to "Tool catalogue", "description" to "Every registered MCP tool grouped by capability area", "mimeType" to "application/json"),
            Json.obj("uri" to "acs://log", "name" to "Console log", "description" to "Output & Debugging console content", "mimeType" to "text/plain"),
            Json.obj("uri" to "acs://calls", "name" to "Tool call history", "description" to "Last 100 tool invocations with timings and outcomes", "mimeType" to "application/json"),
        ),
    )

    private fun resourcesRead(params: Map<String, Any?>): Map<String, Any?> {
        val uri = Json.strOf(params, "uri") ?: throw RpcError(RpcError.INVALID_PARAMS, "resources/read requires 'uri'")
        val (mime, text) = when (uri) {
            "acs://status" -> "application/json" to Json.pretty(IdeTools.statusMap())
            "acs://settings" -> "application/json" to Json.pretty(McpSettingsStore.current.toPublicMap())
            "acs://paths" -> "application/json" to Json.pretty(IdeTools.pathsMap())
            "acs://tools" -> "application/json" to Json.pretty(Json.obj("groups" to ToolRegistry.byGroup(), "enabled" to ToolRegistry.enabledSpecs().map { it.name }))
            "acs://project" -> "application/json" to Json.pretty(projectResource())
            "acs://log" -> "text/plain" to McpLog.dumpText(500)
            "acs://calls" -> "application/json" to Json.pretty(
                Json.obj(
                    "stats" to CallHistory.stats(),
                    "calls" to CallHistory.tail(100).map { it.toMap() },
                ),
            )
            else -> throw RpcError(RpcError.INVALID_PARAMS, "unknown resource uri '$uri'")
        }
        return Json.obj("contents" to Json.arr(Json.obj("uri" to uri, "mimeType" to mime, "text" to text)))
    }

    private fun projectResource(): Map<String, Any?> {
        val dir = runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.environment.openProjectDir }.getOrNull()
            ?: return Json.obj("openProject" to false)
        val out = ToolRegistry.invoke("acs_project_info", Json.obj("projectDir" to dir.absolutePath), origin = "resource")
        return out.data ?: Json.obj("openProject" to true, "path" to dir.absolutePath, "text" to out.text)
    }

    private fun promptsList(): Map<String, Any?> = Json.obj(
        "prompts" to Json.arr(
            Json.obj(
                "name" to "build_and_install",
                "title" to "构建并安装当前项目",
                "description" to "引导 AI 依次调用 acs_build_apk 与 acs_install_apk，并在失败时用 acs_crash_report 定位",
                "arguments" to Json.arr(
                    Json.obj("name" to "variant", "description" to "debug 或 release", "required" to false),
                    Json.obj("name" to "package", "description" to "安装后要启动的包名", "required" to false),
                ),
            ),
            Json.obj(
                "name" to "diagnose_build_failure",
                "title" to "诊断构建失败",
                "description" to "抓取 Gradle 失败输出、项目配置与设备环境，给出第一个失败任务与根因",
                "arguments" to Json.arr(Json.obj("name" to "task", "description" to "失败的 Gradle 任务", "required" to false)),
            ),
            Json.obj(
                "name" to "explore_project",
                "title" to "了解当前项目",
                "description" to "用 acs_project_info / acs_project_tree / acs_file_search 概览项目结构与关键配置",
                "arguments" to Json.arr(),
            ),
        ),
    )

    private fun promptsGet(params: Map<String, Any?>): Map<String, Any?> {
        val name = Json.strOf(params, "name") ?: throw RpcError(RpcError.INVALID_PARAMS, "prompts/get requires 'name'")
        val args = Json.asMap(params["arguments"])
        val variant = Json.strOf(args, "variant", "debug") ?: "debug"
        val pkg = Json.strOf(args, "package")
        val task = Json.strOf(args, "task")
        val text = when (name) {
            "build_and_install" -> buildString {
                append("请对当前 Android Code Studio 项目执行构建并安装：\n")
                append("1) 调用 acs_project_info 确认模块与 applicationId；\n")
                append("2) 调用 acs_build_apk（variant=$variant）；\n")
                append("3) 若成功，用返回的 newestApk.path 调用 acs_install_apk；\n")
                if (pkg != null) append("4) 调用 acs_launch_app（packageName=$pkg）启动应用；\n")
                append("5) 任一步失败时调用 acs_crash_report 或读取 gradle 输出，定位第一个失败任务并给出根因与最小修复。\n")
                append("请只做可回滚的最小改动，并在结束时汇报：产物路径、大小、SHA-256、安装结果、启动结果。")
            }
            "diagnose_build_failure" -> buildString {
                append("请诊断当前项目的构建失败：\n")
                append("1) acs_project_info 读取模块/SDK/AGP 配置；\n")
                append("2) acs_gradle_run 重跑 ${task ?: "assembleDebug"} 并附带 --stacktrace；\n")
                append("3) acs_ide_status 与 acs_env_get 核对 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME；\n")
                append("4) 区分「第一个失败任务」与下游噪声，给出根因、证据行号与最小可回滚修复步骤。")
            }
            "explore_project" -> "请用 acs_project_info、acs_project_tree、acs_file_search 概览当前项目：模块划分、依赖版本、入口 Activity、构建产物位置，并用不超过 20 行总结。"
            else -> throw RpcError(RpcError.INVALID_PARAMS, "unknown prompt '$name'")
        }
        return Json.obj(
            "description" to name,
            "messages" to Json.arr(Json.obj("role" to "user", "content" to Json.obj("type" to "text", "text" to text))),
        )
    }

    private fun loggingSetLevel(params: Map<String, Any?>): Map<String, Any?> {
        val level = Json.strOf(params, "level")?.uppercase() ?: "INFO"
        val mapped = when (level) {
            "DEBUG" -> McpLog.DEBUG
            "INFO", "NOTICE", "WARNING" -> McpLog.INFO
            "ERROR", "CRITICAL", "ALERT", "EMERGENCY" -> McpLog.ERROR
            else -> McpLog.INFO
        }
        McpSettingsStore.update { it.minLogLevel = mapped }
        return Json.obj("ok" to true, "minLogLevel" to mapped)
    }

    private fun clamp(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…"

    private val INSTRUCTIONS = """
        This is the Android Code Studio (ACSIDE) MCP server. It exposes the whole IDE and the
        currently open project as tools. Typical order of work:
          acs_ide_status -> acs_project_info -> acs_file_* -> acs_gradle_run / acs_build_apk
          -> acs_install_apk -> acs_launch_app -> acs_logcat_dump / acs_crash_report.
        Long running Gradle work goes through acs_gradle_run / acs_build_apk with an explicit
        timeoutSec. Mutating tools are refused while read-only mode is enabled in the MCP
        settings page. All paths should be absolute; acs_paths lists every IDE-owned directory.
    """.trimIndent()
}
