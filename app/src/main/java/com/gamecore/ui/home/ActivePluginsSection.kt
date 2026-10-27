package com.gamecore.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSection
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val ACTIVE_PLUGINS_GRACE_MILLIS = 5_000L

/**
 * The plugins that are running right now, shown at the top of Home.
 *
 * This is a status strip, not a second plugin manager. It reads the registry's own [InstalledPlugin]
 * list and applies the same active rule the Plugins screen uses: the global kill switch is off, the
 * plugin is enabled, it is not suspended or quarantined, and its manifest is runnable on this host.
 * The permission gate still decides what each plugin may actually do; this card only answers "which
 * plugins are on?".
 *
 * The strip is deliberately host-drawn. A plugin name is a manifest string the registry has already
 * sanitised, and the section labels come from the manifest's declared sections. Nothing a plugin
 * authored is rendered here, and the chips only navigate to the manager.
 */
@HiltViewModel
class ActivePluginsSectionViewModel @Inject constructor(
    registry: PluginRegistry,
) : ViewModel() {

    data class ActivePlugin(
        val id: String,
        val name: String,
        val sections: String,
    )

    data class UiState(
        val isLoaded: Boolean = false,
        val killSwitchOn: Boolean = false,
        val plugins: List<ActivePlugin> = emptyList(),
    ) {
        /** True when there is something honest to say at the top of Home. */
        val shouldShow: Boolean get() = isLoaded && (killSwitchOn || plugins.isNotEmpty())

        val subtitle: String
            get() = when {
                killSwitchOn -> "Paused by the global switch"
                plugins.size == 1 -> "1 running"
                else -> "${plugins.size} running"
            }
    }

    val state: StateFlow<UiState> = combine(
        registry.installed,
        registry.killSwitchOn,
        registry.isLoaded,
    ) { installed, killSwitchOn, isLoaded ->
        UiState(
            isLoaded = isLoaded,
            killSwitchOn = killSwitchOn,
            plugins = if (killSwitchOn) emptyList() else installed.filter { it.isActive }.map { it.toActivePlugin() },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ACTIVE_PLUGINS_GRACE_MILLIS),
        initialValue = UiState(),
    )

    private fun InstalledPlugin.toActivePlugin(): ActivePlugin = ActivePlugin(
        id = id,
        name = name,
        sections = manifest.declares
            .mapNotNull { it.homeLabel() }
            .distinct()
            .joinToString(" · "),
    )

    private fun PluginSection.homeLabel(): String? = when (this) {
        PluginSection.THEME -> "Theme"
        PluginSection.PILL -> "Overlay"
        PluginSection.SCREENS -> "Screens"
        PluginSection.RULES -> "Automation"
        PluginSection.WIDGETS -> "Widgets"
        PluginSection.SETTINGS -> "Settings"
        // The icon is chrome, not a running surface; the rest are not sections this build ships.
        PluginSection.ICON -> null
        else -> null
    }
}

/**
 * A compact, horizontally scrolling strip of the active plugin names.
 *
 * It is emitted only when [ActivePluginsSectionViewModel.UiState.shouldShow] is true, so a device
 * with no active plugins does not get an empty card at the top of Home. The kill-switch state is
 * the one exception: it is shown even with no active plugins, because "nothing is running" is the
 * fact the user needs when they have just turned everything off.
 */
@Composable
fun ActivePluginsSection(
    state: ActivePluginsSectionViewModel.UiState,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.shouldShow) return

    SectionCard(
        title = "Active plugins",
        subtitle = state.subtitle,
        icon = Icons.Filled.Extension,
        action = {
            TextButton(onClick = onManage) { Text("Manage") }
        },
        modifier = modifier,
    ) {
        when {
            state.killSwitchOn -> NoteBanner(
                text = "The global plugin switch is on, so no plugin is running. Your individual " +
                    "plugin switches are unchanged.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                action = {
                    TextButton(onClick = onManage) { Text("Open") }
                },
            )
            state.plugins.isEmpty() -> Text(
                text = "No plugins are running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.plugins.forEach { plugin ->
                    ActivePluginChip(plugin = plugin, onClick = onManage)
                }
            }
        }
    }
}

@Composable
private fun ActivePluginChip(
    plugin: ActivePluginsSectionViewModel.ActivePlugin,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.width(190.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.sections.isNotEmpty()) {
                    Text(
                        text = plugin.sections,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
