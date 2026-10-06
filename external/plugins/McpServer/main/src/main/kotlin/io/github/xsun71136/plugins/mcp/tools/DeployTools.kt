package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.util.Exec
import io.github.xsun71136.plugins.mcp.util.ProcResult
import java.io.File

/**
 * deploy group: install / launch / stop / inspect the built software and read logcat.
 *
 * These commands run on the Android host, not inside proot. Without shell or
 * Shizuku privileges pm/am will report a permission error; every tool therefore
 * also offers an adb fallback (settings.adbCommand) and returns both attempts so
 * the caller can see exactly what the device allowed.
 */
object DeployTools {

    private const val TAG = "deploy"

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_install_apk",
            group = ToolGroups.DEPLOY,
            description = "安装 APK 到本机。先用宿主 pm install（需要 shell/Shizuku 权限），失败则回退到 settings.adbCommand。返回两种尝试的原始输出。",
            inputSchema = Json.schema(
                listOf(
                    Triple("apkPath", "string", "APK 绝对路径；可由 acs_build_apk / acs_artifacts_find 得到"),
                    Triple("reinstall", "boolean", "默认 true：-r 保留数据覆盖安装"),
                    Triple("downgrade", "boolean", "默认 false：-d 允许降级"),
                    Triple("useAdbOnly", "boolean", "默认 false：跳过 pm 直接用 adb"),
                    Triple("timeoutSec", "integer", "默认 600"),
                ),
                listOf("apkPath"),
            ),
            handler = { args ->
                val path = Json.reqStr(args, "apkPath")
                val f = FileGuard.resolve(path)
                if (!f.exists()) return@ToolSpec ToolOutput.fail("apk not found: ${f.absolutePath}")
                val flags = ArrayList<String>()
                flags.add("install")
                if (Json.boolOf(args, "reinstall", true)) flags.add("-r")
                if (Json.boolOf(args, "downgrade", false)) flags.add("-d")
                flags.add(f.absolutePath)
                val timeout = Json.intOf(args, "timeoutSec", 600).coerceIn(10, 3600)
                val attempts = ArrayList<Map<String, Any?>>()
                var success: ProcResult? = null
                if (!Json.boolOf(args, "useAdbOnly", false)) {
                    val pm = Exec.host(listOf("pm", *flags.toTypedArray()), null, emptyMap(), timeout)
                    attempts.add(pm.toMap(8000))
                    if (isInstallSuccess(pm)) success = pm
                }
                if (success == null) {
                    val adbFlags = ArrayList<String>()
                    adbFlags.add("install")
                    if (Json.boolOf(args, "reinstall", true)) adbFlags.add("-r")
                    if (Json.boolOf(args, "downgrade", false)) adbFlags.add("-d")
                    adbFlags.add(f.absolutePath)
                    val adb = Exec.adb(adbFlags, timeout)
                    attempts.add(adb.toMap(8000))
                    if (isInstallSuccess(adb)) success = adb
                }
                McpLog.info(TAG, "install ${f.name} -> ${if (success != null) "SUCCESS" else "FAILED"}")
                val data = Json.obj(
                    "ok" to (success != null),
                    "apk" to f.absolutePath,
                    "size" to f.length(),
                    "sizeHuman" to io.github.xsun71136.plugins.mcp.util.Fs.human(f.length()),
                    "attempts" to attempts,
                    "hint" to "若两次都失败：pm 需要 shell/Shizuku 权限，adb 需要在设置里配置可用的 adbCommand（无线调试或 USB-OTG）",
                )
                if (success == null) return@ToolSpec ToolOutput(Json.pretty(data), data, true)
                ToolOutput.ok(data)
            },
        ),
        ToolSpec(
            name = "acs_uninstall_app",
            group = ToolGroups.DEPLOY,
            description = "卸载应用（pm uninstall，失败回退 adb uninstall）。",
            inputSchema = Json.schema(listOf(Triple("packageName", "string", null), Triple("keepData", "boolean", "默认 false：-k 保留数据")), listOf("packageName")),
            handler = { args ->
                val pkg = Json.reqStr(args, "packageName")
                val keep = Json.boolOf(args, "keepData", false)
                val hostArgs = ArrayList<String>()
                hostArgs.add("uninstall")
                if (keep) hostArgs.add("-k")
                hostArgs.add(pkg)
                hostAndAdb(listOf("pm", *hostArgs.toTypedArray()), hostArgs, 300, "uninstall $pkg")
            },
        ),
        ToolSpec(
            name = "acs_launch_app",
            group = ToolGroups.DEPLOY,
            description = "启动应用。未指定 activity 时用 monkey 拉起 LAUNCHER 入口；指定时用 am start -n pkg/activity。失败回退 adb shell。",
            inputSchema = Json.schema(
                listOf(
                    Triple("packageName", "string", null),
                    Triple("activity", "string", "可选：完整或相对 Activity 名，如 .MainActivity"),
                    Triple("extras", "object", "可选：am start 的 --es key value 形式附加参数（键值均为字符串）"),
                ),
                listOf("packageName"),
            ),
            handler = { args ->
                val pkg = Json.reqStr(args, "packageName")
                val activity = Json.strOf(args, "activity")?.trim()?.takeIf { it.isNotEmpty() }
                val script = if (activity != null) {
                    val sb = StringBuilder("am start -n ").append(pkg).append('/').append(activity)
                    Json.asMap(args["extras"]).forEach { (k, v) ->
                        sb.append(" --es ").append(k).append(' ').append(Json.str(v) ?: "")
                    }
                    sb.toString()
                } else {
                    "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                }
                val host = Exec.hostShell(script, null, 60)
                if (host.exitCode == 0 && !host.stderr.contains("Error", true)) {
                    return@ToolSpec ToolOutput.okText(host.text(8000), host.toMap(8000))
                }
                val adb = Exec.adb(listOf("shell", script), 60)
                val data = Json.obj(
                    "ok" to (adb.exitCode == 0),
                    "package" to pkg,
                    "activity" to activity,
                    "command" to script,
                    "hostAttempt" to host.toMap(4000),
                    "adbAttempt" to adb.toMap(4000),
                )
                if (adb.exitCode != 0) return@ToolSpec ToolOutput(Json.pretty(data), data, true)
                ToolOutput.ok(data)
            },
        ),
        ToolSpec(
            name = "acs_stop_app",
            group = ToolGroups.DEPLOY,
            description = "强制停止应用（am force-stop，失败回退 adb shell am force-stop）。",
            inputSchema = Json.schema(listOf(Triple("packageName", "string", null)), listOf("packageName")),
            handler = { args ->
                val pkg = Json.reqStr(args, "packageName")
                hostAndAdb(listOf("am", "force-stop", pkg), listOf("shell", "am", "force-stop", pkg), 60, "force-stop $pkg")
            },
        ),
        ToolSpec(
            name = "acs_packages_list",
            group = ToolGroups.DEPLOY,
            description = "列出已安装应用包名（pm list packages）。thirdPartyOnly=true 时只列第三方应用；filter 做子串过滤。",
            inputSchema = Json.schema(
                listOf(
                    Triple("filter", "string", "可选：包名子串"),
                    Triple("thirdPartyOnly", "boolean", "默认 true：-3 只列第三方应用"),
                    Triple("showPath", "boolean", "默认 false：-f 同时输出 APK 路径"),
                ),
            ),
            handler = { args ->
                val cmd = ArrayList<String>()
                cmd.add("pm")
                cmd.add("list")
                cmd.add("packages")
                if (Json.boolOf(args, "thirdPartyOnly", true)) cmd.add("-3")
                if (Json.boolOf(args, "showPath", false)) cmd.add("-f")
                val filter = Json.strOf(args, "filter")?.trim()?.takeIf { it.isNotEmpty() }
                if (filter != null) cmd.add(filter)
                val res = Exec.host(cmd, null, emptyMap(), 60)
                val lines = res.stdout.split('\n').map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
                ToolOutput.ok(
                    Json.obj(
                        "ok" to (res.exitCode == 0),
                        "count" to lines.size,
                        "filter" to filter,
                        "packages" to lines.take(2000),
                        "stderr" to res.stderr.take(1000),
                    ),
                )
            },
        ),
        ToolSpec(
            name = "acs_logcat_dump",
            group = ToolGroups.DEPLOY,
            description = "读取 logcat（宿主侧）。支持按行数、包名/PID、优先级、关键字过滤。未授权时会返回“只能读取本应用日志”的说明而不是空结果。",
            inputSchema = Json.schema(
                listOf(
                    Triple("lines", "integer", "默认 500：-t <lines>"),
                    Triple("packageName", "string", "可选：先用 pidof 解析 PID 再 --pid 过滤"),
                    Triple("priority", "string", "可选：V/D/I/W/E/F 之一，作为 *:P 过滤器"),
                    Triple("grep", "string", "可选：只返回包含该子串的行"),
                    Triple("clear", "boolean", "默认 false：读取前先 logcat -c"),
                    Triple("buffer", "string", "默认 main；可选 system/crash/events/all"),
                    Triple("maxOutputChars", "integer", "默认 24000"),
                ),
            ),
            handler = { args -> dumpLogcat(args) },
        ),
        ToolSpec(
            name = "acs_logcat_clear",
            group = ToolGroups.DEPLOY,
            description = "清空 logcat 缓冲区（logcat -c）。",
            inputSchema = Json.schema(emptyList()),
            handler = {
                val res = Exec.hostShell("logcat -c", null, 30)
                ToolOutput.okText(res.text(4000), res.toMap(4000))
            },
        ),
        ToolSpec(
            name = "acs_crash_report",
            group = ToolGroups.DEPLOY,
            description = "抓取最近的崩溃证据：crash 缓冲区 + FATAL EXCEPTION / AndroidRuntime / tombstone 关键字，并汇总信号、异常类型与栈顶若干帧。用于构建后启动失败的定位。",
            inputSchema = Json.schema(
                listOf(
                    Triple("packageName", "string", "可选：只看该包"),
                    Triple("lines", "integer", "默认 1200"),
                ),
            ),
            handler = { args ->
                val lines = Json.intOf(args, "lines", 1200).coerceIn(50, 20000)
                val pkg = Json.strOf(args, "packageName")?.trim()?.takeIf { it.isNotEmpty() }
                val crash = Exec.hostShell("logcat -d -b crash -t $lines -v threadtime", null, 60)
                val main = Exec.hostShell("logcat -d -t $lines -v threadtime", null, 60)
                val keywords = listOf("FATAL EXCEPTION", "AndroidRuntime", "Fatal signal", "ClassNotFoundException", "NoClassDefFoundError", "VerifyError", "UnsatisfiedLinkError", "SIGSEGV", "SIGABRT", "ANR in", "beginning of crash")
                val combined = (crash.stdout + "\n" + main.stdout).split('\n')
                val filtered = combined.filter { line ->
                    keywords.any { line.contains(it) } && (pkg == null || line.contains(pkg))
                }
                val tombstones = Exec.hostShell("ls -1 /data/tombstones 2>/dev/null | tail -5", null, 20)
                ToolOutput.ok(
                    Json.obj(
                        "ok" to (crash.exitCode == 0 || main.exitCode == 0),
                        "package" to pkg,
                        "requestedLines" to lines,
                        "crashLines" to filtered.size,
                        "crashes" to filtered.take(300),
                        "tombstones" to tombstones.stdout.trim(),
                        "note" to "无 root/Shizuku 时 logcat 可能只包含本应用日志；此时请用 acs_adb 走无线调试获取完整日志",
                    ),
                )
            },
        ),
    )

    private fun dumpLogcat(args: Map<String, Any?>): ToolOutput {
        val lines = Json.intOf(args, "lines", 500).coerceIn(10, 20000)
        val pkg = Json.strOf(args, "packageName")?.trim()?.takeIf { it.isNotEmpty() }
        val priority = Json.strOf(args, "priority")?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        val grep = Json.strOf(args, "grep")?.takeIf { it.isNotEmpty() }
        val buffer = Json.strOf(args, "buffer", "main") ?: "main"
        val max = Json.intOf(args, "maxOutputChars", 24000)

        val script = StringBuilder()
        if (Json.boolOf(args, "clear", false)) script.append("logcat -c; ")
        script.append("logcat -d -v threadtime -b ").append(buffer).append(" -t ").append(lines)
        if (pkg != null) {
            script.append(" | grep -E \"").append(pkg.replace("\"", "")).append("\"")
        }
        if (priority != null) {
            script.append(" | grep -E \" [VDIWEF]/\"")
        }
        if (grep != null) {
            script.append(" | grep -F -- \"").append(grep.replace("\"", "\\\"")).append("\"")
        }
        val res = Exec.hostShell(script.toString(), null, Json.intOf(args, "timeoutSec", 120).coerceIn(5, 600))
        val outLines = res.stdout.split('\n').filter { it.isNotBlank() }
        val data = LinkedHashMap<String, Any?>()
        data["ok"] = (res.exitCode == 0)
        data["command"] = script.toString()
        data["buffer"] = buffer
        data["requestedLines"] = lines
        data["returnedLines"] = outLines.size
        data["package"] = pkg
        data["priority"] = priority
        data["grep"] = grep
        data["exitCode"] = res.exitCode
        data["stderr"] = ProcResult.clamp(res.stderr, 4000)
        data["logs"] = ProcResult.clamp(res.stdout, max)
        data["tail"] = ProcResult.tail(res.stdout, 120)
        if (outLines.isEmpty() && res.exitCode == 0) {
            data["note"] = "结果为空：无 root/Shizuku 时应用通常只能读取自身日志，可改用 acs_adb 走无线调试"
        }
        return ToolOutput.okText(ProcResult.clamp(res.stdout, max), data)
    }

    private fun isInstallSuccess(res: ProcResult): Boolean {
        val out = (res.stdout + res.stderr).lowercase()
        return res.exitCode == 0 && (out.contains("success") || out.contains("performing stream"))
    }

    private fun hostAndAdb(hostCmd: List<String>, adbArgs: List<String>, timeout: Int, label: String): ToolOutput {
        val host = Exec.host(hostCmd, null, emptyMap(), timeout)
        if (host.exitCode == 0 && host.stderr.isBlank()) {
            McpLog.info(TAG, "$label -> ok (host)")
            return ToolOutput.okText(host.text(8000), host.toMap(8000))
        }
        val adb = Exec.adb(adbArgs, timeout)
        val ok = adb.exitCode == 0 && !adb.stdout.contains("Failure", true) && !adb.stderr.contains("error", true)
        McpLog.info(TAG, "$label -> ${if (ok) "ok (adb)" else "failed"}")
        val data = Json.obj(
            "ok" to ok,
            "label" to label,
            "adbCommand" to McpSettingsStore.current.adbCommand,
            "hostAttempt" to host.toMap(4000),
            "adbAttempt" to adb.toMap(4000),
            "hint" to "pm/am 需要 shell 或 Shizuku 权限；adb 需要在 MCP 设置里配置可用的 adbCommand",
        )
        return if (ok) ToolOutput.ok(data) else ToolOutput(Json.pretty(data), data, true)
    }
}
