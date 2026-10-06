package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json

/** editor group: drive the currently open file through IEditorApi. */
object EditorTools {

    private fun api() = com.nullij.androidcodestudio.plugins.api.PluginApi.editor

    private fun guard(): String? = try {
        if (!api().isAvailable()) "no editor is currently open (IEditorApi.isAvailable() == false)" else null
    } catch (t: Throwable) {
        "editor API unavailable: ${t.javaClass.simpleName}: ${t.message}"
    }

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_editor_status",
            group = ToolGroups.EDITOR,
            description = "当前编辑器状态：是否可用、光标行列（0 基与 1 基显示值）、是否有选区、选区文本长度、能否撤销/重做、全文行数与字符数。",
            inputSchema = Json.schema(emptyList()),
            handler = {
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val e = api()
                val text = e.getText()
                ToolOutput.ok(
                    Json.obj(
                        "available" to true,
                        "line" to e.getCurrentLine(),
                        "column" to e.getCurrentColumn(),
                        "lineDisplay" to e.getCurrentLineDisplay(),
                        "columnDisplay" to e.getCurrentColumnDisplay(),
                        "hasSelection" to e.hasSelection(),
                        "selectionLength" to (e.getSelectedText()?.length ?: 0),
                        "canUndo" to e.canUndo(),
                        "canRedo" to e.canRedo(),
                        "lineCount" to (text.count { it == '\n' } + 1),
                        "charCount" to text.length,
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_editor_read",
            group = ToolGroups.EDITOR,
            description = "读取当前打开文件的内容，支持 startLine/maxLines 分页。",
            inputSchema = Json.schema(
                listOf(
                    Triple("startLine", "integer", "1 基，默认 1"),
                    Triple("maxLines", "integer", "默认 1000"),
                    Triple("withLineNumbers", "boolean", "默认 false"),
                ),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val text = api().getText()
                val lines = text.split('\n')
                val start = Json.intOf(args, "startLine", 1).coerceIn(1, maxOf(1, lines.size))
                val max = Json.intOf(args, "maxLines", 1000).coerceIn(1, 50000)
                val from = start - 1
                val slice = lines.subList(from, minOf(lines.size, from + max))
                val numbered = Json.boolOf(args, "withLineNumbers", false)
                val body = if (numbered) slice.mapIndexed { i, l -> "${from + i + 1}| $l" }.joinToString("\n") else slice.joinToString("\n")
                ToolOutput.okText(
                    body,
                    Json.obj(
                        "totalLines" to lines.size,
                        "startLine" to start,
                        "returnedLines" to slice.size,
                        "truncated" to (from + slice.size < lines.size),
                        "content" to body,
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_editor_write",
            group = ToolGroups.EDITOR,
            description = "整体替换当前打开文件的内容（进入撤销历史）。",
            inputSchema = Json.schema(listOf(Triple("content", "string", "新的完整文本")), listOf("content")),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val content = Json.strOf(args, "content") ?: ""
                api().setText(content)
                ToolOutput.ok(Json.obj("ok" to true, "chars" to content.length, "lines" to (content.count { it == '\n' } + 1)))
            },
        ),
        ToolSpec(
            name = "acs_editor_insert",
            group = ToolGroups.EDITOR,
            description = "在光标处插入文本；给定 line/column（0 基）时先移动光标再插入。",
            inputSchema = Json.schema(
                listOf(
                    Triple("text", "string", null),
                    Triple("line", "integer", "可选：0 基行号"),
                    Triple("column", "integer", "可选：0 基列号"),
                ),
                listOf("text"),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val e = api()
                if (args.containsKey("line") || args.containsKey("column")) {
                    e.setCursor(Json.intOf(args, "line", e.getCurrentLine()), Json.intOf(args, "column", e.getCurrentColumn()))
                }
                val text = Json.strOf(args, "text") ?: ""
                e.insertText(text)
                ToolOutput.ok(Json.obj("ok" to true, "insertedChars" to text.length, "line" to e.getCurrentLine(), "column" to e.getCurrentColumn()))
            },
        ),
        ToolSpec(
            name = "acs_editor_replace",
            group = ToolGroups.EDITOR,
            description = "在当前文件内做文本替换（读全文 -> 替换 -> setText，因此整步可撤销）。支持字面量或正则、全部或仅首个、区分大小写。返回替换次数。",
            inputSchema = Json.schema(
                listOf(
                    Triple("find", "string", null),
                    Triple("replace", "string", null),
                    Triple("all", "boolean", "默认 true"),
                    Triple("regex", "boolean", "默认 false"),
                    Triple("caseSensitive", "boolean", "默认 true"),
                ),
                listOf("find", "replace"),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val find = Json.reqStr(args, "find")
                val replace = Json.strOf(args, "replace") ?: ""
                val all = Json.boolOf(args, "all", true)
                val regex = Json.boolOf(args, "regex", false)
                val caseSensitive = Json.boolOf(args, "caseSensitive", true)
                val text = api().getText()
                var count = 0
                val newText: String = if (regex) {
                    val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    val rx = runCatching { Regex(find, opts) }.getOrElse { t -> return@ToolSpec ToolOutput.fail("bad regex: ${t.message}") }
                    count = rx.findAll(text).count()
                    if (all) rx.replace(text, replace) else rx.replaceFirst(text, replace)
                } else {
                    val hay = if (caseSensitive) text else text.lowercase()
                    val needle = if (caseSensitive) find else find.lowercase()
                    if (needle.isEmpty()) return@ToolSpec ToolOutput.fail("'find' must not be empty")
                    var idx = hay.indexOf(needle)
                    count = if (idx < 0) 0 else if (all) {
                        var c = 0
                        var pos = 0
                        while (true) {
                            val i = hay.indexOf(needle, pos)
                            if (i < 0) break
                            c++
                            pos = i + needle.length
                        }
                        c
                    } else 1
                    when {
                        count == 0 -> text
                        all && caseSensitive -> text.replace(find, replace)
                        all -> {
                            val rx = Regex(Regex.escape(find), setOf(RegexOption.IGNORE_CASE))
                            rx.replace(text, replace)
                        }
                        caseSensitive -> text.replaceFirst(find, replace)
                        else -> Regex(Regex.escape(find), setOf(RegexOption.IGNORE_CASE)).replaceFirst(text, replace)
                    }
                }
                if (count == 0) return@ToolSpec ToolOutput.fail("no occurrence of 'find' in the current file")
                api().setText(newText)
                ToolOutput.ok(Json.obj("ok" to true, "replacements" to count, "regex" to regex, "charsBefore" to text.length, "charsAfter" to newText.length))
            },
        ),
        ToolSpec(
            name = "acs_editor_cursor",
            group = ToolGroups.EDITOR,
            description = "读取或设置光标位置。不传 line/column 时只读取；传了就移动（0 基，越界自动收敛）。",
            inputSchema = Json.schema(listOf(Triple("line", "integer", "0 基"), Triple("column", "integer", "0 基"))),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val e = api()
                if (args.containsKey("line") || args.containsKey("column")) {
                    e.setCursor(Json.intOf(args, "line", e.getCurrentLine()), Json.intOf(args, "column", e.getCurrentColumn()))
                }
                ToolOutput.ok(
                    Json.obj(
                        "line" to e.getCurrentLine(),
                        "column" to e.getCurrentColumn(),
                        "lineDisplay" to e.getCurrentLineDisplay(),
                        "columnDisplay" to e.getCurrentColumnDisplay(),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_editor_selection",
            group = ToolGroups.EDITOR,
            description = "读取当前选区文本；selectAll=true 时先全选；replace 非空时用其替换选区。",
            inputSchema = Json.schema(
                listOf(
                    Triple("selectAll", "boolean", "默认 false"),
                    Triple("replace", "string", "可选：替换选区的内容"),
                ),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val e = api()
                if (Json.boolOf(args, "selectAll", false)) e.selectAll()
                val selected = e.getSelectedText()
                val replacement = Json.strOf(args, "replace")
                if (replacement != null) {
                    if (selected != null) e.deleteSelection()
                    e.insertText(replacement)
                }
                ToolOutput.ok(
                    Json.obj(
                        "hasSelection" to e.hasSelection(),
                        "selectedText" to selected,
                        "selectedLength" to (selected?.length ?: 0),
                        "replaced" to (replacement != null),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_editor_command",
            group = ToolGroups.EDITOR,
            description = "执行一个编辑器命令：undo / redo / format / copy / cut / paste / selectAll / deleteSelection。format 会调用当前语言服务器的格式化能力。",
            inputSchema = Json.schema(
                listOf(
                    Triple(
                        "command",
                        Json.obj("type" to "string", "enum" to Json.arr("undo", "redo", "format", "copy", "cut", "paste", "selectAll", "deleteSelection")),
                        null,
                    ),
                ),
                listOf("command"),
            ),
            handler = { args ->
                val g = guard()
                if (g != null) return@ToolSpec ToolOutput.fail(g)
                val e = api()
                val cmd = Json.reqStr(args, "command")
                when (cmd) {
                    "undo" -> if (e.canUndo()) e.undo() else return@ToolSpec ToolOutput.fail("nothing to undo")
                    "redo" -> if (e.canRedo()) e.redo() else return@ToolSpec ToolOutput.fail("nothing to redo")
                    "format" -> e.formatDocument()
                    "copy" -> e.copy()
                    "cut" -> e.cut()
                    "paste" -> e.paste()
                    "selectAll" -> e.selectAll()
                    "deleteSelection" -> e.deleteSelection()
                    else -> return@ToolSpec ToolOutput.fail("unknown command '$cmd'")
                }
                ToolOutput.ok(Json.obj("ok" to true, "command" to cmd, "canUndo" to e.canUndo(), "canRedo" to e.canRedo()))
            },
        ),
    )

    /** Convenience for the self-test in the console. */
    fun probe(): String {
        val g = guard() ?: return "editor available: line=${api().getCurrentLine()} col=${api().getCurrentColumn()}"
        return "editor unavailable: $g"
    }
}
