# CODEX_GO.md

本文档面向接手电脑上的 agent。目标不是解释项目背景，而是把 `xiaokeer-idea-psi-mcp` 从 GitHub 源码落地到本机 Codex，并验证到健康可用。

## 目标

把本仓库安装为本机 Codex 可调用的本地 MCP server：

```text
Codex
  -> stdio MCP server: mcp-server/src/mcp.ts
  -> 127.0.0.1 token-authenticated HTTP bridge
  -> IntelliJ IDEA plugin
  -> IDEA committed PSI, Smart Mode, ReferencesSearch
```

成功标准：

- Codex 新会话能看到 `mcp__xiaokeer_idea_psi` 工具命名空间。
- `psi_search_symbol`、`psi_resolve_symbol`、`psi_find_usages` 均能在一个真实打开的 TypeScript 或 JavaScript 项目中返回 `ok: true`。
- 返回结果的 `sourceState` 是 `ideCommittedPsi`。
- 失败时能得到稳定错误码，而不是静默失败或退化为文本搜索。

## 唯一事实

- 本项目是本地 harness，不是业务项目运行时依赖。
- `plugin/` 是 IntelliJ IDEA 插件，负责 PSI、Smart Mode、read action、项目边界和引用搜索。
- `mcp-server/` 是 TypeScript stdio MCP server，负责 MCP tool schema、输入校验、读取运行时 manifest、调用插件 HTTP 服务、返回 structuredContent。
- MCP server 不提供 TypeScript Language Service、SCIP、grep 或磁盘扫描语义兜底。
- 当前 IDEA 目标来自 `plugin/build.gradle.kts`：IntelliJ IDEA Ultimate `2026.1.2`，插件兼容 build `261.*`，依赖 bundled `JavaScript` plugin。
- 当前 Codex 注册脚本来自 `scripts/runtime.mjs`。

## 不允许做的事

- 不要把 `.xiaokeer/`、`node_modules/`、`plugin/build/`、`mcp-server/dist/`、`.gradle/` 或本机日志提交到 Git。
- 不要把 runtime manifest、bearer token、完整请求体或大段 PSI dump 写入 README、issue、日志或提交。
- 不要为了通过验证而增加临时 env 开关、兼容旧逻辑、fallback 搜索或伪造 `ok: true`。
- 不要在 stdout 写普通日志；stdio MCP 的 stdout 只属于 JSON-RPC 协议。诊断信息应走 stderr、CLI 命令输出或结构化错误。
- 不要承诺 Codex 配置热加载。修改 MCP 配置后，用新 Codex 会话或重启 Codex 客户端验证。

## 前置条件

在目标电脑上确认：

```bash
git --version
node --version
pnpm --version
codex --version
```

需要：

- macOS。当前安装脚本默认按 macOS 用户目录和 JetBrains 插件目录处理。
- Node.js 与 pnpm 可用。
- Codex CLI 或 Codex desktop 可用，并读取同一个 `~/.codex/config.toml`。
- IntelliJ IDEA Ultimate `2026.1.x` 可用，且 JavaScript plugin 启用。
- 目标 TypeScript 或 JavaScript 项目已经在 IntelliJ IDEA 中打开并完成索引。

如果 `pnpm` 不在 PATH 中，设置：

```bash
export XIAOKEER_IDEA_PSI_PNPM_BIN=/absolute/path/to/pnpm
```

如果 IDEA 插件目录不是默认位置，设置：

```bash
export XIAOKEER_IDEA_PSI_IDEA_PLUGINS_DIR="/absolute/path/to/JetBrains/IntelliJIdea2026.1/plugins"
```

## 获取源码

选择一个长期保留的位置，不要放到临时目录：

```bash
mkdir -p ~/project
cd ~/project
git clone https://github.com/15102045545/xiaokeer-idea-psi-mcp.git
cd xiaokeer-idea-psi-mcp
```

检查工作树：

```bash
git status --short --branch
```

如果已有非你产生的改动，先确认它们属于当前安装任务；不要随手覆盖。

## 安装依赖和构建

安装 MCP server 依赖：

```bash
pnpm --dir mcp-server install
```

验证 MCP TypeScript：

```bash
pnpm mcp:typecheck
```

构建 IDEA 插件：

```bash
pnpm plugin:build
```

期望插件包存在：

```text
plugin/build/distributions/xiaokeer-idea-psi-mcp-plugin-1.0.0.zip
```

如果 Gradle 下载失败，先修复网络或 Gradle 环境；不要绕过插件构建验证。

## 安装 IDEA 插件

把构建产物安装进本机 IntelliJ IDEA 插件目录：

```bash
node scripts/runtime.mjs install-plugin
```

成功输出应包含：

```json
{
  "ok": true,
  "action": "install-plugin",
  "restartRequired": true
}
```

然后重启 IntelliJ IDEA。

打开一个真实 TypeScript 或 JavaScript 项目，等待 IDEA 索引结束。工具依赖 Smart Mode；Dumb Mode 或索引中会返回 `index_not_ready`。

## 验证插件 HTTP 服务

插件启动后会写用户本地 manifest：

```text
~/Library/Application Support/xiaokeer-idea-psi-mcp/runtime.json
```

不要打印或提交其中的 token。

检查插件健康状态：

```bash
pnpm --dir mcp-server cli health
```

成功标准：

- `ok: true`
- `service: "xiaokeer-idea-psi-mcp"`
- `host: "127.0.0.1"`
- `projects` 中包含你在 IDEA 打开的目标项目 `basePath`

列出打开项目：

```bash
pnpm --dir mcp-server cli projects
```

如果没有目标项目，回到 IDEA 打开项目根目录并等待索引完成。

## 独立验证 MCP stdio server

用项目中的 smoke 先验证 stdio MCP server 能启动、列工具、调用插件：

```bash
pnpm --dir mcp-server mcp:smoke -- --project-path "/absolute/path/to/your/project" --query "ExistingSymbolName"
```

`ExistingSymbolName` 必须是目标项目里 IDEA 能搜到的真实导出或命名符号。成功输出应包含：

```json
{
  "ok": true,
  "tools": [
    "psi_find_usages",
    "psi_resolve_symbol",
    "psi_search_symbol"
  ],
  "searchEnvelopeOk": true
}
```

如果没有现成符号，先在项目源码中找一个稳定的 TypeScript/JavaScript 函数、类、变量或导出名。不要新增临时代码来制造符号。

可选：使用 MCP Inspector 验证 stdio 协议：

```bash
npx @modelcontextprotocol/inspector pnpm --dir "$(pwd)/mcp-server" exec tsx src/mcp.ts
```

Inspector 能连接并列出三类工具，说明 MCP 进程本身可被 client 启动。

## 注册到 Codex

运行注册脚本：

```bash
node scripts/runtime.mjs install
```

脚本会：

- 创建或更新 `~/.codex/bin/xiaokeer-idea-psi-mcp.sh`
- 在 `~/.codex/config.toml` upsert：

```toml
[mcp_servers.xiaokeer-idea-psi]
command = "/Users/<you>/.codex/bin/xiaokeer-idea-psi-mcp.sh"
```

wrapper 使用安装时解析到的 pnpm 绝对路径。若解析失败，设置 `XIAOKEER_IDEA_PSI_PNPM_BIN` 后重新运行安装。

也可以使用 Codex CLI 写入 stdio server 配置：

```bash
codex mcp add xiaokeer-idea-psi -- "$HOME/.codex/bin/xiaokeer-idea-psi-mcp.sh"
```

如果 `node scripts/runtime.mjs install` 已经注册同名 MCP，不要重复执行这条命令。CLI 入口适用于手动创建等价 wrapper 后再写入 Codex 配置的场景。

如需手动配置，也必须使用绝对路径：

```toml
[mcp_servers.xiaokeer-idea-psi]
command = "/Users/<you>/.codex/bin/xiaokeer-idea-psi-mcp.sh"
startup_timeout_sec = 30
tool_timeout_sec = 60
enabled = true
```

如果需要固定环境变量，使用 server 配置下的 env 表；如果需要从本机或远端转发环境变量，使用 Codex 支持的 `env_vars`。不要混淆固定注入与转发。

修改 `~/.codex/config.toml` 后，开启新 Codex 会话或重启 Codex 客户端。

## Codex 内验收

在新 Codex 会话中，先确认工具命名空间已经暴露：

```text
mcp__xiaokeer_idea_psi.psi_search_symbol
mcp__xiaokeer_idea_psi.psi_resolve_symbol
mcp__xiaokeer_idea_psi.psi_find_usages
```

用目标项目真实符号跑三段验收。

1. 搜索符号：

```json
{
  "projectPath": "/absolute/path/to/your/project",
  "query": "ExistingSymbolName",
  "limit": 10,
  "includeSnippet": true,
  "waitForSmartModeMs": 5000
}
```

期望：

- `ok: true`
- `sourceState: "ideCommittedPsi"`
- `data.matches` 至少包含目标符号

2. 解析引用或定义：

```json
{
  "projectPath": "/absolute/path/to/your/project",
  "filePath": "relative/path/to/file.ts",
  "line": 10,
  "column": 15,
  "includeSnippet": true,
  "waitForSmartModeMs": 5000
}
```

期望：

- `ok: true`
- `data.target` 或 `data.primaryTarget` 指向 IDEA PSI 解析出的声明

3. 查找 usages：

```json
{
  "projectPath": "/absolute/path/to/your/project",
  "filePath": "relative/path/to/definition.ts",
  "line": 10,
  "column": 15,
  "includeSnippet": true,
  "limit": 50,
  "waitForSmartModeMs": 5000
}
```

期望：

- `ok: true`
- `data.usages` 包含 definition 和真实引用位置

只有三段都通过，才能把本机 Codex 接入状态标记为健康可用。

## Doctor

本仓库提供聚合检查：

```bash
node scripts/runtime.mjs doctor
```

关注字段：

- `wrapperExists`
- `codexConfigRegistered`
- `dependenciesInstalled`
- `pluginZipExists`
- `pluginInstalled`
- `pluginHealth.ok`

`doctor.ok` 为 false 时，先修复对应字段，再重新打开 Codex 新会话验证。

## 常见失败和处理

### Codex 中没有工具命名空间

检查：

```bash
node scripts/runtime.mjs doctor
cat ~/.codex/config.toml
ls -l ~/.codex/bin/xiaokeer-idea-psi-mcp.sh
```

处理：

- 确认 `command` 是绝对路径。
- 确认 wrapper 可执行。
- 确认 `pnpm` 路径有效。
- 开启新 Codex 会话或重启 Codex 客户端。

### `runtime_manifest_missing`

IDEA 插件尚未运行或没有写 manifest。

处理：

- 确认插件已安装。
- 重启 IntelliJ IDEA。
- 打开目标项目。
- 等待索引完成。
- 重新运行 `pnpm --dir mcp-server cli health`。

### `plugin_not_available`

manifest 存在，但 loopback HTTP 服务不可达。

处理：

- 重启 IntelliJ IDEA。
- 确认没有旧 manifest 指向已退出的 IDEA 进程。
- 重新检查 `cli health`。

### `project_not_open`

传入的 `projectPath` 不是 IDEA 当前打开项目根目录。

处理：

- 用 `pnpm --dir mcp-server cli projects` 查看可用 `basePath`。
- 传入完全一致的绝对路径。

### `path_outside_project` 或 `file_not_found`

`filePath` 不在 `projectPath` 内，或路径拼写错误。

处理：

- 优先传项目相对路径。
- 不要传 symlink 后的另一个根路径。

### `unsupported_language`

当前工具只面向 TypeScript 和 JavaScript PSI 场景。

处理：

- 换 `.ts`、`.tsx`、`.js`、`.jsx` 相关文件。
- 不要用它处理 Java/Kotlin/Python/Markdown。

### `invalid_position`

`line` 或 `column` 不是 1-based UTF-16 位置，或不在符号上。

处理：

- 用 `nl -ba` 或编辑器行列信息确认。
- `column` 指向标识符内部，而不是空白或标点。

### `index_not_ready`

IDEA 仍在索引。

处理：

- 等待 Smart Mode。
- 增大 `waitForSmartModeMs`。
- 不要改成磁盘扫描兜底。

### `unresolved_symbol` 或 `symbol_not_found`

IDEA PSI 没有解析到目标。

处理：

- 确认项目依赖已安装。
- 确认 IDEA 能在编辑器中跳转定义。
- 确认符号名完全匹配。

## 维护检查清单

修改不同区域后执行对应验证：

| 修改区域 | 最小验证 |
| --- | --- |
| 文档 | 阅读 `README.md`、`CODEX_GO.md` 和相关源文件，确保唯一事实一致 |
| MCP schema/server/client | `pnpm mcp:typecheck` + `pnpm --dir mcp-server mcp:smoke -- --project-path "<project>" --query "<symbol>"` |
| 注册脚本 | `node scripts/runtime.mjs install` + `node scripts/runtime.mjs doctor` + 新 Codex 会话验收 |
| IDEA plugin | `pnpm plugin:build` + 重装插件 + `pnpm --dir mcp-server cli health` |
| 完整链路 | IDEA 打开真实项目 + smoke + Codex 三工具验收 |

## 发布前检查

开源发布前确认：

- 根目录存在 MIT `LICENSE`。
- README 说明用途、架构、安装、Codex 配置、验证和限制。
- `.gitignore` 排除依赖、构建产物、IDE 本地状态、日志和 `.xiaokeer/`。
- `git status --short` 没有意外文件。
- `git log` 和 staged diff 中没有 token、私钥、账号敏感信息或 runtime manifest。
- GitHub 仓库为 public。
- GitHub description/topics 能让其他人理解这是 `codex`、`mcp`、`intellij-idea`、`psi`、`typescript` 相关项目。

## 交付判定

当前电脑的 agent 只有在以下事实都成立时，才能向用户报告“健康可用”：

- IDEA 插件已安装或开发 IDE 已运行。
- 目标项目在 IDEA 中打开且处于 Smart Mode。
- `pnpm --dir mcp-server cli health` 返回 `ok: true`，并列出目标项目。
- `pnpm --dir mcp-server mcp:smoke -- --project-path "<project>" --query "<symbol>"` 返回 `ok: true`。
- 新 Codex 会话暴露 `mcp__xiaokeer_idea_psi` 工具命名空间。
- Codex 内三类工具均在真实项目符号上返回 `ok: true`。
