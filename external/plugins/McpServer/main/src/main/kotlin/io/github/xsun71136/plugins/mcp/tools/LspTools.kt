package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import java.io.File

/** lsp group: inspect and drive the IDE's language servers. */
object LspTools {

    private fun api() = com.nullij.androidcodestudio.plugins.api.PluginApi.lsp

    private fun guard(): String? =
        if (api() == null) "LSP API is null: ILspApi only exists inside EditorActivity (open a file first)" else null

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_lsp_status",
            group = ToolGroups.LSP,
            description = "语言服务器总览：已注册的语言、正在运行的语言、扩展名到语言 ID 的映射，以及指定语言是否已注册/正在运行。",
            inputSchema = Json.schema(listOf(Triple("languageId", "string", "可选：额外检查该语言的状态"))),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val m = LinkedHashMap<String, Any?>()
                m["available"] = lsp.getAvailableServers().sorted()
                m["running"] = lsp.getRunningServers().sorted()
                m["extensions"] = lsp.getRegisteredExtensions().toSortedMap()
                val lang = Json.strOf(args, "languageId")?.trim()?.takeIf { it.isNotEmpty() }
                if (lang != null) {
                    m["query"] = Json.obj(
                        "languageId" to lang,
                        "hasServer" to lsp.hasServer(lang),
                        "isRunning" to lsp.isServerRunning(lang),
                    )
                }
                ToolOutput.ok(m)
            },
        ),
        ToolSpec(
            name = "acs_lsp_start",
            group = ToolGroups.LSP,
            description = "启动指定语言的服务器（例如 kotlin / java / xml / dart / python）。返回是否成功或已在运行。",
            inputSchema = Json.schema(listOf(Triple("languageId", "string", null)), listOf("languageId")),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val lang = Json.reqStr(args, "languageId")
                val started = lsp.startServer(lang)
                ToolOutput.ok(Json.obj("ok" to started, "languageId" to lang, "running" to lsp.getRunningServers().sorted()))
            },
        ),
        ToolSpec(
            name = "acs_lsp_stop",
            group = ToolGroups.LSP,
            description = "停止指定语言的服务器。",
            inputSchema = Json.schema(listOf(Triple("languageId", "string", null)), listOf("languageId")),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val lang = Json.reqStr(args, "languageId")
                lsp.stopServer(lang)
                ToolOutput.ok(Json.obj("ok" to true, "languageId" to lang, "running" to lsp.getRunningServers().sorted()))
            },
        ),
        ToolSpec(
            name = "acs_lsp_stop_all",
            group = ToolGroups.LSP,
            description = "停止全部语言服务器（排障时使用，会中断补全与诊断）。",
            inputSchema = Json.schema(emptyList()),
            handler = {
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val before = lsp.getRunningServers().sorted()
                lsp.stopAllServers()
                ToolOutput.ok(Json.obj("ok" to true, "stopped" to before, "running" to lsp.getRunningServers().sorted()))
            },
        ),
        ToolSpec(
            name = "acs_lsp_detect",
            group = ToolGroups.LSP,
            description = "由文件名或路径推断语言 ID（detectLanguage），用于确认某个文件会被哪个语言服务器接管。",
            inputSchema = Json.schema(listOf(Triple("pathOrName", "string", "文件路径或文件名，如 Main.kt")), listOf("pathOrName")),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val name = Json.reqStr(args, "pathOrName")
                val byName = lsp.detectLanguage(name)
                val byFile = lsp.detectLanguage(File(name))
                ToolOutput.ok(
                    Json.obj(
                        "pathOrName" to name,
                        "languageIdByName" to byName,
                        "languageIdByFile" to byFile,
                        "languageId" to (byName ?: byFile),
                        "serverRegistered" to ((byName ?: byFile)?.let { lsp.hasServer(it) } ?: false),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_lsp_extensions",
            group = ToolGroups.LSP,
            description = "查看或修改扩展名到语言 ID 的映射：action=list 列出全部；action=register 关联 extension->languageId；action=unregister 移除关联。",
            inputSchema = Json.schema(
                listOf(
                    Triple("action", Json.obj("type" to "string", "enum" to Json.arr("list", "register", "unregister")), "默认 list"),
                    Triple("extension", "string", "register/unregister 需要，如 kt"),
                    Triple("languageId", "string", "register 需要，如 kotlin"),
                ),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val action = Json.strOf(args, "action", "list") ?: "list"
                when (action) {
                    "register" -> {
                        val ext = Json.reqStr(args, "extension")
                        val lang = Json.reqStr(args, "languageId")
                        lsp.registerExtension(ext, lang)
                    }
                    "unregister" -> lsp.unregisterExtension(Json.reqStr(args, "extension"))
                    "list" -> Unit
                    else -> return@ToolSpec ToolOutput.fail("unknown action '$action'")
                }
                ToolOutput.ok(Json.obj("ok" to true, "action" to action, "extensions" to lsp.getRegisteredExtensions().toSortedMap()))
            },
        ),
        ToolSpec(
            name = "acs_lsp_document",
            group = ToolGroups.LSP,
            description = "向语言服务器发送文档生命周期事件：action=open/close/changed。changed 需要 content 与递增的 version，用于让外部修改的文件重新参与诊断与补全。",
            inputSchema = Json.schema(
                listOf(
                    Triple("action", Json.obj("type" to "string", "enum" to Json.arr("open", "close", "changed")), null),
                    Triple("path", "string", "文件绝对路径"),
                    Triple("content", "string", "action=changed 必填：新的完整内容"),
                    Triple("version", "integer", "action=changed 必填：必须单调递增"),
                ),
                listOf("action", "path"),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val lsp = api() ?: return@ToolSpec ToolOutput.fail("lsp unavailable")
                val action = Json.reqStr(args, "action")
                val (file, err) = FileGuard.resolveChecked(Json.reqStr(args, "path"))
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                val result: Any = when (action) {
                    "open" -> lsp.openDocument(file)
                    "close" -> { lsp.closeDocument(file); true }
                    "changed" -> {
                        val content = Json.strOf(args, "content") ?: return@ToolSpec ToolOutput.fail("action=changed requires 'content'")
                        val version = Json.intOf(args, "version", -1)
                        if (version <= 0) return@ToolSpec ToolOutput.fail("action=changed requires a positive, increasing 'version'")
                        lsp.documentChanged(file, content, version)
                        true
                    }
                    else -> return@ToolSpec ToolOutput.fail("unknown action '$action'")
                }
                ToolOutput.ok(
                    Json.obj(
                        "ok" to true,
                        "action" to action,
                        "path" to file.absolutePath,
                        "languageId" to lsp.detectLanguage(file),
                        "result" to result,
                        "running" to lsp.getRunningServers().sorted(),
                    ),
                )
            },
        ),
    )
}
