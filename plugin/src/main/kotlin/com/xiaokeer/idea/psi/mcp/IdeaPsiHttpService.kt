package com.xiaokeer.idea.psi.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors

class IdeaPsiHttpService : Disposable {
  private val logger = Logger.getInstance(IdeaPsiHttpService::class.java)
  private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
  private val startedAt = Instant.now().toString()
  private val token = generateToken()
  private val executor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "xiaokeer-idea-psi-mcp-http").apply { isDaemon = true }
  }
  private val queryService = PsiQueryService()
  private val runtimeDir = Path.of(System.getProperty("user.home"), "Library", "Application Support", SERVICE_NAME)
  private val manifestPath = runtimeDir.resolve("runtime.json")
  private val server: HttpServer

  init {
    server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    server.executor = executor
    server.createContext("/health") { exchange -> handle(exchange) { healthPayload() } }
    server.createContext("/projects") { exchange -> handle(exchange) { projectsPayload() } }
    server.createContext("/psi/resolve-symbol") { exchange ->
      handle(exchange, requiredMethod = "POST") {
        queryService.resolveSymbol(mapper.readValue(exchange.readBody()))
      }
    }
    server.createContext("/psi/find-usages") { exchange ->
      handle(exchange, requiredMethod = "POST") {
        queryService.findUsages(mapper.readValue(exchange.readBody()))
      }
    }
    server.createContext("/psi/search-symbol") { exchange ->
      handle(exchange, requiredMethod = "POST") {
        queryService.searchSymbol(mapper.readValue(exchange.readBody()))
      }
    }
    server.start()
    refreshManifest()
  }

  fun refreshManifest() {
    try {
      Files.createDirectories(runtimeDir)
      val manifest = RuntimeManifestDto(
        schemaVersion = 1,
        protocolVersion = PROTOCOL_VERSION,
        service = SERVICE_NAME,
        host = "127.0.0.1",
        port = server.address.port,
        pid = ProcessHandle.current().pid(),
        startedAt = startedAt,
        token = token,
        projects = openProjectManifests(),
      )
      val tmp = manifestPath.resolveSibling("${manifestPath.fileName}.tmp")
      Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8)
      restrictOwnerOnly(tmp)
      Files.move(tmp, manifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      restrictOwnerOnly(manifestPath)
    } catch (error: Exception) {
      logger.warn("Failed to refresh PSI MCP runtime manifest: ${error.javaClass.simpleName}")
    }
  }

  override fun dispose() {
    server.stop(0)
    executor.shutdownNow()
    try {
      Files.deleteIfExists(manifestPath)
    } catch (error: Exception) {
      logger.warn("Failed to delete PSI MCP runtime manifest: ${error.javaClass.simpleName}")
    }
  }

  private fun handle(exchange: HttpExchange, requiredMethod: String? = null, block: () -> Any?) {
    exchange.use {
      try {
        if (requiredMethod != null && exchange.requestMethod != requiredMethod) {
          sendJson(exchange, HttpURLConnection.HTTP_BAD_METHOD, errorEnvelope("http", "unsupported_method", "Unsupported HTTP method."))
          return
        }
        if (!isAuthorized(exchange)) {
          sendJson(exchange, HttpURLConnection.HTTP_UNAUTHORIZED, errorEnvelope("http", "unauthorized", "Invalid bearer token."))
          return
        }
        val payload = block()
        sendJson(exchange, HttpURLConnection.HTTP_OK, payload)
      } catch (error: Exception) {
        logger.warn("PSI MCP HTTP handler failed: ${error.javaClass.simpleName}")
        sendJson(
          exchange,
          HttpURLConnection.HTTP_INTERNAL_ERROR,
          errorEnvelope("http", "internal_error", "Internal plugin service error."),
        )
      }
    }
  }

  private fun healthPayload(): Map<String, Any?> {
    refreshManifest()
    return mapOf(
      "ok" to true,
      "protocolVersion" to PROTOCOL_VERSION,
      "service" to SERVICE_NAME,
      "host" to "127.0.0.1",
      "port" to server.address.port,
      "pid" to ProcessHandle.current().pid(),
      "startedAt" to startedAt,
      "runtimeManifestPath" to manifestPath.toString(),
      "projects" to openProjectManifests(),
    )
  }

  private fun projectsPayload(): Map<String, Any?> {
    refreshManifest()
    return mapOf(
      "ok" to true,
      "protocolVersion" to PROTOCOL_VERSION,
      "projects" to openProjectManifests(),
    )
  }

  private fun isAuthorized(exchange: HttpExchange): Boolean {
    val authorization = exchange.requestHeaders.getFirst("Authorization") ?: return false
    return authorization == "Bearer $token"
  }

  private fun sendJson(exchange: HttpExchange, status: Int, payload: Any?) {
    val body = mapper.writeValueAsBytes(payload)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
  }

  private fun HttpExchange.readBody(maxBytes: Int = 1_048_576): String {
    val bytes = requestBody.readNBytes(maxBytes + 1)
    if (bytes.size > maxBytes) {
      throw IllegalArgumentException("Request body too large")
    }
    return bytes.toString(StandardCharsets.UTF_8)
  }

  private fun openProjectManifests(): List<ProjectManifestDto> =
    ProjectManager.getInstance().openProjects.mapNotNull { project ->
      val basePath = project.basePath ?: return@mapNotNull null
      ProjectManifestDto(
        name = project.name,
        basePath = canonicalPathString(basePath),
        isDisposed = project.isDisposed,
      )
    }

  private fun restrictOwnerOnly(path: Path) {
    try {
      Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    } catch (_: UnsupportedOperationException) {
      // macOS supports POSIX permissions; this fallback keeps the plugin portable.
    }
  }

  private fun generateToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}

