# Operation Notes

## Startup Model

The IDEA plugin starts the internal loopback service inside the IntelliJ IDEA process. The service binds only to `127.0.0.1`, chooses an available port, writes a user-local runtime manifest, and serves token-authenticated JSON endpoints.

The MCP server is a stdio process. It reads the manifest for each tool call, validates project and path boundaries before calling the plugin, and returns the plugin's structured envelope through MCP `structuredContent`.

## Required IDEA State

`petaskApp` must be open in IntelliJ IDEA. The plugin validates `projectPath` against the open project roots and does not infer a project from `filePath`.

The `psi_*` tools require Smart Mode. If IDEA is indexing and the caller does not wait long enough, the plugin returns `index_not_ready`.

## Source State

The source state is IDEA's committed PSI state. The plugin commits the target document to PSI before position-based queries and returns `sourceState: "ideCommittedPsi"` for successful PSI results.

Position-based queries run under IDEA document commit, read action, Smart Mode, and a progress indicator. Plugin-side PSI queries use a 30 second cancellation boundary. If IDEA cancels the query or remains in Dumb Mode, the plugin returns a structured retryable error instead of falling back to disk search.

## Result Limits

`psi_search_symbol` defaults to 50 results and caps at 200.

`psi_find_usages` defaults to 100 results and caps at 500.

Snippets are one-line context and capped before returning to the MCP server.
