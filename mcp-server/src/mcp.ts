#!/usr/bin/env node
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

import { createIdeaPsiMcpServer } from './server.js';

const server = createIdeaPsiMcpServer();
const transport = new StdioServerTransport();
await server.connect(transport);

