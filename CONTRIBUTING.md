# Contributing

This project is a local IntelliJ IDEA PSI harness for Codex. Contributions should preserve the core contract: successful `psi_*` results come from IDEA committed PSI, not from text search or fallback semantic engines.

## Development Setup

Install dependencies:

```bash
pnpm --dir mcp-server install
```

Build and check the MCP server:

```bash
pnpm --dir mcp-server typecheck
pnpm --dir mcp-server build
```

Build the IntelliJ IDEA plugin:

```bash
pnpm plugin:build
```

Install or run the plugin, open a TypeScript or JavaScript project in IntelliJ IDEA, then run a live smoke:

```bash
pnpm --dir mcp-server mcp:smoke -- --project-path "/absolute/path/to/project" --query "ExistingSymbolName"
```

## Change Rules

- Keep the MCP tool surface read-only.
- Keep public tool names, schemas, annotations, error envelopes, and docs in sync.
- Do not add TypeScript Language Service, SCIP, grep, or disk-scan fallback behavior for successful `psi_*` semantics.
- Do not log bearer tokens, runtime manifests, full request bodies, or large PSI dumps.
- Do not write normal logs to stdout from the stdio MCP process.
- Do not commit generated outputs, local IDE state, logs, dependency folders, or `.xiaokeer/`.

## Validation Matrix

| Changed surface | Minimum validation |
| --- | --- |
| Documentation | Read affected docs and source contracts for fact drift |
| MCP server or schema | `pnpm --dir mcp-server typecheck` and live `mcp:smoke` |
| Runtime registration | `node scripts/runtime.mjs doctor` and a new Codex session |
| IDEA plugin | `pnpm plugin:build`, reinstall or run plugin, then `cli health` |
| Full behavior | IDEA open project + smoke + Codex `psi_*` tool calls |

## Security

Report token leakage, path traversal, unauthorized loopback access, or unexpected write behavior as security issues. This repository should not contain runtime tokens, private project data, generated manifests, or secrets.
