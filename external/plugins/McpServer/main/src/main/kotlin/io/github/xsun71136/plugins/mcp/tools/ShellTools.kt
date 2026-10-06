package io.github.xsun71136.plugins.mcp.tools

import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.util.Exec
import java.io.File

/** shell group: run commands in the IDE environment or on the Android host. */
object ShellTools {

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            name = "acs_shell_exec",
            group = ToolGroups.SHELL,
            description = "在 IDE 的终端环境（proot/acsenv，含 JDK、Gradle、Android SDK、已安装的 Termux 包）中执行一条 shell 命令。自动附加 SDK/存储/项目目录挂载与 IDE 环境变量。适合 java -version、sdkmanager、git、cmake、ninja、flutter 等。",
            inputSchema = Json.schema(
                listOf(
                    Triple("command", "string", "要执行的命令行（会交给 settings.shellCommand 解释器）"),
                    Triple("cwd", "string", "工作目录，默认当前项目目录"),
                    Triple("timeoutSec", "integer", "默认 600"),
                    Triple("extraEnv", "object", "附加环境变量 map"),
                    Triple("maxOutputChars", "integer", "默认 24000"),
                ),
                listOf("command"),
            ),
            handler = { args ->
                val command = Json.reqStr(args, "command")
                val cwdStr = Json.strOf(args, "cwd")
                val cwd: File? = if (cwdStr.isNullOrEmpty()) IdeTools.resolveProjectDir(args) else FileGuard.resolve(cwdStr)
                val extra = LinkedHashMap<String, String>()
                Json.asMap(args["extraEnv"]).forEach { (k, v) -> Json.str(v)?.let { extra[k] = it } }
                val timeout = Json.intOf(args, "timeoutSec", 600).coerceIn(5, 7200)
                val res = Exec.ide(listOf(command), cwd = cwd, extraEnv = extra, timeoutSec = timeout, viaShell = true)
                val max = Json.intOf(args, "maxOutputChars", 24000)
                ToolOutput.okText(res.text(max), res.toMap(max))
            },
        ),
        ToolSpec(
            name = "acs_shell_argv",
            group = ToolGroups.SHELL,
            description = "在 IDE 终端环境中直接以 argv 方式启动一个可执行文件（不经过 shell），适合需要精确参数或长驻进程的场景；返回输出与退出码。",
            inputSchema = Json.schema(
                listOf(
                    Triple("argv", "array", "可执行文件与参数，rootfs 内路径，如 [\"/usr/bin/env\"]"),
                    Triple("cwd", "string", null),
                    Triple("timeoutSec", "integer", "默认 300"),
                    Triple("attachSdk", "boolean", "默认 true"),
                    Triple("attachStorage", "boolean", "默认 true"),
                ),
                listOf("argv"),
            ),
            handler = { args ->
                val argv = Json.strList(args, "argv")
                if (argv.isEmpty()) return@ToolSpec ToolOutput.fail("'argv' must not be empty")
                val cwdStr = Json.strOf(args, "cwd")
                val cwd: File? = if (cwdStr.isNullOrEmpty()) null else FileGuard.resolve(cwdStr)
                val res = Exec.ide(
                    argv,
                    cwd = cwd,
                    timeoutSec = Json.intOf(args, "timeoutSec", 300).coerceIn(5, 7200),
                    attachSdk = Json.boolOf(args, "attachSdk", true),
                    attachStorage = Json.boolOf(args, "attachStorage", true),
                    viaShell = false,
                )
                ToolOutput.okText(res.text(24000), res.toMap(24000))
            },
        ),
        ToolSpec(
            name = "acs_host_exec",
            group = ToolGroups.SHELL,
            description = "在 Android 宿主侧（IDE 应用进程，非 proot）用 settings.hostShell 执行脚本。可访问 pm/am/logcat/getprop/dumpsys 等设备命令；需要 shell 或 Shizuku 权限的命令会返回权限错误而不是静默失败。",
            inputSchema = Json.schema(
                listOf(
                    Triple("script", "string", "交给 /system/bin/sh -c 的脚本"),
                    Triple("cwd", "string", null),
                    Triple("timeoutSec", "integer", "默认 120"),
                ),
                listOf("script"),
            ),
            handler = { args ->
                val script = Json.reqStr(args, "script")
                val cwdStr = Json.strOf(args, "cwd")
                val cwd: File? = if (cwdStr.isNullOrEmpty()) null else FileGuard.resolve(cwdStr)
                val res = Exec.hostShell(script, cwd, Json.intOf(args, "timeoutSec", 120).coerceIn(5, 3600))
                ToolOutput.okText(res.text(24000), res.toMap(24000))
            },
        ),
        ToolSpec(
            name = "acs_adb",
            group = ToolGroups.SHELL,
            description = "通过 settings.adbCommand（默认 adb）执行 adb 子命令，用于无线调试 / USB-OTG 场景下的 install、shell、logcat、devices 等完整能力。",
            inputSchema = Json.schema(
                listOf(
                    Triple("args", "array", "adb 参数，如 [\"devices\"] 或 [\"install\",\"-r\",\"/path/app.apk\"]"),
                    Triple("timeoutSec", "integer", "默认 180"),
                ),
                listOf("args"),
            ),
            handler = { args ->
                val argv = Json.strList(args, "args")
                if (argv.isEmpty()) return@ToolSpec ToolOutput.fail("'args' must not be empty")
                val res = Exec.adb(argv, Json.intOf(args, "timeoutSec", 180).coerceIn(5, 3600))
                val extra = Json.obj("adbCommand" to McpSettingsStore.current.adbCommand)
                val data = LinkedHashMap<String, Any?>()
                data.putAll(res.toMap(24000))
                data.putAll(extra)
                ToolOutput.okText(res.text(24000), data)
            },
        ),
    )
}
