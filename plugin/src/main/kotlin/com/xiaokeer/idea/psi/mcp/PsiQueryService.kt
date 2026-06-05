package com.xiaokeer.idea.psi.mcp

import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSElement
import com.intellij.lang.javascript.psi.JSField
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSNamedElement
import com.intellij.lang.javascript.psi.JSParameter
import com.intellij.lang.javascript.psi.JSPsiElementBase
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.navigation.JavaScriptSymbolContributor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptField
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunction
import com.intellij.lang.javascript.psi.ecma6.TypeScriptImportStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptParameter
import com.intellij.lang.javascript.psi.ecma6.TypeScriptType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.lang.javascript.psi.ecma6.TypeScriptVariable
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.ecmal4.JSImportStatement
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement
import com.intellij.lang.javascript.psi.stubs.JSClassIndex
import com.intellij.lang.javascript.psi.stubs.JSGlobalSymbolIndex
import com.intellij.lang.javascript.psi.stubs.JSNameIndex
import com.intellij.lang.javascript.psi.stubs.JSNonGlobalSymbolIndex
import com.intellij.lang.javascript.psi.stubs.JSSymbolIndex2
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.Timer
import java.util.TimerTask
import kotlin.io.path.extension
import kotlin.io.path.name

class PsiQueryService {
  fun resolveSymbol(request: PositionRequest): EnvelopeDto =
    runPositionTool("psi_resolve_symbol", request) { context, lookup ->
      val definition = context.declarationAt(lookup)
      if (definition != null) {
        val target = context.symbolTarget(definition, "PsiNameIdentifierOwner")
        okEnvelope(
          "psi_resolve_symbol",
          context.projectRoot.toString(),
          mapOf(
            "inputRole" to "definition",
            "symbolName" to target.symbolName,
            "kind" to target.kind,
            "language" to target.language,
            "target" to target,
            "primaryTarget" to target,
            "candidates" to emptyList<SymbolTargetDto>(),
            "resolutionPath" to listOf(target),
            "range" to target.range,
            "navigationOffset" to target.navigationOffset,
            "sourceApi" to target.sourceApi,
            "adjustedFromInput" to lookup.adjustedFromInput,
          ),
        )
      } else {
        val reference = context.referenceAt(lookup)
          ?: throw ToolFailure("unresolved_symbol", "No PSI reference exists at the requested position.")
        val candidates = context.resolveReferenceCandidates(reference)
        if (candidates.isEmpty()) {
          throw ToolFailure("unresolved_symbol", "The PSI reference did not resolve to a declaration.")
        }
        val primary = candidates.first()
        val diagnostics = if (candidates.size > 1) {
          listOf(DiagnosticDto("polyvariant_resolution", "Reference resolved to multiple PSI candidates."))
        } else {
          emptyList()
        }
        val primaryTarget = context.symbolTarget(primary, referenceSourceApi(reference))
        val source = context.symbolTarget(reference.element, "PsiReference.getElement")
        okEnvelope(
          "psi_resolve_symbol",
          context.projectRoot.toString(),
          mapOf(
            "inputRole" to "usage",
            "symbolName" to primaryTarget.symbolName,
            "kind" to primaryTarget.kind,
            "language" to primaryTarget.language,
            "target" to primaryTarget,
            "primaryTarget" to primaryTarget,
            "candidates" to candidates.map { context.symbolTarget(it, referenceSourceApi(reference)) },
            "resolutionPath" to listOf(source, primaryTarget),
            "range" to primaryTarget.range,
            "navigationOffset" to primaryTarget.navigationOffset,
            "sourceApi" to referenceSourceApi(reference),
            "adjustedFromInput" to lookup.adjustedFromInput,
          ),
          diagnostics,
        )
      }
    }

  fun findUsages(request: PositionRequest): EnvelopeDto =
    runPositionTool("psi_find_usages", request) { context, lookup ->
      val resolved = context.resolveInputElement(lookup)
      val limit = (request.limit ?: 100).coerceIn(1, 500)
      val target = resolved.element
      val targetDto = context.symbolTarget(target, resolved.sourceApi)
      val usages = mutableListOf<UsageDto>()
      usages += context.usageDto(target, true, "definition", request.includeSnippet, "definition")

      var truncated = false
      val scope = GlobalSearchScope.projectScope(context.project)
      ReferencesSearch.search(target, scope, false).forEach(Processor { reference ->
        if (usages.size >= limit) {
          truncated = true
          return@Processor false
        }
        val element = reference.element
        if (element.isValid && context.isElementInsideAllowedProject(element)) {
          usages += context.usageDto(
            element,
            isDefinition = false,
            usageKind = context.usageKind(element),
            includeSnippet = request.includeSnippet,
            sourceApi = "ReferencesSearch.search",
          )
        }
        true
      })

      okEnvelope(
        "psi_find_usages",
        context.projectRoot.toString(),
        mapOf(
          "inputRole" to resolved.inputRole,
          "symbolName" to targetDto.symbolName,
          "kind" to targetDto.kind,
          "language" to targetDto.language,
          "target" to targetDto,
          "usages" to usages.take(limit),
          "limit" to limit,
          "truncated" to truncated,
          "nextCursor" to null,
          "rerunHint" to if (truncated) "Rerun with a narrower future scope or a larger limit up to 500." else null,
          "adjustedFromInput" to lookup.adjustedFromInput,
          "sourceApi" to "ReferencesSearch.search",
        ),
      )
    }

  fun searchSymbol(request: SearchRequest): EnvelopeDto =
    runProjectTool("psi_search_symbol", request.projectPath, request.waitForSmartModeMs) { context ->
      val query = request.query.trim()
      if (query.isEmpty()) {
        throw ToolFailure("symbol_not_found", "Symbol query must not be empty.")
      }
      val limit = request.limit.coerceIn(1, 200)
      val matches = mutableListOf<SymbolTargetDto>()
      val seen = linkedSetOf<String>()
      val warnings = mutableListOf<DiagnosticDto>()
      val scope = GlobalSearchScope.projectScope(context.project)
      var truncated = false

      fun addElement(element: PsiElement, sourceApi: String): Boolean {
        if (matches.size >= limit) {
          truncated = true
          return false
        }
        if (!element.isValid || !context.isElementInsideAllowedProject(element)) return true
        val name = (element as? PsiNamedElement)?.name ?: (element as? NavigationItem)?.name
        if (name != query) return true
        if (element is PsiNamedElement && !context.isSupportedSymbolElement(element)) return true
        val target = context.symbolTarget(element, sourceApi)
        val key = "${target.absoluteFilePath}:${target.range}:${target.symbolName}:${target.kind}"
        if (seen.add(key)) {
          if (target.kind == "unknown") {
            warnings += DiagnosticDto(
              "symbol_kind_unknown",
              "A PSI symbol matched the query but could not be classified into a phase-one kind.",
            )
          }
          matches += target
        }
        return matches.size < limit
      }

      JavaScriptSymbolContributor().processElementsWithName(
        query,
        Processor { item -> (item as? PsiElement)?.let { addElement(it, "JavaScriptSymbolContributor") } ?: true },
        FindSymbolParameters.wrap(query, scope),
      )

      val stubIndex = StubIndex.getInstance()
      stubIndex.processElements(JSNameIndex().key, query, context.project, scope, JSQualifiedNamedElement::class.java, Processor { addElement(it, "JSNameIndex") })
      stubIndex.processElements(JSGlobalSymbolIndex().key, query, context.project, scope, JSElement::class.java, Processor { addElement(it, "JSGlobalSymbolIndex") })
      stubIndex.processElements(JSNonGlobalSymbolIndex().key, query, context.project, scope, JSElement::class.java, Processor { addElement(it, "JSNonGlobalSymbolIndex") })
      stubIndex.processElements(JSSymbolIndex2().key, query, context.project, scope, JSElement::class.java, Processor { addElement(it, "JSSymbolIndex2") })
      JSClassIndex.processElements(query, context.project, scope, Processor<JSPsiElementBase> { addElement(it, "JSClassIndex") })

      if (matches.isEmpty()) {
        throw ToolFailure("symbol_not_found", "No IDEA PSI symbol matched the exact query.")
      }

      okEnvelope(
        "psi_search_symbol",
        context.projectRoot.toString(),
        mapOf(
          "query" to query,
          "matches" to matches,
          "limit" to limit,
          "truncated" to truncated,
          "coverageWarnings" to warnings.distinctBy { "${it.code}:${it.message}" },
          "sourceApi" to "JavaScriptSymbolContributor+JS stub indexes",
        ),
        warnings.distinctBy { "${it.code}:${it.message}" },
      )
    }

  private fun runPositionTool(tool: String, request: PositionRequest, block: (QueryContext, PositionLookup) -> EnvelopeDto): EnvelopeDto =
    runPositionToolInternal(tool, request) { context ->
      withProgress {
        PsiDocumentManager.getInstance(context.project).commitAndRunReadAction(Computable {
          ensureSmartMode(context.project)
          context.preparePsiFile()
          context.assertDocumentCommitted()
          val lookup = context.positionLookup(request.line, request.column)
          block(context, lookup)
        })
      }
    }

  private fun runProjectTool(
    tool: String,
    projectPath: String,
    waitForSmartModeMs: Long,
    filePath: String? = null,
    block: (QueryContext) -> EnvelopeDto,
  ): EnvelopeDto {
    val canonicalProjectPath = canonicalPath(projectPath)
    return try {
      val project = findOpenProject(canonicalProjectPath)
      waitForSmartMode(project, waitForSmartModeMs)
      withProgress {
        ApplicationManager.getApplication().runReadAction(Computable {
          ensureSmartMode(project)
          block(QueryContext(project, canonicalProjectPath))
        })
      }
    } catch (failure: ToolFailure) {
      errorEnvelope(
        tool = tool,
        code = failure.code,
        message = failure.message ?: failure.code,
        retryable = failure.retryable,
        retryHint = failure.retryHint,
        projectPath = canonicalProjectPath.toString(),
        filePath = filePath,
        diagnostics = failure.diagnostics,
      )
    } catch (error: IndexNotReadyException) {
      errorEnvelope(
        tool = tool,
        code = "index_not_ready",
        message = "IDEA indexing is not complete.",
        retryable = true,
        retryHint = "Wait for IDEA indexing to finish, or retry with waitForSmartModeMs.",
        projectPath = canonicalProjectPath.toString(),
        filePath = filePath,
        diagnostics = listOf(DiagnosticDto("index_not_ready", "Caught IndexNotReadyException.", retryable = true)),
      )
    } catch (error: ProcessCanceledException) {
      errorEnvelope(
        tool = tool,
        code = "timeout",
        message = "IDEA canceled the PSI query.",
        retryable = true,
        retryHint = "Retry after IDEA finishes background work.",
        projectPath = canonicalProjectPath.toString(),
        filePath = filePath,
        diagnostics = listOf(DiagnosticDto("process_canceled", error.javaClass.simpleName, retryable = true)),
      )
    } catch (error: Exception) {
      errorEnvelope(
        tool = tool,
        code = "internal_error",
        message = "Internal PSI query error.",
        retryable = false,
        projectPath = canonicalProjectPath.toString(),
        filePath = filePath,
        diagnostics = listOf(DiagnosticDto("exception_type", error.javaClass.simpleName)),
      )
    }
  }

  private fun runPositionToolInternal(tool: String, request: PositionRequest, block: (QueryContext) -> EnvelopeDto): EnvelopeDto {
    val canonicalProjectPath = canonicalPath(request.projectPath)
    return try {
      val project = findOpenProject(canonicalProjectPath)
      waitForSmartMode(project, request.waitForSmartModeMs)
      val context = QueryContext(project, canonicalProjectPath)
      context.prepareVirtualFile(request.filePath)
      block(context)
    } catch (failure: ToolFailure) {
      errorEnvelope(
        tool = tool,
        code = failure.code,
        message = failure.message ?: failure.code,
        retryable = failure.retryable,
        retryHint = failure.retryHint,
        projectPath = canonicalProjectPath.toString(),
        filePath = request.filePath,
        diagnostics = failure.diagnostics,
      )
    } catch (error: IndexNotReadyException) {
      errorEnvelope(
        tool = tool,
        code = "index_not_ready",
        message = "IDEA indexing is not complete.",
        retryable = true,
        retryHint = "Wait for IDEA indexing to finish, or retry with waitForSmartModeMs.",
        projectPath = canonicalProjectPath.toString(),
        filePath = request.filePath,
        diagnostics = listOf(DiagnosticDto("index_not_ready", "Caught IndexNotReadyException.", retryable = true)),
      )
    } catch (error: ProcessCanceledException) {
      errorEnvelope(
        tool = tool,
        code = "timeout",
        message = "IDEA canceled the PSI query.",
        retryable = true,
        retryHint = "Retry after IDEA finishes background work.",
        projectPath = canonicalProjectPath.toString(),
        filePath = request.filePath,
        diagnostics = listOf(DiagnosticDto("process_canceled", error.javaClass.simpleName, retryable = true)),
      )
    } catch (error: Exception) {
      errorEnvelope(
        tool = tool,
        code = "internal_error",
        message = "Internal PSI query error.",
        retryable = false,
        projectPath = canonicalProjectPath.toString(),
        filePath = request.filePath,
        diagnostics = listOf(DiagnosticDto("exception_type", error.javaClass.simpleName)),
      )
    }
  }

  private fun <T> withProgress(block: () -> T): T =
    EmptyProgressIndicator().let { indicator ->
      val timer = Timer("xiaokeer-idea-psi-mcp-query-timeout", true)
      timer.schedule(
        object : TimerTask() {
          override fun run() {
            indicator.cancel()
          }
        },
        QUERY_TIMEOUT_MS,
      )
      try {
        ProgressManager.getInstance().runProcess(Computable { block() }, indicator)
      } finally {
        timer.cancel()
      }
    }

  private fun ensureSmartMode(project: Project) {
    if (DumbService.getInstance(project).isDumb) {
      throw ToolFailure(
        "index_not_ready",
        "IDEA indexing is not complete.",
        retryable = true,
        retryHint = "Wait for IDEA indexing to finish, or retry with waitForSmartModeMs.",
      )
    }
  }

  private fun waitForSmartMode(project: Project, waitForSmartModeMs: Long) {
    val dumbService = DumbService.getInstance(project)
    if (!dumbService.isDumb) return
    val deadline = System.currentTimeMillis() + waitForSmartModeMs.coerceAtLeast(0)
    while (dumbService.isDumb && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    if (dumbService.isDumb) {
      throw ToolFailure(
        "index_not_ready",
        "IDEA indexing is not complete.",
        retryable = true,
        retryHint = "Wait for IDEA indexing to finish, or retry with waitForSmartModeMs.",
        diagnostics = listOf(DiagnosticDto("isDumbMode", "true", retryable = true)),
      )
    }
  }

  private fun findOpenProject(projectPath: Path): Project {
    val matches = ProjectManager.getInstance().openProjects.filter { project ->
      !project.isDisposed && project.basePath?.let { canonicalPath(it) == projectPath } == true
    }
    if (matches.isEmpty()) {
      throw ToolFailure("project_not_open", "No open IntelliJ IDEA project matches projectPath.")
    }
    if (matches.size > 1) {
      throw ToolFailure("ambiguous_project", "Multiple open IntelliJ IDEA projects match projectPath.")
    }
    return matches.single()
  }

  private class QueryContext(val project: Project, val projectRoot: Path) {
    lateinit var virtualFile: VirtualFile
    lateinit var psiFile: PsiFile

    fun prepareVirtualFile(filePath: String) {
      val candidate = Path.of(filePath)
      val resolved = if (candidate.isAbsolute) candidate else projectRoot.resolve(candidate)
      val canonical = canonicalPath(resolved.toString())

      if (!canonical.startsWith(projectRoot)) {
        throw ToolFailure("path_outside_project", "filePath must be inside projectPath.")
      }
      if (isExcludedPath(canonical, projectRoot)) {
        throw ToolFailure("path_outside_project", "filePath is inside an excluded project directory.")
      }
      if (!Files.exists(canonical)) {
        throw ToolFailure("file_not_found", "filePath does not exist.")
      }
      if (!isSupportedExtension(canonical.extension)) {
        throw ToolFailure("unsupported_language", "Only TypeScript and JavaScript files are supported in phase one.")
      }

      virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(canonical)
        ?: throw ToolFailure("file_not_found", "VirtualFile is not available for filePath.")
      if (!virtualFile.isValid) {
        throw ToolFailure("file_not_found", "VirtualFile is invalid.")
      }
    }

    fun preparePsiFile() {
      psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        ?: throw ToolFailure("unsupported_file", "IDEA did not provide a PsiFile for filePath.")
    }

    fun assertDocumentCommitted() {
      val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
      if (!PsiDocumentManager.getInstance(project).isCommitted(document)) {
        throw ToolFailure("document_not_committed", "IDEA document is not committed to PSI.")
      }
    }

    fun positionLookup(line: Int, column: Int): PositionLookup {
      if (line <= 0 || column <= 0) {
        throw ToolFailure("invalid_position", "line and column must be positive 1-based coordinates.")
      }
      val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        ?: throw ToolFailure("document_not_committed", "No IDEA document is available for the PsiFile.")
      if (line > document.lineCount) {
        throw ToolFailure("invalid_position", "line is outside the document.")
      }
      val lineStart = document.getLineStartOffset(line - 1)
      val lineEnd = document.getLineEndOffset(line - 1)
      val lineLength = lineEnd - lineStart
      if (column > lineLength + 1) {
        throw ToolFailure("invalid_position", "column is outside the line.")
      }
      val inputOffset = lineStart + column - 1
      val lookupOffset = when {
        inputOffset < document.textLength && psiFile.findElementAt(inputOffset) != null -> inputOffset
        inputOffset > 0 && psiFile.findElementAt(inputOffset - 1) != null -> inputOffset - 1
        else -> throw ToolFailure("invalid_position", "No PSI element exists at the requested position.")
      }
      return PositionLookup(inputOffset, lookupOffset, inputOffset != lookupOffset)
    }

    fun declarationAt(lookup: PositionLookup): PsiElement? {
      val element = psiFile.findElementAt(lookup.lookupOffset) ?: return null
      val owner = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false) ?: return null
      val identifier = owner.nameIdentifier ?: return null
      return if (identifier.textRange.containsOffset(lookup.lookupOffset)) owner else null
    }

    fun referenceAt(lookup: PositionLookup): PsiReference? =
      psiFile.findReferenceAt(lookup.lookupOffset)
        ?: if (lookup.lookupOffset > 0) psiFile.findReferenceAt(lookup.lookupOffset - 1) else null

    fun resolveInputElement(lookup: PositionLookup): ResolvedInput {
      val definition = declarationAt(lookup)
      if (definition != null) {
        return ResolvedInput("definition", definition, "PsiNameIdentifierOwner")
      }
      val reference = referenceAt(lookup)
        ?: throw ToolFailure("unresolved_symbol", "No PSI reference exists at the requested position.")
      val candidates = resolveReferenceCandidates(reference)
      if (candidates.isEmpty()) {
        throw ToolFailure("unresolved_symbol", "The PSI reference did not resolve to a declaration.")
      }
      return ResolvedInput("usage", candidates.first(), referenceSourceApi(reference))
    }

    fun resolveReferenceCandidates(reference: PsiReference): List<PsiElement> {
      val elements = if (reference is PsiPolyVariantReference) {
        reference.multiResolve(false).mapNotNull { it.element }
      } else {
        listOfNotNull(reference.resolve())
      }
      return elements.filter { it.isValid }
    }

    fun symbolTarget(element: PsiElement, sourceApi: String): SymbolTargetDto {
      val navigation = element.navigationElement ?: element
      val file = navigation.containingFile
      val vf = file?.virtualFile
      val symbolName = (navigation as? PsiNamedElement)?.name ?: (element as? PsiNamedElement)?.name ?: identifierText(element)
      return SymbolTargetDto(
        symbolName = symbolName,
        name = symbolName,
        kind = symbolKind(navigation),
        language = languageFor(vf, file),
        filePath = vf?.let { relativePath(it) },
        absoluteFilePath = vf?.path,
        range = rangeFor(navigation),
        navigationOffset = navigation.textOffset.takeIf { it >= 0 },
        containingSymbol = containingSymbolName(navigation),
        isExported = isExported(navigation),
        sourceApi = sourceApi,
      )
    }

    fun usageDto(element: PsiElement, isDefinition: Boolean, usageKind: String, includeSnippet: Boolean, sourceApi: String): UsageDto {
      val target = symbolTarget(element, sourceApi)
      return UsageDto(
        symbolName = target.symbolName,
        kind = target.kind,
        language = target.language,
        filePath = target.filePath,
        absoluteFilePath = target.absoluteFilePath,
        range = target.range,
        navigationOffset = target.navigationOffset,
        containingSymbol = target.containingSymbol,
        isDefinition = isDefinition,
        usageKind = usageKind,
        snippet = if (includeSnippet) snippetFor(element) else null,
        sourceApi = sourceApi,
      )
    }

    fun usageKind(element: PsiElement): String {
      if (PsiTreeUtil.getParentOfType(element, TypeScriptImportStatement::class.java, false) != null ||
        PsiTreeUtil.getParentOfType(element, JSImportStatement::class.java, false) != null ||
        PsiTreeUtil.getParentOfType(element, ES6ImportDeclaration::class.java, false) != null ||
        PsiTreeUtil.getParentOfType(element, ES6ImportExportDeclaration::class.java, false) != null ||
        PsiTreeUtil.getParentOfType(element, ES6ImportSpecifier::class.java, false) != null
      ) {
        return "import"
      }
      if (PsiTreeUtil.getParentOfType(element, TypeScriptType::class.java, false) != null) {
        return "type_reference"
      }
      val assignment = PsiTreeUtil.getParentOfType(element, JSAssignmentExpression::class.java, false)
      if (assignment?.lOperand?.textRange?.contains(element.textRange) == true) {
        return "write"
      }
      val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false)
      if (call != null) {
        val methodRange = call.methodExpression?.textRange
        return if (methodRange != null && methodRange.contains(element.textRange)) "call" else "route_handler_or_argument"
      }
      return "read"
    }

    fun isElementInsideAllowedProject(element: PsiElement): Boolean {
      val vf = element.containingFile?.virtualFile ?: return false
      return isVirtualFileAllowed(vf)
    }

    fun isVirtualFileAllowed(vf: VirtualFile): Boolean {
      val path = canonicalPath(vf.path)
      return path.startsWith(projectRoot) && !isExcludedPath(path, projectRoot) && isSupportedExtension(path.extension)
    }

    fun isSupportedSymbolElement(element: PsiNamedElement): Boolean {
      if (element.name.isNullOrBlank()) return false
      if (element !is JSNamedElement && element.javaClass.name.startsWith("com.intellij.lang.javascript").not()) return false
      return symbolKind(element) != "unknown" || element is JSNamedElement
    }

    private fun rangeFor(element: PsiElement): RangeDto? {
      val file = element.containingFile ?: return null
      val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
      val range = element.textRange ?: return null
      if (range.startOffset < 0 || range.endOffset > document.textLength) return null
      return RangeDto(
        line = document.getLineNumber(range.startOffset) + 1,
        column = range.startOffset - document.getLineStartOffset(document.getLineNumber(range.startOffset)) + 1,
        endLine = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength)) + 1,
        endColumn = range.endOffset - document.getLineStartOffset(document.getLineNumber(range.endOffset.coerceAtMost(document.textLength))) + 1,
      )
    }

    private fun relativePath(vf: VirtualFile): String =
      projectRoot.relativize(canonicalPath(vf.path)).toString()

    private fun languageFor(vf: VirtualFile?, file: PsiFile?): String {
      val ext = vf?.extension?.lowercase()
      return when (ext) {
        "ts", "tsx" -> "TypeScript"
        "js", "jsx", "mjs", "cjs" -> "JavaScript"
        else -> file?.language?.id ?: "unknown"
      }
    }

    private fun containingSymbolName(element: PsiElement): String? {
      var parent = element.parent
      while (parent != null) {
        if (parent is PsiNamedElement && parent.name != null && parent !== element) {
          return parent.name
        }
        parent = parent.parent
      }
      return null
    }

    private fun isExported(element: PsiElement): Boolean? {
      val owner = element as? JSAttributeListOwner ?: return null
      return owner.attributeList?.hasModifier(JSAttributeList.ModifierType.EXPORT)
    }

    private fun snippetFor(element: PsiElement): String? {
      val file = element.containingFile ?: return null
      val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
      val offset = element.textRange.startOffset.coerceIn(0, document.textLength)
      val line = document.getLineNumber(offset)
      val lineText = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()
      return if (lineText.length > 240) lineText.take(237) + "..." else lineText
    }

    private fun identifierText(element: PsiElement): String? {
      val text = element.text ?: return null
      return text.takeIf { it.length <= 120 && it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) }
    }
  }

  private data class PositionLookup(val inputOffset: Int, val lookupOffset: Int, val adjustedFromInput: Boolean)
  private data class ResolvedInput(val inputRole: String, val element: PsiElement, val sourceApi: String)

  private class ToolFailure(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val retryHint: String? = null,
    val diagnostics: List<DiagnosticDto> = emptyList(),
  ) : RuntimeException(message)

  companion object {
    private val SUPPORTED_EXTENSIONS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
    private val EXCLUDED_DIRS = setOf(".git", "node_modules", "dist", "build", ".gradle", ".idea", ".xiaokeer")
    private const val QUERY_TIMEOUT_MS = 30_000L

    private fun isSupportedExtension(extension: String?): Boolean =
      extension?.lowercase() in SUPPORTED_EXTENSIONS

    private fun isExcludedPath(path: Path, root: Path): Boolean {
      val relative = runCatching { root.relativize(path) }.getOrNull() ?: return true
      return relative.any { it.name in EXCLUDED_DIRS }
    }

    private fun symbolKind(element: PsiElement): String =
      when (element) {
        is TypeScriptInterface -> "interface"
        is TypeScriptTypeAlias -> "type_alias"
        is TypeScriptEnum -> "enum"
        is TypeScriptClass, is JSClass -> "class"
        is TypeScriptFunction, is JSFunction -> if (PsiTreeUtil.getParentOfType(element, JSClass::class.java, true) != null) "method" else "function"
        is TypeScriptField, is JSField, is JSProperty -> "property"
        is TypeScriptVariable, is JSVariable -> "variable"
        is TypeScriptParameter, is JSParameter -> "parameter"
        else -> "unknown"
      }

    private fun referenceSourceApi(reference: PsiReference): String =
      if (reference is PsiPolyVariantReference) "PsiPolyVariantReference.multiResolve" else "PsiReference.resolve"
  }
}

fun canonicalPath(path: String): Path =
  Path.of(path).toAbsolutePath().normalize().toFile().canonicalFile.toPath()

fun canonicalPathString(path: String): String = canonicalPath(path).toString()

private fun TextRange.contains(other: TextRange): Boolean =
  containsOffset(other.startOffset) && containsOffset(other.endOffset.coerceAtLeast(other.startOffset))
