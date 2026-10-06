package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json

/** One MCP tool definition plus its implementation. */
data class ToolSpec(
    val name: String,
    val group: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val handler: (Map<String, Any?>) -> ToolOutput,
)

/**
 * Result of a tools/call. `text` is always present (MCP clients render it),
 * `data` additionally populates structuredContent for clients that use it.
 */
class ToolOutput(
    val text: String,
    val data: Map<String, Any?>? = null,
    val isError: Boolean = false,
) {
    fun toResultMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["content"] = Json.arr(Json.obj("type" to "text", "text" to text))
        if (isError) m["isError"] = true
        if (data != null) m["structuredContent"] = data
        return m
    }

    companion object {
        fun ok(data: Map<String, Any?>): ToolOutput = ToolOutput(Json.pretty(data), data, false)
        fun text(s: String): ToolOutput = ToolOutput(s, null, false)
        fun okText(s: String, data: Map<String, Any?>? = null): ToolOutput = ToolOutput(s, data, false)
        fun fail(msg: String, data: Map<String, Any?>? = null): ToolOutput =
            ToolOutput(msg, data ?: Json.obj("ok" to false, "error" to msg), true)
    }
}

/** One recorded invocation, shown in the 调试 tab of the console. */
data class CallRecord(
    val seq: Long,
    val time: Long,
    val tool: String,
    val argsSummary: String,
    val ok: Boolean,
    val durationMs: Long,
    val detail: String,
    val origin: String,
) {
    fun toMap(): Map<String, Any?> = Json.obj(
        "seq" to seq,
        "time" to time,
        "tool" to tool,
        "args" to argsSummary,
        "ok" to ok,
        "durationMs" to durationMs,
        "detail" to detail,
        "origin" to origin,
    )
}

/** Bounded invocation history for the debug tab and for acs_mcp_calls. */
object CallHistory {
    private const val LIMIT = 200
    private val items = ArrayDeque<CallRecord>()
    private var counter = 0L

    @Synchronized
    fun add(r: CallRecord) {
        items.addLast(r)
        while (items.size > LIMIT) items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<CallRecord> = items.toList()

    @Synchronized
    fun tail(n: Int): List<CallRecord> {
        val all = items.toList()
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun nextSeq(): Long = ++counter

    @Synchronized
    fun stats(): Map<String, Any?> {
        val total = items.size
        val failed = items.count { !it.ok }
        val byTool = items.groupingBy { it.tool }.eachCount()
        return Json.obj(
            "recorded" to total,
            "failed" to failed,
            "byTool" to byTool.toSortedMap(),
        )
    }
}

/**
 * Central registry. Visibility is decided per call from McpSettingsStore, so
 * toggling a group / the read-only mode / the allow list in the settings page
 * takes effect on the very next tools/list without restarting anything.
 */
object ToolRegistry {

    private const val TAG = "tools"
    private val tools = LinkedHashMap<String, ToolSpec>()

    @Synchronized
    fun registerAll(specs: List<ToolSpec>) {
        for (s in specs) tools[s.name] = s
        verifyReadOnlyCoverage(specs)
    }

    @Synchronized
    fun allSpecs(): List<ToolSpec> = tools.values.toList().sortedBy { it.name }

    /** Fail loudly (in the console log) if the read-only deny list drifts from reality. */
    private fun verifyReadOnlyCoverage(specs: List<ToolSpec>) {
        val known = tools.keys
        val missing = ToolGroups.MUTATING.filter { !known.contains(it) }
        if (missing.isNotEmpty()) {
            McpLog.warn(TAG, "ToolGroups.MUTATING lists unknown tool names: $missing")
        }
        val unknownGroups = specs.map { it.group }.distinct().filter { !ToolGroups.ALL.contains(it) }
        if (unknownGroups.isNotEmpty()) {
            McpLog.warn(TAG, "tools registered with undeclared groups: $unknownGroups")
        }
    }

    @Synchronized
    fun names(): List<String> = tools.keys.toList()

    @Synchronized
    fun find(name: String): ToolSpec? = tools[name]

    @Synchronized
    fun size(): Int = tools.size

    fun enabledSpecs(): List<ToolSpec> {
        val s = McpSettingsStore.current
        return synchronized(this) { tools.values.toList() }
            .filter { s.isToolEnabled(it.name, it.group) }
            .sortedBy { it.name }
    }

    fun definitions(): List<Map<String, Any?>> = enabledSpecs().map {
        Json.obj(
            "name" to it.name,
            "description" to it.description,
            "inputSchema" to it.inputSchema,
        )
    }

    /** Group -> tool names, used by the settings page and by acs_mcp_tools. */
    fun byGroup(): Map<String, List<String>> {
        val all = synchronized(this) { tools.values.toList() }
        val map = LinkedHashMap<String, List<String>>()
        for (g in ToolGroups.ALL) {
            map[g] = all.filter { it.group == g }.map { it.name }.sorted()
        }
        return map
    }

    fun isAllowed(name: String): Pair<Boolean, String> {
        val spec = find(name) ?: return false to "unknown tool '$name'"
        val s = McpSettingsStore.current
        if (!s.enabled) return false to "MCP server is disabled in settings"
        if (s.deniedTools.contains(name)) return false to "tool '$name' is on the deny list"
        if (s.allowedTools.isNotEmpty() && !s.allowedTools.contains(name)) return false to "tool '$name' is not on the allow list"
        if (!s.groupEnabled(spec.group)) return false to "tool group '${spec.group}' is disabled"
        if (s.readOnly && ToolGroups.MUTATING.contains(name)) return false to "read-only mode is on; '$name' would modify state"
        return true to ""
    }

    fun invoke(name: String, args: Map<String, Any?>, origin: String = "mcp"): ToolOutput {
        val started = System.currentTimeMillis()
        val (allowed, reason) = isAllowed(name)
        if (!allowed) {
            record(name, args, false, started, reason, origin)
            McpLog.warn(TAG, "refused $name: $reason")
            return ToolOutput.fail("refused: $reason")
        }
        val spec = find(name) ?: return ToolOutput.fail("unknown tool '$name'")
        return try {
            val out = spec.handler(args)
            record(name, args, !out.isError, started, if (out.isError) out.text.take(300) else "ok", origin)
            McpLog.debug(TAG, "$name -> ${if (out.isError) "error" else "ok"} in ${System.currentTimeMillis() - started}ms")
            out
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            record(name, args, false, started, msg, origin)
            McpLog.error(TAG, "$name threw", t)
            ToolOutput.fail("tool '$name' failed: $msg", Json.obj("ok" to false, "error" to msg, "tool" to name))
        }
    }

    private fun record(name: String, args: Map<String, Any?>, ok: Boolean, started: Long, detail: String, origin: String) {
        val summary = runCatching { Json.stringify(args) }.getOrElse { "{}" }
        CallHistory.add(
            CallRecord(
                seq = CallHistory.nextSeq(),
                time = System.currentTimeMillis(),
                tool = name,
                argsSummary = ProcResultClamp.clamp(summary, 400),
                ok = ok,
                durationMs = System.currentTimeMillis() - started,
                detail = ProcResultClamp.clamp(detail, 400),
                origin = origin,
            ),
        )
    }
}

private object ProcResultClamp {
    fun clamp(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max) + "…"
}
