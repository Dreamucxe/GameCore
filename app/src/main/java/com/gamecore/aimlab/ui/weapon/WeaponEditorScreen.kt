package com.gamecore.aimlab.ui.weapon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.RecoilEngine
import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.SeededRng
import com.gamecore.aimlab.engine.Vec2
import com.gamecore.aimlab.engine.FireMode
import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import kotlin.math.max
import kotlin.math.min

/**
 * §13's weapon editor: the saved weapons, and the numbers behind whichever one is open.
 *
 * Every control on this screen is a real parameter of the training engine. There is no cosmetic slider
 * and no field that is stored but unread — fire rate becomes the interval between shots, magazine size
 * becomes when a reload interrupts a drill, and the four recoil figures become the pattern the recoil
 * mode makes the player fight. §7's honesty point applies to the whole screen: these are fictional
 * training weapons, not a database of real-world ballistics, and the subtitle says so.
 *
 * The recoil preview is why the editor is worth using rather than a table of numbers. It runs the actual
 * [RecoilEngine] over the drafted [RecoilSpec] and draws the burst it produces, so the user changes
 * "randomness" and immediately sees a learnable pattern turn into a spray. It is seeded, so the picture
 * is stable while unrelated sliders move and changes only when the recoil itself does.
 *
 * Built-in weapons are shown but not editable: their fields are disabled, their name is a read-only row,
 * and the only action offered is Duplicate, which makes a user-owned copy and opens it.
 *
 * @param onBack pop back to the Aim Lab home screen.
 */
@Composable
fun WeaponEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeaponEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // The dialog sits outside the list rather than in an item, so a confirmation cannot be disposed by
    // scrolling the row that raised it off the screen.
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Weapon editor",
                    subtitle = "Fictional training weapons: fire rate, recoil and spread. Not real-world " +
                        "weapon data.",
                    onBack = onBack,
                    action = {
                        IconButton(onClick = viewModel::newWeapon) {
                            Icon(Icons.Filled.Add, contentDescription = "New weapon")
                        }
                    },
                )
            }

            item {
                WeaponListCard(
                    state = state,
                    onSelect = viewModel::select,
                    onNew = viewModel::newWeapon,
                    modifier = padded,
                )
            }

            item {
                WeaponFieldsCard(
                    state = state,
                    onDraft = viewModel::updateDraft,
                    modifier = padded,
                )
            }

            item {
                RecoilCard(
                    state = state,
                    onDraft = viewModel::updateDraft,
                    modifier = padded,
                )
            }

            item {
                EditorActions(
                    state = state,
                    onSave = viewModel::save,
                    onDuplicate = viewModel::duplicate,
                    onDelete = viewModel::requestDelete,
                    modifier = padded,
                )
            }
        }

        if (state.confirmingDelete) {
            ConfirmDialog(
                title = "Delete this weapon?",
                message = "\"${state.draft.name}\" and its recoil settings are removed. Sessions already " +
                    "recorded with it keep their results, but the weapon cannot be brought back.",
                confirmLabel = "Delete it",
                onConfirm = viewModel::confirmDelete,
                onDismiss = viewModel::dismissDelete,
                isDestructive = true,
            )
        }
    }
}

/**
 * The saved weapons, newest-looking first by nothing at all — the repository's order is kept, which puts
 * the built-ins where they were seeded and the user's own after them.
 *
 * A row says the three things that identify a weapon at a glance: what it is called, what family it
 * belongs to, and how fast it fires. The trailing label marks the one currently open, so a user who has
 * scrolled down to the editor and back knows which row they are editing.
 */
@Composable
private fun WeaponListCard(
    state: WeaponEditorState,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Weapons",
        modifier = modifier,
        subtitle = if (state.weapons.isEmpty()) null else "Tap one to open it in the editor below.",
        icon = Icons.Filled.Build,
        action = {
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New weapon")
            }
        },
    ) {
        when {
            // The first frame, before the database has answered. Neither list nor empty state is true
            // yet, so the card shows nothing rather than flashing "no weapons".
            state.loading -> Unit

            state.weapons.isEmpty() -> EmptyState(
                icon = Icons.Filled.Build,
                title = "No weapons yet",
                message = "Build one and the recoil, tracking and reload drills will have something to " +
                    "run on.",
                actionLabel = "New weapon",
                onAction = onNew,
            )

            else -> state.weapons.forEachIndexed { index, weapon ->
                if (index > 0) RowDivider()
                val isOpen = weapon.id == state.editingId
                NavRow(
                    title = weapon.name,
                    onClick = { onSelect(weapon.id) },
                    description = state.subtitleFor(weapon),
                    icon = Icons.Filled.Build,
                    trailing = when {
                        isOpen -> "Editing"
                        weapon.isBuiltIn -> "Built-in"
                        else -> null
                    },
                    trailingTone = if (isOpen) Tone.Accent else Tone.Muted,
                )
            }
        }
    }
}

/**
 * Name, family, and the four handling figures.
 *
 * Each slider's description says what the number does to a drill, because "ADS 250 ms" means nothing on
 * its own and a user tuning a weapon is choosing a feel, not a figure. The fire-rate and magazine rows
 * show the derived consequence — the interval between shots, how long a magazine lasts — computed by the
 * model itself rather than restated here.
 */
@Composable
private fun WeaponFieldsCard(
    state: WeaponEditorState,
    onDraft: (WeaponDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val editable = !state.editingBuiltIn

    SectionCard(
        title = state.editorTitle,
        modifier = modifier,
        subtitle = if (state.isNew) "Every field below is a parameter the drills run on." else null,
        icon = Icons.Filled.Build,
    ) {
        if (!editable) {
            NoteBanner(
                text = "Built-in weapons are read-only. Duplicate this one to get a copy you can retune.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // A disabled text field would still look like somewhere to type, so a built-in's name is a
            // plain value row instead — the "no renaming in place" rule made visible rather than enforced
            // by a tap that does nothing.
            KeyValueRow(label = "Name", value = draft.name)
            KeyValueRow(label = "Category", value = draft.category.label)
        } else {
            TextFieldRow(
                label = "Name",
                value = draft.name,
                onValueChange = { onDraft(draft.copy(name = it)) },
                placeholder = "Training rifle",
                maxLength = WeaponDraft.MAX_NAME_LENGTH,
                description = "What this weapon is called in the drill picker and on your results.",
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "CATEGORY",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ChoiceRow(
                options = WeaponCategory.entries,
                selected = draft.category,
                onSelect = { onDraft(draft.copy(category = it)) },
                label = { it.label },
                perRow = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "FIRE MODE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = FireMode.entries,
            selected = draft.fireMode,
            onSelect = { onDraft(draft.copy(fireMode = it)) },
            label = { it.label },
            perRow = 3,
        )
        if (draft.fireMode == FireMode.BURST) {
            SliderRow(
                title = "Burst count",
                value = draft.burstCount,
                range = WeaponDraft.BURST_RANGE,
                onValueChange = { onDraft(draft.copy(burstCount = it)) },
                valueLabel = "${draft.burstCount} rounds",
                description = "Rounds per trigger pull before the trigger must be released.",
                enabled = editable,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SliderRow(
            title = "Fire rate",
            value = draft.fireRateRpm,
            range = WeaponDraft.RPM_RANGE,
            onValueChange = { onDraft(draft.copy(fireRateRpm = it)) },
            valueLabel = "${draft.fireRateRpm} rpm",
            description = "${draft.shotIntervalMillis} ms between shots.",
            enabled = editable,
        )
        SliderRow(
            title = "Magazine",
            value = draft.magazineSize,
            range = WeaponDraft.MAGAZINE_RANGE,
            onValueChange = { onDraft(draft.copy(magazineSize = it)) },
            valueLabel = "${draft.magazineSize} rounds",
            description = "Empties in ${secondsLabel(draft.magazineDurationMillis)} of held fire.",
            enabled = editable,
        )
        SliderRow(
            title = "Reload",
            value = draft.reloadMillis,
            range = WeaponDraft.RELOAD_RANGE,
            onValueChange = { onDraft(draft.copy(reloadMillis = it)) },
            valueLabel = "${draft.reloadMillis} ms",
            description = "Dead time after a magazine runs out.",
            enabled = editable,
        )
        SliderRow(
            title = "Aim down sights",
            value = draft.adsMillis,
            range = WeaponDraft.ADS_RANGE,
            onValueChange = { onDraft(draft.copy(adsMillis = it)) },
            valueLabel = "${draft.adsMillis} ms",
            description = "How long the weapon takes to settle into the aimed view.",
            enabled = editable,
        )
        SliderRow(
            title = "Movement penalty",
            value = draft.movementPenaltyPercent,
            range = WeaponDraft.PERCENT_RANGE,
            onValueChange = { onDraft(draft.copy(movementPenaltyPercent = it)) },
            valueLabel = "${draft.movementPenaltyPercent}%",
            description = "How much moving widens your aim error. 0% punishes movement not at all.",
            enabled = editable,
        )
        SliderRow(
            title = "Spread",
            value = draft.spreadPercent,
            range = WeaponDraft.SPREAD_RANGE,
            onValueChange = { onDraft(draft.copy(spreadPercent = it)) },
            valueLabel = "${draft.spreadPercent}%",
            description = "Base scatter at rest, as a fraction of the arena. 0% is pinpoint.",
            enabled = editable,
        )
    }
}

/**
 * The four recoil parameters, and the burst they add up to.
 *
 * The preview sits at the top of the card rather than the bottom: the sliders are below it, so a thumb
 * on one is not covering the drawing it is changing.
 */
@Composable
private fun RecoilCard(
    state: WeaponEditorState,
    onDraft: (WeaponDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val editable = !state.editingBuiltIn

    SectionCard(
        title = "Recoil",
        modifier = modifier,
        subtitle = "The pattern below is the first $PREVIEW_SHOTS shots these numbers produce.",
        icon = Icons.Filled.Build,
    ) {
        RecoilPreview(spec = draft.recoil)
        Spacer(modifier = Modifier.height(4.dp))

        SliderRow(
            title = "Vertical per shot",
            value = draft.verticalPerMille,
            range = WeaponDraft.KICK_RANGE,
            onValueChange = { onDraft(draft.copy(verticalPerMille = it)) },
            valueLabel = tenthsLabel(draft.verticalPerMille, "%"),
            description = "How far each shot climbs. This is the kick you pull down against.",
            enabled = editable,
        )
        SliderRow(
            title = "Horizontal per shot",
            value = draft.horizontalPerMille,
            range = WeaponDraft.KICK_RANGE,
            onValueChange = { onDraft(draft.copy(horizontalPerMille = it)) },
            valueLabel = tenthsLabel(draft.horizontalPerMille, "%"),
            description = "Sideways drift per shot. The engine alternates its direction, so the pattern " +
                "weaves rather than walking off one side.",
            enabled = editable,
        )
        SliderRow(
            title = "Randomness",
            value = draft.randomnessPercent,
            range = WeaponDraft.PERCENT_RANGE,
            onValueChange = { onDraft(draft.copy(randomnessPercent = it)) },
            valueLabel = "${draft.randomnessPercent}%",
            description = "How much of each kick is unpredictable. 0% is a fixed pattern you can learn; " +
                "100% cannot be learned at all.",
            enabled = editable,
        )
        SliderRow(
            title = "Recovery",
            value = draft.recoveryTenths,
            range = WeaponDraft.RECOVERY_RANGE,
            onValueChange = { onDraft(draft.copy(recoveryTenths = it)) },
            valueLabel = tenthsLabel(draft.recoveryTenths, "/s"),
            description = "How quickly the aim settles back once you stop firing.",
            enabled = editable,
        )
    }
}

/**
 * The drafted recoil, drawn as the burst it actually generates.
 *
 * The points come from the real [RecoilEngine] rather than from a formula reimplemented for the picture,
 * so what the user sees here is what the recoil drill will make them fight. The seed is fixed, which
 * matters twice: the same numbers always draw the same shape, so moving an unrelated slider does not
 * reshuffle the preview; and the randomised component is shown as one representative burst rather than
 * as an animation that would make the pattern impossible to read.
 *
 * [remember] is keyed on the spec — a data class, so the comparison is over the four values — which is
 * what makes the drawing live: change a recoil slider and the pattern is regenerated on that frame,
 * change the magazine size and it is not.
 */
@Composable
private fun RecoilPreview(spec: RecoilSpec, modifier: Modifier = Modifier) {
    val pattern = remember(spec) {
        RecoilEngine(SeededRng(PREVIEW_SEED)).pattern(spec, PREVIEW_SHOTS)
    }
    val scheme = MaterialTheme.colorScheme
    val trace = scheme.primary
    val shots = scheme.onSurface
    val guide = scheme.outline.copy(alpha = 0.5f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT_DP.dp),
    ) {
        drawRecoilPattern(points = pattern, trace = trace, shots = shots, guide = guide)
    }
}

/**
 * Fits the cumulative offsets into the box and joins them up.
 *
 * The origin is included in the bounds deliberately: it is where the aim starts, and a pattern drawn
 * without it would hide how far the first shot already moves. The scale is uniform across both axes so
 * the shape stays truthful — a weapon that climbs ten times as far as it drifts must look like it, not
 * be stretched into a zigzag by an axis fitted independently.
 */
private fun DrawScope.drawRecoilPattern(
    points: List<Vec2>,
    trace: Color,
    shots: Color,
    guide: Color,
) {
    val inset = PREVIEW_INSET_PX
    val usableWidth = size.width - inset * 2f
    val usableHeight = size.height - inset * 2f
    if (points.isEmpty() || usableWidth <= 0f || usableHeight <= 0f) return

    // Bounds over the shots and the origin they start from.
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxY = 0f
    points.forEach { point ->
        minX = min(minX, point.x)
        maxX = max(maxX, point.x)
        minY = min(minY, point.y)
        maxY = max(maxY, point.y)
    }
    val spanX = maxX - minX
    val spanY = maxY - minY
    // A weapon with no kick at all has zero span on both axes; it is drawn as the single point it is,
    // rather than divided by zero into nothing.
    val scale = when {
        spanX <= 0f && spanY <= 0f -> 0f
        spanX <= 0f -> usableHeight / spanY
        spanY <= 0f -> usableWidth / spanX
        else -> min(usableWidth / spanX, usableHeight / spanY)
    }
    val centreX = inset + usableWidth / 2f
    val centreY = inset + usableHeight / 2f
    val midX = (minX + maxX) / 2f
    val midY = (minY + maxY) / 2f

    fun project(point: Vec2) = Offset(
        x = centreX + (point.x - midX) * scale,
        y = centreY + (point.y - midY) * scale,
    )

    // Where the aim was before the first shot: the thing the whole pattern is measured from.
    val origin = project(Vec2(0f, 0f))
    val tick = PREVIEW_TICK_PX
    drawLine(guide, Offset(origin.x - tick, origin.y), Offset(origin.x + tick, origin.y), 1f)
    drawLine(guide, Offset(origin.x, origin.y - tick), Offset(origin.x, origin.y + tick), 1f)

    var previous = origin
    points.forEach { point ->
        val current = project(point)
        drawLine(
            color = trace,
            start = previous,
            end = current,
            strokeWidth = PREVIEW_STROKE_PX,
            cap = StrokeCap.Round,
        )
        previous = current
    }
    points.forEach { point ->
        drawCircle(color = shots, radius = PREVIEW_DOT_PX, center = project(point))
    }
}

/**
 * Save, Duplicate, Delete.
 *
 * Duplicate is the action a built-in offers instead of the other two, and it is present for a saved
 * user weapon as well — "start from this one" is how most weapons after the first get made. Delete is
 * last, separated, and never shown for a built-in.
 */
@Composable
private fun EditorActions(
    state: WeaponEditorState,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Actions", modifier = modifier, icon = Icons.Filled.Save) {
        ActionRow {
            if (!state.editingBuiltIn) {
                Button(onClick = onSave, enabled = state.canSave) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.isNew) "Create" else "Save")
                }
            }
            TextButton(onClick = onDuplicate, enabled = state.canDuplicate) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Duplicate")
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.canDelete) {
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = Tone.Danger.colour(),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Delete", color = Tone.Danger.colour())
                }
            }
        }
        val error = state.error
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(text = error, tone = Tone.Danger, icon = Icons.Filled.Info)
        } else if (!state.canSave && !state.editingBuiltIn && state.draft.name.isBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "Give the weapon a name before saving it.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/** A tenths-scaled integer as a one-decimal figure: `25` becomes "2.5". Integer maths, so no locale. */
private fun tenthsLabel(value: Int, suffix: String): String = "${value / 10}.${value % 10}$suffix"

/** Milliseconds as a one-decimal count of seconds, for durations the user thinks of in seconds. */
private fun secondsLabel(millis: Long): String = "${millis / 1000}.${(millis % 1000) / 100} s"

/**
 * The seed the preview burst is generated from.
 *
 * Fixed rather than rolled, because the preview's job is to let two sets of numbers be compared. A new
 * seed on every recomposition would change the drawing when nothing about the weapon had.
 */
private const val PREVIEW_SEED = 42L

/** A burst long enough to show where a pattern goes, short enough to stay legible in a card. */
private const val PREVIEW_SHOTS = 30

private const val PREVIEW_HEIGHT_DP = 180
private const val PREVIEW_INSET_PX = 16f
private const val PREVIEW_TICK_PX = 6f
private const val PREVIEW_STROKE_PX = 2.5f
private const val PREVIEW_DOT_PX = 2.5f
