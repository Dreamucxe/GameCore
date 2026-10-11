package com.gamecore.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginInstallPlan
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.RiskTier
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * The screen where a user sees exactly what one plugin asks for and grants it item by item.
 *
 * This is a **core safety screen, and a plugin can never restyle it**: the screen where you take a
 * permission away must not be drawn by the thing you are taking it from. Every colour, font and shape
 * below comes from GameCore's own theme even when the plugin on screen is the one currently supplying
 * the app's appearance — because a plugin that could draw this screen could draw a switch that looks
 * off while it is on, grey out its own Remove button, or hide the advanced-access row entirely, and
 * the user would have no way to tell. The one piece of plugin styling here is a *preview* of its
 * theme's colours, rendered as four labelled swatches inside a card, which is a sample rather than a
 * surrender.
 *
 * Everything the plugin says about itself — its name, its developer, its description, its scope names
 * — is **untrusted input rendered as data**. It goes through [TextSanitizer] on the way to the screen
 * and lands in a plain `Text`. Nothing here builds an `AnnotatedString` from plugin input, turns a
 * plugin string into a link, or hands one to a markup renderer, because any of those would let the
 * manifest decide how its own consent prompt reads. The *words beside each switch* come from
 * GameCore's own [PluginPermission] enum — [PluginPermission.title], [PluginPermission.rationale] and
 * [PluginPermission.whatItTouches] — so a plugin cannot describe the permission it is requesting.
 *
 * Four rules the layout enforces rather than suggests:
 *
 *  1. **Granting is per permission and never pre-checked.** There is no "allow all", and a freshly
 *     installed plugin arrives here with an empty grant. A scoped permission grants no scopes with
 *     it; the user opens each aperture separately.
 *  2. **Taking something away is never blocked.** The permission switches stay live even for a plugin
 *     this build cannot run, because a revoke must not depend on the plugin being in a good state.
 *     Only the *Enable* switch is gated (§32: it is off with the reason beside it, not dead).
 *  3. **Nothing is granted by being looked at.** Every switch edits an unsaved draft; the stored
 *     grant only changes on Save, and the screen reports what the registry gives back afterwards
 *     rather than what was tapped (§24).
 *  4. **Capabilities the platform refuses are shown as refused**, not omitted. A user who wonders
 *     whether a plugin can reach the internet gets an answer on this screen instead of an absence.
 *
 * §11: no control here is colour-only — every switch has a text title a screen reader announces, each
 * risk tier is a worded chip rather than a red dot, and the theme swatches carry their own captions.
 */
@Composable
fun PluginDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenScreen: (pluginId: String, screenId: String) -> Unit = { _, _ -> },
    onOpenAutomationHistory: (pluginId: String) -> Unit = {},
    viewModel: PluginDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Pulled into locals once: `state` is a delegated property, so the compiler cannot narrow
    // `state.plugin` to non-null inside the item lambdas below, and these four travel together —
    // the ViewModel writes the plugin, its plan and both grants from the same emission.
    val plugin = state.plugin
    val plan = state.plan
    val draft = state.draft
    val saved = state.saved

    if (plugin == null || plan == null || draft == null || saved == null) {
        PluginDetailUnavailable(
            notFound = state.notFound,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    // Falls back to the id when the sanitiser leaves nothing behind, the same way [rowFor] does in the
    // manager and for a sharper reason: a package whose name is entirely format characters would
    // otherwise give this screen a blank header and a removal dialog reading "Remove ?" — a
    // confirmation that does not name what it is about is not a confirmation.
    val name = TextSanitizer.sanitizeName(plugin.name).ifBlank { plugin.id }
    val unsaved = state.hasUnsavedChanges
    // Computed once and handed to both cards that show it, so the running state and the thing about to
    // be stored cannot disagree about how many permissions that is.
    val summary = grantSummary(plan, draft)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = name,
                subtitle = "What this plugin asks for, and what you have allowed",
                onBack = onBack,
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        // The compatibility verdict goes above everything the user could act on, because it is the one
        // state nothing on this screen can clear and acting on the rest first would be wasted effort.
        if (!plan.canInstall) {
            item {
                NoteBanner(
                    text = plan.compatibility.reason,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Warning,
                    modifier = padded,
                )
            }
        }

        if (state.suspended) {
            item {
                NoteBanner(
                    text = suspensionText(state.suspensionDetail),
                    tone = Tone.Danger,
                    icon = Icons.Filled.Warning,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::clearSuspension) { Text("Try again") } },
                )
            }
        }

        item { IdentityCard(plugin = plugin, plan = plan, modifier = padded) }

        item {
            RunCard(
                enabled = state.enabled,
                canTurnOn = state.canTurnOn,
                inForce = canEnable(plan, draft, saved),
                suspended = state.suspended,
                summary = summary,
                unsaved = unsaved,
                onSetEnabled = viewModel::setEnabled,
                modifier = padded,
            )
        }

        item {
            PermissionsCard(
                manifest = plugin.manifest,
                plan = plan,
                advancedAccess = draft.advancedAccess,
                grantedScopesOf = { permission -> draft.scopes(permission) },
                isGranted = { permission -> draft.has(permission) },
                onTogglePermission = viewModel::togglePermission,
                onToggleScope = viewModel::toggleScope,
                modifier = padded,
            )
        }

        if (plan.requestsAdvancedAccess) {
            item {
                AdvancedAccessCard(
                    advancedAccess = draft.advancedAccess,
                    inert = state.requiresAdvancedAccess,
                    onSetAdvancedAccess = viewModel::setAdvancedAccess,
                    modifier = padded,
                )
            }
        }

        // Save sits directly under the switches it would store, and only exists while there is
        // something to store — a permanently lit Save button on a consent screen invites the user to
        // believe a tap is needed before their revoke counts.
        if (unsaved) {
            item {
                UnsavedCard(
                    summary = summary,
                    onSave = viewModel::saveGrant,
                    onRevert = viewModel::revertGrant,
                    modifier = padded,
                )
            }
        }

        item { RefusedCapabilitiesCard(modifier = padded) }

        // The card is not gated purely on the manifest's claim. A plugin that is *currently supplying
        // the app's theme* gets it rendered even if the parsed sections came back without one, because
        // this card carries the only way back to GameCore's own appearance — and a manifest that lies
        // about carrying a theme is exactly the case where the user most needs that button.
        if (PluginSection.THEME in plan.declaredSections || state.isThemeSource) {
            item {
                ThemeCard(
                    theme = state.themePreview,
                    isThemeSource = state.isThemeSource,
                    onUseThisTheme = viewModel::useThisTheme,
                    onUseDefaultTheme = viewModel::useDefaultTheme,
                    modifier = padded,
                )
            }
        }

        if (state.ruleCount > 0) {
            item { RulesCard(ruleCount = state.ruleCount, modifier = padded) }
        }

        // Guarded on the parsed schema, not the manifest's claim: a plugin that declares the section but
        // ships a settings.json that no longer parses has no schema here, and an empty card would invite
        // the user to hunt for controls that cannot exist. The controls are disabled over the schema's
        // own defaults unless Storage is in the *saved* grant — the permission every settings write is
        // funnelled through — so a tap can never look like it stuck when the gate would refuse it.
        state.settingsSchema?.let { schema ->
            item {
                SettingsCard(
                    schema = schema,
                    values = state.settingValues,
                    editable = state.settingsEditable,
                    onSet = viewModel::onSetSetting,
                    modifier = padded,
                )
            }
        }

        // Shown only when the plugin could actually open a screen: it declares at least one, it is
        // active (enabled and not suspended), and change_ui is in the *saved* grant — the same live
        // conditions resolvePluginScreen applies at the route, so the Open affordance never promises a
        // screen the destination would then refuse to draw. `plugin` and `saved` are the non-null
        // locals the early guard established.
        if (state.screens.isNotEmpty() &&
            state.enabled &&
            !state.suspended &&
            saved.has(PluginPermission.CHANGE_UI)
        ) {
            item {
                ScreensCard(
                    screens = state.screens,
                    onOpenScreen = { screenId -> onOpenScreen(plugin.id, screenId) },
                    modifier = padded,
                )
            }
        }

        // The automation-history entry point appears only while this plugin holds the automation-history
        // read scope in its live granted set — the same gate the registry applies when the screen then
        // asks for the records, so the row never offers a page the host would answer with "not allowed",
        // and it drops the instant the plugin is disabled, suspended or caught by the kill switch. The
        // screen it opens is GameCore's own: the plugin is handed none of the entries, it only asked that
        // the user be shown them.
        if (state.hasAutomationHistoryAccess) {
            item {
                AutomationHistoryCard(
                    onOpen = { onOpenAutomationHistory(plugin.id) },
                    modifier = padded,
                )
            }
        }

        item { RemovalCard(plugin = plugin, onAskRemove = viewModel::askRemove, modifier = padded) }

        item {
            NoteBanner(
                text = "GameCore draws this screen itself. A plugin cannot change how it looks, which " +
                    "switch it shows, or what any of them says.",
                tone = Tone.Muted,
                icon = Icons.Filled.Shield,
                modifier = padded,
            )
        }
    }

    if (state.pendingRemoval) {
        ConfirmDialog(
            title = "Remove $name?",
            message = "Its files, the permissions you granted it, anything it stored and its theme " +
                "selection are all deleted. Sessions and games it only read are untouched.",
            confirmLabel = "Remove",
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove,
        )
    }
}

/**
 * The screen before the plugin is known, and the screen after it is gone.
 *
 * Two states rather than one spinner, because "still reading from disk" and "this plugin is not
 * installed" call for opposite things from the user — waiting, or leaving. A single indeterminate
 * spinner for both is the version of this screen that hangs forever on a stale back-stack entry.
 */
@Composable
private fun PluginDetailUnavailable(
    notFound: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Plugin", onBack = onBack)
        if (notFound) {
            EmptyState(
                icon = Icons.Filled.Extension,
                title = "This plugin is not installed",
                message = "It may have been removed while this screen was open. Nothing it was granted " +
                    "is still in force.",
                actionLabel = "Back",
                onAction = onBack,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Who wrote this, which version it is, what it claims to carry, and the digest of the exact bytes.
 *
 * The digest is here rather than hidden behind a "details" row because it is the only field on the
 * screen a plugin cannot influence — the loader computed it over the package it actually read, so it
 * is what a user compares against a hash they were given elsewhere. Monospaced and grouped in
 * sixteens so a human can check it a block at a time instead of character by character.
 */
@Composable
private fun IdentityCard(
    plugin: InstalledPlugin,
    plan: PluginInstallPlan,
    modifier: Modifier = Modifier,
) {
    val description = TextSanitizer.sanitizeMultiline(plan.description)
    SectionCard(
        title = "About this plugin",
        icon = Icons.Filled.Extension,
        modifier = modifier,
    ) {
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        KeyValueRow(label = "Developer", value = TextSanitizer.sanitizeName(plan.developer))
        KeyValueRow(label = "Version", value = TextSanitizer.sanitizeName(plan.versionName))
        KeyValueRow(label = "Where it came from", value = plugin.origin.label)
        KeyValueRow(label = "What it carries", value = sectionsText(plan.declaredSections))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Package digest",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = plan.sha256.chunked(16).joinToString(" "),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "This is the digest GameCore computed over the package it read from disk, not a " +
                "value the plugin stated about itself. Compare it against one you were given " +
                "elsewhere — a plugin cannot influence it, and a package whose bytes changed has a " +
                "different digest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one switch that decides whether the plugin runs at all, with the grant total beside it.
 *
 * The switch is disabled only while the plugin is off and one of the two states in which turning it on
 * would be a lie applies — a build that cannot run the package, and a granted advanced permission with
 * the advanced opt-in off — and each of those already has its own banner or note saying so, which is
 * what keeps a disabled switch from reading as a bug (§32).
 *
 * [canTurnOn] gates one direction only, which is why it is read here as `canTurnOn || enabled`. Both of
 * its refusals are reasons a plugin must not *start*, and [PluginDetailViewModel.setEnabled] refuses
 * neither of them on the way off — it stops the plugin before it looks at anything else. Gating both
 * directions would leave a plugin that is already running with no stop switch on its own screen, and
 * the way back to one would be to undo the draft edit that disabled it: the opposite of what a user
 * reaching for this switch wants. Turning something off must not depend on the state it is in.
 *
 * An unsaved edit does *not* disable it. Enabling then stores the grant first, and the description
 * says so before the tap rather than after it.
 *
 * [inForce] is the §24 clause of this card: a plugin can be switched on and still not be running what
 * the screen shows, and the chip says "Not in force" rather than "On" when that is the case. A status
 * chip that reported the switch position instead of the stored state is exactly the kind of honest-
 * looking lie a permissions screen must not tell.
 */
@Composable
private fun RunCard(
    enabled: Boolean,
    canTurnOn: Boolean,
    inForce: Boolean,
    suspended: Boolean,
    summary: String,
    unsaved: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, tone) = when {
        suspended -> "Stopped" to Tone.Danger
        enabled && !inForce -> "Not in force" to Tone.Warning
        enabled -> "On" to Tone.Good
        else -> "Off" to Tone.Muted
    }
    SectionCard(
        title = "Running",
        subtitle = summary,
        icon = Icons.Filled.Shield,
        modifier = modifier,
        action = {
            StatusChip(
                text = label,
                tone = tone,
                icon = if (tone == Tone.Danger || tone == Tone.Warning) Icons.Filled.Warning else null,
            )
        },
    ) {
        SwitchRow(
            title = "Let this plugin run",
            checked = enabled,
            onCheckedChange = onSetEnabled,
            description = if (unsaved) {
                "Your unsaved permission changes are stored first, so it starts with exactly what is " +
                    "shown below."
            } else {
                "It only ever acts through the permissions below, and only while this is on."
            },
            enabled = canTurnOn,
        )
    }
}

/**
 * Every permission the manifest asked for, grouped worst-first, one switch each.
 *
 * Grouped by [RiskTier] in the plan's own HIGH-first order for a specific reason: a plugin that asked
 * for device actions alongside six harmless reads must not be able to bury the one that matters under
 * the ones that do not. The tier heading is a word ([RiskTier.label]), not a colour, so the grouping
 * survives a colour-blind user and a greyscale screenshot.
 *
 * The switches are never disabled, not even for a plugin this build cannot run. Revoking is the action
 * this screen exists for, and making it conditional on the plugin being healthy would mean the one
 * moment a user most wants to withdraw consent is the moment the screen refuses.
 */
@Composable
private fun PermissionsCard(
    manifest: PluginManifest,
    plan: PluginInstallPlan,
    advancedAccess: Boolean,
    isGranted: (PluginPermission) -> Boolean,
    grantedScopesOf: (PluginPermission) -> Set<String>,
    onTogglePermission: (PluginPermission, Boolean) -> Unit,
    onToggleScope: (PluginPermission, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requested = requestedPermissions(plan)
    SectionCard(
        title = "What it asks for",
        subtitle = "Nothing is granted until you turn it on, one at a time",
        icon = Icons.Filled.Lock,
        modifier = modifier,
    ) {
        if (requested.isEmpty()) {
            Text(
                text = "This plugin requests no permissions. It can be themed and listed, and that is " +
                    "all it can do.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        var firstGroup = true
        plan.permissionsByTier.forEach { (tier, permissions) ->
            if (permissions.isEmpty()) return@forEach
            if (!firstGroup) {
                Spacer(modifier = Modifier.height(10.dp))
                RowDivider()
                Spacer(modifier = Modifier.height(10.dp))
            }
            firstGroup = false
            Text(
                text = tier.label,
                style = MaterialTheme.typography.labelMedium,
                color = tierTone(tier).colour(),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tierMeaning(tier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            permissions.forEach { permission ->
                PermissionRow(
                    permission = permission,
                    requestedScopes = scopesRequestedFor(manifest, permission),
                    granted = isGranted(permission),
                    grantedScopes = grantedScopesOf(permission),
                    advancedAccess = advancedAccess,
                    onToggle = { on -> onTogglePermission(permission, on) },
                    onToggleScope = { scope, on -> onToggleScope(permission, scope, on) },
                )
            }
        }
    }
}

/**
 * One permission: the switch, GameCore's own words for it, and its apertures.
 *
 * [PluginPermission.whatItTouches] is shown under the switch rather than folded into the description,
 * because it is the sentence that bounds the permission — "appearance only", "the Pill only", "never a
 * raw command" — and a user skimming switch titles needs the limit as visible as the capability.
 *
 * Scope switches appear only once the permission itself is granted. An ungranted permission's scopes
 * would be switches that authorise nothing (the gate checks the permission first), so instead the row
 * names what will be on offer, which keeps the information without the dead taps (§32).
 */
@Composable
private fun PermissionRow(
    permission: PluginPermission,
    requestedScopes: Set<String>,
    granted: Boolean,
    grantedScopes: Set<String>,
    advancedAccess: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleScope: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = permission.title,
            checked = granted,
            onCheckedChange = onToggle,
            description = permission.rationale,
        )
        Text(
            text = permission.whatItTouches,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A granted HIGH-tier permission is a real state with no effect while the advanced opt-in is
        // off, and the switch above cannot show the difference. Said on the row itself rather than only
        // in the advanced card, because a user who reads one permission at a time reads this one and
        // stops — and "on" is the answer they would otherwise take away.
        if (permission.tier == RiskTier.HIGH && granted) {
            Spacer(modifier = Modifier.height(4.dp))
            NoteBanner(
                text = if (advancedAccess) {
                    "Granted, and advanced access is on — GameCore carries these out itself, within " +
                        "the range it considers safe."
                } else {
                    "Granted but not in force: advanced access is off below, so every request this " +
                        "makes is refused until you turn it on."
                },
                tone = if (advancedAccess) Tone.Good else Tone.Warning,
                icon = Icons.Filled.Warning,
            )
        }
        if (requestedScopes.isEmpty()) return@Column
        Spacer(modifier = Modifier.height(6.dp))
        if (!granted) {
            Text(
                text = "Asks for: ${scopeNames(requestedScopes)}. Grant the permission above to choose " +
                    "which of them it gets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = "Which parts it may use",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        requestedScopes.sorted().forEach { raw ->
            val scope = DataScope.fromId(raw)
            SwitchRow(
                title = scope?.label ?: TextSanitizer.sanitizeName(raw),
                checked = raw.trim().lowercase() in grantedScopes,
                onCheckedChange = { on -> onToggleScope(raw, on) },
                description = scope?.description,
            )
        }
    }
}

/**
 * The separate opt-in that device-affecting requests need, and the whole truth about what it allows.
 *
 * Separate from the permission on purpose. Granting "change device settings" says the user is willing
 * in principle; this switch is the one that lets the request actually leave GameCore, so a plugin that
 * talked a user through a permission list still has a second, differently-worded consent to get past.
 *
 * The description is deliberately specific about the shape of what the plugin gets, because "advanced
 * access" on its own sounds like a power level rather than a mechanism: **GameCore's broker performs a
 * fixed, reviewed action on the plugin's behalf, clamped to a range this app considers safe, and
 * reverts it when the session ends.** The plugin never receives a shell, a binder or a command string
 * of its own — it names an action from GameCore's list and GameCore decides what that means.
 */
@Composable
private fun AdvancedAccessCard(
    advancedAccess: Boolean,
    inert: Boolean,
    onSetAdvancedAccess: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Advanced access",
        subtitle = "Needed on top of the permission above before any device request is carried out",
        icon = Icons.Filled.Warning,
        modifier = modifier,
    ) {
        SwitchRow(
            title = "Let GameCore carry out its device requests",
            checked = advancedAccess,
            onCheckedChange = onSetAdvancedAccess,
            description = "GameCore performs the change itself, from its own fixed list of actions, " +
                "clamped to a range it considers safe, and puts it back when the session ends. The " +
                "plugin never gets a shell or a command of its own — it asks, GameCore decides.",
        )
        if (inert) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "An advanced permission is granted while this is off, so every request it makes " +
                    "would be refused. Turn this on, or withhold that permission.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * Unsaved changes, with the two ways out of them.
 *
 * Shown as its own card rather than a floating bar so it cannot cover a switch the user is still
 * reading, and worded as a statement of fact: the switches above are not in force yet. §24 — the
 * screen says which of the two grants it is showing instead of letting the user assume.
 */
@Composable
private fun UnsavedCard(
    summary: String,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Not saved yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "The switches above are what you have chosen. The plugin is still checked against " +
                "what was stored before.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The same sentence the Running card carries, repeated here because this is the card the user
        // is looking at while deciding whether to store: "4 of 5" is what they are about to approve,
        // and a count they have to scroll up to check is a count they will not check.
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            Button(onClick = onSave) { Text("Save permissions") }
            OutlinedButton(onClick = onRevert) { Text("Undo changes") }
        }
    }
}

/**
 * The capabilities no plugin can be granted, listed rather than left out.
 *
 * A consent screen that only lists what was requested leaves the most common worry unanswered: can
 * this thing phone home. The answer is structural — a `.gcplugin` is data, with no code, no scripting
 * and no network of its own — and it belongs on the screen where the user is deciding, not in a
 * document they will not read.
 *
 * Each line is filtered through [PluginPermission.isReserved], so this card can only ever name a
 * capability the platform genuinely refuses. If a future build were to make one of these grantable,
 * the line would disappear instead of becoming a false reassurance.
 */
@Composable
private fun RefusedCapabilitiesCard(modifier: Modifier = Modifier) {
    val refused = REFUSED_CAPABILITIES.filter { PluginPermission.isReserved(it.first) }
    if (refused.isEmpty()) return
    SectionCard(
        title = "What it can never ask for",
        subtitle = "Refused for every plugin, with or without your permission",
        icon = Icons.Filled.Block,
        modifier = modifier,
    ) {
        refused.forEachIndexed { index, (_, description) ->
            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(text = "Refused", tone = Tone.Danger, icon = Icons.Filled.Block)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A preview of the plugin's theme, and the always-available way back to GameCore's own.
 *
 * The swatches are the only plugin-chosen colours on the screen, and they are confined to small boxes
 * with their own captions — a preview of what the app would look like, not a demonstration performed
 * on the consent screen itself. Each swatch is captioned because a row of unlabelled colour squares
 * carries nothing to a screen reader and nothing to a user who cannot distinguish two of them (§11).
 *
 * "Use GameCore's own theme" is offered unconditionally and never refuses. It is the way out of a
 * theme that has made the app hard to read, so it must not depend on the offending plugin still being
 * enabled, still parsing, or still granted anything.
 */
@Composable
private fun ThemeCard(
    theme: PluginTheme?,
    isThemeSource: Boolean,
    onUseThisTheme: () -> Unit,
    onUseDefaultTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Appearance",
        subtitle = "This plugin carries a theme for GameCore's ordinary screens",
        icon = Icons.Filled.Palette,
        modifier = modifier,
        action = {
            if (isThemeSource) {
                StatusChip(text = "In use", tone = Tone.Good, icon = Icons.Filled.Palette)
            }
        },
    ) {
        if (theme == null) {
            Text(
                text = "Its theme could not be read back from the package, so there is nothing to " +
                    "preview.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KeyValueRow(label = "Theme", value = TextSanitizer.sanitizeName(theme.name))
            KeyValueRow(label = "Mode", value = if (theme.dark) "Dark" else "Light")
            KeyValueRow(
                label = "Font",
                value = theme.font.id.replaceFirstChar { it.uppercaseChar() },
            )
            KeyValueRow(label = "Corner radius", value = "${theme.cornerRadiusDp}dp")
            KeyValueRow(label = "Text and spacing", value = "${(theme.uiScale * 100).toInt()}%")
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                ThemeSwatch(label = "Primary", colour = Color(theme.primary))
                ThemeSwatch(label = "Accent", colour = Color(theme.accent))
                ThemeSwatch(label = "Background", colour = Color(theme.background))
                ThemeSwatch(label = "Surface", colour = Color(theme.surface))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Safety screens — this one, permissions, and anything that can stop a plugin — keep " +
                "GameCore's own styling whatever a theme says.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            if (!isThemeSource) {
                Button(onClick = onUseThisTheme) { Text("Use this theme") }
            }
            OutlinedButton(onClick = onUseDefaultTheme) { Text("Use GameCore's theme") }
        }
    }
}

/** One captioned colour sample. The caption is what makes the row mean anything without colour vision. */
@Composable
private fun ThemeSwatch(label: String, colour: Color) {
    Column(
        modifier = Modifier.padding(end = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(colour),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How many automation rules the package carries.
 *
 * A count rather than the rules themselves, because a rule is a trigger plus conditions plus
 * allowlisted actions and reading them is a job for the automation screen that can show one at a time.
 * The count is here because it tells the user that granting "run automations" is granting a known
 * quantity, not an open-ended one.
 */
/**
 * The screens this plugin adds, each a row that opens a real navigation destination.
 *
 * Only ever shown while the plugin is active and change_ui is granted (the caller gates it), so this
 * card does not re-check — it lists what the registry parsed and lets the user open one. Each screen is
 * drawn by GameCore itself from declared data: the note says so, because a page that looked like the
 * plugin's own could otherwise be mistaken for code the plugin is running.
 */
@Composable
private fun ScreensCard(
    screens: List<PluginScreen>,
    onOpenScreen: (screenId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Screens",
        subtitle = "Pages this plugin adds to GameCore",
        icon = Icons.Filled.Dashboard,
        modifier = modifier,
    ) {
        screens.forEach { screen ->
            NavRow(
                title = screen.title,
                onClick = { onOpenScreen(screen.id) },
                icon = pluginScreenIcon(screen.icon),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "GameCore draws these pages itself from data the plugin declared. They are " +
                "read-only — opening one cannot run plugin code or change anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The way in to GameCore's own record of automations that have run on the device.
 *
 * Shown only while this plugin holds the automation-history read scope (the caller gates it), and it
 * opens a screen GameCore draws itself: the plugin receives none of the records, it asked only that the
 * user be shown them. The note says as much, because a row reading "automation history" beside a
 * plugin's name could otherwise be taken for the plugin's own log rather than the device's — and this
 * plugin can neither read nor change what that screen shows.
 */
@Composable
private fun AutomationHistoryCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Automation history",
        subtitle = "GameCore's record of automations that have run on your device",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        NavRow(
            title = "View automation history",
            onClick = onOpen,
            icon = Icons.Filled.History,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "GameCore draws this screen itself from its own records. This plugin is allowed to " +
                "show it to you — it never receives the entries, and it cannot change or clear them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RulesCard(ruleCount: Int, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Automations",
        icon = Icons.Filled.Checklist,
        modifier = modifier,
    ) {
        KeyValueRow(
            label = "Rules in this package",
            value = ruleCount.toString(),
        )
        Text(
            text = "Each rule reacts to an event GameCore defines and may only name one of GameCore's " +
                "own broker actions — the same fixed, reviewed list the advanced permission uses. " +
                "There is no script, no expression language and no way for a rule to invent an action. " +
                "They run only while \"Run automations\" is granted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Deleting the plugin, or the reason there is nothing to delete.
 *
 * A bundled plugin gets a sentence instead of a greyed button: its bytes are inside the APK and
 * covered by the app's own signature, so "Remove" has nothing to act on, and a disabled control with
 * no explanation beside it is indistinguishable from a broken one (§32).
 */
@Composable
private fun RemovalCard(
    plugin: InstalledPlugin,
    onAskRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Remove",
        icon = Icons.Filled.Delete,
        modifier = modifier,
    ) {
        if (!plugin.canBeRemoved) {
            Text(
                text = "This plugin ships inside GameCore, so there is no file to delete. Turning it " +
                    "off above stops it completely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            text = "Deletes its files, the permissions you granted it, anything it stored and its theme " +
                "selection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            TextButton(onClick = onAskRemove) {
                Text(text = "Remove this plugin", color = Tone.Danger.colour())
            }
        }
    }
}

/**
 * The capabilities this build refuses to every plugin, each keyed by the reserved manifest id that
 * proves it is refused.
 *
 * Kept as ids paired with plain sentences rather than as a hard-coded list of claims, so
 * [RefusedCapabilitiesCard] can check each line against [PluginPermission.isReserved] before showing
 * it. The two synonym ids the loader also reserves (`internet`, `launch_app`) are deliberately not
 * separate lines: they name the same two capabilities, and listing a capability twice makes the list
 * read as longer rather than clearer.
 */
private val REFUSED_CAPABILITIES = listOf(
    "network" to "Reach the internet, on its own or through GameCore.",
    "external_app" to "Open, control or read another app on your device.",
    "intent" to "Send an Android request of its own making to anything outside GameCore.",
)

/** Worded risk, coloured consistently. The word carries the meaning; the colour only reinforces it. */
private fun tierTone(tier: RiskTier): Tone = when (tier) {
    RiskTier.LOW -> Tone.Good
    RiskTier.MEDIUM -> Tone.Warning
    RiskTier.HIGH -> Tone.Danger
}

/**
 * What a risk tier means, in plain words, under its heading.
 *
 * [RiskTier.label] is a name, not an explanation: "Advanced" and "Medium risk" tell a user how much to
 * care without telling them what is actually being decided. The group heading is where that has to be
 * said, because it is the only point on the screen where several permissions are covered by one
 * sentence — a second explanation on every row would be forty words of repetition the user learns to
 * skip, which is how the one sentence that matters stops being read.
 */
private fun tierMeaning(tier: RiskTier): String = when (tier) {
    RiskTier.LOW ->
        "Only GameCore's own screens and records. Nothing leaves the app, and none of it can change " +
            "anything outside it."
    RiskTier.MEDIUM ->
        "A bounded window or a change inside GameCore, limited to what the plugin declares. Nothing " +
            "here reaches another app."
    RiskTier.HIGH ->
        "The only requests that change something outside GameCore. Each one needs advanced access " +
            "below as well, and GameCore carries it out itself."
}

/**
 * The scopes the manifest asked for under one permission.
 *
 * Read from the manifest rather than from the grant, because this is the list of apertures the plugin
 * *wants* and the grant only says which of them are open. A plugin that declared a scope twice is
 * de-duplicated by the set it is already stored in.
 */
private fun scopesRequestedFor(manifest: PluginManifest, permission: PluginPermission): Set<String> =
    manifest.permissions.firstOrNull { it.permission == permission }?.scopes.orEmpty()

/**
 * Scope ids as readable names.
 *
 * A known [DataScope] supplies GameCore's own label. Anything else is a settings-area string the
 * plugin wrote, so it is sanitised before it is shown and never rendered as anything but text.
 */
private fun scopeNames(scopes: Set<String>): String =
    scopes.sorted().joinToString(", ") { raw ->
        DataScope.fromId(raw)?.label ?: TextSanitizer.sanitizeName(raw)
    }

/** The declared sections as a sentence, in the enum's own order so two plugins read the same way. */
private fun sectionsText(sections: Set<PluginSection>): String {
    if (sections.isEmpty()) return "Nothing yet"
    return PluginSection.entries
        .filter { it in sections }
        .joinToString(", ") { sectionLabel(it) }
}

/** GameCore's own name for each section. Never the plugin's — these label what the host will run. */
private fun sectionLabel(section: PluginSection): String = when (section) {
    PluginSection.THEME -> "Theme"
    PluginSection.PILL -> "Game Pill modules"
    PluginSection.SCREENS -> "Screens"
    PluginSection.PROFILES -> "Game profiles"
    PluginSection.RULES -> "Automation rules"
    PluginSection.AIMLAB -> "Aim Lab content"
    PluginSection.STATS -> "Stat readouts"
    PluginSection.SETTINGS -> "Settings"
    PluginSection.WIDGETS -> "Dashboard widgets"
}

/**
 * What to say about a stopped plugin.
 *
 * The supervisor's detail is appended when there is one, sanitised on the way in: it can quote a
 * plugin's own section name, and a failure message is not a licence for plugin text to arrive on this
 * screen unchecked.
 */
private fun suspensionText(detail: String?): String {
    val base = "GameCore stopped this plugin for the rest of this run after it failed repeatedly."
    val tail = TextSanitizer.sanitizeMultiline(detail)
    return if (tail.isEmpty()) base else "$base $tail"
}
