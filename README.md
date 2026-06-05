# xiaokeer-idea-psi-mcp

`xiaokeer-idea-psi-mcp` is a local, read-only IntelliJ IDEA PSI harness for Codex. It exposes structured MCP tools that query IntelliJ IDEA's live project PSI model for TypeScript and JavaScript symbol resolution, usages, and symbol search.

The implementation is intentionally independent from `petaskApp` and `petask-code-intel-mcp`.

## Architecture

```text
Codex
  -> stdio MCP server in mcp-server/
  -> token-authenticated loopback HTTP service
  -> IntelliJ IDEA plugin in plugin/
  -> IDEA Project Model, committed PSI, Smart Mode, references, and JavaScript plugin context
```

The MCP server owns the public MCP contract. The IDEA plugin owns semantic authority. Successful `psi_*` results come from IDEA PSI; the MCP server does not provide text, TypeScript Language Service, or SCIP fallback semantics.

## Repository Layout

| Path | Role |
| --- | --- |
| `plugin/` | IntelliJ IDEA plugin, Kotlin, Gradle IntelliJ Platform Plugin. |
| `mcp-server/` | TypeScript MCP stdio server, plugin HTTP client, CLI, and smoke checks. |
| `scripts/runtime.mjs` | Local Codex registration and diagnostics helper. |
| `docs/` | Focused operating notes. |

## Tool Surface

The MCP server exposes three read-only tools:

| Tool | Purpose |
| --- | --- |
| `psi_resolve_symbol` | Resolve a symbol at a source position to its IDEA PSI declaration target. |
| `psi_find_usages` | Resolve a declaration or usage position, then return IDEA PSI reference usages. |
| `psi_search_symbol` | Search JavaScript and TypeScript named PSI symbols in an open IDEA project. |

All successful and failed tool executions return a structured envelope:

```json
{
  "ok": true,
  "tool": "psi_search_symbol",
  "projectPath": "/Users/chongwen002/project/petaskApp",
  "sourceState": "ideCommittedPsi",
  "data": {},
  "diagnostics": []
}
```

Execution failures use `ok: false` with stable error codes such as `runtime_manifest_missing`, `plugin_not_available`, `project_not_open`, `path_outside_project`, `file_not_found`, `unsupported_language`, `invalid_position`, `index_not_ready`, `unresolved_symbol`, `symbol_not_found`, `timeout`, and `internal_error`.

## Runtime Manifest

The IDEA plugin binds an internal JSON HTTP service to `127.0.0.1` on an available port, generates a fresh bearer token each startup, and writes a runtime manifest outside project repositories:

```text
~/Library/Application Support/xiaokeer-idea-psi-mcp/runtime.json
```

The MCP server reads that manifest and sends the token as a bearer token. Tokens are not logged and are not returned through MCP.

## Local Commands

Install MCP dependencies:

```bash
pnpm --dir mcp-server install
```

Check MCP server:

```bash
pnpm --dir mcp-server typecheck
pnpm --dir mcp-server build
```

Build the IDEA plugin:

```bash
cd plugin
./gradlew buildPlugin
```

Run a development IDEA with the plugin:

```bash
cd plugin
./gradlew runIde
```

Inspect the plugin runtime:

```bash
pnpm --dir mcp-server cli health
pnpm --dir mcp-server cli projects
```

Run the live MCP smoke after IntelliJ IDEA has loaded the plugin and opened `petaskApp`:

```bash
pnpm --dir mcp-server mcp:smoke
```

Register the MCP server with local Codex:

```bash
node scripts/runtime.mjs install
```

Install the built plugin into IntelliJ IDEA 2026.1:

```bash
node scripts/runtime.mjs install-plugin
```

Restart IntelliJ IDEA after installing the plugin through this local file path.

## Codex Registration

The runtime helper writes:

```text
~/.codex/bin/xiaokeer-idea-psi-mcp.sh
```

and upserts this Codex block:

```toml
[mcp_servers.xiaokeer-idea-psi]
command = "/Users/chongwen002/.codex/bin/xiaokeer-idea-psi-mcp.sh"
```

The wrapper starts the stdio MCP server directly. The IDEA plugin must already be installed or running in a development IDE for `psi_*` tools to return semantic results.

## Smoke Target

The first validation project is:

```text
/Users/chongwen002/project/petaskApp
```

Smoke symbol:

```text
getHealthSummaryPlugin
```

Expected IDEA PSI results:

- `psi_search_symbol` returns a match with `name: "getHealthSummaryPlugin"` for the exported function in `apps/server/src/modules/aiAgentTools/controllers/aiAgentTools.controller.ts`.
- `psi_resolve_symbol` resolves the route handler usage in `apps/server/src/modules/aiAgentTools/routes/index.ts`.
- `psi_find_usages` includes the controller definition, route import, and route handler argument usage.
