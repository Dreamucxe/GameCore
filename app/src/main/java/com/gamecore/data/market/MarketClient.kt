package com.gamecore.data.market

import android.content.Context
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.market.MarketEntry
import com.gamecore.core.plugin.market.MarketIndexCodec
import com.gamecore.core.plugin.market.MarketIndexDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of reading the curated index. */
sealed interface MarketFetchResult {
    /**
     * A decoded index. [fromBundledAsset] is true when the network read failed and this is the snapshot
     * shipped inside the APK: the list is still useful, but it is not the live index and the screen says
     * so rather than passing yesterday's catalog off as a successful refresh.
     */
    data class Success(
        val document: MarketIndexDocument,
        val fromBundledAsset: Boolean = false,
    ) : MarketFetchResult

    data class Failure(val message: String) : MarketFetchResult
}

/** The outcome of downloading one package, with the digest computed from the bytes received. */
sealed interface MarketDownloadResult {
    data class Success(val bytes: ByteArray, val sha256: String) : MarketDownloadResult
    data class Failure(val message: String) : MarketDownloadResult
}

/**
 * The only network client in the plugin system.
 *
 * It speaks HTTPS to one curated host, caps every response, and computes the digest of exactly the
 * bytes it returns so the caller can compare it with the index before installing. The download server
 * is transport: it cannot make GameCore install bytes the index did not name.
 *
 * There is no OkHttp dependency in this project, and one narrow GET does not justify adding one.
 * [HttpURLConnection] keeps the code small, and every decision that matters — host, scheme, size cap,
 * timeout, no cache — is explicit here rather than in a library's defaults.
 */
@Singleton
class MarketClient @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationContext private val context: Context,
) {

    /**
     * The live index first, the snapshot in the APK second.
     *
     * The snapshot exists for the two situations where the network read cannot succeed: a fresh install
     * with no connection, and a tree whose curated index has not been published yet. Falling back keeps
     * Discover from being an error screen on a device that has nothing wrong with it. The caller knows
     * which one it got, so the screen can label the list as a snapshot instead of pretending the fetch
     * worked.
     */
    suspend fun fetchIndex(): MarketFetchResult = withContext(io) {
        val remote = fetchRemoteIndex()
        if (remote is MarketFetchResult.Success) remote else bundledIndex() ?: remote
    }

    private fun fetchRemoteIndex(): MarketFetchResult {
        return try {
            val connection = open(INDEX_URL)
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return MarketFetchResult.Failure("The plugin index returned HTTP $code.")
                }
                if (connection.url.host != MarketIndexCodec.PACKAGE_HOST) {
                    return MarketFetchResult.Failure(
                        "The plugin index redirected somewhere unexpected.",
                    )
                }
                val bytes = connection.inputStream.use {
                    readCapped(it, MarketIndexCodec.MAX_INDEX_BYTES)
                } ?: return MarketFetchResult.Failure(
                    "The plugin index is larger than ${MarketIndexCodec.MAX_INDEX_BYTES} bytes.",
                )
                val document = MarketIndexCodec.decode(bytes.toString(Charsets.UTF_8))
                    ?: return MarketFetchResult.Failure(
                        "The plugin index could not be read. It may have changed format.",
                    )
                MarketFetchResult.Success(document)
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            MarketFetchResult.Failure(networkMessage(error))
        }
    }

    /** Reads the snapshot under `assets/market/`, decoding it through the same codec as the live file. */
    private fun bundledIndex(): MarketFetchResult.Success? = runCatching {
        val bytes = context.assets.open(BUNDLED_INDEX).use { it.readBytes() }
        val document = if (bytes.size <= MarketIndexCodec.MAX_INDEX_BYTES) {
            MarketIndexCodec.decode(bytes.toString(Charsets.UTF_8))
        } else {
            null
        }
        document?.let { MarketFetchResult.Success(it, fromBundledAsset = true) }
    }.getOrNull()

    suspend fun download(entry: MarketEntry): MarketDownloadResult = withContext(io) {
        if (!MarketIndexCodec.isAllowedPackageUrl(entry.downloadUrl)) {
            return@withContext MarketDownloadResult.Failure(
                "This entry does not point at the curated package host.",
            )
        }
        try {
            val connection = open(entry.downloadUrl)
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return@withContext MarketDownloadResult.Failure("The download returned HTTP $code.")
                }
                if (connection.url.host != MarketIndexCodec.PACKAGE_HOST) {
                    return@withContext MarketDownloadResult.Failure(
                        "The download redirected somewhere unexpected.",
                    )
                }
                val declared = connection.contentLengthLong
                if (declared > PluginPackageLoader.MAX_PACKAGE_BYTES) {
                    return@withContext MarketDownloadResult.Failure(
                        "The package is larger than GameCore will install.",
                    )
                }
                val bytes = connection.inputStream.use {
                    readCapped(it, PluginPackageLoader.MAX_PACKAGE_BYTES)
                } ?: return@withContext MarketDownloadResult.Failure(
                    "The package is larger than GameCore will install.",
                )
                MarketDownloadResult.Success(bytes = bytes, sha256 = sha256Hex(bytes))
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            MarketDownloadResult.Failure(networkMessage(error))
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private fun networkMessage(error: Exception): String = when (error) {
        is java.net.UnknownHostException -> "No connection to the plugin index."
        is java.net.SocketTimeoutException -> "The plugin index took too long to answer."
        is javax.net.ssl.SSLException -> "The secure connection to the plugin index failed."
        else -> "The plugin index could not be reached."
    }

    private companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/Dreamucxe/GameCore/main/market/index.json"

        /** The same document, baked in so a failed network read still has a catalog to show. */
        const val BUNDLED_INDEX = "market/index.json"

        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val USER_AGENT = "GameCore-Android"
    }
}
