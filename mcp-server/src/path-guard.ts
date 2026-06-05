import { existsSync, realpathSync } from 'node:fs';
import path from 'node:path';

import type { ErrorCode, IdeaPsiMcpToolName, ToolEnvelope } from './contract.js';
import { failureEnvelope } from './contract.js';

const SUPPORTED_EXTENSIONS = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.cjs']);
const EXCLUDED_SEGMENTS = new Set(['.git', 'node_modules', 'dist', 'build', '.gradle', '.idea', '.xiaokeer']);

export interface PositionLikeInput {
  projectPath: string;
  filePath: string;
}

export function canonicalizeExistingOrResolved(value: string) {
  const resolved = path.resolve(value);
  return existsSync(resolved) ? realpathSync(resolved) : resolved;
}

export function validateProjectPath(tool: IdeaPsiMcpToolName, projectPath: string): ToolEnvelope | undefined {
  if (!path.isAbsolute(projectPath)) {
    return failureEnvelope(tool, 'project_not_open', 'projectPath must be an absolute path.', { projectPath });
  }
  return undefined;
}

export function validatePositionPath(tool: IdeaPsiMcpToolName, input: PositionLikeInput): ToolEnvelope | undefined {
  const projectPath = canonicalizeExistingOrResolved(input.projectPath);
  const rawFilePath = path.isAbsolute(input.filePath) ? input.filePath : path.join(projectPath, input.filePath);
  const filePath = canonicalizeExistingOrResolved(rawFilePath);

  if (!isInside(projectPath, filePath)) {
    return failure(tool, 'path_outside_project', 'filePath must be inside projectPath.', projectPath, input.filePath);
  }
  if (hasExcludedSegment(projectPath, filePath)) {
    return failure(tool, 'path_outside_project', 'filePath is inside an excluded project directory.', projectPath, input.filePath);
  }
  if (!existsSync(filePath)) {
    return failure(tool, 'file_not_found', 'filePath does not exist.', projectPath, input.filePath);
  }
  if (!SUPPORTED_EXTENSIONS.has(path.extname(filePath).toLowerCase())) {
    return failure(tool, 'unsupported_language', 'Only TypeScript and JavaScript files are supported in phase one.', projectPath, input.filePath);
  }
  return undefined;
}

function failure(tool: IdeaPsiMcpToolName, code: ErrorCode, message: string, projectPath: string, filePath: string) {
  return failureEnvelope(tool, code, message, { projectPath, filePath });
}

function isInside(root: string, candidate: string) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function hasExcludedSegment(root: string, candidate: string) {
  const relative = path.relative(root, candidate);
  return relative.split(path.sep).some((segment) => EXCLUDED_SEGMENTS.has(segment));
}

