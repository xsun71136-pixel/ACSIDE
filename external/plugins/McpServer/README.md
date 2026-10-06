# McpServer — Android Code Studio 的 MCP 服务器插件

在 ACSIDE 内部启动一个 **Model Context Protocol (MCP) 服务器**，把「整个 IDE + 当前项目软件」
的能力以标准 MCP 工具的形式暴露给外部 AI 客户端。面板挂在 **输出与调试**，并且有 **独立的设置页**。

```
外部 AI 客户端 (Cursor / Cline / VS Code / mcp-remote)
        │  JSON-RPC 2.0 over Streamable HTTP (+ 兼容 HTTP/SSE)
        ▼
ACSIDE 进程内的 McpServer  ──►  PluginApi.editor / environment / lsp / process / templates / ui
        │
        └──► proot/acsenv: JDK · Gradle · Android SDK · 终端包
        └──► Android 宿主: pm · am · logcat · getprop（以及可选 adb）
```

---

## 1. 为什么是插件

ACSIDE 的 IDE 本体是闭源的（仓库根 README 的 *About the Source Code* 一节），
公开仓库只有 `core/resources` 与 `external/tooling/atc` 两个模块，没有 app 模块，
所以无法通过改 IDE 源码来加功能，也无法在云端打出完整的 IDE APK。

官方支持的扩展点是 **插件系统**：

- 插件 API：`com.github.AndroidCSIDE:acside-plugins-api`（JitPack）
- 打包插件：`io.github.nullij.acside-gradle-plugin`（Maven Central），产出加密的 `.acp`
- 插件仓库格式：`repository.json`（见 `AndroidCSIDE/androidcs-plugins`）

本目录就是一个完整的、可云端构建的 ACSIDE 插件工程。

## 2. 目录结构

```
external/plugins/McpServer/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml          # Kotlin 2.3.0 · AGP 8.13.0 · Compose 1.7.6 · API 0.3.1
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.13
├── repository.json                    # 插件仓库清单（CI 会刷新 checksum/size/lastUpdated）
├── docs/MCP_TOOLS.md                  # 全部 MCP 工具与客户端接入配置
└── main/
    ├── build.gradle.kts               # acpPlugin { metaFolderPath="meta"; outputFileName="McpServer.acp" }
    ├── meta/{plugin.json,actions.json,icon.png}
    └── src/main/kotlin/io/github/xsun71136/plugins/mcp/
        ├── McpServerPlugin.kt         # 插件入口（actions.json 指向 setContext/getContext）
        ├── McpSettings.kt             # 独立设置模型 + settings.json 持久化 + 工具分组/只读清单
        ├── McpLog.kt                  # 「输出与调试」控制台日志环（同时打到 logcat tag ACS-MCP）
        ├── json/Json.kt               # 零依赖 JSON 编解码（不打包任何三方库）
        ├── mcp/McpServer.kt           # HTTP/1.1 + SSE 监听、会话、鉴权、CORS、状态页
        ├── mcp/McpDispatch.kt         # JSON-RPC 分发：initialize/tools/resources/prompts/logging
        ├── mcp/OverlayHandleRef.kt
        ├── tools/…                    # 9 组 40 个工具 + 注册表 + 调用历史 + 路径沙箱
        └── ui/McpConsole.kt           # 输出 / 调试 / MCP 设置 三页签面板
```

## 3. 输出与调试面板

打开任意项目文件后（`actions.json` 的 `showIn: onEditorActivityLaunched`）自动出现，
也可通过 `acs_mcp_config_set` 关闭自启：

| 页签 | 内容 |
|---|---|
| **输出** | 运行状态、绑定地址/端口、只读与认证状态、uptime、会话/请求/工具调用计数、endpoint 列表、启动/停止/重启/复制地址、实时日志（可清空） |
| **调试** | 本机 socket 回环自检（`initialize → tools/list → tools/call`）、插件探针、**工具覆盖表**（9 组分别显示工具数与启用状态）、最近 100 条调用（参数摘要/耗时/来源/错误） |
| **MCP 设置** | 本插件的独立设置页，见下节；保存后立即生效，端口/绑定变化会自动重启监听 |

日志同时写入 logcat（tag `ACS-MCP`），所以也能在 IDE 自己的日志输出里看到。

## 4. 可以单独设置

设置文件：`<IDE filesDir>/acs-mcp/settings.json`（普通 JSON，可直接在编辑器里改，
面板「重载文件」或 `acs_mcp_config_get` 生效）。

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `autoStart` | `true` | 进入编辑器时自动监听 |
| `showConsoleOnLaunch` | `true` | 自动弹出「输出与调试」面板 |
| `bindAll` | `false` | `false`=只听 127.0.0.1；`true`=听所有网卡（局域网可访问） |
| `port` | `8388` | 监听端口 |
| `requireAuth` | `true` | 是否要求 `Authorization: Bearer <token>` |
| `authToken` | 首次运行随机生成 | 也支持 `x-mcp-token` 头或 `?token=` |
| `readOnly` | `false` | 只读安全模式：拒绝全部写入/执行类工具 |
| `maxBodyBytes` | `4194304` | 请求体上限 |
| `requestTimeoutSec` | `600` | 工具默认超时 |
| `logLimit` / `minLogLevel` | `500` / `INFO` | 控制台日志保留行数与最低级别 |
| `serverName` / `serverVersion` / `protocolVersion` | `acs-mcp` / `1.0` / `2025-06-18` | `initialize` 应答内容 |
| `shellCommand` / `shellArgs` | `/bin/bash` / `-lc` | IDE 环境（proot）里的解释器 |
| `gradleCommand` | `auto` | `auto`=优先 `./gradlew`；也可写 `gradle` 或绝对路径 |
| `gradleJvmArgs` | `-Xmx2048m -Dfile.encoding=UTF-8` | 写入 `GRADLE_OPTS` |
| `hostShell` | `/system/bin/sh` | 宿主侧执行 pm/am/logcat 用的 shell |
| `adbCommand` | `adb` | 无线调试 / USB-OTG 场景下的 adb |
| `extraEnv` | `{}` | 合并进每个 IDE 环境进程的额外环境变量 |
| `toolGroups` | 全部 `true` | 按组开关：`ide/project/file/build/deploy/shell/editor/lsp/mcp` |
| `allowedTools` | `[]` | 允许清单（空=全部） |
| `deniedTools` | `[]` | 拒绝清单（优先级最高） |

## 5. MCP 覆盖整个项目软件

9 组共 40 个工具，完整清单见 [docs/MCP_TOOLS.md](docs/MCP_TOOLS.md)：

- **ide** — `acs_ide_status` `acs_env_get` `acs_device_info` `acs_paths`
- **project** — `acs_project_list` `acs_project_info` `acs_project_tree`
- **file** — `acs_file_list` `acs_file_stat` `acs_file_read` `acs_file_write` `acs_file_mkdir` `acs_file_delete` `acs_file_search`
- **build** — `acs_gradle_run` `acs_gradle_tasks` `acs_gradle_projects` `acs_build_apk` `acs_artifacts_find`
- **deploy** — `acs_install_apk` `acs_uninstall_app` `acs_launch_app` `acs_stop_app` `acs_packages_list` `acs_logcat_dump` `acs_logcat_clear` `acs_crash_report`
- **shell** — `acs_shell_exec` `acs_shell_argv` `acs_host_exec` `acs_adb`
- **editor** — `acs_editor_status` `acs_editor_read` `acs_editor_write` `acs_editor_insert` `acs_editor_replace` `acs_editor_cursor` `acs_editor_selection` `acs_editor_command`
- **lsp** — `acs_lsp_status` `acs_lsp_start` `acs_lsp_stop` `acs_lsp_stop_all` `acs_lsp_detect` `acs_lsp_extensions` `acs_lsp_document`
- **mcp** — `acs_mcp_status` `acs_mcp_config_get` `acs_mcp_config_set` `acs_mcp_tools` `acs_mcp_calls` `acs_mcp_log` `acs_mcp_log_clear` `acs_mcp_start` `acs_mcp_stop` `acs_mcp_restart` `acs_mcp_endpoint` `acs_mcp_selftest`

另有 7 个 `resources`（`acs://status|settings|project|paths|tools|log|calls`）与
3 个 `prompts`（`build_and_install`、`diagnose_build_failure`、`explore_project`）。

## 6. 客户端接入

```jsonc
// Cursor / VS Code / Cline 等支持 streamable-http 的客户端
{
  "mcpServers": {
    "acs": {
      "url": "http://127.0.0.1:8388/mcp",
      "headers": { "Authorization": "Bearer <acs-mcp/settings.json 里的 authToken>" }
    }
  }
}
```

- 同机客户端（Termux/adb 转发）：`adb forward tcp:8388 tcp:8388` 后用 127.0.0.1。
- 局域网客户端：把 `bindAll` 设为 `true`，用 `acs_mcp_endpoint` 返回的局域网地址。
- Claude Desktop 只支持 stdio：用 `mcp-remote` 之类的桥接器指向上面的 url。
- 探活：`GET /health`（无需认证）；状态页：浏览器打开 `GET /`。

## 7. 云端构建

`.github/workflows/mcp-plugin.yml`：

1. JDK 17（temurin）+ Android SDK（`platforms;android-36`、`build-tools;35.0.0`）+ Gradle 8.13；
2. `gradle :main:assembleRelease`（失败自动回退 `assembleDebug`）；
3. 官方 `acside-gradle-plugin` 产出 `main/build/McpServer.acp`，`updateRepositoryJson`
   任务把它复制到插件根目录并刷新 `repository.json` 的 `checksum/size/latestVersion/lastUpdated`；
4. 校验 `.acp` 的容器魔数 `ACSPluginBasedSystem-1.0`、大小与 SHA-256；
5. 上传 workflow artifact `McpServer-acp`；`workflow_dispatch` 且 `publish=true` 时
   额外创建 Release 并把 `McpServer.acp` 作为 `releases/latest/download/McpServer.acp` 发布。

本地构建（在 IDE 里或任意装了 JDK17 + Android SDK 的环境）：

```bash
cd external/plugins/McpServer
gradle :main:assembleRelease      # 或 ./gradlew，如果补上了 wrapper jar
ls -l McpServer.acp               # 产物
```

## 8. 安全

- 默认只听 `127.0.0.1`，默认要求 Bearer Token（首次运行随机生成，只存在设备本地的 settings.json）。
- `readOnly=true` 时全部写入/执行类工具被拒绝（清单在 `ToolGroups.MUTATING`，注册时会自检名单是否漂移）。
- 文件工具受 `FileGuard` 约束：只能访问 IDE 自有目录（projectsDir / acsRootProjects /
  filesDir / homeDir / localDir / tmpDir / androidSdkDir / flutterDir / `/storage/emulated/0` / `/data/local/tmp`）。
- `acs_env_get` 默认对含 token/secret/password 的键打码。
- 覆盖写文件默认先备份成 `<name>.mcp-bak`。
- 想彻底断开：`enabled=false`（或 `acs_mcp_stop`）。

## 9. 已知限制

- `pm` / `am` / 全量 `logcat` 在宿主侧需要 shell 或 Shizuku 权限；没有权限时
  `acs_install_apk` 会自动回退到 `acs_adb`，`acs_logcat_dump` 会在结果里明确说明只拿到本应用日志。
  要完整能力请在设置里配置可用的 `adbCommand`（无线调试 / USB-OTG）。
- `PluginApi.lsp` 与 `PluginApi.ui` 只在 `EditorActivity` 内可用（IDE 的设计），
  所以要先打开一个项目文件，面板与 LSP 工具才可用；服务器本身不受影响。
- `IProcessApi` 启动的进程在 proot/acsenv 内，`shellCommand` 若与实际环境不符，
  在设置里改成正确的解释器路径即可。
