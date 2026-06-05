import { existsSync, readFileSync, statSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import type { ErrorCode, IdeaPsiMcpToolName, ToolEnvelope } from './contract.js';
import { PROTOCOL_VERSION, SERVICE_NAME, failureEnvelope, toolEnvelopeSchema } from './contract.js';
import { canonicalizeExistingOrResolved, validatePositionPath, validateProjectPath } from './path-guard.js';

export interface RuntimeManifest {
  schemaVersion: number;
  protocolVersion: string;
  service: string;
  host: string;
  port: number;
  pid: number;
  startedAt: string;
  token: string;
  projects: Array<{ name: string; basePath: string; isDisposed: boolean }>;
}

export interface PluginClientOptions {
  manifestPath?: string;
  timeoutMs?: number;
}

export class IdeaPsiPluginClient {
  private readonly manifestPath: string;
  private readonly timeoutMs: number;

  constructor(options: PluginClientOptions = {}) {
    this.manifestPath =
      options.manifestPath ??
      process.env.XIAOKEER_IDEA_PSI_RUNTIME_MANIFEST ??
      path.join(os.homedir(), 'Library', 'Application Support', SERVICE_NAME, 'runtime.json');
    this.timeoutMs = options.timeoutMs ?? Number(process.env.XIAOKEER_IDEA_PSI_HTTP_TIMEOUT_MS ?? 30_000);
  }

  async health() {
    const manifest = this.readManifest();
    if (isToolEnvelope(manifest)) return manifest;
    return this.httpJson(manifest, 'GET', '/health');
  }

  async projects() {
    const manifest = this.readManifest();
    if (isToolEnvelope(manifest)) return manifest;
    return this.httpJson(manifest, 'GET', '/projects');
  }

  async callTool(tool: IdeaPsiMcpToolName, input: Record<string, unknown>): Promise<ToolEnvelope> {
    const projectPath = typeof input.projectPath === 'string' ? canonicalizeExistingOrResolved(input.projectPath) : undefined;
    if (projectPath) {
      const projectError = validateProjectPath(tool, projectPath);
      if (projectError) return projectError;
    }
    if ((tool === 'psi_resolve_symbol' || tool === 'psi_find_usages') && hasPositionInput(input)) {
      const pathError = validatePositionPath(tool, input);
      if (pathError) return pathError;
    }

    const manifest = this.readManifest(tool, projectPath);
    if (isToolEnvelope(manifest)) return manifest;

    const endpoint = {
      psi_resolve_symbol: '/psi/resolve-symbol',
      psi_find_usages: '/psi/find-usages',
      psi_search_symbol: '/psi/search-symbol',
    }[tool];

    const payload = {
      protocolVersion: PROTOCOL_VERSION,
      ...input,
      projectPath: projectPath ?? input.projectPath,
    };

    const response = await this.httpJson(manifest, 'POST', endpoint, payload, tool, projectPath);
    const parsed = toolEnvelopeSchema.safeParse(response);
    if (!parsed.success) {
      return failureEnvelope(tool, 'internal_error', 'Plugin returned a malformed tool envelope.', {
        projectPath,
        diagnostics: [{ code: 'schema_validation_failed', message: parsed.error.message }],
      });
    }
    return parsed.data;
  }

  private readManifest(tool?: IdeaPsiMcpToolName, projectPath?: string): RuntimeManifest | ToolEnvelope {
    if (!existsSync(this.manifestPath)) {
      return failureEnvelope(tool ?? 'psi_search_symbol', 'runtime_manifest_missing', 'IDEA PSI runtime manifest is missing.', {
        retryable: true,
        retryHint: 'Start IntelliJ IDEA with the Xiaokeer IDEA PSI MCP plugin installed.',
        projectPath,
      });
    }

    try {
      const manifest = JSON.parse(readFileSync(this.manifestPath, 'utf8')) as RuntimeManifest;
      if (manifest.service !== SERVICE_NAME || manifest.protocolVersion !== PROTOCOL_VERSION || manifest.host !== '127.0.0.1') {
        return failureEnvelope(tool ?? 'psi_search_symbol', 'runtime_manifest_stale', 'IDEA PSI runtime manifest does not match this MCP server.', {
          retryable: true,
          retryHint: 'Restart IntelliJ IDEA or reinstall the matching plugin and MCP server together.',
          projectPath,
        });
      }
      if (!manifest.token || !Number.isInteger(manifest.port) || manifest.port <= 0) {
        return failureEnvelope(tool ?? 'psi_search_symbol', 'runtime_manifest_stale', 'IDEA PSI runtime manifest is incomplete.', {
          retryable: true,
          projectPath,
        });
      }
      if (!isPidAlive(manifest.pid)) {
        return failureEnvelope(tool ?? 'psi_search_symbol', 'runtime_manifest_stale', 'IDEA PSI runtime process is not running.', {
          retryable: true,
          retryHint: 'Restart IntelliJ IDEA with the plugin installed.',
          projectPath,
        });
      }
      statSync(this.manifestPath);
      return manifest;
    } catch (error) {
      return failureEnvelope(tool ?? 'psi_search_symbol', 'runtime_manifest_stale', 'IDEA PSI runtime manifest cannot be read.', {
        retryable: true,
        projectPath,
        diagnostics: [{ code: 'manifest_read_failed', message: error instanceof Error ? error.name : String(error) }],
      });
    }
  }

  private async httpJson(
    manifest: RuntimeManifest,
    method: 'GET' | 'POST',
    endpoint: string,
    body?: unknown,
    tool: IdeaPsiMcpToolName = 'psi_search_symbol',
    projectPath?: string,
  ) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(`http://${manifest.host}:${manifest.port}${endpoint}`, {
        method,
        headers: {
          Authorization: `Bearer ${manifest.token}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      const payload = await response.json().catch(() => null);
      if (response.status === 401) {
        return failureEnvelope(tool, 'unauthorized', 'IDEA PSI plugin rejected the bearer token.', {
          retryable: true,
          retryHint: 'Restart IntelliJ IDEA so the MCP server reads the current runtime manifest.',
          projectPath,
        });
      }
      if (!response.ok) {
        return failureEnvelope(tool, statusToCode(response.status), 'IDEA PSI plugin returned an HTTP error.', {
          retryable: response.status >= 500,
          projectPath,
          diagnostics: [{ code: 'http_status', message: String(response.status) }],
        });
      }
      return payload;
    } catch (error) {
      return failureEnvelope(tool, error instanceof Error && error.name === 'AbortError' ? 'timeout' : 'plugin_not_available', 'IDEA PSI plugin is not reachable.', {
        retryable: true,
        retryHint: 'Open IntelliJ IDEA with the project and plugin running.',
        projectPath,
        diagnostics: [{ code: 'fetch_failed', message: error instanceof Error ? error.name : String(error) }],
      });
    } finally {
      clearTimeout(timer);
    }
  }
}

function hasPositionInput(input: Record<string, unknown>): input is { projectPath: string; filePath: string } {
  return typeof input.projectPath === 'string' && typeof input.filePath === 'string';
}

function isToolEnvelope(value: RuntimeManifest | ToolEnvelope): value is ToolEnvelope {
  return typeof value === 'object' && value !== null && 'ok' in value;
}

function isPidAlive(pid: number) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function statusToCode(status: number): ErrorCode {
  if (status === 401) return 'unauthorized';
  if (status === 408) return 'timeout';
  return 'plugin_not_available';
}
