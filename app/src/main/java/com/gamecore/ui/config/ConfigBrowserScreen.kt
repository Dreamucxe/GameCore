package com.gamecore.ui.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.gamecore.ui.theme.Spacing

/**
 * One row in the config browser: a file to look at or a folder to descend into.
 *
 * A flat, self-describing value with no handle back to a store, so the same row can stand for a real file
 * on disk or a literal in a preview, and the screen that draws it never has to know which. The caller
 * resolves [relativePath] to whatever it points at; the browser only shows it and reports the tap. There is
 * deliberately no "editable" flag: whether a file can be written is the editor's verdict when it opens the
 * bytes (a binary or over-cap file opens view-only there), and a folder is descended into, not edited — so
 * the browser never claims a read/write verdict a later screen could contradict.
 */
data class ConfigBrowserItem(
    val relativePath: String,
    val displayName: String,
    val sizeLabel: String,
)

/**
 * A read-through browser over a list of configuration files.
 *
 * Deliberately stateless: it holds no store, no ViewModel and no navigation of its own — it is handed a
 * [title], the [items] to show and the two things a user can do (open one, or go [onBack]), and draws
 * exactly that. Loading and empty are first-class states rather than a blank list, because a browser that
 * shows nothing while it is still reading looks the same as one that is broken.
 *
 * Every row is tappable and drawn the same way: [onOpen] fires for each, and the caller decides whether the
 * tap descends into a folder or opens a file to view or edit. The browser draws no "read only" verdict of
 * its own — see [ConfigBrowserItem] for why that decision belongs to the editor, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBrowserScreen(
    title: String,
    items: List<ConfigBrowserItem>,
    isLoading: Boolean,
    emptyMessage: String,
    onOpen: (ConfigBrowserItem) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            isLoading -> CenteredBox(modifier = Modifier.padding(innerPadding)) {
                CircularProgressIndicator()
            }

            items.isEmpty() -> CenteredBox(modifier = Modifier.padding(innerPadding)) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.xl),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = Spacing.xs),
            ) {
                items(items = items, key = { it.relativePath }) { item ->
                    ConfigBrowserRow(item = item, onOpen = onOpen)
                }
            }
        }
    }
}

/**
 * One tappable file row: its name, its path under it, and its size on the right.
 *
 * Built as a clickable [Surface] with a transparent fill — the shape the app's other navigating rows use —
 * so the whole row is a single focus target and a single ripple. Every row reads the same: the tap lands and
 * the caller decides what "open" means, so there is no dimmed or "read only" variant to draw here.
 */
@Composable
private fun ConfigBrowserRow(
    item: ConfigBrowserItem,
    onOpen: (ConfigBrowserItem) -> Unit,
) {
    Surface(
        onClick = { onOpen(item) },
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.relativePath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Text(
                text = item.sizeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A box that fills its space and centres its one child — the loading spinner and the empty note share it. */
@Composable
private fun CenteredBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
