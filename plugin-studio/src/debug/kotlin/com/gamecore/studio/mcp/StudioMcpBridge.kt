package com.gamecore.studio.mcp

import android.util.Log
import com.gamecore.studio.core.DraftCodec
import com.gamecore.studio.core.ImportOutcome
import com.gamecore.studio.core.PackageInspector
import com.gamecore.studio.core.PluginComposer
import com.gamecore.studio.core.PluginImporter
import com.gamecore.studio.core.StudioCapabilities
import com.gamecore.studio.core.StudioSession
import com.gamecore.studio.core.Templates
import com.gamecore.studio.model.PluginDraft
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A debug-only JSON bridge between the running Plugin Maker and a local MCP server.
 *
 * The MCP process cannot hold a Compose draft or call the package writer directly — it is a
 * separate process with no Android runtime. This bridge is the one seam: the app owns the draft,
 * validation rules and composer, and the bridge exposes only those operations over loopback.
 *
 * **Why it lives in `src/debug`.** It is compiled into debug variants only. The release APK does
 * not contain this class, its routes or its token; R8 never has to strip a dormant server from the
 * thing users install. The bridge is started by the maker's debug-only `Application`, never by the
 * release manifest.
 *
 * **What it is not.** It is not a remote API and not a security boundary. It binds `127.0.0.1`,
 * refuses non-loopback peers through the bind itself, requires a token that is deliberately fixed
 * in a debuggable build, and exposes draft editing and package bytes only. It cannot install a
 * plugin, grant a permission, run a rule, open a screen or change GameCore.
 *
 * Wire contract: `docs/PLUGIN_STUDIO_MCP.md`. Every route is versioned under `/v1`.
 */
class StudioMcpBridge(
    private val session: StudioSession,
    private val appPackage: String,
    private val appVersion: String,
    port: Int = DEFAULT_PORT,
    token: String = DEFAULT_TOKEN,
    private val logger: (String) -> Unit = { message -> Log.i(TAG, message) },
) : Closeable {

    private val requestedPort = port
    private val token = token
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var closed = false
    private val workers = Executors.newFixedThreadPool(WORKERS) { runnable ->
        Thread(runnable, "studio-mcp-worker").apply { isDaemon = true }
    }

    /** True while the accept loop is running. */
    val isRunning: Boolean get() = synchronized(lock) { serverSocket != null }

    /** The actual port, which differs from the requested one when tests ask for port 0. */
    val boundPort: Int get() = synchronized(lock) { serverSocket?.localPort ?: -1 }

    /**
     * Bind loopback and start accepting. Returns false — and leaves the app fully usable — when the
     * port is taken or the socket cannot be created. A debug convenience must never crash the app.
     */
    fun start(): Boolean = synchronized(lock) {
        if (serverSocket != null) return true
        if (closed) return false
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), requestedPort), BACKLOG)
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket) }, "studio-mcp-accept").apply {
                isDaemon = true
                start()
            }
            logger("bridge listening on $LOOPBACK:${socket.localPort}")
            true
        } catch (failure: Exception) {
            logger("bridge could not start: ${failure.javaClass.simpleName}: ${failure.message}")
            false
        }
    }

    /** Stop accepting and close the worker pool. Safe to call more than once. */
    override fun close() {
        val socket: ServerSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            socket = serverSocket
            serverSocket = null
            acceptThread = null
        }
        runCatching { socket?.close() }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(1, TimeUnit.SECONDS) }
        logger("bridge stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                break
            }
            try {
                workers.execute { handleClient(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val request = try {
                HttpTransport.read(socket.getInputStream())
            } catch (bad: BadHttpRequest) {
                runCatching {
                    HttpTransport.write(
                        socket.getOutputStream(),
                        error(bad.status, "bad_request", bad.message ?: "The request is malformed."),
                    )
                }
                return
            } catch (_: Exception) {
                return
            }
            if (request == null) return
            val response = try {
                handle(request)
            } catch (failure: InvalidDraft) {
                error(400, "invalid_draft", failure.message ?: "The draft is not valid JSON.")
            } catch (failure: Exception) {
                logger("request failed: ${request.method} ${request.path.substringBefore('?')} (${failure.javaClass.simpleName})")
                error(500, "internal_error", "The bridge could not complete the request.")
            }
            runCatching { HttpTransport.write(socket.getOutputStream(), response) }
        }
    }

    /**
     * Route one parsed request. Exposed to unit tests so every route can be checked without a
     * socket; the HTTP layer above only adds framing and socket limits.
     */
    internal fun handle(request: HttpRequest): HttpResponse {
        val path = request.path.substringBefore('?')
        if (path == "/v1/health") {
            if (request.method != "GET") return error(405, "method_not_allowed", "Use GET.")
            return json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("protocol", PROTOCOL)
                    .put("bridge", "debug-loopback"),
            )
        }
        if (!authorised(request)) {
            return error(401, "unauthorized", "A valid $TOKEN_HEADER header is required.")
        }
        return try {
            when (path) {
                "/v1/status" -> status(request)
                "/v1/draft" -> draft(request)
                "/v1/validate" -> validate(request)
                "/v1/export" -> export(request)
                "/v1/inspect" -> inspect(request)
                "/v1/templates" -> templates(request)
                "/v1/capabilities" -> capabilities(request)
                else -> error(404, "not_found", "No bridge route matches $path.")
            }
        } catch (failure: InvalidDraft) {
            error(400, "invalid_draft", failure.message ?: "The draft is not valid JSON.")
        }
    }

    private fun status(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return error(405, "method_not_allowed", "Use GET.")
        val current = session.current()
        val problems = current.problems()
        return json(
            200,
            JSONObject()
                .put("protocol", PROTOCOL)
                .put("bridge", "debug-loopback")
                .put("app", appPackage)
                .put("version", appVersion)
                .put("port", boundPort)
                .put(
                    "draft",
                    JSONObject()
                        .put("id", current.id)
                        .put("name", current.name)
                        .put("version", current.version)
                        .put("versionName", current.versionName)
                        .put("valid", problems.isEmpty())
                        .put("problemCount", problems.size),
                ),
        )
    }

    private fun draft(request: HttpRequest): HttpResponse = when (request.method) {
        "GET" -> json(200, draftResponse(session.current()))
        "PUT", "POST" -> {
            val next = parseDraft(request, required = true)
            session.update(next)
            json(200, draftResponse(next))
        }
        else -> error(405, "method_not_allowed", "Use GET or PUT.")
    }

    private fun validate(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return error(405, "method_not_allowed", "Use POST.")
        val candidate = parseDraft(request, required = false) ?: session.current()
        return json(
            200,
            validationJson(candidate)
                .put("draftId", candidate.id)
                .put("draftName", candidate.name),
        )
    }

    private fun export(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return error(405, "method_not_allowed", "Use POST.")
        val candidate = parseDraft(request, required = false) ?: session.current()
        val validation = validationJson(candidate)
        if (!validation.optBoolean("valid")) {
            return json(
                422,
                JSONObject()
                    .put("error", "invalid_draft")
                    .put("message", "The package is not exported while the draft has problems.")
                    .put("validation", validation),
            )
        }
        val bytes = PluginComposer.packageBytes(candidate)
        return json(
            200,
            JSONObject()
                .put("fileName", candidate.fileName + ".gcplugin")
                .put("sha256", PluginComposer.sha256(bytes))
                .put("size", bytes.size)
                .put("entries", JSONArray(PluginComposer.entryNames(candidate)))
                .put("base64", Base64.getEncoder().encodeToString(bytes))
                .put("draftId", candidate.id)
                .put("versionName", candidate.versionName),
        )
    }

    private fun inspect(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return error(405, "method_not_allowed", "Use POST.")
        val root = parseJsonObject(request, required = true)
        val encoded = root.optString("packageBase64", "").trim()
        if (encoded.isEmpty()) throw InvalidDraft("\"packageBase64\" is required.")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw InvalidDraft("\"packageBase64\" is not valid base64.")
        }
        if (bytes.size > PluginImporter.MAX_TOTAL_BYTES) {
            return error(413, "package_too_large", "The package is larger than ${PluginImporter.MAX_TOTAL_BYTES} bytes.")
        }
        val inspection = PackageInspector.inspect(bytes)
        val inspectionJson = JSONObject()
            .put(
                "entries",
                JSONArray().also { array ->
                    inspection.entries.forEach { entry ->
                        array.put(JSONObject().put("name", entry.name).put("bytes", entry.bytes))
                    }
                },
            )
            .put("totalBytes", inspection.totalBytes)
            .put("sha256", inspection.sha256)
            .put("issues", JSONArray(inspection.issues))
            .put("manifestJson", inspection.manifestJson)
            .put("themeJson", inspection.themeJson)
        val importJson = when (val outcome = PluginImporter.import(bytes)) {
            is ImportOutcome.Success -> JSONObject()
                .put("ok", true)
                .put("draft", draftJson(outcome.draft))
                .put("warnings", JSONArray(outcome.report.warnings))
                .put("ignoredManifestKeys", JSONArray(outcome.report.ignoredManifestKeys))
                .put("ignoredThemeKeys", JSONArray(outcome.report.ignoredThemeKeys))
                .put("ignoredSections", JSONArray(outcome.report.ignoredSections))
            is ImportOutcome.Failure -> JSONObject()
                .put("ok", false)
                .put("message", outcome.message)
        }
        return json(200, JSONObject().put("inspection", inspectionJson).put("import", importJson))
    }

    private fun templates(request: HttpRequest): HttpResponse = when (request.method) {
        "GET" -> json(
            200,
            JSONObject().put(
                "templates",
                JSONArray().also { array ->
                    Templates.ALL.forEach { template ->
                        array.put(
                            JSONObject()
                                .put("id", template.id)
                                .put("name", template.name)
                                .put("description", template.description)
                                .put("palette", JSONArray(template.palette.map { argbHex(it) })),
                        )
                    }
                },
            ),
        )
        "POST" -> {
            val root = parseJsonObject(request, required = true)
            val id = root.optString("id", "").trim()
            if (id.isEmpty()) throw InvalidDraft("\"id\" is required.")
            val template = Templates.ALL.firstOrNull { it.id == id }
                ?: return error(404, "unknown_template", "No template has the id \"$id\".")
            val current = session.current()
            // Match the editor exactly: a template replaces the theme, not the package identity.
            val next = current.copy(theme = template.theme.copy(name = current.theme.name))
            session.update(next)
            json(200, draftResponse(next))
        }
        else -> error(405, "method_not_allowed", "Use GET or POST.")
    }

    private fun capabilities(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return error(405, "method_not_allowed", "Use GET.")
        return json(200, StudioCapabilities.asJson())
    }

    private fun draftResponse(draft: PluginDraft): JSONObject = JSONObject()
        .put("draft", draftJson(draft))
        .put("validation", validationJson(draft))

    private fun draftJson(draft: PluginDraft): JSONObject = JSONObject(DraftCodec.encode(draft))

    private fun validationJson(draft: PluginDraft): JSONObject {
        val problems = draft.problems()
        return JSONObject()
            .put("valid", problems.isEmpty())
            .put(
                "problems",
                JSONArray().also { array ->
                    problems.forEach { problem ->
                        array.put(JSONObject().put("field", problem.field).put("message", problem.message))
                    }
                },
            )
    }

    private fun parseDraft(request: HttpRequest, required: Boolean): PluginDraft {
        if (request.body.isEmpty()) {
            if (required) throw InvalidDraft("A JSON draft object is required.")
            return session.current()
        }
        val root = parseJsonObject(request, required = true)
        val candidate = if (root.has("draft")) {
            root.optJSONObject("draft") ?: throw InvalidDraft("\"draft\" must be an object.")
        } else {
            root
        }
        return DraftCodec.decode(candidate.toString())
            ?: throw InvalidDraft("The body is not a valid studio draft.")
    }

    private fun parseJsonObject(request: HttpRequest, required: Boolean): JSONObject {
        if (request.body.isEmpty()) {
            if (required) throw InvalidDraft("A JSON object body is required.")
            return JSONObject()
        }
        return try {
            JSONObject(String(request.body, StandardCharsets.UTF_8))
        } catch (_: JSONException) {
            throw InvalidDraft("The body is not a JSON object.")
        }
    }

    private fun authorised(request: HttpRequest): Boolean {
        val supplied = request.headers[TOKEN_HEADER.lowercase(Locale.ROOT)].orEmpty()
        return MessageDigest.isEqual(
            token.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun json(status: Int, body: JSONObject): HttpResponse = HttpResponse(
        status = status,
        contentType = "application/json; charset=utf-8",
        body = body.toString().toByteArray(StandardCharsets.UTF_8),
    )

    private fun error(status: Int, code: String, message: String): HttpResponse = json(
        status,
        JSONObject().put("error", code).put("message", message),
    )

    private fun argbHex(value: Long): String =
        String.format(Locale.ROOT, "#%08X", value and 0xFFFFFFFFL)

    /** The draft body could not be decoded; a 400, not an internal error. */
    private class InvalidDraft(message: String) : Exception(message)

    companion object {
        const val PROTOCOL = 1
        const val DEFAULT_PORT = 47653
        const val DEFAULT_TOKEN = "gamecore-studio-debug-v1"
        const val TOKEN_HEADER = "X-GameCore-Studio-Token"

        private const val TAG = "StudioMcp"
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 16
        private const val WORKERS = 4
    }
}
