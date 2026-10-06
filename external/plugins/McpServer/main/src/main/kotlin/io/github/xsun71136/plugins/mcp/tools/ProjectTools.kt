package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.util.Exec
import java.io.File

/** project group: enumerate and describe the software the IDE is working on. */
object ProjectTools {

    private val PROJECT_MARKERS = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradlew", "pubspec.yaml", ".git")

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_project_list",
            group = ToolGroups.PROJECT,
            description = "列出 IDE 项目根目录（外部存储 ~/AndroidCSProjects 与内部存储）下的全部项目，标注类型（Gradle/Flutter）、是否含 wrapper、是否有 .git、是否当前打开、最近修改时间。",
            inputSchema = Json.schema(
                listOf(
                    Triple("root", "string", "可选：指定项目根目录，默认扫描 IDE 的两个项目根"),
                    Triple("maxEntries", "integer", "默认 200"),
                ),
            ),
            handler = { args ->
                val roots: List<File> = Json.strOf(args, "root")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(FileGuard.resolve(it)) } ?: Exec.projectsDirs()
                if (roots.isEmpty()) return@ToolSpec ToolOutput.fail("no project roots available (IDE environment not initialised?)")
                val maxEntries = Json.intOf(args, "maxEntries", 200).coerceIn(1, 2000)
                val openDir = runCatching { com.nullij.androidcodestudio.plugins.api.PluginApi.environment.openProjectDir?.canonicalPath }.getOrNull()
                val items = ArrayList<Map<String, Any?>>()
                val seen = HashSet<String>()
                for (root in roots) {
                    if (!root.exists() || !root.isDirectory) continue
                    val children = root.listFiles()?.sortedByDescending { it.lastModified() } ?: continue
                    for (c in children) {
                        if (!c.isDirectory) continue
                        if (items.size >= maxEntries) break
                        val key = runCatching { c.canonicalPath }.getOrElse { c.absolutePath }
                        if (!seen.add(key)) continue
                        items.add(describeProject(c, openDir))
                    }
                }
                ToolOutput.ok(
                    Json.obj(
                        "roots" to roots.map { it.absolutePath },
                        "count" to items.size,
                        "openProjectDir" to openDir,
                        "projects" to items,
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_project_info",
            group = ToolGroups.PROJECT,
            description = "解析一个项目的构建配置：settings.gradle 的 include 模块列表、gradle.properties 关键项、local.properties 的 sdk.dir、app 模块的 applicationId/versionName/versionCode/minSdk/targetSdk/compileSdk、已存在的 APK 产物、以及 .git 状态摘要。projectDir 省略时用当前打开的项目。",
            inputSchema = Json.schema(listOf(Triple("projectDir", "string", "项目根目录，省略则用当前打开的项目"))),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                if (!dir.exists()) return@ToolSpec ToolOutput.fail("project dir not found: ${dir.absolutePath}")
                ToolOutput.ok(describe(dir))
            },
        ),
        ToolSpec(
            name = "acs_project_tree",
            group = ToolGroups.PROJECT,
            description = "输出项目目录树（有界）。默认深度 3、最多 800 项，并跳过 build/.gradle/.git/.idea 等生成目录。返回缩进文本与结构化条目。",
            inputSchema = Json.schema(
                listOf(
                    Triple("projectDir", "string", "省略则用当前打开的项目"),
                    Triple("depth", "integer", "默认 3"),
                    Triple("maxEntries", "integer", "默认 800"),
                    Triple("includeHidden", "boolean", "默认 false"),
                    Triple("skipDirs", "array", "跳过的目录名，默认 build/.gradle/.git/.idea/.kotlin/node_modules/.cxx"),
                ),
            ),
            handler = { args ->
                val root = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                if (!root.exists()) return@ToolSpec ToolOutput.fail("not found: ${root.absolutePath}")
                val depth = Json.intOf(args, "depth", 3).coerceIn(1, 12)
                val maxEntries = Json.intOf(args, "maxEntries", 800).coerceIn(10, 20000)
                val includeHidden = Json.boolOf(args, "includeHidden", false)
                val skipArg = Json.strList(args, "skipDirs")
                val skip = (if (skipArg.isEmpty()) listOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", ".cxx") else skipArg).toHashSet()

                val lines = ArrayList<String>()
                var count = 0
                var truncated = false

                fun recurse(f: File, level: Int, prefix: String) {
                    if (truncated) return
                    if (level > depth) return
                    val children = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
                    val visible = children.filter { includeHidden || !it.name.startsWith(".") || skip.contains(it.name) }
                    var idx = 0
                    for (c in visible) {
                        if (count >= maxEntries) { truncated = true; return }
                        if (c.isDirectory && skip.contains(c.name)) {
                            lines.add("$prefix${c.name}/  (skipped)")
                            idx++
                            continue
                        }
                        val last = idx == visible.size - 1
                        val branch = if (last) "└── " else "├── "
                        val suffix = if (c.isDirectory) "/" else "  ${c.length()}B"
                        lines.add("$prefix$branch${c.name}$suffix")
                        count++
                        idx++
                        if (c.isDirectory) recurse(c, level + 1, prefix + (if (last) "    " else "│   "))
                    }
                }

                lines.add(root.name + "/")
                count++
                recurse(root, 1, "")
                return@ToolSpec ToolOutput.okText(
                    lines.joinToString("\n"),
                    Json.obj(
                        "root" to root.absolutePath,
                        "depth" to depth,
                        "entries" to count,
                        "maxEntries" to maxEntries,
                        "truncated" to truncated,
                        "tree" to lines.joinToString("\n"),
                    ),
                )
            },
        ),
    )

    private fun describeProject(dir: File, openDir: String?): Map<String, Any?> {
        val markers = PROJECT_MARKERS.filter { File(dir, it).exists() }
        val isFlutter = File(dir, "pubspec.yaml").exists()
        val isGradle = markers.any { it.startsWith("settings.gradle") || it.startsWith("build.gradle") }
        val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        val m = LinkedHashMap<String, Any?>()
        m["name"] = dir.name
        m["path"] = dir.absolutePath
        m["kind"] = when {
            isFlutter && isGradle -> "android+flutter"
            isFlutter -> "flutter"
            isGradle -> "gradle"
            else -> "other"
        }
        m["markers"] = markers
        m["hasWrapper"] = File(dir, "gradlew").exists()
        m["hasGit"] = File(dir, ".git").exists()
        m["isOpenProject"] = (openDir != null && openDir == canonical)
        m["lastModified"] = dir.lastModified()
        m["topLevelEntries"] = (dir.listFiles()?.size ?: 0)
        return m
    }

    private fun describe(dir: File): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["name"] = dir.name
        m["path"] = dir.absolutePath
        m.putAll(describeProject(dir, null).filterKeys { it != "name" && it != "path" })

        val settings = listOf("settings.gradle.kts", "settings.gradle").map { File(dir, it) }.firstOrNull { it.exists() }
        if (settings != null) {
            m["settingsFile"] = settings.name
            val text = runCatching { settings.readText() }.getOrDefault("")
            m["modules"] = parseIncludes(text)
        }

        val props = File(dir, "gradle.properties")
        if (props.exists()) {
            val text = runCatching { props.readText() }.getOrDefault("")
            val interesting = LinkedHashMap<String, String>()
            for (line in text.split('\n')) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val eq = t.indexOf('=')
                if (eq <= 0) continue
                val k = t.substring(0, eq).trim()
                val v = t.substring(eq + 1).trim()
                if (k.startsWith("android.") || k.startsWith("org.gradle.") || k.startsWith("kotlin.")) interesting[k] = v
            }
            m["gradleProperties"] = interesting
        }

        val local = File(dir, "local.properties")
        if (local.exists()) {
            val text = runCatching { local.readText() }.getOrDefault("")
            val sdk = text.split('\n').firstOrNull { it.trim().startsWith("sdk.dir=") }
            m["localPropertiesSdkDir"] = sdk?.substringAfter("sdk.dir=")?.trim()
        }

        val appDir = listOf(File(dir, "app"), File(dir, "main")).firstOrNull { it.isDirectory }
        if (appDir != null) {
            val build = listOf("build.gradle.kts", "build.gradle").map { File(appDir, it) }.firstOrNull { it.exists() }
            if (build != null) {
                val text = runCatching { build.readText() }.getOrDefault("")
                val cfg = LinkedHashMap<String, Any?>()
                cfg["buildFile"] = build.absolutePath
                for (key in listOf("applicationId", "namespace", "versionName", "minSdk", "targetSdk", "compileSdk", "versionCode")) {
                    firstCapture(text, Regex("\"?$key\"?\\s*[=]?\\s*[\"']?([^\"',\\r\\n)]+)"))?.let { cfg[key] = it.trim() }
                }
                m["appModule"] = cfg
            }
        }

        val apks = ArrayList<Map<String, Any?>>()
        collectApks(dir, apks, 40)
        m["apkArtifacts"] = apks
        m["apkCount"] = apks.size

        if (File(dir, ".git").exists()) {
            val git = Exec.ide(listOf("git", "status", "--porcelain=v1", "-b"), cwd = dir, timeoutSec = 30)
            if (git.exitCode == 0) {
                val lines = git.stdout.split('\n').filter { it.isNotBlank() }
                m["git"] = Json.obj(
                    "branch" to (lines.firstOrNull { it.startsWith("#") } ?: ""),
                    "dirtyFiles" to lines.count { !it.startsWith("#") },
                    "head" to lines.take(30),
                )
            } else {
                m["git"] = Json.obj("error" to git.stderr.trim().take(300))
            }
        }
        return m
    }

    private fun parseIncludes(text: String): List<String> {
        val out = ArrayList<String>()
        val rx = Regex("include\\s*\\(([^)]*)\\)|include\\s+((?:[\"'][^\"']+[\"']\\s*,?\\s*)+)")
        for (match in rx.findAll(text)) {
            val body = match.groupValues[1].ifEmpty { match.groupValues[2] }
            for (part in body.split(',')) {
                val name = part.trim().removeSurrounding("\"").removeSurrounding("'")
                if (name.isNotEmpty()) out.add(name)
            }
        }
        return out.distinct()
    }

    private fun firstCapture(text: String, rx: Regex): String? = rx.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun collectApks(dir: File, out: ArrayList<Map<String, Any?>>, limit: Int) {
        if (out.size >= limit) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (out.size >= limit) return
            if (c.isDirectory) {
                if (c.name == ".git" || c.name == ".gradle" || c.name == "node_modules") continue
                collectApks(c, out, limit)
            } else if (c.name.endsWith(".apk") || c.name.endsWith(".aab")) {
                out.add(
                    Json.obj(
                        "path" to c.absolutePath,
                        "name" to c.name,
                        "size" to c.length(),
                        "sizeHuman" to io.github.xsun71136.plugins.mcp.util.Fs.human(c.length()),
                        "lastModified" to c.lastModified(),
                    ),
                )
            }
        }
    }
}
