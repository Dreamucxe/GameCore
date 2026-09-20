package com.gamecore.aimlab.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.ui.home.AimLabDestination
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * Personal records: every best the user has actually set, and nothing else.
 *
 * This screen is a report on stored rows, and the one rule it is written around is that **a record is
 * evidence of a session**. `PersonalRecordBook` writes a [PersonalRecord] only when a finished session beat
 * the previous best for that (mode, difficulty, weapon, metric) key, so every row here can be traced to a
 * run the user did. The corollary is what the screen does *not* draw: a metric never scored on has no row, a
 * mode never trained has no card, and with nothing stored at all there is an [EmptyState] pointing at
 * training rather than a grid of dashes or zeroes (§1/§30). A "Best score: 0" line would be the absence of a
 * record presented as a record.
 *
 * Records are separated by all four keys, so a row names its difficulty and — only when the record is
 * weapon-specific — its weapon. A weapon-less record shows no weapon line rather than "None": the modes that
 * do not involve a weapon genuinely have nothing to report there.
 *
 * Direction is handled per metric rather than assumed. `RecordMetric.FASTEST_REACTION` has
 * `higherIsBetter = false`, so on that metric the record *fell* and the margin over the old one is worded
 * "faster"; everywhere else the record rose and the margin is worded "higher" or "longer". [RecordFormat]
 * owns both that and the unit each metric is measured in, so a reaction time is never printed as a score.
 *
 * @param onBack leave the screen.
 * @param onTrain where the empty state sends the user. Defaults to [onBack], because Aim Lab's home screen —
 *   which is what this screen is reached from — is where a training mode is chosen.
 */
@Composable
fun RecordsScreen(
    onBack: () -> Unit,
    onTrain: () -> Unit = onBack,
    modifier: Modifier = Modifier,
    viewModel: RecordsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Personal records",
                    subtitle = headerSubtitle(state),
                    onBack = onBack,
                )
            }

            if (state.reset) {
                item {
                    NoteBanner(
                        text = "Records cleared, along with the sessions they were set from. Your next " +
                            "scored session starts the list again.",
                        tone = Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            }

            when {
                // Nothing has come back from the database yet. Drawing the empty state now would claim the
                // user has no records before anything has been asked.
                state.loading -> Unit

                !state.hasAny -> item {
                    EmptyState(
                        icon = Icons.Filled.EmojiEvents,
                        title = "No records yet",
                        message = "A record appears here the moment a session beats your previous best — " +
                            "nothing is filled in ahead of time. Train a scored mode and your first " +
                            "result becomes the mark to beat.",
                        actionLabel = "Choose a training mode",
                        onAction = onTrain,
                    )
                }

                else -> {
                    if (state.offersModeFilter || state.offersDifficultyFilter) {
                        item {
                            FilterCard(
                                state = state,
                                onMode = viewModel::onModeSelected,
                                onDifficulty = viewModel::onDifficultySelected,
                                modifier = padded,
                            )
                        }
                    }

                    if (state.noMatches) {
                        item {
                            // Records exist; this combination has none. A different fact from having none,
                            // and it has a different way out.
                            NoteBanner(
                                text = "No record matches this filter.",
                                tone = Tone.Muted,
                                icon = Icons.Filled.FilterList,
                                modifier = padded,
                                action = {
                                    TextButton(onClick = viewModel::clearFilters) { Text("Show all") }
                                },
                            )
                        }
                    }

                    items(state.groups) { group ->
                        GroupCard(group = group, modifier = padded)
                    }

                    item {
                        NoteBanner(
                            text = "Every line above is a session that beat your previous best. A metric you " +
                                "have not scored on is not listed at all.",
                            tone = Tone.Muted,
                            icon = Icons.Filled.EmojiEvents,
                            modifier = padded,
                        )
                    }

                    item {
                        Box(modifier = padded) {
                            ActionRow {
                                OutlinedButton(
                                    onClick = viewModel::requestReset,
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.resetting,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Tone.Danger.colour(),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (state.resetting) "Resetting…" else "Reset records")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.confirmingReset) {
            ConfirmDialog(
                title = "Reset personal records?",
                message = "Every best listed here is removed, together with the session history it was set " +
                    "from — a record is derived from a session, so the two are cleared as one. Nothing is " +
                    "kept anywhere and there is no undo.",
                confirmLabel = "Reset records",
                onConfirm = viewModel::confirmReset,
                onDismiss = viewModel::dismissReset,
                isDestructive = true,
            )
        }
    }
}

/**
 * The header's second line: a count of what is actually stored.
 *
 * Nothing is rounded up or estimated — it is the length of the list, and while the query is still running it
 * says so rather than showing "0 records".
 */
private fun headerSubtitle(state: RecordsState): String = when {
    state.loading -> "Reading your records…"
    !state.hasAny -> "Your best result in each mode, once you have set one."
    else -> {
        val records = Formatters.count(state.records.size, "record")
        val modes = Formatters.count(state.modesWithRecords.size, "mode")
        "$records across $modes."
    }
}

// --------------------------------------------------------------------------------------------- filters

/**
 * The two filters, each offering only what the records contain.
 *
 * The leading chip on each row is "everything", and the rest are read from the stored rows — so tapping any
 * chip always lands on at least one record, and a mode the user has never set a record in is not advertised
 * as a category. A row with a single real option is omitted entirely by the caller: one choice is not a
 * filter.
 */
@Composable
private fun FilterCard(
    state: RecordsState,
    onMode: (TrainingMode?) -> Unit,
    onDifficulty: (Difficulty?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Filter",
        subtitle = "Only the modes and difficulties you hold a record in.",
        icon = Icons.Filled.FilterList,
        modifier = modifier,
    ) {
        if (state.offersModeFilter) {
            FilterLabel("Mode")
            ChoiceRow(
                options = state.modeOptions,
                selected = state.modeFilter,
                onSelect = onMode,
                label = { it?.label ?: "All modes" },
                perRow = 2,
            )
        }
        if (state.offersDifficultyFilter) {
            if (state.offersModeFilter) RowDivider()
            FilterLabel("Difficulty")
            ChoiceRow(
                options = state.difficultyOptions,
                selected = state.difficultyFilter,
                onSelect = onDifficulty,
                label = { it?.label ?: "All" },
                perRow = 3,
            )
        }
    }
}

/** A heading for one filter inside the card that holds both. */
@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ---------------------------------------------------------------------------------------------- rows

/**
 * One mode's records.
 *
 * The card exists because the group does, and a group exists because at least one record is in it — there is
 * no card for a mode the user has not set a record in, and therefore no empty card to explain.
 */
@Composable
private fun GroupCard(group: RecordGroup, modifier: Modifier = Modifier) {
    SectionCard(
        title = group.mode.label,
        subtitle = Formatters.count(group.records.size, "record"),
        icon = iconFor(group.mode),
        modifier = modifier,
    ) {
        group.records.forEachIndexed { index, record ->
            if (index > 0) RowDivider()
            RecordEntry(record)
        }
    }
}

/**
 * One record: what was measured, on what settings, how good it was, when, and what it beat.
 *
 * Four of the six lines are conditional, and each condition is an absence rather than a blank:
 *
 *  - **Weapon** appears only when the record is weapon-specific. A weapon-less mode has no weapon, so it
 *    gets no line — not "Weapon: None".
 *  - **Previous** appears only when this record beat one. The first record for a key has no previous value,
 *    and showing "Previous: 0" or "—" would invent a result the user never had.
 *  - **The margin** appears only when the stored numbers actually show an improvement in the metric's own
 *    direction, which for `FASTEST_REACTION` means the value fell. If they do not, the beaten value is shown
 *    plainly with no claim attached.
 *
 * The chip carries the difficulty, because a best on Easy and a best on Extreme are separate records and the
 * difference between them is the whole point of keeping both.
 */
@Composable
private fun RecordEntry(record: PersonalRecord) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.metric.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = RecordFormat.direction(record.metric),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(text = record.difficulty.label, tone = Tone.Muted)
        }

        KeyValueRow(
            label = "Record",
            value = RecordFormat.value(record.metric, record.value),
            tone = Tone.Good,
        )

        // Only weapon-specific records carry a weapon. The rest have none, which is not a gap to fill.
        val weapon = record.weaponName
        if (weapon != null) {
            KeyValueRow(label = "Weapon", value = weapon)
        }

        KeyValueRow(label = "Set", value = RecordFormat.achieved(record.achievedAtMillis))

        // A first record beat nothing, so there is nothing to compare it against.
        val previous = record.previousValue
        if (previous != null) {
            val beaten = RecordFormat.value(record.metric, previous)
            val margin = RecordFormat.improvement(record.metric, record.value, previous)
            KeyValueRow(
                label = "Beat",
                value = if (margin == null) beaten else "$beaten · $margin",
                tone = Tone.Muted,
            )
        }
    }
}

/**
 * The icon the home grid already uses for this mode.
 *
 * Read from [AimLabDestination] rather than repeated here, so a records card and the home card that launches
 * the same mode cannot end up wearing different icons.
 */
private fun iconFor(mode: TrainingMode): ImageVector =
    AimLabDestination.entries.firstOrNull { it.mode == mode }?.icon ?: Icons.Filled.EmojiEvents
