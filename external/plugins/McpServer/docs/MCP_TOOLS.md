# MCP 工具清单（覆盖整个项目软件）

服务器：`acs-mcp` v1.0 · 协议 `2025-06-18`（同时兼容 `2025-03-26` / `2024-11-05` / `2024-10-07`）
传输：Streamable HTTP `POST|GET|DELETE /mcp`，兼容 HTTP+SSE `GET /sse` + `POST /messages`，
探活 `GET /health`，状态页 `GET /`。

> `*` = 变更类工具，在 `readOnly=true` 时被拒绝。
> 每个工具都可用设置里的 `toolGroups` 按组开关，或用 `allowedTools` / `deniedTools` 精确控制。

## ide — IDE 状态与环境（4）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_ide_status` | — | 环境是否初始化、当前项目、各类目录、SDK/Flutter 路径、rootfs JAVA_HOME、编辑器/LSP/UI 可用性、语言服务器、MCP 监听与会话统计、工具分组状态、调用统计 |
| `acs_env_get` | `filter?`, `redact?=true` | IDE 子进程环境变量表；含 token/secret/password 的键自动打码 |
| `acs_device_info` | — | Android 版本/SDK、ABI、型号、CPU、JVM 内存、getprop 关键属性 |
| `acs_paths` | — | 所有 IDE 自有目录 + acs-mcp 配置目录/文件路径 |

## project — 项目（3）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_project_list` | `root?`, `maxEntries?=200` | 扫描两个项目根，标注 Gradle/Flutter、wrapper、.git、是否当前打开、最近修改 |
| `acs_project_info` | `projectDir?` | 解析 settings.gradle 的 include 模块、gradle.properties 关键项、local.properties 的 sdk.dir、app 模块的 applicationId/versionName/versionCode/min/target/compileSdk、已有 APK/AAB 产物、git 状态摘要 |
| `acs_project_tree` | `projectDir?`, `depth?=3`, `maxEntries?=800`, `includeHidden?=false`, `skipDirs?` | 有界目录树，同时返回缩进文本与结构化数据 |

## file — 文件（7）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_file_list` | `path?`, `maxEntries?=300` | 目录直接子项 |
| `acs_file_stat` | `path`, `hash?=true` | 元信息 + 行数 + SHA-256（≤8MB） |
| `acs_file_read` | `path`, `startLine?=1`, `maxLines?=500`, `maxBytes?=262144`, `withLineNumbers?=false` | 按行分页读取；识别并拒绝二进制 |
| `acs_file_write` * | `path`, `content`, `mode?=overwrite`, `backup?=true`, `createDirs?=true` | 覆盖/追加写；默认先备份 `<name>.mcp-bak` |
| `acs_file_mkdir` * | `path` | 创建目录（含父目录） |
| `acs_file_delete` * | `path`, `recursive?=false` | 删除文件/目录 |
| `acs_file_search` | `query`, `root?`, `regex?=false`, `caseSensitive?=false`, `fileGlob?`, `contentSearch?=true`, `maxHits?=200`, `maxFileSizeBytes?`, `maxFilesScanned?`, `skipDirs?` | 文件名 + 内容搜索，返回命中文件与行号/行内容 |

所有路径都经过 `FileGuard`：只允许 IDE 自有目录（projectsDir / acsRootProjects / filesDir /
homeDir / localDir / tmpDir / androidSdkDir / flutterDir / `/storage/emulated/0` / `/data/local/tmp`）。

## build — 构建（5）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_gradle_run` * | `tasks[]`, `projectDir?`, `args?[]`, `timeoutSec?=1800`, `offline?`, `refreshDependencies?`, `maxOutputChars?` | 在 IDE 构建环境里跑 Gradle；自动用 `./gradlew`、附加 `--console=plain`；返回退出码/耗时/stdout/stderr 与末尾行 |
| `acs_gradle_tasks` | `projectDir?`, `all?=true`, `timeoutSec?=600` | 列出可用任务 |
| `acs_gradle_projects` | `projectDir?`, `timeoutSec?=300` | 模块结构 |
| `acs_build_apk` * | `projectDir?`, `variant?=debug`, `module?=app`, `timeoutSec?=2400` | 跑 `:<module>:assemble<Variant>`，成功后定位 APK 并返回路径/大小/SHA-256 |
| `acs_artifacts_find` | `projectDir?`, `extensions?=["apk"]`, `maxResults?=20`, `hash?=true` | 查找 apk/aab/jar/aar/so/dex 产物 |

## deploy — 安装 / 运行 / 日志（8）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_install_apk` * | `apkPath`, `reinstall?=true`, `downgrade?=false`, `useAdbOnly?=false`, `timeoutSec?=600` | 先 `pm install`，失败自动回退 `adb install`；两次尝试的原始输出都返回 |
| `acs_uninstall_app` * | `packageName`, `keepData?=false` | 卸载 |
| `acs_launch_app` * | `packageName`, `activity?`, `extras?` | `am start -n` 或 `monkey` 拉起 LAUNCHER；失败回退 adb |
| `acs_stop_app` * | `packageName` | `am force-stop` |
| `acs_packages_list` | `filter?`, `thirdPartyOnly?=true`, `showPath?=false` | `pm list packages` |
| `acs_logcat_dump` | `lines?=500`, `packageName?`, `priority?`, `grep?`, `clear?=false`, `buffer?=main`, `maxOutputChars?` | 读取 logcat（宿主侧） |
| `acs_logcat_clear` * | — | `logcat -c` |
| `acs_crash_report` | `packageName?`, `lines?=1200` | crash 缓冲区 + FATAL/AndroidRuntime/Fatal signal/ClassNotFound/VerifyError/SIGSEGV/ANR 关键字汇总 + tombstones 列表 |

## shell — 命令执行（4）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_shell_exec` * | `command`, `cwd?`, `timeoutSec?=600`, `extraEnv?`, `maxOutputChars?` | IDE 终端环境（proot/acsenv）里执行；自动挂载 SDK/存储/项目目录并注入 IDE 环境变量 |
| `acs_shell_argv` * | `argv[]`, `cwd?`, `timeoutSec?=300`, `attachSdk?=true`, `attachStorage?=true` | 不经 shell，直接以 argv 启动可执行文件 |
| `acs_host_exec` * | `script`, `cwd?`, `timeoutSec?=120` | Android 宿主侧 `hostShell -c`，可访问 pm/am/logcat/getprop/dumpsys |
| `acs_adb` * | `args[]`, `timeoutSec?=180` | 走 `adbCommand`（无线调试 / USB-OTG），拿到完整设备能力 |

## editor — 当前编辑器（8）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_editor_status` | — | 可用性、光标（0 基/1 基）、选区、可撤销/重做、行数与字符数 |
| `acs_editor_read` | `startLine?=1`, `maxLines?=1000`, `withLineNumbers?=false` | 读取当前文件内容 |
| `acs_editor_write` * | `content` | 整体替换（进撤销历史） |
| `acs_editor_insert` * | `text`, `line?`, `column?` | 光标处插入（可先移动光标） |
| `acs_editor_replace` * | `find`, `replace`, `all?=true`, `regex?=false`, `caseSensitive?=true` | 全文查找替换，返回替换次数（整步可撤销） |
| `acs_editor_cursor` | `line?`, `column?` | 读取或设置光标 |
| `acs_editor_selection` | `selectAll?=false`, `replace?` | 读取选区 / 全选 / 替换选区 |
| `acs_editor_command` * | `command` ∈ undo·redo·format·copy·cut·paste·selectAll·deleteSelection | 执行编辑器命令，format 走当前语言服务器 |

## lsp — 语言服务器（7）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_lsp_status` | `languageId?` | 已注册/正在运行的语言、扩展名映射 |
| `acs_lsp_start` * | `languageId` | 启动（kotlin/java/xml/dart/python…） |
| `acs_lsp_stop` * | `languageId` | 停止 |
| `acs_lsp_stop_all` * | — | 全部停止 |
| `acs_lsp_detect` | `pathOrName` | 由文件名/路径推断语言 ID |
| `acs_lsp_extensions` * | `action?=list`, `extension?`, `languageId?` | 查看/注册/注销扩展名映射 |
| `acs_lsp_document` * | `action`(open/close/changed), `path`, `content?`, `version?` | 发送文档生命周期事件，让外部修改重新参与诊断 |

> `PluginApi.lsp` 只在 `EditorActivity` 内非空（IDE 设计），未打开文件时这些工具会明确返回原因。

## mcp — 服务器自管理（12）

| 工具 | 参数 | 作用 |
|---|---|---|
| `acs_mcp_status` | — | 运行状态、端口/绑定、endpoint、会话、统计、工具数、lastError |
| `acs_mcp_config_get` | `includeToken?=false` | 完整配置（token 打码）+ 设置文件路径 + 分组明细 |
| `acs_mcp_config_set` * | `settings{}`, `restart?=auto`, `reset?=false` | 在线改配置并立即生效；端口/绑定/开关变化自动重启监听 |
| `acs_mcp_tools` | `group?`, `includeSchema?=false`, `onlyEnabled?=false` | 全工具目录：分组、是否启用、被禁用原因、描述、inputSchema |
| `acs_mcp_calls` | `n?=50`, `tool?`, `onlyFailed?=false`, `stats?=true` | 最近调用记录与聚合统计 |
| `acs_mcp_log` | `n?=200`, `level?`, `clear?=false` | 读取控制台日志 |
| `acs_mcp_log_clear` * | `calls?=true` | 清空日志与调用历史 |
| `acs_mcp_start` * / `acs_mcp_stop` * / `acs_mcp_restart` * | — | 监听生命周期 |
| `acs_mcp_endpoint` | `includeToken?=true` | 可直接粘贴的连接信息与 Cursor/Cline/VS Code 配置片段 |
| `acs_mcp_selftest` | `timeoutMs?=8000` | 本机真实 socket 走 `initialize → tools/list → tools/call(acs_ide_status)`，逐步返回状态行与耗时 |

## resources（7）

`acs://status` · `acs://settings` · `acs://project` · `acs://paths` · `acs://tools` · `acs://log` · `acs://calls`

## prompts（3）

| 名称 | 参数 | 用途 |
|---|---|---|
| `build_and_install` | `variant?`, `package?` | 引导 AI：确认模块 → 构建 → 安装 → 启动 → 失败时抓崩溃证据 |
| `diagnose_build_failure` | `task?` | 引导 AI：重跑失败任务（--stacktrace）→ 核对环境 → 区分第一个失败任务与下游噪声 |
| `explore_project` | — | 引导 AI 用 20 行以内总结项目结构与关键配置 |

## 典型链路

```
acs_ide_status → acs_project_info → acs_file_search / acs_file_read
   → acs_file_write（改动）
   → acs_gradle_run 或 acs_build_apk（构建）
   → acs_install_apk（安装） → acs_launch_app（启动）
   → acs_logcat_dump / acs_crash_report（验证）
   → acs_mcp_calls / acs_mcp_log（复盘）
```
