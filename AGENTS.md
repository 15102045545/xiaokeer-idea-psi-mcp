# AGENTS.md

This file is the root instruction layer for agents working inside `xiaokeer-idea-psi-mcp`.

## Core Mental Model

`xiaokeer-idea-psi-mcp` is an external read-only IntelliJ IDEA PSI harness for local Codex work. It is infrastructure, not `petaskApp` business code.

The project has two cooperating layers:

- `plugin/`: IntelliJ IDEA plugin code that runs inside the IDE process and owns PSI, project model, read actions, smart mode, and reference search.
- `mcp-server/`: TypeScript stdio MCP server that validates public tool inputs, reads the plugin runtime manifest, calls the plugin loopback HTTP service, and returns structured MCP results.

The MCP layer must not parse source files or provide TypeScript Language Service, SCIP, grep, or disk-scan semantic fallbacks for successful `psi_*` calls.

## Fact Sources

Use source and config as facts, in this order:

1. `README.md` for public behavior, operation model, and validation commands.
2. `plugin/build.gradle.kts` and `plugin/src/main/resources/META-INF/plugin.xml` for IDEA plugin dependencies and target IDE facts.
3. `plugin/src/main/kotlin/com/xiaokeer/idea/psi/mcp/` for plugin HTTP and PSI behavior.
4. `mcp-server/src/contract.ts` for MCP tool names, schemas, annotations, envelopes, and error codes.
5. `mcp-server/src/server.ts` and `mcp-server/src/plugin-client.ts` for MCP registration and plugin transport behavior.
6. `scripts/runtime.mjs` for local Codex registration, wrapper script, health, and doctor behavior.
7. `docs/` for focused operational notes that do not duplicate source contracts.

Generated directories such as `node_modules/`, `mcp-server/dist/`, `plugin/build/`, `.gradle/`, and runtime logs are not source facts.

## Editing Rules

- Keep source in this independent project. Do not copy implementation into `petaskApp` or `petask-code-intel-mcp`.
- Do not manually merge lockfiles. If dependency changes affect `pnpm-lock.yaml`, regenerate it with pnpm.
- Do not add `.env.example`. Durable configuration belongs in source defaults, scripts, or the single local `.env` mechanism if one is introduced.
- Do not add compatibility shims, temporary environment switches, or semantic fallback paths that weaken the read-only PSI contract.
- Do not log bearer tokens, source snippets, full request bodies, manifest contents, or large PSI dumps.
- Do not preserve temporary scripts, throwaway probes, or generated helper files after validation.

## Search And Cleanup

- Use `rg` and `rg --files` for repository search.
- Exclude `node_modules`, `mcp-server/dist`, `plugin/build`, `.gradle`, and any `.xiaokeer` scratch directory from ordinary searches.
- Runtime state belongs under the user-local runtime directory, not in `petaskApp`.

## Validation Matrix

Use the smallest validation that covers the changed surface:

| Changed surface | Minimum validation |
| --- | --- |
| Documentation only | Read Markdown for fact drift and duplication. |
| MCP schema, contract, client, or server code | `pnpm --dir mcp-server typecheck` and `pnpm --dir mcp-server mcp:smoke` |
| Runtime registration or wrapper scripts | `pnpm --dir mcp-server typecheck`, then `node scripts/runtime.mjs doctor` |
| IDEA plugin Kotlin code, plugin XML, or Gradle config | `cd plugin && ./gradlew buildPlugin` |
| Full harness behavior | Plugin running in IDEA, runtime manifest present, then `pnpm --dir mcp-server cli health` and the `getHealthSummaryPlugin` smoke calls |

## Public Contract Coupling

When tool names, annotations, error codes, or output envelopes change, update these files in the same task:

- `mcp-server/src/contract.ts`
- `mcp-server/src/server.ts`
- `plugin/src/main/kotlin/com/xiaokeer/idea/psi/mcp/Models.kt`
- `plugin/src/main/kotlin/com/xiaokeer/idea/psi/mcp/PsiQueryService.kt`
- `README.md`
- relevant smoke or CLI checks

