package com.xiaokeer.idea.psi.mcp

const val SERVICE_NAME = "xiaokeer-idea-psi-mcp"
const val PROTOCOL_VERSION = "2026-06-05.phase2"
const val SOURCE_STATE = "ideCommittedPsi"

data class DiagnosticDto(
  val code: String,
  val message: String,
  val retryable: Boolean = false,
  val details: Map<String, Any?> = emptyMap(),
)

data class ErrorDto(
  val code: String,
  val message: String,
  val retryable: Boolean,
  val retryHint: String? = null,
  val projectPath: String? = null,
  val filePath: String? = null,
  val diagnostics: List<DiagnosticDto> = emptyList(),
)

data class EnvelopeDto(
  val ok: Boolean,
  val tool: String,
  val projectPath: String? = null,
  val sourceState: String? = null,
  val data: Any? = null,
  val error: ErrorDto? = null,
  val diagnostics: List<DiagnosticDto> = emptyList(),
)

data class RangeDto(
  val line: Int,
  val column: Int,
  val endLine: Int,
  val endColumn: Int,
)

data class PositionRequest(
  val protocolVersion: String? = null,
  val projectPath: String,
  val filePath: String,
  val line: Int,
  val column: Int,
  val waitForSmartModeMs: Long = 0,
  val includeSnippet: Boolean = false,
  val limit: Int? = null,
)

data class SearchRequest(
  val protocolVersion: String? = null,
  val projectPath: String,
  val query: String,
  val waitForSmartModeMs: Long = 0,
  val includeSnippet: Boolean = false,
  val limit: Int = 50,
)

data class ProjectManifestDto(
  val name: String,
  val basePath: String,
  val isDisposed: Boolean,
)

data class RuntimeManifestDto(
  val schemaVersion: Int,
  val protocolVersion: String,
  val service: String,
  val host: String,
  val port: Int,
  val pid: Long,
  val startedAt: String,
  val token: String,
  val projects: List<ProjectManifestDto>,
)

data class SymbolTargetDto(
  val symbolName: String?,
  val name: String?,
  val kind: String,
  val language: String,
  val filePath: String?,
  val absoluteFilePath: String?,
  val range: RangeDto?,
  val navigationOffset: Int?,
  val containingSymbol: String?,
  val isExported: Boolean?,
  val sourceApi: String,
)

data class UsageDto(
  val symbolName: String?,
  val kind: String,
  val language: String,
  val filePath: String?,
  val absoluteFilePath: String?,
  val range: RangeDto?,
  val navigationOffset: Int?,
  val containingSymbol: String?,
  val isDefinition: Boolean,
  val usageKind: String,
  val snippet: String?,
  val sourceApi: String,
)

fun okEnvelope(tool: String, projectPath: String?, data: Any?, diagnostics: List<DiagnosticDto> = emptyList()) =
  EnvelopeDto(
    ok = true,
    tool = tool,
    projectPath = projectPath,
    sourceState = SOURCE_STATE,
    data = data,
    diagnostics = diagnostics,
  )

fun errorEnvelope(
  tool: String,
  code: String,
  message: String,
  retryable: Boolean = false,
  retryHint: String? = null,
  projectPath: String? = null,
  filePath: String? = null,
  diagnostics: List<DiagnosticDto> = emptyList(),
) =
  EnvelopeDto(
    ok = false,
    tool = tool,
    projectPath = projectPath,
    error = ErrorDto(
      code = code,
      message = message,
      retryable = retryable,
      retryHint = retryHint,
      projectPath = projectPath,
      filePath = filePath,
      diagnostics = diagnostics,
    ),
    diagnostics = diagnostics,
  )
