package com.gamecore.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.core.whatsnew.FeatureNote
import com.gamecore.core.whatsnew.ReleaseNote
import com.gamecore.core.whatsnew.WhatsNewRegistry
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard

/**
 * Every release, and what it added — the browsable half of "What's New" (the other is the one-time card).
 *
 * Like [com.gamecore.ui.developer.DeveloperScreen], there is nothing to load and so no ViewModel: the
 * changelog is a compiled-in Kotlin object, not a database or a network call, so the screen reads
 * [WhatsNewRegistry] straight and renders it. Sorted by `versionCode` descending here rather than
 * trusting the registry's hand-kept order, so a release appended out of order still lands newest-first.
 *
 * All copy comes from the registry — no `strings.xml` — which is the single place a release's entry is
 * written and the single place the two surfaces read.
 */
@Composable
fun WhatsNewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val releases = WhatsNewRegistry.releases.sortedByDescending { it.versionCode }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "What's New",
                subtitle = "Every release, and what it added",
                onBack = onBack,
            )
        }

        items(releases, key = { it.versionCode }) { release ->
            ReleaseCard(release = release, modifier = padded)
        }
    }
}

/** One release: its version and date as the heading, then every feature it introduced. */
@Composable
private fun ReleaseCard(release: ReleaseNote, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Version ${release.versionName}",
        subtitle = release.date.takeIf { it.isNotBlank() },
        modifier = modifier,
    ) {
        release.features.forEachIndexed { index, feature ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            FeatureRow(feature)
        }
    }
}

/** A feature's short title over the plain sentence describing it. */
@Composable
private fun FeatureRow(feature: FeatureNote) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = feature.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = feature.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
