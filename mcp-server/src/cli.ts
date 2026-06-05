#!/usr/bin/env node
import { IdeaPsiPluginClient } from './plugin-client.js';

const client = new IdeaPsiPluginClient();

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
  if (!Number.isInteger(parsed)) throw new Error(`--${name} must be an integer`);
  return parsed;
}

function booleanOption(argv: string[], name: string) {
  return argv.includes(`--${name}`);
}

async function main() {
  const argv = process.argv.slice(2);
  const command = argv[0] ?? 'help';

  if (command === 'health') {
    return client.health();
  }
  if (command === 'projects') {
    return client.projects();
  }
  if (command === 'search') {
    return client.callTool('psi_search_symbol', {
      projectPath: required(argv, 'project-path'),
      query: required(argv, 'query'),
      waitForSmartModeMs: numberOption(argv, 'wait-for-smart-mode-ms') ?? 0,
      includeSnippet: booleanOption(argv, 'include-snippet'),
      limit: numberOption(argv, 'limit') ?? 50,
    });
  }
  if (command === 'resolve') {
    return client.callTool('psi_resolve_symbol', positionArgs(argv));
  }
  if (command === 'usages') {
    return client.callTool('psi_find_usages', {
      ...positionArgs(argv),
      limit: numberOption(argv, 'limit') ?? 100,
    });
  }

  return {
    ok: false,
    error: {
      code: 'unknown_command',
      message: 'Use one of: health, projects, search, resolve, usages.',
    },
  };
}

function positionArgs(argv: string[]) {
  return {
    projectPath: required(argv, 'project-path'),
    filePath: required(argv, 'file-path'),
    line: requiredNumber(argv, 'line'),
    column: requiredNumber(argv, 'column'),
    waitForSmartModeMs: numberOption(argv, 'wait-for-smart-mode-ms') ?? 0,
    includeSnippet: booleanOption(argv, 'include-snippet'),
  };
}

function required(argv: string[], name: string) {
  const value = stringOption(argv, name);
  if (!value) throw new Error(`Missing --${name}`);
  return value;
}

function requiredNumber(argv: string[], name: string) {
  const value = numberOption(argv, name);
  if (value === undefined) throw new Error(`Missing --${name}`);
  return value;
}

try {
  const result = await main();
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stdout.write(
    `${JSON.stringify(
      {
        ok: false,
        error: {
          code: 'cli_error',
          message: error instanceof Error ? error.message : String(error),
        },
      },
      null,
      2,
    )}\n`,
  );
  process.exitCode = 1;
}

