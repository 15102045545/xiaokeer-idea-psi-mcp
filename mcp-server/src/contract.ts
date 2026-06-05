import type { CallToolResult, ToolAnnotations } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';

export const SERVICE_NAME = 'xiaokeer-idea-psi-mcp';
export const PROTOCOL_VERSION = '2026-06-05.phase2';
export const SOURCE_STATE = 'ideCommittedPsi';

export const IDEA_PSI_MCP_TOOLS = ['psi_resolve_symbol', 'psi_find_usages', 'psi_search_symbol'] as const;

export type IdeaPsiMcpToolName = (typeof IDEA_PSI_MCP_TOOLS)[number];

export const READ_ONLY_ANNOTATION: ToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};

export const IDEA_PSI_MCP_TOOL_ANNOTATIONS: Record<IdeaPsiMcpToolName, ToolAnnotations> = {
  psi_resolve_symbol: READ_ONLY_ANNOTATION,
  psi_find_usages: READ_ONLY_ANNOTATION,
  psi_search_symbol: READ_ONLY_ANNOTATION,
};

export const ERROR_CODES = [
  'plugin_not_available',
  'runtime_manifest_missing',
  'runtime_manifest_stale',
  'unauthorized',
  'project_not_open',
  'ambiguous_project',
  'path_outside_project',
  'file_not_found',
  'unsupported_file',
  'unsupported_language',
  'invalid_position',
  'document_not_committed',
  'index_not_ready',
  'symbol_not_found',
  'unresolved_symbol',
  'ambiguous_symbol',
  'timeout',
  'result_limit_exceeded',
  'internal_error',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export const positionInputSchema = {
  projectPath: z.string().describe('Canonical local path to an open IntelliJ IDEA project root.'),
  filePath: z.string().describe('Project-relative or absolute path to a TypeScript or JavaScript source file inside projectPath.'),
  line: z.number().int().positive().describe('1-based line number.'),
  column: z.number().int().positive().describe('1-based UTF-16 column number.'),
  waitForSmartModeMs: z.number().int().min(0).optional().describe('Milliseconds to wait for IDEA Smart Mode before returning index_not_ready.'),
  includeSnippet: z.boolean().optional().describe('Include one-line snippets capped by the plugin. Defaults to false.'),
};

export const findUsagesInputSchema = {
  ...positionInputSchema,
  limit: z.number().int().positive().max(500).optional().describe('Maximum usages to return. Defaults to 100, maximum 500.'),
};

export const searchSymbolInputSchema = {
  projectPath: z.string().describe('Canonical local path to an open IntelliJ IDEA project root.'),
  query: z.string().min(1).describe('Exact symbol name to search.'),
  waitForSmartModeMs: z.number().int().min(0).optional().describe('Milliseconds to wait for IDEA Smart Mode before returning index_not_ready.'),
  includeSnippet: z.boolean().optional().describe('Reserved for future search snippets. Defaults to false.'),
  limit: z.number().int().positive().max(200).optional().describe('Maximum symbols to return. Defaults to 50, maximum 200.'),
};

const diagnosticSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    retryable: z.boolean().optional(),
    details: z.record(z.unknown()).optional(),
  })
  .passthrough();

const errorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    retryable: z.boolean(),
    retryHint: z.string().nullable().optional(),
    projectPath: z.string().nullable().optional(),
    filePath: z.string().nullable().optional(),
    diagnostics: z.array(diagnosticSchema).optional(),
  })
  .passthrough();

export const toolEnvelopeSchema = z
  .object({
    ok: z.boolean(),
    tool: z.string(),
    projectPath: z.string().nullable().optional(),
    sourceState: z.string().nullable().optional(),
    data: z.unknown().optional(),
    error: errorSchema.nullable().optional(),
    diagnostics: z.array(diagnosticSchema).optional(),
  })
  .passthrough();

export const MCP_OUTPUT_SCHEMAS: Record<IdeaPsiMcpToolName, typeof toolEnvelopeSchema> = {
  psi_resolve_symbol: toolEnvelopeSchema,
  psi_find_usages: toolEnvelopeSchema,
  psi_search_symbol: toolEnvelopeSchema,
};

export type ToolEnvelope = z.infer<typeof toolEnvelopeSchema>;

export function okToolResult(envelope: ToolEnvelope): CallToolResult {
  return {
    content: [{ type: 'text', text: summarizeEnvelope(envelope) }],
    structuredContent: envelope,
  };
}

export function failureEnvelope(
  tool: IdeaPsiMcpToolName,
  code: ErrorCode,
  message: string,
  options: {
    retryable?: boolean;
    retryHint?: string;
    projectPath?: string;
    filePath?: string;
    diagnostics?: Array<Record<string, unknown>>;
  } = {},
): ToolEnvelope {
  return {
    ok: false,
    tool,
    projectPath: options.projectPath,
    error: {
      code,
      message,
      retryable: options.retryable ?? false,
      retryHint: options.retryHint,
      projectPath: options.projectPath,
      filePath: options.filePath,
      diagnostics: (options.diagnostics ?? []).map((diagnostic) => ({
        code: String(diagnostic.code ?? code),
        message: String(diagnostic.message ?? message),
        retryable: Boolean(diagnostic.retryable ?? options.retryable ?? false),
        details: diagnostic,
      })),
    },
    diagnostics: (options.diagnostics ?? []).map((diagnostic) => ({
      code: String(diagnostic.code ?? code),
      message: String(diagnostic.message ?? message),
      retryable: Boolean(diagnostic.retryable ?? options.retryable ?? false),
      details: diagnostic,
    })),
  };
}

export function summarizeEnvelope(envelope: ToolEnvelope) {
  if (!envelope.ok) {
    return `${envelope.tool}: ${envelope.error?.code ?? 'error'}`;
  }
  const data = envelope.data && typeof envelope.data === 'object' ? (envelope.data as Record<string, unknown>) : {};
  if (Array.isArray(data.matches)) return `${envelope.tool}: ${data.matches.length} matches`;
  if (Array.isArray(data.usages)) return `${envelope.tool}: ${data.usages.length} usages`;
  if (data.symbolName) return `${envelope.tool}: ${String(data.symbolName)}`;
  return `${envelope.tool}: ok`;
}

