package com.gamecore.ui.setup

import com.gamecore.core.permissions.GamePermission

/**
 * The Setup health screen's state, and the pure logic that decides every word on it (spec §A3).
 *
 * The screen answers one question — "is the thing I turned on actually going to work?" — and it has to
 * answer it without nagging about anything the user chose not to have. That distinction is the whole design
 * of this file, and it lives in [HealthState]: a missing permission that an *enabled* feature needs is
 * [HealthState.NotSetUp] and is worth a Fix button; the identical missing permission for a feature the user
 * left off is [HealthState.Skipped] and is worth nothing more than a line saying so.
 *
 * Because the distinction is encoded in the state rather than recomputed by each caller, §A3's Home-card
 * rule collapses to [needsAttention] — "any row is NotSetUp" — and the card can never appear for a feature
 * the user turned off. Getting that wrong is the difference between a setup reminder and a permission
 * prompt the user already declined, so it is pure and it is tested.
 *
 * Nothing in this file imports `android.*` or Compose. [SetupHealthViewModel] reads the device and fills in
 * [SetupHealthInputs]; every decision after that point is a function here.
 */

/**
 * One row on the screen (§A3's "each permission, Shizuku, notifications, ping host, first profile").
 *
 * Declaration order is display order before [orderedRows] re-sorts by urgency, and it is the spec's own
 * order rather than an alphabetical one — the three special accesses, then Shizuku, then notifications,
 * then the ping host, then the profile — so a reader comparing the screen against §A3 can go down the list.
 *
 * [permission] is null for the three rows that are not permissions at all. Those are the rows whose "Fix"
 * goes somewhere inside GameCore rather than out to a Settings page, which is why the field is what the
 * screen branches on instead of a separate boolean.
 */
enum class SetupHealthItem(
    val title: String,
    val permission: GamePermission?,
    /** What the Fix button says. Named per row because "Fix" alone does not say where the user is going. */
    val fixLabel: String,
) {
    OVERLAY("Display over other apps", GamePermission.OVERLAY, "Open settings"),
    USAGE_ACCESS("Usage access", GamePermission.USAGE_ACCESS, "Open settings"),
    DO_NOT_DISTURB("Do Not Disturb access", GamePermission.NOTIFICATION_POLICY, "Open settings"),
    SHIZUKU("Shizuku", null, "Open Shizuku"),
    NOTIFICATIONS("Notifications", GamePermission.POST_NOTIFICATIONS, "Open settings"),
    PING_HOST("Latency probe host", null, "Open network settings"),
    FIRST_PROFILE("Your first game profile", null, "Add a game"),
}

/**
 * What one row's state is.
 *
 * Four states and not three, because "missing" splits into two things that must not look alike:
 *  - [NotSetUp] — something the user has switched on cannot work. This is the only state that nags, and the
 *    only one [needsAttention] counts.
 *  - [Skipped] — the feature is off, so the permission is not needed. §A3's "skipped optional items never
 *    nag" is satisfied by this state existing rather than by a suppression rule somewhere else.
 *
 * [Unavailable] carries a reason and never an empty string, because the app-wide rule is that anything
 * Android does not report shows as unavailable *with the real reason*. A row in this state has no Fix
 * button: there is nothing the user could do, and offering a button that leads nowhere is the failure this
 * state exists to prevent.
 */
sealed interface HealthState {
    /** The short label the row shows. Always present, so state is never carried by colour alone (§A5). */
    val label: String

    data object Ready : HealthState {
        override val label: String get() = "Ready"
    }

    data object NotSetUp : HealthState {
        override val label: String get() = "Not set up"
    }

    data object Skipped : HealthState {
        override val label: String get() = "Skipped (optional)"
    }

    data class Unavailable(val reason: String) : HealthState {
        override val label: String get() = "Unavailable"
    }
}

/** One row: the item, its state, and the sentence under the title. */
data class SetupHealthRow(
    val item: SetupHealthItem,
    val state: HealthState,
    /**
     * The explanation under the title.
     *
     * For a permission this is the catalogue's own wording, so the Setup health screen, the wizard and the
     * Permissions screen all say the same thing about the same permission. For the three non-permission
     * rows it is written by [buildRows], because there is no catalogue entry to quote.
     */
    val detail: String,
) {
    val title: String get() = item.title

    /** Whether a Fix button belongs on this row: something is missing, and there is somewhere to send the user. */
    val canFix: Boolean get() = state is HealthState.NotSetUp || state is HealthState.Skipped
}

/** The counted summary the screen leads with (§A3's "N of M ready"). */
data class HealthSummary(val ready: Int, val total: Int) {
    /**
     * "N of M ready".
     *
     * M excludes rows this device cannot do at all, because counting an impossibility against the user
     * produces a number that can never reach M and a screen that always looks broken. It *includes*
     * skipped rows: a user who turned three things off is genuinely at "2 of 5", and hiding that would make
     * the count disagree with the list right under it.
     */
    val label: String get() = "$ready of $total ready"
}

/**
 * Every fact [buildRows] needs, gathered by the ViewModel so that the decisions stay pure.
 *
 * The `wants*` flags are the crux and they are read from what the user has actually configured — profiles
 * and settings — never from the wizard's in-flight choices. A wizard choice is an intention that may have
 * been abandoned; a profile with the overlay switched on is a feature the user turned on, which is the
 * exact phrase §A3 uses for when the Home card may appear.
 */
data class SetupHealthInputs(
    val wantsOverlay: Boolean = false,
    val hasOverlay: Boolean = false,
    val wantsUsageAccess: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val wantsDoNotDisturb: Boolean = false,
    val hasDoNotDisturb: Boolean = false,
    val wantsNotifications: Boolean = false,
    val hasNotifications: Boolean = false,
    val wantsShizuku: Boolean = false,
    val isShizukuUsable: Boolean = false,
    /** Why Shizuku cannot be used here, when that is a device fact rather than a not-yet. Null otherwise. */
    val shizukuUnavailableReason: String? = null,
    val measureLatency: Boolean = false,
    val latencyHost: String = "",
    val profileCount: Int = 0,
)

/**
 * Builds every row from the gathered facts (§A3).
 *
 * The shape repeats deliberately: for each item, ask "is it wanted", then "is it present". Wanted and
 * present is [HealthState.Ready]; wanted and absent is [HealthState.NotSetUp]; not wanted is
 * [HealthState.Skipped] regardless of whether the grant happens to exist — a user who left the overlay off
 * does not need to be told their overlay permission is fine.
 *
 * Every row is always returned. Hiding a row when it is not wanted would make the list change shape as the
 * user toggles features elsewhere in the app, and would leave "N of M" jumping between devices for reasons
 * the screen never showed.
 */
fun buildRows(inputs: SetupHealthInputs): List<SetupHealthRow> = listOf(
    permissionRow(
        item = SetupHealthItem.OVERLAY,
        wanted = inputs.wantsOverlay,
        present = inputs.hasOverlay,
        skippedDetail = "No profile is using the in-game overlay, so this is not needed.",
    ),
    permissionRow(
        item = SetupHealthItem.USAGE_ACCESS,
        wanted = inputs.wantsUsageAccess,
        present = inputs.hasUsageAccess,
        skippedDetail = "Automatic profiles are off, so GameCore does not need to see which app is in front.",
    ),
    permissionRow(
        item = SetupHealthItem.DO_NOT_DISTURB,
        wanted = inputs.wantsDoNotDisturb,
        present = inputs.hasDoNotDisturb,
        skippedDetail = "No profile turns Do Not Disturb on, so this is not needed.",
    ),
    shizukuRow(inputs),
    permissionRow(
        item = SetupHealthItem.NOTIFICATIONS,
        wanted = inputs.wantsNotifications,
        present = inputs.hasNotifications,
        skippedDetail = "Nothing is running in the background, so GameCore has no notification to post.",
    ),
    pingHostRow(inputs),
    profileRow(inputs),
)

/**
 * A permission row, worded from the catalogue.
 *
 * The "not set up" detail is [GamePermission.whatBreaks] rather than a sentence written here, because that
 * string is the answer to the question the user is actually asking when they look at a red row: not "what
 * is this permission" but "what am I losing".
 */
private fun permissionRow(
    item: SetupHealthItem,
    wanted: Boolean,
    present: Boolean,
    skippedDetail: String,
): SetupHealthRow {
    val permission = item.permission
    val state = when {
        !wanted -> HealthState.Skipped
        present -> HealthState.Ready
        else -> HealthState.NotSetUp
    }
    val detail = when (state) {
        HealthState.Ready -> permission?.why ?: ""
        HealthState.NotSetUp -> permission?.whatBreaks ?: ""
        HealthState.Skipped -> skippedDetail
        is HealthState.Unavailable -> state.reason
    }
    return SetupHealthRow(item = item, state = state, detail = detail)
}

/**
 * The Shizuku row, which is the one row with a real [HealthState.Unavailable] case.
 *
 * A device whose build has no usable Shizuku path is not a device where the user forgot something, and the
 * reason is passed in rather than guessed — the same reason string the capability checker produced, so the
 * Setup health screen and the Shizuku screen cannot end up disagreeing about why it does not work.
 */
private fun shizukuRow(inputs: SetupHealthInputs): SetupHealthRow {
    val reason = inputs.shizukuUnavailableReason
    val state = when {
        reason != null -> HealthState.Unavailable(reason)
        !inputs.wantsShizuku -> HealthState.Skipped
        inputs.isShizukuUsable -> HealthState.Ready
        else -> HealthState.NotSetUp
    }
    val detail = when (state) {
        HealthState.Ready -> "The elevated shell is connected, so refresh-rate and thermal controls work."
        HealthState.NotSetUp -> "A profile is using a setting that needs the elevated shell. Until Shizuku " +
            "is running, that setting is skipped."
        HealthState.Skipped -> "Nothing you have set up needs the elevated shell."
        is HealthState.Unavailable -> state.reason
    }
    return SetupHealthRow(item = SetupHealthItem.SHIZUKU, state = state, detail = detail)
}

/**
 * The latency probe's host (§A3's "ping host").
 *
 * On the screen because it is the one setting in the app that reaches the network, and a user auditing
 * their own setup should be able to see the host without hunting for it. A blank host while the probe is on
 * is genuinely not set up — there is nothing to connect to — and is the only way this row nags.
 */
private fun pingHostRow(inputs: SetupHealthInputs): SetupHealthRow {
    val host = inputs.latencyHost.trim()
    val state = when {
        !inputs.measureLatency -> HealthState.Skipped
        host.isEmpty() -> HealthState.NotSetUp
        else -> HealthState.Ready
    }
    val detail = when (state) {
        HealthState.Ready -> "Latency is measured with a TCP handshake to $host. No data is sent."
        HealthState.NotSetUp -> "The latency check is on but no host is set, so it cannot run."
        HealthState.Skipped -> "The latency check is off. Nothing connects out."
        is HealthState.Unavailable -> state.reason
    }
    return SetupHealthRow(item = SetupHealthItem.PING_HOST, state = state, detail = detail)
}

/**
 * Whether the user has a profile yet.
 *
 * Never [HealthState.Skipped]: a profile is the thing the whole app exists to apply, so this row stays as
 * an invitation rather than becoming something the user can decline. The screen draws it in a neutral tone
 * even while it is [HealthState.NotSetUp], because having no profile on day one is not a fault — it is the
 * one row where "not set up" means "not yet" rather than "broken".
 */
private fun profileRow(inputs: SetupHealthInputs): SetupHealthRow {
    val state = if (inputs.profileCount > 0) HealthState.Ready else HealthState.NotSetUp
    val detail = if (inputs.profileCount > 0) {
        "You have ${inputs.profileCount} ${if (inputs.profileCount == 1) "profile" else "profiles"}."
    } else {
        "Add a game and GameCore has something to apply when you play."
    }
    return SetupHealthRow(item = SetupHealthItem.FIRST_PROFILE, state = state, detail = detail)
}

/**
 * The rows in the order the screen draws them: what needs doing first, then everything else.
 *
 * Urgency before category, because a user opening Setup health has a reason and it is almost always one
 * broken row. Within a group the order is [SetupHealthItem]'s own, so the list is stable between visits and
 * a row does not move just because a sibling's state changed.
 */
fun orderedRows(rows: List<SetupHealthRow>): List<SetupHealthRow> =
    rows.sortedWith(compareBy({ rankOf(it.state) }, { it.item.ordinal }))

private fun rankOf(state: HealthState): Int = when (state) {
    HealthState.NotSetUp -> 0
    HealthState.Ready -> 1
    HealthState.Skipped -> 2
    is HealthState.Unavailable -> 3
}

/** See [HealthSummary.label] for what is counted and why. */
fun summarise(rows: List<SetupHealthRow>): HealthSummary {
    val applicable = rows.filterNot { it.state is HealthState.Unavailable }
    return HealthSummary(
        ready = applicable.count { it.state is HealthState.Ready },
        total = applicable.size,
    )
}

/**
 * Whether the Home card should appear (§A1/§A3).
 *
 * One condition, because [HealthState.NotSetUp] already means "a feature the user turned on cannot work".
 * Nothing else qualifies: a skipped optional item is a decision, and an unavailable one is a device fact.
 * The card's own dismissal is checked separately by [com.gamecore.domain.setup.decideEntry] — this function
 * answers whether there is anything to say, not whether the user wants to hear it.
 */
fun needsAttention(rows: List<SetupHealthRow>): Boolean = rows.any { it.state is HealthState.NotSetUp }

/**
 * What the Home card says when it appears.
 *
 * Names the count rather than the items, because the card is one line on a dashboard and a list of three
 * permissions on it would compete with the readings the screen is for. The screen it opens has the detail.
 */
fun attentionLine(rows: List<SetupHealthRow>): String {
    val outstanding = rows.count { it.state is HealthState.NotSetUp }
    return when (outstanding) {
        0 -> "Everything you have turned on is set up."
        1 -> "One thing you have turned on is not set up yet."
        else -> "$outstanding things you have turned on are not set up yet."
    }
}

/**
 * The Setup health screen's state.
 *
 * [rows] is already ordered — the ViewModel puts them through [orderedRows] so the screen never sorts, and
 * so a test of the ordering is a test of the thing the user sees.
 */
data class SetupHealthUiState(
    val isLoaded: Boolean = false,
    val rows: List<SetupHealthRow> = emptyList(),
    val summary: HealthSummary = HealthSummary(ready = 0, total = 0),
    val message: String? = null,
) {
    /** Whether anything on this screen is [HealthState.NotSetUp]. Same rule the Home card uses. */
    val hasOutstanding: Boolean get() = needsAttention(rows)
}
