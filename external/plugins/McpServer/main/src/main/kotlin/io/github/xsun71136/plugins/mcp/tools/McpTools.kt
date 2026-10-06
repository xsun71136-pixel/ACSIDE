package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.mcp.McpServer
import java.net.InetSocketAddress
import java.net.Socket

/** mcp group: the server manages itself, so it can be inspected and reconfigured remotely. */
object McpTools {

    private const val TAG = "self"

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_mcp_status",
            group = ToolGroups.MCP,
            description = "MCP 服务器自身状态：是否运行、监听地址与端口、可用 endpoint、会话列表与统计、已启用工具数、只读模式、认证开关、设置文件路径、最近错误。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(McpServer.status()) },
        ),
        ToolSpec(
            name = "acs_mcp_config_get",
            group = ToolGroups.MCP,
            description = "读取完整 MCP 配置（authToken 打码），并给出设置文件的绝对路径，方便在 IDE 里直接手工编辑。",
            inputSchema = Json.schema(listOf(Triple("includeToken", "boolean", "默认 false：不返回明文 token"))),
            handler = { args ->
                val s = McpSettingsStore.current
                val map = if (Json.boolOf(args, "includeToken", false)) s.toMap() else s.toPublicMap()
                ToolOutput.ok(
                    Json.obj(
                        "settingsFile" to McpSettingsStore.file()?.absolutePath,
                        "settingsDir" to McpSettingsStore.dir()?.absolutePath,
                        "settings" to map,
                        "toolGroups" to ToolGroups.ALL.map { g ->
                            Json.obj("group" to g, "enabled" to s.groupEnabled(g), "description" to (ToolGroups.DESCRIPTIONS[g] ?: ""), "tools" to (ToolRegistry.byGroup()[g] ?: emptyList()))
                        },
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_mcp_config_set",
            group = ToolGroups.MCP,
            description = "在线修改 MCP 配置并立即生效（无需重启 IDE）。settings 是一个补丁对象，只写需要改的键：enabled/autoStart/bindAll/port/requireAuth/authToken/readOnly/logLimit/minLogLevel/shellCommand/gradleCommand/adbCommand/hostShell/toolGroups/allowedTools/deniedTools 等。改端口或 bindAll 时会自动重启监听。",
            inputSchema = Json.schema(
                listOf(
                    Triple("settings", "object", "要合并的配置补丁"),
                    Triple("restart", "boolean", "默认 auto：仅当 port/bindAll/enabled 变化时重启"),
                    Triple("reset", "boolean", "默认 false：true 时先恢复默认值再合并补丁"),
                ),
                listOf("settings"),
            ),
            handler = { args ->
                val patch = Json.asMap(args["settings"])
                if (patch.isEmpty() && !Json.boolOf(args, "reset", false)) {
                    return@ToolSpec ToolOutput.fail("'settings' patch is empty; nothing to do")
                }
                val before = McpSettingsStore.current
                val beforePort = before.port
                val beforeBind = before.bindAll
                val beforeEnabled = before.enabled
                if (Json.boolOf(args, "reset", false)) McpSettingsStore.resetToDefaults(true)
                val after = McpSettingsStore.update { s -> applyPatch(s, patch) }
                McpLog.info(TAG, "config updated: ${patch.keys.joinToString()}")
                val portChanged = after.port != beforePort || after.bindAll != beforeBind || after.enabled != beforeEnabled
                val wantRestart = when (Json.strOf(args, "restart")?.lowercase()) {
                    "true", "yes", "1" -> true
                    "false", "no", "0" -> false
                    else -> portChanged
                }
                val restartResult = if (wantRestart) McpServer.restart("config_set") else null
                McpServer.notifyToolsChanged()
                ToolOutput.ok(
                    Json.obj(
                        "ok" to true,
                        "changed" to patch.keys.toList(),
                        "restarted" to wantRestart,
                        "restartResult" to restartResult,
                        "settings" to after.toPublicMap(),
                        "server" to McpServer.status(),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_mcp_tools",
            group = ToolGroups.MCP,
            description = "列出全部 MCP 工具的完整目录：名字、分组、是否启用、被禁用的原因、描述与输入 schema。用于确认「MCP 覆盖整个项目软件」的具体能力面。",
            inputSchema = Json.schema(
                listOf(
                    Triple("group", "string", "可选：只看某个分组（ide/project/file/build/deploy/shell/editor/lsp/mcp）"),
                    Triple("includeSchema", "boolean", "默认 false：附带 inputSchema"),
                    Triple("onlyEnabled", "boolean", "默认 false"),
                ),
            ),
            handler = { args ->
                val group = Json.strOf(args, "group")?.trim()?.takeIf { it.isNotEmpty() }
                val withSchema = Json.boolOf(args, "includeSchema", false)
                val onlyEnabled = Json.boolOf(args, "onlyEnabled", false)
                val items = ArrayList<Map<String, Any?>>()
                for (spec in ToolRegistry.allSpecs()) {
                    if (group != null && spec.group != group) continue
                    val (allowed, reason) = ToolRegistry.isAllowed(spec.name)
                    if (onlyEnabled && !allowed) continue
                    val m = LinkedHashMap<String, Any?>()
                    m["name"] = spec.name
                    m["group"] = spec.group
                    m["enabled"] = allowed
                    if (!allowed) m["disabledReason"] = reason
                    m["description"] = spec.description
                    if (withSchema) m["inputSchema"] = spec.inputSchema
                    items.add(m)
                }
                ToolOutput.ok(
                    Json.obj(
                        "count" to items.size,
                        "registered" to ToolRegistry.size(),
                        "enabled" to ToolRegistry.enabledSpecs().size,
                        "groupFilter" to group,
                        "byGroup" to ToolRegistry.byGroup(),
                        "tools" to items,
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_mcp_calls",
            group = ToolGroups.MCP,
            description = "查看最近的工具调用记录（调试页签的数据源）：工具名、参数摘要、耗时、成功与否、错误详情、调用来源。",
            inputSchema = Json.schema(
                listOf(
                    Triple("n", "integer", "默认 50"),
                    Triple("tool", "string", "可选：只看某个工具"),
                    Triple("onlyFailed", "boolean", "默认 false"),
                    Triple("stats", "boolean", "默认 true：附带按工具聚合的统计"),
                ),
            ),
            handler = { args ->
                val n = Json.intOf(args, "n", 50).coerceIn(1, 200)
                val tool = Json.strOf(args, "tool")?.trim()?.takeIf { it.isNotEmpty() }
                val onlyFailed = Json.boolOf(args, "onlyFailed", false)
                var records = CallHistory.tail(n)
                if (tool != null) records = records.filter { it.tool == tool }
                if (onlyFailed) records = records.filter { !it.ok }
                val m = LinkedHashMap<String, Any?>()
                m["count"] = records.size
                m["calls"] = records.reversed().map { it.toMap() }
                if (Json.boolOf(args, "stats", true)) m["stats"] = CallHistory.stats()
                ToolOutput.ok(m)
            },
        ),
        ToolSpec(
            name = "acs_mcp_log",
            group = ToolGroups.MCP,
            description = "读取「输出与调试」控制台的日志（同时也是 logcat 的 ACS-MCP 标签）。可按级别过滤、限制行数，或先清空再读。",
            inputSchema = Json.schema(
                listOf(
                    Triple("n", "integer", "默认 200"),
                    Triple("level", Json.obj("type" to "string", "enum" to Json.arr("TRACE", "DEBUG", "INFO", "WARN", "ERROR")), "最低级别"),
                    Triple("clear", "boolean", "默认 false：读取前清空"),
                ),
            ),
            handler = { args ->
                if (Json.boolOf(args, "clear", false)) McpLog.clear()
                val n = Json.intOf(args, "n", 200).coerceIn(1, 5000)
                val level = Json.strOf(args, "level")
                val entries = McpLog.tail(n, level)
                ToolOutput.okText(
                    entries.joinToString("\n") { it.format() },
                    Json.obj("count" to entries.size, "level" to level, "lines" to entries.map { it.format() }),
                )
            },
        ),
        ToolSpec(
            name = "acs_mcp_log_clear",
            group = ToolGroups.MCP,
            description = "清空「输出与调试」控制台日志与工具调用历史。",
            inputSchema = Json.schema(listOf(Triple("calls", "boolean", "默认 true：同时清空调用历史"))),
            handler = { args ->
                McpLog.clear()
                val alsoCalls = Json.boolOf(args, "calls", true)
                if (alsoCalls) CallHistory.clear()
                ToolOutput.ok(Json.obj("ok" to true, "logCleared" to true, "callsCleared" to alsoCalls))
            },
        ),
        ToolSpec(
            name = "acs_mcp_start",
            group = ToolGroups.MCP,
            description = "启动 MCP 监听（端口/绑定取自设置）。返回监听结果与可用 endpoint。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(McpServer.start("tool:acs_mcp_start")) },
        ),
        ToolSpec(
            name = "acs_mcp_stop",
            group = ToolGroups.MCP,
            description = "停止 MCP 监听，断开所有会话。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(McpServer.stop("tool:acs_mcp_stop")) },
        ),
        ToolSpec(
            name = "acs_mcp_restart",
            group = ToolGroups.MCP,
            description = "重启 MCP 监听（改完端口/绑定/认证后用）。",
            inputSchema = Json.schema(emptyList()),
            handler = { ToolOutput.ok(McpServer.restart("tool:acs_mcp_restart")) },
        ),
        ToolSpec(
            name = "acs_mcp_endpoint",
            group = ToolGroups.MCP,
            description = "输出可直接粘贴到 MCP 客户端的连接信息：streamable-http / SSE endpoint、认证头、以及 Claude Desktop、Cursor、Cline、VS Code 的配置片段。",
            inputSchema = Json.schema(listOf(Triple("includeToken", "boolean", "默认 true：在配置片段中包含明文 token（本地使用）"))),
            handler = { args ->
                val s = McpSettingsStore.current
                val includeToken = Json.boolOf(args, "includeToken", true)
                val token = if (includeToken) s.authToken else "<token>"
                val endpoints = McpServer.endpoints()
                val primary = endpoints.firstOrNull() ?: "http://127.0.0.1:${s.port}/mcp"
                val lan = endpoints.firstOrNull { !it.contains("127.0.0.1") }
                val cursor = Json.obj("mcpServers" to Json.obj("acs" to Json.obj("url" to primary, "headers" to Json.obj("Authorization" to "Bearer $token"))))
                val cline = Json.obj("mcpServers" to Json.obj("acs" to Json.obj("type" to "streamableHttp", "url" to primary, "headers" to Json.obj("Authorization" to "Bearer $token"))))
                ToolOutput.ok(
                    Json.obj(
                        "running" to McpServer.isRunning,
                        "streamableHttp" to primary,
                        "legacySse" to (primary.removeSuffix("/mcp") + "/sse"),
                        "lanEndpoint" to lan,
                        "health" to (primary.removeSuffix("/mcp") + "/health"),
                        "authHeader" to "Authorization: Bearer $token",
                        "tokenIncluded" to includeToken,
                        "clientSnippets" to Json.obj("cursor" to cursor, "cline" to cline, "vscode" to cursor),
                        "note" to "Claude Desktop 只支持 stdio；请用任意 streamable-http 转 stdio 的桥接器（例如 mcp-remote）指向上面的 url。",
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_mcp_selftest",
            group = ToolGroups.MCP,
            description = "自检：在设备本地用真实 socket 走一遍 initialize -> tools/list -> tools/call(acs_ide_status)，验证监听、认证、协议与工具层是否都通。返回每一步的原始响应摘要。",
            inputSchema = Json.schema(listOf(Triple("timeoutMs", "integer", "默认 8000"))),
            handler = { args ->
                val timeout = Json.intOf(args, "timeoutMs", 8000).coerceIn(1000, 60000)
                val s = McpSettingsStore.current
                val steps = ArrayList<Map<String, Any?>>()
                var ok = true
                if (!McpServer.isRunning) {
                    return@ToolSpec ToolOutput.fail("server is not running; call acs_mcp_start first", McpServer.status())
                }
                val port = McpServer.status()["port"] as? Int ?: s.port
                val results = runLoopback(port, s.authToken, timeout)
                steps.addAll(results)
                for (r in results) if (Json.boolOf(r, "ok", false) != true) ok = false
                ToolOutput(
                    Json.pretty(Json.obj("ok" to ok, "port" to port, "steps" to steps)),
                    Json.obj("ok" to ok, "port" to port, "steps" to steps),
                    !ok,
                )
            },
        ),
    )

    private fun applyPatch(s: io.github.xsun71136.plugins.mcp.McpSettings, patch: Map<String, Any?>) {
        for ((k, v) in patch) {
            when (k) {
                "enabled" -> s.enabled = Json.boolOf(patch, k, s.enabled)
                "autoStart" -> s.autoStart = Json.boolOf(patch, k, s.autoStart)
                "showConsoleOnLaunch" -> s.showConsoleOnLaunch = Json.boolOf(patch, k, s.showConsoleOnLaunch)
                "bindAll" -> s.bindAll = Json.boolOf(patch, k, s.bindAll)
                "port" -> s.port = Json.intOf(patch, k, s.port).coerceIn(1024, 65535)
                "requireAuth" -> s.requireAuth = Json.boolOf(patch, k, s.requireAuth)
                "authToken" -> Json.str(v)?.let { s.authToken = it.trim() }
                "readOnly" -> s.readOnly = Json.boolOf(patch, k, s.readOnly)
                "maxBodyBytes" -> s.maxBodyBytes = Json.intOf(patch, k, s.maxBodyBytes).coerceIn(4096, 64 * 1024 * 1024)
                "requestTimeoutSec" -> s.requestTimeoutSec = Json.intOf(patch, k, s.requestTimeoutSec).coerceIn(5, 7200)
                "logLimit" -> s.logLimit = Json.intOf(patch, k, s.logLimit).coerceIn(50, 20000)
                "minLogLevel" -> Json.str(v)?.let { s.minLogLevel = it.uppercase() }
                "serverName" -> Json.str(v)?.let { s.serverName = it }
                "serverVersion" -> Json.str(v)?.let { s.serverVersion = it }
                "protocolVersion" -> Json.str(v)?.let { s.protocolVersion = it }
                "shellCommand" -> Json.str(v)?.let { s.shellCommand = it }
                "shellArgs" -> Json.str(v)?.let { s.shellArgs = it }
                "gradleCommand" -> Json.str(v)?.let { s.gradleCommand = it }
                "gradleJvmArgs" -> Json.str(v)?.let { s.gradleJvmArgs = it }
                "hostShell" -> Json.str(v)?.let { s.hostShell = it }
                "adbCommand" -> Json.str(v)?.let { s.adbCommand = it }
                "extraEnv" -> {
                    s.extraEnv.clear()
                    Json.asMap(v).forEach { (ek, ev) -> Json.str(ev)?.let { s.extraEnv[ek] = it } }
                }
                "toolGroups" -> {
                    Json.asMap(v).forEach { (gk, gv) -> s.toolGroups[gk] = Json.boolOf(mapOf("v" to gv), "v", true) }
                }
                "allowedTools" -> { s.allowedTools.clear(); s.allowedTools.addAll(Json.strList(patch, k)) }
                "deniedTools" -> { s.deniedTools.clear(); s.deniedTools.addAll(Json.strList(patch, k)) }
                else -> McpLog.warn(TAG, "ignoring unknown settings key '$k'")
            }
        }
    }

    /** Real socket round-trip against our own listener. */
    private fun runLoopback(port: Int, token: String, timeoutMs: Int): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        out.add(rpc(port, token, timeoutMs, "initialize", 1, Json.obj(
            "protocolVersion" to "2025-06-18",
            "capabilities" to Json.obj(),
            "clientInfo" to Json.obj("name" to "acs-mcp-selftest", "version" to "1.0"),
        )))
        out.add(rpc(port, token, timeoutMs, "tools/list", 2, Json.obj()))
        out.add(rpc(port, token, timeoutMs, "tools/call", 3, Json.obj("name" to "acs_ide_status", "arguments" to Json.obj())))
        return out
    }

    private fun rpc(port: Int, token: String, timeoutMs: Int, method: String, id: Int, params: Map<String, Any?>): Map<String, Any?> {
        val started = System.currentTimeMillis()
        val body = Json.stringify(Json.obj("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        val req = StringBuilder()
        req.append("POST /mcp HTTP/1.1\r\n")
        req.append("Host: 127.0.0.1:").append(port).append("\r\n")
        req.append("Accept: application/json\r\n")
        req.append("Content-Type: application/json\r\n")
        if (token.isNotEmpty()) req.append("Authorization: Bearer ").append(token).append("\r\n")
        req.append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
        req.append("Connection: close\r\n\r\n")
        req.append(body)
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.outputStream.write(req.toString().toByteArray())
            socket.outputStream.flush()
            val raw = socket.inputStream.readBytes()
            socket.close()
            val text = String(raw, Charsets.UTF_8)
            val statusLine = text.substringBefore("\r\n")
            val payload = text.substringAfter("\r\n\r\n", "")
            val parsed = Json.parseOrNull(payload)
            val result = Json.asMap(parsed)
            val hasResult = result.containsKey("result")
            val err = Json.asMap(result["error"])
            Json.obj(
                "step" to method,
                "ok" to (statusLine.contains(" 200 ") && (hasResult || err.isEmpty())),
                "statusLine" to statusLine,
                "durationMs" to (System.currentTimeMillis() - started),
                "error" to (if (err.isEmpty()) null else err),
                "resultKeys" to Json.asMap(result["result"]).keys.toList(),
                "protocolVersion" to Json.strOf(Json.asMap(result["result"]), "protocolVersion"),
                "toolCount" to (Json.asList(Json.asMap(result["result"])["tools"]).size.takeIf { method == "tools/list" }),
                "preview" to payload.take(400),
            )
        } catch (t: Throwable) {
            McpLog.warn(TAG, "selftest $method failed: ${t.message}")
            Json.obj(
                "step" to method,
                "ok" to false,
                "durationMs" to (System.currentTimeMillis() - started),
                "error" to "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }
}
