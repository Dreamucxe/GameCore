package com.gamecore.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup health's decisions (spec §A3) as pure logic: what state each row lands in for every combination of
 * "the user wants it" and "the device has it", how the "N of M ready" count treats skipped and unavailable
 * rows, the display order, and the single rule that decides whether the Home card appears.
 *
 * The Home-card rule is the reason most of this file exists. §A3 says the card appears "only when a feature
 * the user turned on lacks a required permission", and the failure mode is a card that nags about a
 * permission for a feature the user deliberately left off — which is a permission prompt they already
 * declined, arriving again on their dashboard. [needsAttention] is the whole rule and it is asserted from
 * both directions: it fires for a wanted-and-missing row, and it stays silent for every other shape.
 *
 * No Activity, no Compose, no `android.*` call. [SetupHealthInputs] is plain data, so every case here is an
 * assertion on values.
 */
class SetupHealthLogicTest {

    // ------------------------------------------------------------------- per-row states

    @Test
    fun `a wanted and granted permission is ready`() {
        val rows = buildRows(SetupHealthInputs(wantsOverlay = true, hasOverlay = true))
        assertEquals(HealthState.Ready, rowFor(rows, SetupHealthItem.OVERLAY).state)
    }

    @Test
    fun `a wanted and missing permission is not set up`() {
        val rows = buildRows(SetupHealthInputs(wantsOverlay = true, hasOverlay = false))
        assertEquals(HealthState.NotSetUp, rowFor(rows, SetupHealthItem.OVERLAY).state)
    }

    @Test
    fun `an unwanted permission is skipped even when it is granted`() {
        val rows = buildRows(SetupHealthInputs(wantsOverlay = false, hasOverlay = true))
        assertEquals(HealthState.Skipped, rowFor(rows, SetupHealthItem.OVERLAY).state)
    }

    @Test
    fun `an unwanted permission is skipped rather than not set up`() {
        val rows = buildRows(SetupHealthInputs(wantsUsageAccess = false, hasUsageAccess = false))
        assertEquals(HealthState.Skipped, rowFor(rows, SetupHealthItem.USAGE_ACCESS).state)
    }

    @Test
    fun `every row is present whatever the inputs say`() {
        val rows = buildRows(SetupHealthInputs())
        assertEquals(SetupHealthItem.entries.size, rows.size)
        assertEquals(SetupHealthItem.entries.toList(), rows.map { it.item })
    }

    @Test
    fun `every row explains itself in every state`() {
        // The app-wide rule is that nothing shows a bare state with no reason. A blank detail would draw a
        // card with a title, a chip and an empty line, which is the shape this guards against.
        val shapes = listOf(
            SetupHealthInputs(),
            SetupHealthInputs(
                wantsOverlay = true,
                wantsUsageAccess = true,
                wantsDoNotDisturb = true,
                wantsNotifications = true,
                wantsShizuku = true,
                measureLatency = true,
            ),
            SetupHealthInputs(
                wantsOverlay = true,
                hasOverlay = true,
                wantsUsageAccess = true,
                hasUsageAccess = true,
                wantsDoNotDisturb = true,
                hasDoNotDisturb = true,
                wantsNotifications = true,
                hasNotifications = true,
                wantsShizuku = true,
                isShizukuUsable = true,
                measureLatency = true,
                latencyHost = "1.1.1.1",
                profileCount = 2,
            ),
            SetupHealthInputs(shizukuUnavailableReason = "This build's Shizuku is too old to talk to."),
        )
        shapes.forEach { inputs ->
            buildRows(inputs).forEach { row ->
                assertTrue("${row.item} had a blank detail", row.detail.isNotBlank())
                assertTrue("${row.item} had a blank state label", row.state.label.isNotBlank())
            }
        }
    }

    // ------------------------------------------------------------------------- Shizuku

    @Test
    fun `an unsupported Shizuku build is unavailable with its reason`() {
        val reason = "This build's Shizuku is too old to talk to."
        val rows = buildRows(SetupHealthInputs(wantsShizuku = true, shizukuUnavailableReason = reason))
        val row = rowFor(rows, SetupHealthItem.SHIZUKU)
        assertEquals(HealthState.Unavailable(reason), row.state)
        assertEquals(reason, row.detail)
    }

    @Test
    fun `an unavailable row offers no fix`() {
        val rows = buildRows(SetupHealthInputs(wantsShizuku = true, shizukuUnavailableReason = "no"))
        assertFalse(rowFor(rows, SetupHealthItem.SHIZUKU).canFix)
    }

    @Test
    fun `an unavailable Shizuku outranks wanting it`() {
        // A device fact beats an intention: a user whose build cannot use Shizuku is not someone who forgot
        // to start it, and offering them a fix would be sending them nowhere.
        val rows = buildRows(
            SetupHealthInputs(wantsShizuku = true, isShizukuUsable = false, shizukuUnavailableReason = "no"),
        )
        assertTrue(rowFor(rows, SetupHealthItem.SHIZUKU).state is HealthState.Unavailable)
    }

    @Test
    fun `a ready row offers no fix`() {
        val rows = buildRows(SetupHealthInputs(wantsShizuku = true, isShizukuUsable = true))
        assertFalse(rowFor(rows, SetupHealthItem.SHIZUKU).canFix)
    }

    // ------------------------------------------------------------------- the ping host

    @Test
    fun `the latency probe off is skipped and says nothing connects out`() {
        val rows = buildRows(SetupHealthInputs(measureLatency = false, latencyHost = "1.1.1.1"))
        assertEquals(HealthState.Skipped, rowFor(rows, SetupHealthItem.PING_HOST).state)
    }

    @Test
    fun `the latency probe on with a host is ready and names it`() {
        val rows = buildRows(SetupHealthInputs(measureLatency = true, latencyHost = "1.1.1.1"))
        val row = rowFor(rows, SetupHealthItem.PING_HOST)
        assertEquals(HealthState.Ready, row.state)
        assertTrue(row.detail.contains("1.1.1.1"))
    }

    @Test
    fun `the latency probe on with no host is not set up`() {
        val rows = buildRows(SetupHealthInputs(measureLatency = true, latencyHost = "   "))
        assertEquals(HealthState.NotSetUp, rowFor(rows, SetupHealthItem.PING_HOST).state)
    }

    // ---------------------------------------------------------------- the first profile

    @Test
    fun `no profiles is not set up and never skipped`() {
        val rows = buildRows(SetupHealthInputs(profileCount = 0))
        assertEquals(HealthState.NotSetUp, rowFor(rows, SetupHealthItem.FIRST_PROFILE).state)
    }

    @Test
    fun `one profile is ready and counted`() {
        val rows = buildRows(SetupHealthInputs(profileCount = 1))
        val row = rowFor(rows, SetupHealthItem.FIRST_PROFILE)
        assertEquals(HealthState.Ready, row.state)
        assertTrue(row.detail.contains("1 profile"))
    }

    @Test
    fun `several profiles read as plural`() {
        val rows = buildRows(SetupHealthInputs(profileCount = 3))
        assertTrue(rowFor(rows, SetupHealthItem.FIRST_PROFILE).detail.contains("3 profiles"))
    }

    // ---------------------------------------------------------------------- the summary

    @Test
    fun `the count reads N of M ready`() {
        val summary = HealthSummary(ready = 2, total = 5)
        assertEquals("2 of 5 ready", summary.label)
    }

    @Test
    fun `unavailable rows are excluded from the total`() {
        val withReason = summarise(buildRows(SetupHealthInputs(shizukuUnavailableReason = "no")))
        val withoutReason = summarise(buildRows(SetupHealthInputs()))
        assertEquals(withoutReason.total - 1, withReason.total)
    }

    @Test
    fun `skipped rows stay in the total`() {
        // A user who turned three things off is genuinely not at "all ready", and the count has to agree
        // with the list under it.
        val summary = summarise(buildRows(SetupHealthInputs()))
        assertEquals(SetupHealthItem.entries.size, summary.total)
    }

    @Test
    fun `only ready rows are counted ready`() {
        val rows = buildRows(
            SetupHealthInputs(
                wantsOverlay = true,
                hasOverlay = true,
                wantsUsageAccess = true,
                hasUsageAccess = false,
                profileCount = 1,
            ),
        )
        // Overlay and the profile are ready; everything else is skipped or not set up.
        assertEquals(2, summarise(rows).ready)
    }

    // ------------------------------------------------------------------------ the order

    @Test
    fun `what needs doing comes first`() {
        val rows = orderedRows(
            buildRows(
                SetupHealthInputs(
                    wantsNotifications = true,
                    hasNotifications = false,
                    profileCount = 1,
                ),
            ),
        )
        assertEquals(SetupHealthItem.NOTIFICATIONS, rows.first().item)
    }

    @Test
    fun `the order within a state follows the declared order`() {
        val rows = orderedRows(
            buildRows(
                SetupHealthInputs(
                    wantsOverlay = true,
                    hasOverlay = false,
                    wantsUsageAccess = true,
                    hasUsageAccess = false,
                ),
            ),
        )
        val outstanding = rows.filter { it.state == HealthState.NotSetUp }.map { it.item }
        assertEquals(
            listOf(SetupHealthItem.OVERLAY, SetupHealthItem.USAGE_ACCESS, SetupHealthItem.FIRST_PROFILE),
            outstanding,
        )
    }

    @Test
    fun `unavailable rows sink to the bottom`() {
        val rows = orderedRows(
            buildRows(SetupHealthInputs(profileCount = 1, shizukuUnavailableReason = "no")),
        )
        assertEquals(SetupHealthItem.SHIZUKU, rows.last().item)
    }

    @Test
    fun `ordering keeps every row`() {
        val rows = buildRows(SetupHealthInputs(wantsOverlay = true, shizukuUnavailableReason = "no"))
        assertEquals(rows.size, orderedRows(rows).size)
        assertEquals(rows.toSet(), orderedRows(rows).toSet())
    }

    // ------------------------------------------------------------ the home card's rule

    @Test
    fun `the card appears when a wanted permission is missing`() {
        val rows = buildRows(SetupHealthInputs(wantsOverlay = true, hasOverlay = false, profileCount = 1))
        assertTrue(needsAttention(rows))
    }

    @Test
    fun `the card stays away when everything wanted is granted`() {
        val rows = buildRows(
            SetupHealthInputs(
                wantsOverlay = true,
                hasOverlay = true,
                wantsUsageAccess = true,
                hasUsageAccess = true,
                measureLatency = true,
                latencyHost = "1.1.1.1",
                profileCount = 1,
            ),
        )
        assertFalse(needsAttention(rows))
    }

    @Test
    fun `a skipped optional item never brings the card back`() {
        // §A3's "skipped optional items never nag", as an assertion. Every permission is missing here and
        // none of them is wanted, so there is nothing to say.
        val rows = buildRows(SetupHealthInputs(profileCount = 1))
        assertFalse(needsAttention(rows))
    }

    @Test
    fun `an unavailable item never brings the card back`() {
        val rows = buildRows(
            SetupHealthInputs(
                wantsShizuku = true,
                shizukuUnavailableReason = "This build's Shizuku is too old to talk to.",
                profileCount = 1,
            ),
        )
        assertFalse(needsAttention(rows))
    }

    @Test
    fun `the card line counts what is outstanding`() {
        assertEquals(
            "One thing you have turned on is not set up yet.",
            attentionLine(buildRows(SetupHealthInputs(wantsOverlay = true, profileCount = 1))),
        )
        assertEquals(
            "2 things you have turned on are not set up yet.",
            attentionLine(
                buildRows(SetupHealthInputs(wantsOverlay = true, wantsUsageAccess = true, profileCount = 1)),
            ),
        )
        assertEquals(
            "Everything you have turned on is set up.",
            attentionLine(buildRows(SetupHealthInputs(profileCount = 1))),
        )
    }

    // ------------------------------------------------------------------------- the state

    @Test
    fun `the screen state agrees with the rule it displays`() {
        val rows = orderedRows(buildRows(SetupHealthInputs(wantsOverlay = true, profileCount = 1)))
        val state = SetupHealthUiState(isLoaded = true, rows = rows, summary = summarise(rows))
        assertTrue(state.hasOutstanding)
        assertEquals(needsAttention(rows), state.hasOutstanding)
    }

    // ---------------------------------------------------------------------------- helper

    private fun rowFor(rows: List<SetupHealthRow>, item: SetupHealthItem): SetupHealthRow =
        rows.first { it.item == item }
}
