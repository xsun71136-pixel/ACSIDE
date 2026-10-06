package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.util.Fs
import java.io.File

/** file group: read / write / search / delete across the whole project. */
object FileTools {

    private const val TAG = "file"
    private val DEFAULT_SKIP = listOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", ".cxx")

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_file_list",
            group = ToolGroups.FILE,
            description = "列出一个目录的直接子项（名字、类型、大小、修改时间、读写权限）。path 可省略以列出当前项目根目录。",
            inputSchema = Json.schema(
                listOf(
                    Triple("path", "string", "目录路径，绝对或相对当前项目；省略则用当前打开的项目目录"),
                    Triple("maxEntries", "integer", "最多返回条目数，默认 300"),
                ),
            ),
            handler = { args ->
                val dir = targetDir(args) ?: return@ToolSpec ToolOutput.fail("no directory: pass 'path' or open a project first")
                ToolOutput.ok(Fs.listing(dir, Json.intOf(args, "maxEntries", 300)))
            },
        ),
        ToolSpec(
            name = "acs_file_stat",
            group = ToolGroups.FILE,
            description = "返回单个文件或目录的元信息：是否存在、类型、字节数、行数（文本）、修改时间、权限、SHA-256（小文件）。",
            inputSchema = Json.schema(listOf(Triple("path", "string", null), Triple("hash", "boolean", "默认 true：计算 SHA-256（>8MB 时跳过）")), listOf("path")),
            handler = { args ->
                val f = Json.reqStr(args, "path")
                val (file, err) = FileGuard.resolveChecked(f)
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                val m = LinkedHashMap<String, Any?>()
                m["path"] = file.absolutePath
                m["exists"] = file.exists()
                m["isDirectory"] = file.isDirectory
                m["isFile"] = file.isFile
                m["size"] = file.length()
                m["sizeHuman"] = Fs.human(file.length())
                m["lastModified"] = file.lastModified()
                m["canRead"] = file.canRead()
                m["canWrite"] = file.canWrite()
                if (file.isFile) {
                    runCatching {
                        val text = file.readText()
                        m["lineCount"] = text.count { it == '\n' } + if (text.isNotEmpty()) 1 else 0
                        m["charCount"] = text.length
                    }
                    if (Json.boolOf(args, "hash", true) && file.length() <= 8L * 1024 * 1024) {
                        runCatching {
                            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                            m["sha256"] = digest.joinToString("") { b -> "%02x".format(b) }
                        }
                    }
                }
                ToolOutput.ok(m)
            },
        ),
        ToolSpec(
            name = "acs_file_read",
            group = ToolGroups.FILE,
            description = "读取文本文件内容，支持按行分页（startLine/maxLines）与字节上限；返回总行数、是否截断。二进制文件会被识别并拒绝。",
            inputSchema = Json.schema(
                listOf(
                    Triple("path", "string", null),
                    Triple("startLine", "integer", "起始行，1 基，默认 1"),
                    Triple("maxLines", "integer", "最多读取行数，默认 500"),
                    Triple("maxBytes", "integer", "最多读取字节数，默认 262144"),
                    Triple("withLineNumbers", "boolean", "默认 false：为每行加上 '行号| ' 前缀"),
                ),
                listOf("path"),
            ),
            handler = { args ->
                val (file, err) = FileGuard.resolveChecked(Json.reqStr(args, "path"))
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                if (!file.exists()) return@ToolSpec ToolOutput.fail("not found: ${file.absolutePath}")
                if (file.isDirectory) return@ToolSpec ToolOutput.fail("is a directory: ${file.absolutePath} (use acs_file_list)")
                val startLine = Json.intOf(args, "startLine", 1).coerceAtLeast(1)
                val maxLines = Json.intOf(args, "maxLines", 500).coerceIn(1, 20000)
                val maxBytes = Json.intOf(args, "maxBytes", 262144).coerceIn(64, 8 * 1024 * 1024)
                val numbered = Json.boolOf(args, "withLineNumbers", false)
                val bytes = file.inputStream().use { input ->
                    val buf = ByteArray(minOf(maxBytes + 1, maxOf(file.length().toInt(), 1) + 1))
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read == buf.size) buf.copyOf(read) else buf.copyOf(read)
                }
                val limitHit = file.length() > bytes.size.toLong()
                if (bytes.size >= 8000 && bytes.take(8000).any { it == 0.toByte() }) {
                    return@ToolSpec ToolOutput.fail(
                        "binary file (${bytes.size} bytes, contains NUL): ${file.absolutePath}",
                        Json.obj("path" to file.absolutePath, "binary" to true, "size" to file.length()),
                    )
                }
                val text = String(bytes, Charsets.UTF_8)
                val lines = text.split('\n')
                val from = (startLine - 1).coerceAtMost(lines.size)
                val slice = lines.subList(from, minOf(lines.size, from + maxLines))
                val body = if (numbered) {
                    slice.mapIndexed { idx, l -> "${from + idx + 1}| $l" }.joinToString("\n")
                } else slice.joinToString("\n")
                ToolOutput.okText(
                    body,
                    Json.obj(
                        "path" to file.absolutePath,
                        "totalLines" to lines.size,
                        "startLine" to startLine,
                        "returnedLines" to slice.size,
                        "truncatedByLines" to (from + slice.size < lines.size),
                        "truncatedByBytes" to limitHit,
                        "size" to file.length(),
                        "content" to body,
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_file_write",
            group = ToolGroups.FILE,
            description = "写入文本文件。mode=overwrite 覆盖（默认先备份为 <name>.mcp-bak），mode=append 追加。自动创建父目录。只读模式下被拒绝。",
            inputSchema = Json.schema(
                listOf(
                    Triple("path", "string", null),
                    Triple("content", "string", "要写入的完整文本（append 模式为追加内容）"),
                    Triple("mode", Json.obj("type" to "string", "enum" to Json.arr("overwrite", "append")), "默认 overwrite"),
                    Triple("backup", "boolean", "默认 true：覆盖前把原文件备份成 <name>.mcp-bak"),
                    Triple("createDirs", "boolean", "默认 true"),
                ),
                listOf("path", "content"),
            ),
            handler = { args ->
                val (file, err) = FileGuard.resolveChecked(Json.reqStr(args, "path"))
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                val content = Json.strOf(args, "content") ?: ""
                val mode = Json.strOf(args, "mode", "overwrite") ?: "overwrite"
                val existed = file.exists()
                val previousSize = if (existed) file.length() else 0L
                if (existed && mode == "overwrite" && Json.boolOf(args, "backup", true)) {
                    runCatching { file.copyTo(File(file.parentFile, file.name + ".mcp-bak"), overwrite = true) }
                        .onFailure { t -> McpLog.warn(TAG, "backup failed: ${t.message}") }
                }
                if (Json.boolOf(args, "createDirs", true)) file.parentFile?.mkdirs()
                runCatching {
                    if (mode == "append") file.appendText(content) else file.writeText(content)
                }.onFailure { t -> return@ToolSpec ToolOutput.fail("write failed: ${t.javaClass.simpleName}: ${t.message}") }
                McpLog.info(TAG, "$mode ${file.absolutePath} (${previousSize} -> ${file.length()} bytes)")
                ToolOutput.ok(
                    Json.obj(
                        "ok" to true,
                        "path" to file.absolutePath,
                        "mode" to mode,
                        "created" to !existed,
                        "previousSize" to previousSize,
                        "newSize" to file.length(),
                        "backup" to (existed && mode == "overwrite" && Json.boolOf(args, "backup", true)),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_file_mkdir",
            group = ToolGroups.FILE,
            description = "创建目录（含所有缺失的父目录）。",
            inputSchema = Json.schema(listOf(Triple("path", "string", null)), listOf("path")),
            handler = { args ->
                val (file, err) = FileGuard.resolveChecked(Json.reqStr(args, "path"))
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                val created = file.mkdirs()
                ToolOutput.ok(Json.obj("ok" to (created || file.isDirectory), "path" to file.absolutePath, "created" to created, "isDirectory" to file.isDirectory))
            },
        ),
        ToolSpec(
            name = "acs_file_delete",
            group = ToolGroups.FILE,
            description = "删除文件或空目录。目录非空时需要 recursive=true。只读模式下被拒绝。",
            inputSchema = Json.schema(
                listOf(Triple("path", "string", null), Triple("recursive", "boolean", "默认 false")),
                listOf("path"),
            ),
            handler = { args ->
                val (file, err) = FileGuard.resolveChecked(Json.reqStr(args, "path"))
                if (file == null) return@ToolSpec ToolOutput.fail(err ?: "bad path")
                if (!file.exists()) return@ToolSpec ToolOutput.fail("not found: ${file.absolutePath}")
                val recursive = Json.boolOf(args, "recursive", false)
                val removed = if (file.isDirectory && recursive) file.deleteRecursively() else file.delete()
                McpLog.info(TAG, "delete ${file.absolutePath} -> $removed")
                ToolOutput.ok(Json.obj("ok" to removed, "path" to file.absolutePath, "recursive" to recursive, "stillExists" to file.exists()))
            },
        ),
        ToolSpec(
            name = "acs_file_search",
            group = ToolGroups.FILE,
            description = "在项目内做文件名/内容搜索。返回命中文件、行号与该行内容，限制命中数与扫描文件大小；默认跳过 build/.gradle/.git/.idea 等目录。",
            inputSchema = Json.schema(
                listOf(
                    Triple("query", "string", "要查找的文本或正则"),
                    Triple("root", "string", "搜索根目录，默认当前项目目录"),
                    Triple("regex", "boolean", "默认 false：按字面子串匹配"),
                    Triple("caseSensitive", "boolean", "默认 false"),
                    Triple("fileGlob", "string", "可选文件名过滤，如 *.kt / *.gradle.kts"),
                    Triple("contentSearch", "boolean", "默认 true：搜索文件内容；false 时只搜索文件名"),
                    Triple("maxHits", "integer", "默认 200"),
                    Triple("maxFileSizeBytes", "integer", "默认 2097152：超过该大小的文件跳过"),
                    Triple("maxFilesScanned", "integer", "默认 20000：扫描文件数上限"),
                    Triple("skipDirs", "array", "跳过的目录名列表，默认 build/.gradle/.git/.idea/.kotlin/node_modules/.cxx"),
                ),
                listOf("query"),
            ),
            handler = { args -> search(args) },
        ),
    )

    private fun targetDir(args: Map<String, Any?>): File? {
        val p = Json.strOf(args, "path")?.trim()
        if (!p.isNullOrEmpty()) {
            val (f, err) = FileGuard.resolveChecked(p)
            if (f == null) {
                McpLog.warn(TAG, err ?: "bad path")
                return null
            }
            return f
        }
        return IdeTools.resolveProjectDir(args)
    }

    private fun search(args: Map<String, Any?>): ToolOutput {
        val query = Json.reqStr(args, "query")
        val rootArg = Json.strOf(args, "root")?.trim()
        val root: File = if (!rootArg.isNullOrEmpty()) {
            val (f, err) = FileGuard.resolveChecked(rootArg)
            if (f == null) return ToolOutput.fail(err ?: "bad root")
            f
        } else {
            IdeTools.resolveProjectDir(args) ?: return ToolOutput.fail("no root: pass 'root' or open a project first")
        }
        if (!root.exists()) return ToolOutput.fail("root not found: ${root.absolutePath}")

        val useRegex = Json.boolOf(args, "regex", false)
        val caseSensitive = Json.boolOf(args, "caseSensitive", false)
        val contentSearch = Json.boolOf(args, "contentSearch", true)
        val maxHits = Json.intOf(args, "maxHits", 200).coerceIn(1, 5000)
        val maxFileSize = Json.intOf(args, "maxFileSizeBytes", 2 * 1024 * 1024).coerceIn(64, 64 * 1024 * 1024)
        val maxFiles = Json.intOf(args, "maxFilesScanned", 20000).coerceIn(10, 200000)
        val skipArg = Json.strList(args, "skipDirs")
        val skip = (if (skipArg.isEmpty()) DEFAULT_SKIP else skipArg).toHashSet()
        val glob = Json.strOf(args, "fileGlob")?.trim()?.takeIf { it.isNotEmpty() }
        val globRegex = glob?.let { Regex(globToRegex(it), RegexOption.IGNORE_CASE) }

        val nameMatcher: (String) -> Boolean
        if (useRegex) {
            val rx = runCatching {
                Regex(query, if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { t -> return ToolOutput.fail("bad regex: ${t.message}") }
            nameMatcher = { s -> rx.containsMatchIn(s) }
        } else {
            val q = if (caseSensitive) query else query.lowercase()
            nameMatcher = { s -> (if (caseSensitive) s else s.lowercase()).contains(q) }
        }
        val lineMatcher: (String) -> Boolean
        if (useRegex) {
            lineMatcher = nameMatcher
        } else {
            val rx = runCatching {
                Regex(Regex.escape(query), if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE))
            }.getOrElse { t -> return ToolOutput.fail("bad query: ${t.message}") }
            lineMatcher = { s -> rx.containsMatchIn(s) }
        }

        val fileHits = ArrayList<Map<String, Any?>>()
        val lineHits = ArrayList<Map<String, Any?>>()
        var scanned = 0
        var matchedFiles = 0
        var stopped = false

        walk(root, skip) { f ->
            if (stopped) return@walk
            if (scanned >= maxFiles) { stopped = true; return@walk }
            if (globRegex != null && !globRegex.matches(f.name)) return@walk
            scanned++
            val nameHit = nameMatcher(f.name)
            if (nameHit) {
                matchedFiles++
                if (fileHits.size < maxHits) {
                    fileHits.add(Json.obj("path" to f.absolutePath, "name" to f.name, "size" to f.length(), "lastModified" to f.lastModified()))
                }
            }
            if (!contentSearch) return@walk
            if (f.length() > maxFileSize.toLong()) return@walk
            val text = runCatching { f.readText() }.getOrNull() ?: return@walk
            if (text.isEmpty()) return@walk
            if (text.take(4096).indexOf('\u0000') >= 0) return@walk
            val lines = text.split('\n')
            var i = 0
            while (i < lines.size) {
                if (lineMatcher(lines[i])) {
                    if (!nameHit) matchedFiles++
                    if (lineHits.size < maxHits) {
                        lineHits.add(
                            Json.obj(
                                "path" to f.absolutePath,
                                "line" to (i + 1),
                                "text" to lines[i].trim().take(400),
                            ),
                        )
                    } else {
                        stopped = true
                        return@walk
                    }
                }
                i++
            }
        }

        return ToolOutput.ok(
            Json.obj(
                "root" to root.absolutePath,
                "query" to query,
                "regex" to useRegex,
                "caseSensitive" to caseSensitive,
                "contentSearch" to contentSearch,
                "fileGlob" to glob,
                "filesScanned" to scanned,
                "matchedFiles" to matchedFiles,
                "fileHits" to fileHits,
                "lineHits" to lineHits,
                "hitLimitReached" to stopped,
            ),
        )
    }

    private inline fun walk(root: File, skip: Set<String>, visit: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (skip.contains(c.name)) continue
                    stack.addLast(c)
                } else {
                    visit(c)
                }
            }
        }
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        return sb.append('$').toString()
    }
}
