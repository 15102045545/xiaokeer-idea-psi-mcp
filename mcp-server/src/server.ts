import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';

import {
  IDEA_PSI_MCP_TOOL_ANNOTATIONS,
  IDEA_PSI_MCP_TOOLS,
  MCP_OUTPUT_SCHEMAS,
  findUsagesInputSchema,
  okToolResult,
  positionInputSchema,
  searchSymbolInputSchema,
} from './contract.js';
import { IdeaPsiPluginClient } from './plugin-client.js';

export { IDEA_PSI_MCP_TOOL_ANNOTATIONS, IDEA_PSI_MCP_TOOLS } from './contract.js';

export function createIdeaPsiMcpServer(client = new IdeaPsiPluginClient()) {
  const server = new McpServer({
    name: 'xiaokeer-idea-psi',
    version: '1.0.0',
  });

  server.registerTool(
    'psi_resolve_symbol',
    {
      description: 'Resolve a TypeScript or JavaScript symbol position through IntelliJ IDEA committed PSI.',
      inputSchema: positionInputSchema,
      outputSchema: MCP_OUTPUT_SCHEMAS.psi_resolve_symbol,
      annotations: IDEA_PSI_MCP_TOOL_ANNOTATIONS.psi_resolve_symbol,
    },
    async (input) => okToolResult(await client.callTool('psi_resolve_symbol', input)),
  );

  server.registerTool(
    'psi_find_usages',
    {
      description: 'Find IntelliJ IDEA PSI usages for the symbol at a TypeScript or JavaScript source position.',
      inputSchema: findUsagesInputSchema,
      outputSchema: MCP_OUTPUT_SCHEMAS.psi_find_usages,
      annotations: IDEA_PSI_MCP_TOOL_ANNOTATIONS.psi_find_usages,
    },
    async (input) => okToolResult(await client.callTool('psi_find_usages', input)),
  );

  server.registerTool(
    'psi_search_symbol',
    {
      description: 'Search TypeScript or JavaScript symbols by exact name through IntelliJ IDEA PSI.',
      inputSchema: searchSymbolInputSchema,
      outputSchema: MCP_OUTPUT_SCHEMAS.psi_search_symbol,
      annotations: IDEA_PSI_MCP_TOOL_ANNOTATIONS.psi_search_symbol,
    },
    async (input) => okToolResult(await client.callTool('psi_search_symbol', input)),
  );

  return server;
}

