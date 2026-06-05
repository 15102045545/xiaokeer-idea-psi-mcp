#!/usr/bin/env node
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const toolRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const home = process.env.HOME ?? '/Users/chongwen002';
const codexConfigPath = join(home, '.codex', 'config.toml');
const codexBinDir = join(home, '.codex', 'bin');
const wrapperPath = join(codexBinDir, 'xiaokeer-idea-psi-mcp.sh');
const manifestPath =
  process.env.XIAOKEER_IDEA_PSI_RUNTIME_MANIFEST ??
  join(home, 'Library', 'Application Support', 'xiaokeer-idea-psi-mcp', 'runtime.json');
const mcpServerDir = join(toolRoot, 'mcp-server');
const ideaPluginsDir = process.env.XIAOKEER_IDEA_PSI_IDEA_PLUGINS_DIR ?? join(home, 'Library', 'Application Support', 'JetBrains', 'IntelliJIdea2026.1', 'plugins');
const pluginZipPath = join(toolRoot, 'plugin', 'build', 'distributions', 'xiaokeer-idea-psi-mcp-plugin-1.0.0.zip');
const installedPluginDir = join(ideaPluginsDir, 'xiaokeer-idea-psi-mcp-plugin');
const configHeader = '[mcp_servers.xiaokeer-idea-psi]';
const configBlock = `${configHeader}\ncommand = "${wrapperPath}"`;

function ensureDirs() {
  mkdirSync(dirname(codexConfigPath), { recursive: true });
  mkdirSync(codexBinDir, { recursive: true });
}

function wrapperScript() {
  return `#!/usr/bin/env bash
set -euo pipefail
exec /opt/homebrew/bin/pnpm --dir "${mcpServerDir}" exec tsx src/mcp.ts
`;
}

function writeWrapper() {
  writeFileSync(wrapperPath, wrapperScript(), { encoding: 'utf8', mode: 0o755 });
  chmodSync(wrapperPath, 0o755);
}

function upsertCodexConfig() {
  const existing = existsSync(codexConfigPath) ? readFileSync(codexConfigPath, 'utf8') : '';
  const lines = existing.split(/\r?\n/);
  const start = lines.findIndex((line) => line.trim() === configHeader);

  let nextContent;
  if (start === -1) {
    const prefix = existing.trim().length > 0 ? `${existing.replace(/\s*$/, '\n\n')}` : '';
    nextContent = `${prefix}${configBlock}\n`;
  } else {
    let end = start + 1;
    while (end < lines.length && !/^\s*\[/.test(lines[end])) end += 1;
    nextContent = `${[...lines.slice(0, start), ...configBlock.split('\n'), ...lines.slice(end)].join('\n').replace(/\s*$/, '')}\n`;
  }

  writeFileSync(codexConfigPath, nextContent, 'utf8');
}

async function pluginHealth() {
  if (!existsSync(manifestPath)) {
    return { ok: false, code: 'runtime_manifest_missing', manifestPath };
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (error) {
    return { ok: false, code: 'runtime_manifest_stale', manifestPath, error: error instanceof Error ? error.name : String(error) };
  }
  const redactedManifest = { ...manifest, token: manifest.token ? '<redacted>' : undefined };
  try {
    const response = await fetch(`http://${manifest.host}:${manifest.port}/health`, {
      headers: { Authorization: `Bearer ${manifest.token}` },
      signal: AbortSignal.timeout(5000),
    });
    const body = await response.json().catch(() => null);
    return { ok: response.ok && Boolean(body?.ok), status: response.status, manifest: redactedManifest, body };
  } catch (error) {
    return { ok: false, code: 'plugin_not_available', manifest: redactedManifest, error: error instanceof Error ? error.name : String(error) };
  }
}

async function install() {
  ensureDirs();
  writeWrapper();
  upsertCodexConfig();
  process.stdout.write(
    `${JSON.stringify(
      {
        ok: true,
        action: 'install',
        codexConfigPath,
        wrapperPath,
        mcpServerDir,
      },
      null,
      2,
    )}\n`,
  );
}

async function installPlugin() {
  if (!existsSync(pluginZipPath)) {
    process.stdout.write(
      `${JSON.stringify(
        {
          ok: false,
          action: 'install-plugin',
          error: 'plugin_zip_missing',
          pluginZipPath,
          nextAction: 'Run: cd plugin && ./gradlew buildPlugin',
        },
        null,
        2,
      )}\n`,
    );
    process.exitCode = 1;
    return;
  }
  mkdirSync(ideaPluginsDir, { recursive: true });
  rmSync(installedPluginDir, { recursive: true, force: true });
  execFileSync('/usr/bin/unzip', ['-oq', pluginZipPath, '-d', ideaPluginsDir], { stdio: ['ignore', 'pipe', 'pipe'] });
  process.stdout.write(
    `${JSON.stringify(
      {
        ok: true,
        action: 'install-plugin',
        pluginZipPath,
        installedPluginDir,
        restartRequired: true,
      },
      null,
      2,
    )}\n`,
  );
}

async function doctor() {
  const config = existsSync(codexConfigPath) ? readFileSync(codexConfigPath, 'utf8') : '';
  const health = await pluginHealth();
  process.stdout.write(
    `${JSON.stringify(
      {
        ok: existsSync(wrapperPath) && config.includes(configHeader) && health.ok,
        codexConfigPath,
        wrapperPath,
        mcpServerDir,
        manifestPath,
        ideaPluginsDir,
        installedPluginDir,
        wrapperExists: existsSync(wrapperPath),
        codexConfigRegistered: config.includes(configHeader),
        dependenciesInstalled: existsSync(join(mcpServerDir, 'node_modules')),
        pluginZipExists: existsSync(pluginZipPath),
        pluginInstalled: existsSync(installedPluginDir),
        pluginHealth: health,
      },
      null,
      2,
    )}\n`,
  );
}

const action = process.argv[2] ?? 'doctor';

if (action === 'install') {
  await install();
} else if (action === 'install-plugin') {
  await installPlugin();
} else if (action === 'doctor' || action === 'health') {
  await doctor();
} else {
  process.stdout.write(`${JSON.stringify({ ok: false, error: `Unknown action: ${action}` }, null, 2)}\n`);
  process.exitCode = 1;
}
