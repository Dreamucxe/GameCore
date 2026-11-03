package com.gamecore.studio.mcp

import com.gamecore.studio.core.DraftCodec
import com.gamecore.studio.core.StudioSession
import com.gamecore.studio.core.Templates
import com.gamecore.studio.model.CreatorSections
import com.gamecore.studio.model.PluginDraft
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Route-level tests for the debug bridge.
 *
 * These run on the JVM against the debug source set, which is the same code the debug APK runs.
 * They deliberately exercise `handle` as well as a real loopback socket: the former proves the
 * contract and the latter proves framing, token enforcement and the accept loop. They do not
 * prove the release APK lacks the bridge; that is checked by inspecting the built artifact.
 */
class StudioMcpBridgeTest {

    private lateinit var session: StudioSession
    private lateinit var bridge: StudioMcpBridge

    @Before
    fun setUp() {
        session = StudioSession(validDraft())
        bridge = StudioMcpBridge(
            session = session,
            appPackage = "com.gamecore.pluginmaker.debug",
            appVersion = "1.1.0-debug",
            logger = {},
        )
    }

    @After
    fun tearDown() {
        bridge.close()
    }

    @Test
    fun healthIsPublicAndReportsTheProtocol() {
        val response = bridge.handle(request("GET", "/v1/health"))
        assertEquals(200, response.status)
        val body = json(response)
        assertTrue(body.getBoolean("ok"))
        assertEquals(1, body.getInt("protocol"))
    }

    @Test
    fun dataRoutesRequireTheToken() {
        val response = bridge.handle(request("GET", "/v1/status", token = null))
        assertEquals(401, response.status)
        assertEquals("unauthorized", json(response).getString("error"))
    }

    @Test
    fun statusNamesTheAppAndTheOpenDraft() {
        val response = bridge.handle(request("GET", "/v1/status"))
        assertEquals(200, response.status)
        val body = json(response)
        assertEquals("com.gamecore.pluginmaker.debug", body.getString("app"))
        assertEquals("1.1.0-debug", body.getString("version"))
        assertEquals("com.example.mcp", body.getJSONObject("draft").getString("id"))
        assertTrue(body.getJSONObject("draft").getBoolean("valid"))
    }

    @Test
    fun getDraftRoundTripsThroughTheStorageCodec() {
        val response = bridge.handle(request("GET", "/v1/draft"))
        assertEquals(200, response.status)
        val body = json(response)
        val decoded = DraftCodec.decode(body.getJSONObject("draft").toString())
        assertEquals(session.current(), decoded)
        assertTrue(body.getJSONObject("validation").getBoolean("valid"))
    }

    @Test
    fun putDraftReplacesTheLiveSession() {
        val next = validDraft().copy(name = "Renamed over MCP")
        val response = bridge.handle(request("PUT", "/v1/draft", JSONObject().put("draft", draftJson(next))))
        assertEquals(200, response.status)
        assertEquals("Renamed over MCP", session.current().name)
        assertEquals("Renamed over MCP", json(response).getJSONObject("draft").getString("name"))
    }

    @Test
    fun malformedJsonIsABadRequestNotACrash() {
        val response = bridge.handle(
            request("PUT", "/v1/draft", rawBody = "{not json"),
        )
        assertEquals(400, response.status)
        assertEquals("invalid_draft", json(response).getString("error"))
    }

    @Test
    fun validateReportsTheExactField() {
        val invalid = validDraft().copy(id = "not a package id")
        val response = bridge.handle(
            request("POST", "/v1/validate", JSONObject().put("draft", draftJson(invalid))),
        )
        assertEquals(200, response.status)
        val body = json(response)
        assertFalse(body.getBoolean("valid"))
        val problems = body.getJSONArray("problems")
        assertTrue((0 until problems.length()).any { problems.getJSONObject(it).getString("field") == "id" })
    }

    @Test
    fun exportReturnsDeterministicPackageBytes() {
        val first = bridge.handle(request("POST", "/v1/export"))
        val second = bridge.handle(request("POST", "/v1/export"))
        assertEquals(200, first.status)
        assertEquals(200, second.status)
        val firstBody = json(first)
        val secondBody = json(second)
        assertEquals(firstBody.getString("sha256"), secondBody.getString("sha256"))
        val bytes = Base64.getDecoder().decode(firstBody.getString("base64"))
        assertEquals(firstBody.getInt("size"), bytes.size)
        assertEquals(firstBody.getString("sha256"), com.gamecore.studio.core.PluginComposer.sha256(bytes))
        val entries = firstBody.getJSONArray("entries")
        assertTrue((0 until entries.length()).any { entries.getString(it) == "manifest.json" })
        assertTrue((0 until entries.length()).any { entries.getString(it) == "theme.json" })
    }

    @Test
    fun exportRefusesAnInvalidDraft() {
        session.update(validDraft().copy(id = ""))
        val response = bridge.handle(request("POST", "/v1/export"))
        assertEquals(422, response.status)
        val body = json(response)
        assertEquals("invalid_draft", body.getString("error"))
        assertFalse(body.getJSONObject("validation").getBoolean("valid"))
        assertFalse(body.has("base64"))
    }

    @Test
    fun inspectAcceptsAnExportedPackageAndImportsIt() {
        val exported = json(bridge.handle(request("POST", "/v1/export")))
        val response = bridge.handle(
            request("POST", "/v1/inspect", JSONObject().put("packageBase64", exported.getString("base64"))),
        )
        assertEquals(200, response.status)
        val body = json(response)
        val inspection = body.getJSONObject("inspection")
        assertEquals(exported.getString("sha256"), inspection.getString("sha256"))
        assertTrue(inspection.getJSONArray("entries").length() > 0)
        val imported = body.getJSONObject("import")
        assertTrue(imported.getBoolean("ok"))
        assertEquals("com.example.mcp", imported.getJSONObject("draft").getString("id"))
    }

    @Test
    fun inspectRejectsBadBase64() {
        val response = bridge.handle(
            request("POST", "/v1/inspect", JSONObject().put("packageBase64", "%%%")),
        )
        assertEquals(400, response.status)
    }

    @Test
    fun templatesListAndApplyWithoutRenamingTheDraft() {
        val list = bridge.handle(request("GET", "/v1/templates"))
        assertEquals(200, list.status)
        assertEquals(Templates.ALL.size, json(list).getJSONArray("templates").length())

        val applied = bridge.handle(
            request("POST", "/v1/templates", JSONObject().put("id", "amoled")),
        )
        assertEquals(200, applied.status)
        val template = Templates.ALL.first { it.id == "amoled" }
        assertEquals(template.theme.primary, session.current().theme.primary)
        assertEquals("MCP Test", session.current().theme.name)
    }

    @Test
    fun unknownRoutesAndMethodsAreRefused() {
        assertEquals(404, bridge.handle(request("GET", "/v1/nope")).status)
        assertEquals(405, bridge.handle(request("DELETE", "/v1/draft")).status)
    }

    @Test
    fun socketEnforcesTheTokenAndServesHealth() {
        val socketBridge = StudioMcpBridge(
            session = session,
            appPackage = "com.gamecore.pluginmaker.debug",
            appVersion = "1.1.0-debug",
            port = 0,
            logger = {},
        )
        try {
            assertTrue(socketBridge.start())
            val port = socketBridge.boundPort
            assertTrue(port > 0)
            val health = get(port, "/v1/health", token = null)
            assertEquals(200, health.first)
            assertTrue(JSONObject(health.second).getBoolean("ok"))
            assertEquals(401, get(port, "/v1/status", token = null).first)
            assertEquals(200, get(port, "/v1/status", token = StudioMcpBridge.DEFAULT_TOKEN).first)
        } finally {
            socketBridge.close()
        }
    }

    @Test
    fun sessionNotifiesOnlyWhenTheDraftChanges() {
        var notifications = 0
        val listener: (PluginDraft) -> Unit = { notifications++ }
        session.addListener(listener)
        session.update(session.current())
        assertEquals(0, notifications)
        session.update(session.current().copy(name = "Changed"))
        assertEquals(1, notifications)
        session.removeListener(listener)
        session.update(session.current().copy(name = "Changed again"))
        assertEquals(1, notifications)
    }

    private fun validDraft(): PluginDraft = PluginDraft(
        id = "com.example.mcp",
        name = "MCP Test",
        version = 1,
        versionName = "1.0.0",
        permissions = emptySet(),
        theme = Templates.ALL.first().theme.copy(name = "MCP Test"),
        sections = CreatorSections(themeEnabled = true),
    )

    private fun draftJson(draft: PluginDraft): JSONObject = JSONObject(DraftCodec.encode(draft))

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        rawBody: String? = null,
        token: String? = StudioMcpBridge.DEFAULT_TOKEN,
    ): HttpRequest {
        val bytes = (rawBody ?: body?.toString()).orEmpty().toByteArray(StandardCharsets.UTF_8)
        val headers = buildMap {
            if (token != null) put(StudioMcpBridge.TOKEN_HEADER.lowercase(), token)
        }
        return HttpRequest(method = method, path = path, headers = headers, body = bytes)
    }

    private fun json(response: HttpResponse): JSONObject =
        JSONObject(String(response.body, StandardCharsets.UTF_8))

    private fun get(port: Int, path: String, token: String?): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (token != null) append("${StudioMcpBridge.TOKEN_HEADER}: $token\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val text = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val status = text.lineSequence().first().split(" ")[1].toInt()
            return status to text.substringAfter("\r\n\r\n")
        }
    }
}
