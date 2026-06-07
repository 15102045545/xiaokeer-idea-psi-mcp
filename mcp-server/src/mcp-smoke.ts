#!/usr/bin/env node
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { IDEA_PSI_MCP_TOOL_ANNOTATIONS, IDEA_PSI_MCP_TOOLS } from './server.js';

const TOOL_ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const DEFAULT_TIMEOUT_MS = 30_000;

function stringOption(argv: string[], name: string) {
  const index = argv.indexOf(`--${name}`);
  if (index === -1) return undefined;
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`Missing value for --${name}`);
  return value;
}

function numberOption(argv: string[], name: string) {
  const value = stringOption(argv, name);
  if (value === undefined) return undefined;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`--${name} must be a positive integer`);
  return parsed;
}

async function withTimeout<T>(label: string, timeoutMs: number, callback: () => Promise<T>) {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      callback(),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out after ${timeoutMs}ms`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function runSmoke() {
  const argv = process.argv.slice(2);
  const timeoutMs = numberOption(argv, 'timeout-ms') ?? DEFAULT_TIMEOUT_MS;
  const serverScript = stringOption(argv, 'server-script') ?? 'src/mcp.ts';
  const projectPath = stringOption(argv, 'project-path') ?? process.env.XIAOKEER_IDEA_PSI_SMOKE_PROJECT_PATH;
  const query = stringOption(argv, 'query') ?? process.env.XIAOKEER_IDEA_PSI_SMOKE_QUERY;
  if (!projectPath) throw new Error('Missing --project-path or XIAOKEER_IDEA_PSI_SMOKE_PROJECT_PATH.');
  if (!query) throw new Error('Missing --query or XIAOKEER_IDEA_PSI_SMOKE_QUERY.');

  const transport = new StdioClientTransport({
    command: 'pnpm',
    args: ['--dir', TOOL_ROOT, 'exec', 'tsx', serverScript],
    cwd: TOOL_ROOT,
    stderr: 'pipe',
    env: Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined)),
  });
  const stderrChunks: string[] = [];
  transport.stderr?.on('data', (chunk) => stderrChunks.push(String(chunk)));

  const client = new Client({ name: 'xiaokeer-idea-psi-smoke', version: '1.0.0' });
  try {
    await withTimeout('MCP connect', timeoutMs, () => client.connect(transport));
    const tools = await withTimeout('MCP tools/list', timeoutMs, () => client.listTools());
    const toolNames = tools.tools.map((tool) => tool.name).sort();
    const expectedNames = [...IDEA_PSI_MCP_TOOLS].sort();
    if (JSON.stringify(toolNames) !== JSON.stringify(expectedNames)) {
      throw new Error(`Unexpected tool names: ${JSON.stringify(toolNames)}`);
    }
    for (const tool of tools.tools) {
      const expected = IDEA_PSI_MCP_TOOL_ANNOTATIONS[tool.name as keyof typeof IDEA_PSI_MCP_TOOL_ANNOTATIONS];
      if (!expected) throw new Error(`Unexpected tool listed: ${tool.name}`);
      for (const key of ['readOnlyHint', 'destructiveHint', 'idempotentHint', 'openWorldHint'] as const) {
        if (tool.annotations?.[key] !== expected[key]) {
          throw new Error(`Tool annotation mismatch for ${tool.name}.${key}`);
        }
      }
    }

    const result = await withTimeout('MCP psi_search_symbol', timeoutMs, () =>
      client.callTool({
        name: 'psi_search_symbol',
        arguments: {
          projectPath,
          query,
          limit: 5,
          waitForSmartModeMs: 0,
        },
      }),
    );
    if (!result.structuredContent || typeof result.structuredContent !== 'object') {
      throw new Error('psi_search_symbol did not return structuredContent');
    }
    const structured = result.structuredContent as Record<string, unknown>;
    if (structured.tool !== 'psi_search_symbol' || typeof structured.ok !== 'boolean') {
      throw new Error(`Unexpected psi_search_symbol envelope: ${JSON.stringify(structured)}`);
    }
    if (structured.ok !== true) {
      const code =
        structured.error && typeof structured.error === 'object' && 'code' in structured.error
          ? String((structured.error as { code: unknown }).code)
          : 'unknown_error';
      throw new Error(`psi_search_symbol failed during smoke: ${code}`);
    }
    const data = structured.data && typeof structured.data === 'object' ? (structured.data as Record<string, unknown>) : {};
    const matches = Array.isArray(data.matches) ? data.matches : [];
    const hasExpectedMatch = matches.some((match) => {
      if (!match || typeof match !== 'object') return false;
      const record = match as Record<string, unknown>;
      return record.name === query || record.symbolName === query;
    });
    if (!hasExpectedMatch) {
      throw new Error(`psi_search_symbol smoke did not return ${query}.`);
    }

    process.stdout.write(
      `${JSON.stringify(
        {
          ok: true,
          tools: toolNames,
          searchEnvelopeOk: structured.ok,
          searchMatches: matches.length,
        },
        null,
        2,
      )}\n`,
    );
  } catch (error) {
    const stderr = stderrChunks.join('').trim();
    process.stdout.write(
      `${JSON.stringify(
        {
          ok: false,
          error: error instanceof Error ? error.message : String(error),
          serverStderr: stderr.length > 4000 ? `${stderr.slice(0, 4000)}...` : stderr,
        },
        null,
        2,
      )}\n`,
    );
    process.exitCode = 1;
  } finally {
    await client.close().catch(() => undefined);
    await transport.close().catch(() => undefined);
  }
}

await runSmoke();
