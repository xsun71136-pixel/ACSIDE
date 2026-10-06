package io.github.xsun71136.plugins.mcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullij.androidcodestudio.plugins.api.OverlayHandle
import io.github.xsun71136.plugins.mcp.LogEntry
import io.github.xsun71136.plugins.mcp.McpLog
import io.github.xsun71136.plugins.mcp.McpServerPlugin
import io.github.xsun71136.plugins.mcp.McpSettings
import io.github.xsun71136.plugins.mcp.McpSettingsStore
import io.github.xsun71136.plugins.mcp.ToolGroups
import io.github.xsun71136.plugins.mcp.json.Json
import io.github.xsun71136.plugins.mcp.mcp.McpServer
import io.github.xsun71136.plugins.mcp.mcp.OverlayHandleRef
import io.github.xsun71136.plugins.mcp.tools.CallHistory
import io.github.xsun71136.plugins.mcp.tools.CallRecord
import io.github.xsun71136.plugins.mcp.tools.ToolRegistry

private typealias Block = @Composable () -> Unit

private val BG = Color(0xFF1B1B1F)
private val PANEL = Color(0xFF232329)
private val CARD = Color(0xFF2C2C34)
private val ACCENT = Color(0xFF4CC2FF)
private val GOOD = Color(0xFF6BCB77)
private val BAD = Color(0xFFFF6B6B)
private val WARN = Color(0xFFFFD166)
private val TEXT = Color(0xFFE8E8EC)
private val MUTED = Color(0xFF9A9AA6)
private val MONO = FontFamily.Monospace

/**
 * The 「输出与调试」 MCP console.
 *
 * Rendered through PluginApi.ui.showOverlay, i.e. inside the IDE's live Compose
 * composition. Three tabs:
 *   输出     - server state, endpoints, live log (mirrored to logcat tag ACS-MCP)
 *   调试     - loopback self-test, tool coverage map, recent tool calls
 *   MCP 设置 - this plugin's own, separate settings page
 */
@Composable
fun McpConsolePanel(handle: OverlayHandle, onClose: () -> Unit) {
    OverlayHandleRef.current = handle
    var tab by remember { mutableStateOf(0) }
    val logs = remember { McpLog.snapshot().toMutableStateList() }
    var status by remember { mutableStateOf(McpServer.status()) }
    var calls by remember { mutableStateOf(CallHistory.tail(100).reversed()) }
    var notice by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener: (LogEntry) -> Unit = { entry ->
            main.post {
                logs.add(entry)
                while (logs.size > 400) logs.removeAt(0)
            }
        }
        McpLog.addListener(listener)
        val tick = object : Runnable {
            override fun run() {
                status = McpServer.status()
                calls = CallHistory.tail(100).reversed()
                main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(tick, 2000)
        onDispose {
            McpLog.removeListener(listener)
            main.removeCallbacks(tick)
            OverlayHandleRef.current = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { onClose() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .background(BG, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
        ) {
            ConsoleHeader(status, notice, onClose)
            ConsoleTabs(tab) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
                when (tab) {
                    0 -> OutputTab(status, logs) { notice = it }
                    1 -> DebugTab(calls) { notice = it }
                    else -> SettingsTab(McpSettingsStore.current) { notice = it }
                }
            }
            ConsoleFooter(status, McpSettingsStore.current)
        }
    }
}

@Composable
private fun ConsoleHeader(status: Map<String, Any?>, notice: String, onClose: () -> Unit) {
    val running = Json.boolOf(status, "running", false)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (running) GOOD else BAD, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("输出与调试 · MCP 服务器", color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "运行中 :${Json.strOf(status, "port")}" else "已停止",
            color = if (running) GOOD else MUTED,
            fontSize = 12.sp,
            fontFamily = MONO,
        )
        Spacer(Modifier.weight(1f))
        if (notice.isNotEmpty()) {
            Text(notice, color = WARN, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .background(CARD, RoundedCornerShape(8.dp))
                .clickable { onClose() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Text("✕", color = TEXT, fontSize = 14.sp) }
    }
}

@Composable
private fun ConsoleTabs(tab: Int, select: (Int) -> Unit) {
    val titles = listOf("输出", "调试", "MCP 设置")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        for (i in titles.indices) {
            val selected = tab == i
            Column(
                Modifier
                    .padding(end = 6.dp)
                    .background(if (selected) PANEL else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { select(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    titles[i],
                    color = if (selected) ACCENT else MUTED,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) ACCENT else Color.Transparent))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Scroll(content: Block) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

@Composable
private fun Card(title: String, content: Block) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(PANEL, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(title, color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun KV(k: String, v: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(k, color = MUTED, fontSize = 11.sp, modifier = Modifier.width(150.dp))
        Text(v, color = TEXT, fontSize = 11.sp, fontFamily = if (mono) MONO else FontFamily.Default)
    }
}

@Composable
private fun ActionRow(actions: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in actions) {
            OutlinedButton(onClick = a.second, modifier = Modifier.height(34.dp)) {
                Text(a.first, color = TEXT, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun OutputTab(status: Map<String, Any?>, logs: List<LogEntry>, notify: (String) -> Unit) {
    val clipboard = runCatching { LocalClipboardManager.current }.getOrNull()
    val endpoints = Json.asList(status["endpoints"]).mapNotNull { Json.str(it) }
    Scroll {
        Card("服务器") {
            KV("state", if (Json.boolOf(status, "running", false)) "RUNNING" else "STOPPED")
            KV("bind", "${Json.strOf(status, "bindAddress")}:${Json.strOf(status, "port")}")
            KV("bindAll (LAN)", Json.boolOf(status, "bindAll", false).toString())
            KV("readOnly", Json.boolOf(status, "readOnly", false).toString())
            KV(
                "auth",
                if (Json.boolOf(status, "requireAuth", false)) {
                    if (Json.boolOf(status, "authTokenConfigured", false)) "Bearer token 已配置" else "Bearer token 未配置"
                } else "off",
            )
            KV("uptime", "${Json.longOf(status, "uptimeMs", 0L) / 1000}s")
            KV("sessions", Json.strOf(status, "sessionCount") ?: "0")
            KV("requests", Json.strOf(status, "totalRequests") ?: "0")
            KV("tool calls", Json.strOf(status, "totalToolCalls") ?: "0")
            KV("tools", "${Json.strOf(status, "enabledTools")}/${Json.strOf(status, "registeredTools")} enabled")
            val lastError = Json.strOf(status, "lastError")
            if (lastError != null) KV("lastError", lastError)
            for (e in endpoints) KV("endpoint", e)
            ActionRow(
                listOf(
                    "启动" to { notify(msg(McpServer.start("console"))) },
                    "停止" to { notify(msg(McpServer.stop("console"))) },
                    "重启" to { notify(msg(McpServer.restart("console"))) },
                    "复制地址" to {
                        val first = endpoints.firstOrNull() ?: ""
                        runCatching { clipboard?.setText(AnnotatedString(first)) }
                        notify(if (first.isEmpty()) "无可用地址" else "已复制")
                    },
                ),
            )
        }
        Card("输出日志 (logcat tag: ACS-MCP)") {
            ActionRow(listOf("清空日志" to { McpLog.clear(); notify("日志已清空") }))
            Spacer(Modifier.height(6.dp))
            if (logs.isEmpty()) {
                Text("暂无日志", color = MUTED, fontSize = 11.sp)
            } else {
                for (entry in logs.asReversed().take(400)) {
                    Text(
                        entry.format(),
                        color = when (entry.level) {
                            McpLog.ERROR -> BAD
                            McpLog.WARN -> WARN
                            McpLog.DEBUG, McpLog.TRACE -> MUTED
                            else -> TEXT
                        },
                        fontSize = 10.sp,
                        fontFamily = MONO,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugTab(calls: List<CallRecord>, notify: (String) -> Unit) {
    var probe by remember { mutableStateOf("") }
    val s = McpSettingsStore.current
    Scroll {
        Card("自检") {
            Text("在本机用真实 socket 走一遍 initialize → tools/list → tools/call(acs_ide_status)。", color = MUTED, fontSize = 11.sp)
            ActionRow(
                listOf(
                    "运行自检" to {
                        val out = ToolRegistry.invoke("acs_mcp_selftest", Json.obj("timeoutMs" to 8000), origin = "console")
                        probe = out.text
                        notify(if (out.isError) "自检失败" else "自检通过")
                    },
                    "插件探针" to {
                        probe = Json.pretty(McpServerPlugin.selfProbe())
                        notify("已刷新探针")
                    },
                    "清空历史" to { CallHistory.clear(); notify("调用历史已清空") },
                ),
            )
            if (probe.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(probe, color = TEXT, fontSize = 10.sp, fontFamily = MONO)
            }
        }
        Card("工具覆盖（整个项目软件）") {
            val groups = ToolRegistry.byGroup()
            for (g in ToolGroups.ALL) {
                val names = groups[g] ?: emptyList()
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(g, color = if (s.groupEnabled(g)) ACCENT else MUTED, fontSize = 11.sp, modifier = Modifier.width(70.dp))
                    Text(
                        "${names.size} 工具 · ${if (s.groupEnabled(g)) "启用" else "禁用"}",
                        color = TEXT, fontSize = 11.sp, modifier = Modifier.width(130.dp),
                    )
                    Text(names.joinToString(", "), color = MUTED, fontSize = 10.sp, fontFamily = MONO)
                }
            }
        }
        Card("最近调用 (${calls.size})") {
            if (calls.isEmpty()) {
                Text("暂无调用记录", color = MUTED, fontSize = 11.sp)
            } else {
                for (c in calls.take(120)) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .background(CARD, RoundedCornerShape(8.dp)).padding(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (c.ok) GOOD else BAD, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(c.tool, color = TEXT, fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text("${c.durationMs}ms", color = MUTED, fontSize = 10.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(c.origin, color = MUTED, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            Text(McpLog.stamp(c.time), color = MUTED, fontSize = 10.sp)
                        }
                        if (c.argsSummary.isNotEmpty() && c.argsSummary != "{}") {
                            Text(c.argsSummary, color = MUTED, fontSize = 10.sp, fontFamily = MONO)
                        }
                        if (!c.ok) Text(c.detail, color = BAD, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(initial: McpSettings, notify: (String) -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var autoStart by remember { mutableStateOf(initial.autoStart) }
    var showConsole by remember { mutableStateOf(initial.showConsoleOnLaunch) }
    var bindAll by remember { mutableStateOf(initial.bindAll) }
    var requireAuth by remember { mutableStateOf(initial.requireAuth) }
    var readOnly by remember { mutableStateOf(initial.readOnly) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var token by remember { mutableStateOf(initial.authToken) }
    var shell by remember { mutableStateOf(initial.shellCommand) }
    var shellArgs by remember { mutableStateOf(initial.shellArgs) }
    var gradleCmd by remember { mutableStateOf(initial.gradleCommand) }
    var gradleJvm by remember { mutableStateOf(initial.gradleJvmArgs) }
    var adbCmd by remember { mutableStateOf(initial.adbCommand) }
    var hostShell by remember { mutableStateOf(initial.hostShell) }
    var logLimit by remember { mutableStateOf(initial.logLimit.toString()) }
    var minLevel by remember { mutableStateOf(initial.minLogLevel) }
    var timeout by remember { mutableStateOf(initial.requestTimeoutSec.toString()) }
    var groups by remember { mutableStateOf(LinkedHashMap(initial.toolGroups)) }
    var allowed by remember { mutableStateOf(initial.allowedTools.joinToString(",")) }
    var denied by remember { mutableStateOf(initial.deniedTools.joinToString(",")) }

    Scroll {
        Card("开关") {
            SwitchRow("启用 MCP 服务器", enabled) { enabled = it }
            SwitchRow("IDE 启动时自动监听 (autoStart)", autoStart) { autoStart = it }
            SwitchRow("启动时显示「输出与调试」面板", showConsole) { showConsole = it }
            SwitchRow("监听所有网卡（局域网可访问）", bindAll) { bindAll = it }
            SwitchRow("需要 Bearer Token 认证", requireAuth) { requireAuth = it }
            SwitchRow("只读安全模式（拒绝写入/执行类工具）", readOnly) { readOnly = it }
        }
        Card("网络与认证") {
            FieldRow("端口 (1024-65535)", port) { port = it }
            FieldRow("认证 Token", token) { token = it }
            ActionRow(listOf("随机 Token" to { token = McpSettings.randomToken(); notify("已生成新 Token，请保存") }))
        }
        Card("执行环境") {
            FieldRow("IDE 环境 shell", shell) { shell = it }
            FieldRow("shell 参数", shellArgs) { shellArgs = it }
            FieldRow("Gradle 命令 (auto/gradlew/gradle/路径)", gradleCmd) { gradleCmd = it }
            FieldRow("GRADLE_OPTS", gradleJvm) { gradleJvm = it }
            FieldRow("宿主 shell (pm/am/logcat)", hostShell) { hostShell = it }
            FieldRow("adb 命令", adbCmd) { adbCmd = it }
        }
        Card("日志与超时") {
            FieldRow("日志保留行数", logLimit) { logLimit = it }
            FieldRow("最低级别 TRACE/DEBUG/INFO/WARN/ERROR", minLevel) { minLevel = it.uppercase() }
            FieldRow("工具默认超时秒数", timeout) { timeout = it }
        }
        Card("工具覆盖范围（按分组单独开关）") {
            for (g in ToolGroups.ALL) {
                val label = ToolGroups.DESCRIPTIONS[g] ?: ""
                SwitchRow("$g — $label", groups[g] ?: true) { v ->
                    val copy = LinkedHashMap(groups)
                    copy[g] = v
                    groups = copy
                }
            }
            FieldRow("允许清单（逗号分隔，空=全部）", allowed) { allowed = it }
            FieldRow("拒绝清单（逗号分隔，优先级最高）", denied) { denied = it }
        }
        Card("持久化") {
            KV("设置文件", McpSettingsStore.file()?.absolutePath ?: "(未初始化)", mono = false)
            Text("该文件是普通 JSON，可在 IDE 编辑器里手工修改，然后用「重载文件」或 acs_mcp_config_get 生效。", color = MUTED, fontSize = 10.sp)
            ActionRow(
                listOf(
                    "保存并应用" to {
                        val p = port.trim().toIntOrNull()
                        if (p == null || p < 1024 || p > 65535) {
                            notify("端口无效")
                        } else {
                            McpSettingsStore.update { st ->
                                st.enabled = enabled
                                st.autoStart = autoStart
                                st.showConsoleOnLaunch = showConsole
                                st.bindAll = bindAll
                                st.requireAuth = requireAuth
                                st.readOnly = readOnly
                                st.port = p
                                st.authToken = token.trim()
                                st.shellCommand = shell.trim()
                                st.shellArgs = shellArgs.trim()
                                st.gradleCommand = gradleCmd.trim()
                                st.gradleJvmArgs = gradleJvm.trim()
                                st.hostShell = hostShell.trim()
                                st.adbCommand = adbCmd.trim()
                                st.logLimit = (logLimit.trim().toIntOrNull() ?: 500).coerceIn(50, 20000)
                                st.minLogLevel = minLevel.trim().uppercase()
                                st.requestTimeoutSec = (timeout.trim().toIntOrNull() ?: 600).coerceIn(5, 7200)
                                st.toolGroups.clear()
                                st.toolGroups.putAll(groups)
                                for (g in ToolGroups.ALL) if (!st.toolGroups.containsKey(g)) st.toolGroups[g] = true
                                st.allowedTools.clear()
                                st.allowedTools.addAll(splitList(allowed))
                                st.deniedTools.clear()
                                st.deniedTools.addAll(splitList(denied))
                            }
                            notify("已保存并应用")
                        }
                    },
                    "重载文件" to { McpSettingsStore.reload(); notify("已从磁盘重载") },
                    "恢复默认" to { McpSettingsStore.resetToDefaults(true); notify("已恢复默认（保留 Token）") },
                ),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TEXT, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FieldRow(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MUTED, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            textStyle = TextStyle(color = TEXT, fontSize = 12.sp, fontFamily = MONO),
            singleLine = true,
        )
    }
}

@Composable
private fun ConsoleFooter(status: Map<String, Any?>, settings: McpSettings) {
    val endpoints = Json.asList(status["endpoints"]).mapNotNull { Json.str(it) }
    Row(
        Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            endpoints.firstOrNull() ?: "(未监听)",
            color = ACCENT, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.weight(1f),
        )
        if (settings.readOnly) Text("只读 ", color = WARN, fontSize = 10.sp)
        if (!settings.requireAuth) Text("无认证 ", color = BAD, fontSize = 10.sp)
        Text("v${settings.serverVersion}", color = MUTED, fontSize = 10.sp)
    }
}

private fun splitList(v: String): List<String> =
    v.split(Regex("[,\\n;]+")).map { it.trim() }.filter { it.isNotEmpty() }

private fun msg(m: Map<String, Any?>): String =
    if (Json.boolOf(m, "ok", false)) "ok" else (Json.strOf(m, "error") ?: "failed")
