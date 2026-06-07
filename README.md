# xiaokeer-idea-psi-mcp

`xiaokeer-idea-psi-mcp` is a local, read-only IntelliJ IDEA PSI harness for Codex. It exposes structured MCP tools that query IntelliJ IDEA's live project PSI model for TypeScript and JavaScript symbol resolution, usages, and symbol search.

The implementation is intentionally independent from any application repository. It is infrastructure for local coding agents that need IDE-grade TypeScript and JavaScript semantics.

For a complete agent-facing installation runbook, see [`CODEX_GO.md`](./CODEX_GO.md).

## Status

- License: MIT.
- Transport: local stdio MCP server.
- IDE bridge: token-authenticated loopback HTTP service bound to `127.0.0.1`.
- IDEA target: IntelliJ IDEA Ultimate `2026.1.x` with the bundled JavaScript plugin.
- Tool scope: read-only TypeScript and JavaScript PSI queries.

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
| `CODEX_GO.md` | Agent-facing from-zero-to-healthy Codex installation guide. |

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
  "projectPath": "/absolute/path/to/project",
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

Run the live MCP smoke after IntelliJ IDEA has loaded the plugin and opened a TypeScript or JavaScript project:

```bash
pnpm --dir mcp-server mcp:smoke -- --project-path "/absolute/path/to/project" --query "ExistingSymbolName"
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
command = "/Users/<you>/.codex/bin/xiaokeer-idea-psi-mcp.sh"
```

The wrapper starts the stdio MCP server directly. The IDEA plugin must already be installed or running in a development IDE for `psi_*` tools to return semantic results.

After changing Codex MCP configuration, open a new Codex session or restart the Codex client before validating tool availability.

## Smoke Expectations

Choose a stable TypeScript or JavaScript symbol from a project currently open in IntelliJ IDEA, then run the smoke with that project path and symbol name.

Expected IDEA PSI behavior:

- `psi_search_symbol` returns a match for the requested symbol.
- `psi_resolve_symbol` resolves a usage to its declaration target.
- `psi_find_usages` includes the declaration and real reference locations.

## Limitations

- The tool surface is read-only.
- The plugin must be installed or running in IntelliJ IDEA, and the target project must be open in IDEA.
- Position arguments use 1-based UTF-16 line and column numbers.
- Successful semantic results come from IDEA committed PSI. The MCP server does not fall back to text search when PSI cannot answer.
- stdout from the stdio MCP process is reserved for MCP protocol traffic. Diagnostics belong in structured tool results, CLI output, stderr, or logs.

## License

MIT. See [`LICENSE`](./LICENSE).
