package com.gamecore.data.market

import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.market.MarketEntry
import com.gamecore.core.plugin.market.MarketIntegrity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The index as the screen sees it: entries, a load state, and whether the last read was partial. */
data class MarketCatalogState(
    val entries: List<MarketEntry> = emptyList(),
    /**
     * True before the first read finishes. The discover screen shows a progress indicator rather than
     * its empty state, so a slow network is not reported as "the index could not be read".
     */
    val isLoading: Boolean = true,
    val error: String? = null,
    val skippedEntries: Int = 0,
    val lastLoadedAtMillis: Long = 0L,
)

/** What one download-and-install attempt did. */
sealed interface MarketInstallOutcome {
    data class Installed(
        val pluginName: String,
        val wasUpdate: Boolean,
    ) : MarketInstallOutcome

    data class Refused(val message: String) : MarketInstallOutcome
}

/**
 * The discover screen's data source.
 *
 * It composes two authorities without blending them: [MarketClient] is transport and returns bytes plus
 * a digest, the index pins the digest, and [PluginRegistry] is the only thing allowed to write a plugin.
 * An index entry that is unreadable, a package whose digest does not match, and a package the loader
 * refuses are three different failures and stay three different sentences on the screen.
 *
 * The singleton keeps the last successful index for the life of the process. A refresh failure after a
 * successful read therefore leaves the list usable and adds an error line, rather than replacing a
 * catalog with an empty screen.
 */
@Singleton
class MarketRepository @Inject constructor(
    private val client: MarketClient,
    private val registry: PluginRegistry,
) {

    private val _state = MutableStateFlow(MarketCatalogState())
    val state: StateFlow<MarketCatalogState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.update { it.copy(isLoading = true) }
        when (val fetched = client.fetchIndex()) {
            is MarketFetchResult.Failure -> _state.update {
                it.copy(isLoading = false, error = fetched.message)
            }

            is MarketFetchResult.Success -> _state.update {
                it.copy(
                    entries = fetched.document.entries,
                    isLoading = false,
                    error = when {
                        fetched.fromBundledAsset -> {
                            "The curated index could not be reached. Showing the copy bundled with " +
                                "this build; refresh when you are online for the latest entries."
                        }

                        fetched.document.skippedEntries > 0 -> {
                            "${fetched.document.skippedEntries} index " +
                                (if (fetched.document.skippedEntries == 1) {
                                    "entry was"
                                } else {
                                    "entries were"
                                }) + " unreadable and are not shown."
                        }

                        else -> null
                    },
                    skippedEntries = fetched.document.skippedEntries,
                    lastLoadedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Downloads exactly [entry]'s pinned bytes, checks the digest, and installs them.
     *
     * The hash is checked before the loader sees the bytes, so a mismatch is reported as a download
     * problem rather than as an invalid package: those are different failures and the user can act on
     * only one of them. The loader is still the authority on everything else — compatibility, manifest,
     * sections — and its refusal is relayed in its own terms.
     */
    suspend fun install(entry: MarketEntry): MarketInstallOutcome {
        val installedBefore = registry.installed.value.firstOrNull { it.id == entry.id }
        // A bundled copy being replaced is an update too: the asset was the installed version until
        // this package arrived. Compare versions rather than origin so the message follows the fact.
        val wasUpdate = installedBefore != null && installedBefore.manifest.version < entry.versionCode

        return when (val download = client.download(entry)) {
            is MarketDownloadResult.Failure -> MarketInstallOutcome.Refused(download.message)

            is MarketDownloadResult.Success -> {
                if (!MarketIntegrity.matches(entry, download.sha256)) {
                    MarketInstallOutcome.Refused(
                        "The downloaded package did not match the hash in the index. Nothing was installed.",
                    )
                } else {
                    when (val outcome = registry.install(download.bytes, PluginOrigin.SIDELOADED)) {
                        is InstallOutcome.Installed -> MarketInstallOutcome.Installed(
                            pluginName = outcome.plugin.name,
                            wasUpdate = wasUpdate,
                        )

                        is InstallOutcome.Rejected -> MarketInstallOutcome.Refused(
                            outcome.detail?.takeIf { it.isNotBlank() }
                                ?: "The package was refused by the plugin loader.",
                        )

                        is InstallOutcome.Incompatible -> MarketInstallOutcome.Refused(
                            outcome.compatibility.reason,
                        )

                        is InstallOutcome.StorageFailed -> MarketInstallOutcome.Refused(
                            outcome.detail.takeIf { it.isNotBlank() }
                                ?: "The package could not be stored.",
                        )
                    }
                }
            }
        }
    }
}
