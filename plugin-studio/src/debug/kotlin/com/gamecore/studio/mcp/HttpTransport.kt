package com.gamecore.studio.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** One parsed HTTP/1.1 request. The bridge serves a closed set of routes, not a web server. */
internal data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** One response. The body is always owned by the caller and written verbatim. */
internal data class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

/** A request that is malformed or over a hard limit; answered with 400/413, never a stack trace. */
internal class BadHttpRequest(message: String, val status: Int = 400) : Exception(message)

/**
 * The smallest HTTP/1.1 reader/writer the debug bridge can use.
 *
 * `com.sun.net.httpserver` is a Java SE API that Android does not ship, and a web framework would
 * add a dependency and an attack surface for four routes. This parser accepts exactly what the
 * MCP client sends — one request, `Content-Length`, `Connection: close` — and refuses everything
 * else. Header bytes and body bytes are capped before allocation, so a local process cannot make
 * the studio allocate without bound.
 */
internal object HttpTransport {

    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 6 * 1024 * 1024

    /** Read one request, or null when the peer closed before sending anything. */
    fun read(input: InputStream): HttpRequest? {
        val headerBytes = readHeader(input) ?: return null
        val headerText = String(headerBytes, StandardCharsets.UTF_8)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) throw BadHttpRequest("The request line is empty.")

        val parts = requestLine.split(Regex("\\s+"))
        if (parts.size < 3) throw BadHttpRequest("The request line is malformed.")
        val method = parts[0].uppercase(Locale.ROOT)
        val path = parts[1]
        if (!path.startsWith("/")) throw BadHttpRequest("The request target must be an absolute path.")
        if (parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
            throw BadHttpRequest("Only HTTP/1.1 and HTTP/1.0 are supported.")
        }

        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isEmpty()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadHttpRequest("A request header is malformed.")
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            if (name.isEmpty()) throw BadHttpRequest("A request header has no name.")
            headers[name] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw BadHttpRequest("Chunked request bodies are not supported.")
        }

        val length = when (val raw = headers["content-length"]) {
            null -> 0
            else -> raw.toIntOrNull() ?: throw BadHttpRequest("Content-Length is not a number.")
        }
        if (length < 0) throw BadHttpRequest("Content-Length is negative.")
        if (length > MAX_BODY_BYTES) throw BadHttpRequest("The request body is too large.", status = 413)

        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read <= 0) throw BadHttpRequest("The request body ended early.")
            offset += read
        }
        return HttpRequest(method = method, path = path, headers = headers, body = body)
    }

    /** Write one response and force `Connection: close`; the caller closes the socket. */
    fun write(output: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(response.body)
        output.flush()
    }

    private fun readHeader(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next == -1) {
                if (buffer.size() == 0) return null
                throw BadHttpRequest("The request headers ended early.")
            }
            buffer.write(next)
            matched = when {
                next == '\r'.code && matched == 0 -> 1
                next == '\n'.code && matched == 1 -> 2
                next == '\r'.code && matched == 2 -> 3
                next == '\n'.code && matched == 3 -> 4
                else -> 0
            }
            if (matched == 4) return buffer.toByteArray()
        }
        throw BadHttpRequest("The request headers are too large.", status = 413)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        422 -> "Unprocessable Entity"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        else -> "Response"
    }
}
