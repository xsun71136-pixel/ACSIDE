package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.util.Exec
import io.github.xsun71136.plugins.mcp.util.ProcResult
import java.io.File

/** build group: drive the project's Gradle build through the IDE environment. */
object BuildTools {

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_gradle_run",
            group = ToolGroups.BUILD,
            description = "在 IDE 的构建环境（proot/acsenv，含 JDK 与 Android SDK）中执行 Gradle 任务。默认使用项目的 ./gradlew，自动附加 --console=plain。返回退出码、耗时、stdout/stderr 与末尾若干行。",
            inputSchema = Json.schema(
                listOf(
                    Triple("tasks", "array", "Gradle 任务列表，如 [\"assembleDebug\"] 或 [\":app:clean\", \":app:assembleRelease\"]"),
                    Triple("projectDir", "string", "项目根目录，省略则用当前打开的项目"),
                    Triple("args", "array", "附加 Gradle 参数，如 [\"-Pfoo=bar\", \"--stacktrace\"]"),
                    Triple("timeoutSec", "integer", "默认 1800"),
                    Triple("offline", "boolean", "默认 false：追加 --offline"),
                    Triple("refreshDependencies", "boolean", "默认 false：追加 --refresh-dependencies"),
                    Triple("maxOutputChars", "integer", "默认 24000：stdout/stderr 截断长度"),
                ),
                listOf("tasks"),
            ),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                if (!dir.isDirectory) return@ToolSpec ToolOutput.fail("project dir not found: ${dir.absolutePath}")
                val tasks = Json.strList(args, "tasks")
                if (tasks.isEmpty()) return@ToolSpec ToolOutput.fail("'tasks' must contain at least one Gradle task")
                val extra = ArrayList<String>()
                extra.addAll(Json.strList(args, "args"))
                if (Json.boolOf(args, "offline", false)) extra.add("--offline")
                if (Json.boolOf(args, "refreshDependencies", false)) extra.add("--refresh-dependencies")
                val timeout = Json.intOf(args, "timeoutSec", 1800).coerceIn(10, 7200)
                val res = Exec.gradle(dir, tasks, extra, timeout)
                ToolOutput.okText(res.text(Json.intOf(args, "maxOutputChars", 24000)), res.toMap(Json.intOf(args, "maxOutputChars", 24000)))
            },
        ),
        ToolSpec(
            name = "acs_gradle_tasks",
            group = ToolGroups.BUILD,
            description = "列出项目可用的 Gradle 任务（gradle tasks --all）。用于确认模块名与任务名后再调用 acs_gradle_run。",
            inputSchema = Json.schema(
                listOf(
                    Triple("projectDir", "string", null),
                    Triple("all", "boolean", "默认 true：--all"),
                    Triple("timeoutSec", "integer", "默认 600"),
                ),
            ),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                val cmd = ArrayList<String>()
                cmd.addAll(Exec.gradleInvoker(dir))
                cmd.add("tasks")
                if (Json.boolOf(args, "all", true)) cmd.add("--all")
                val res = Exec.ide(cmd, cwd = dir, timeoutSec = Json.intOf(args, "timeoutSec", 600))
                ToolOutput.okText(res.text(24000), res.toMap(24000))
            },
        ),
        ToolSpec(
            name = "acs_gradle_projects",
            group = ToolGroups.BUILD,
            description = "执行 gradle projects，列出项目的模块结构与根项目名。",
            inputSchema = Json.schema(listOf(Triple("projectDir", "string", null), Triple("timeoutSec", "integer", "默认 300"))),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                val cmd = ArrayList<String>()
                cmd.addAll(Exec.gradleInvoker(dir))
                cmd.add("projects")
                val res = Exec.ide(cmd, cwd = dir, timeoutSec = Json.intOf(args, "timeoutSec", 300))
                ToolOutput.okText(res.text(16000), res.toMap(16000))
            },
        ),
        ToolSpec(
            name = "acs_build_apk",
            group = ToolGroups.BUILD,
            description = "一键构建 APK：执行 :<module>:assemble<Variant>，成功后自动定位生成的 .apk 并返回绝对路径、大小与 SHA-256，可直接交给 acs_install_apk。",
            inputSchema = Json.schema(
                listOf(
                    Triple("projectDir", "string", null),
                    Triple("variant", Json.obj("type" to "string", "enum" to Json.arr("debug", "release")), "默认 debug"),
                    Triple("module", "string", "默认 app"),
                    Triple("timeoutSec", "integer", "默认 2400"),
                ),
            ),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                val variant = (Json.strOf(args, "variant", "debug") ?: "debug").let {
                    if (it.isEmpty()) "debug" else it.substring(0, 1).uppercase() + it.substring(1).lowercase()
                }
                val module = (Json.strOf(args, "module", "app") ?: "app").trim().removePrefix(":")
                val task = if (module.isEmpty() || module == "root") "assemble$variant" else ":$module:assemble$variant"
                val timeout = Json.intOf(args, "timeoutSec", 2400).coerceIn(30, 10800)
                val res = Exec.gradle(dir, listOf(task), emptyList(), timeout)
                val data = LinkedHashMap<String, Any?>()
                data.putAll(res.toMap(20000))
                data["task"] = task
                val apks = ArrayList<Map<String, Any?>>()
                collectArtifacts(dir, listOf("apk"), apks, 20)
                data["apkArtifacts"] = apks
                data["newestApk"] = apks.maxByOrNull { Json.longOf(it, "lastModified", 0L) }
                if (!res.ok && apks.isEmpty()) return@ToolSpec ToolOutput(res.text(20000), data, true)
                ToolOutput.okText(res.text(20000), data)
            },
        ),
        ToolSpec(
            name = "acs_artifacts_find",
            group = ToolGroups.BUILD,
            description = "在项目内查找构建产物（apk/aab/jar/aar/so/dex），按修改时间倒序返回绝对路径、大小、SHA-256（小于 200MB 时）。",
            inputSchema = Json.schema(
                listOf(
                    Triple("projectDir", "string", null),
                    Triple("extensions", "array", "默认 [\"apk\"]"),
                    Triple("maxResults", "integer", "默认 20"),
                    Triple("hash", "boolean", "默认 true"),
                ),
            ),
            handler = { args ->
                val dir = IdeTools.resolveProjectDir(args) ?: return@ToolSpec ToolOutput.fail("no project open; pass projectDir explicitly")
                val exts = Json.strList(args, "extensions").ifEmpty { listOf("apk") }.map { it.removePrefix(".").lowercase() }
                val out = ArrayList<Map<String, Any?>>()
                collectArtifacts(dir, exts, out, Json.intOf(args, "maxResults", 20).coerceIn(1, 500))
                if (Json.boolOf(args, "hash", true)) {
                    for (item in out) {
                        val p = Json.strOf(item, "path") ?: continue
                        val f = File(p)
                        if (f.isFile && f.length() <= 200L * 1024 * 1024) {
                            runCatching {
                                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                                @Suppress("UNCHECKED_CAST")
                                (item as MutableMap<String, Any?>)["sha256"] = digest.joinToString("") { b -> "%02x".format(b) }
                            }
                        }
                    }
                }
                ToolOutput.ok(Json.obj("root" to dir.absolutePath, "extensions" to exts, "count" to out.size, "artifacts" to out))
            },
        ),
    )

    private fun collectArtifacts(dir: File, exts: List<String>, out: ArrayList<Map<String, Any?>>, limit: Int) {
        if (out.size >= limit) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (out.size >= limit) return
            if (c.isDirectory) {
                if (c.name == ".git" || c.name == ".gradle" || c.name == "node_modules") continue
                collectArtifacts(c, exts, out, limit)
            } else {
                val ext = c.name.substringAfterLast('.', "").lowercase()
                if (exts.contains(ext)) {
                    out.add(
                        Json.obj(
                            "path" to c.absolutePath,
                            "name" to c.name,
                            "extension" to ext,
                            "size" to c.length(),
                            "sizeHuman" to io.github.xsun71136.plugins.mcp.util.Fs.human(c.length()),
                            "lastModified" to c.lastModified(),
                        ),
                    )
                }
            }
        }
        out.sortByDescending { Json.longOf(it, "lastModified", 0L) }
        while (out.size > limit) out.removeAt(out.size - 1)
    }

    /** Convenience used by other tools. */
    fun runGradle(dir: File, tasks: List<String>, timeoutSec: Int): ProcResult = Exec.gradle(dir, tasks, emptyList(), timeoutSec)
}
