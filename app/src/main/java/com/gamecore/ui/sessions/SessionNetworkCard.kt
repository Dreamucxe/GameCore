package com.gamecore.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.domain.diagnostics.NetworkAttribution
import com.gamecore.domain.diagnostics.NetworkReport
import com.gamecore.domain.diagnostics.NetworkVerdict
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * What the session's two network lanes say about its frame-time spikes — and what they cannot say.
 *
 * The card exists to keep one claim out of the report: that a spike was "the game server". GameCore
 * never sees a game's traffic, has no address for it and opens no socket to it, so it measures the
 * two things it can — the link to the router, and a known host beyond it — and compares their
 * *timing* with the spikes. The wording follows from that: `correlated`, `at the same moment`,
 * `beyond this device`. Never `caused by`.
 *
 * Three states, each drawn differently:
 *
 *  - **Nothing to compare.** No spikes, or no probes. A banner says which of the two is missing and
 *    why the other one cannot answer. This is the common case for sessions recorded with the network
 *    check switched off, and it is deliberately not drawn as a clean bill of health.
 *  - **Spikes, with network evidence.** The verdict, the counts it rests on, and one sentence naming
 *    which lane answered.
 *  - **Spikes, without network evidence.** The card says the network is *not supported* as an
 *    explanation, and stops there. The device-side verdict belongs to the attribution card above and
 *    this one does not second-guess it.
 */
@Composable
fun SessionNetworkCard(
    detail: SessionReportDetail,
    modifier: Modifier = Modifier,
) {
    val report = remember(detail) { NetworkAttribution.attribute(detail.samples, detail.events) }

    if (!report.hasStutters || !report.hasProbes) {
        NoteBanner(
            text = NetworkAttribution.explainsAbsence(detail.samples, detail.events)
                ?: "There is no network record to compare this session's spikes against.",
            tone = Tone.Muted,
            modifier = modifier,
        )
        return
    }

    PlainCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Network",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = verdictLabel(report.verdict),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = verdictTone(report.verdict).colour(),
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = verdictSentence(report),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            RowDivider(modifier = Modifier.padding(vertical = 10.dp))

            EvidenceRow(
                label = "Frame-time spikes",
                value = report.stutterCount.toString(),
            )
            EvidenceRow(
                label = "At the same moment as a network spike",
                value = "${report.correlatedStutters} of ${report.stutterCount}",
            )
            EvidenceRow(
                label = "Router lane spikes",
                value = if (report.hasLocalLane) report.localSpikes.toString() else "not recorded",
            )
            EvidenceRow(
                label = "Beyond-device lane spikes",
                value = if (report.hasUpstreamLane) report.upstreamSpikes.toString() else "not recorded",
            )

            Text(
                text = "Latency is a TCP handshake — to the router and to a known host — not a ping, " +
                    "which an app without root cannot send. The beyond-device lane says the path " +
                    "answered slowly; it says nothing about a game's own server, which GameCore never " +
                    "contacts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun EvidenceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
}

private fun verdictLabel(verdict: NetworkVerdict): String = when (verdict) {
    NetworkVerdict.UPSTREAM -> "Beyond this device, at the same moment"
    NetworkVerdict.LOCAL_LINK -> "Between this device and the router"
    NetworkVerdict.NETWORK_CORRELATED -> "Network — which hop is not localisable"
    NetworkVerdict.UNKNOWN -> "The network record does not explain these spikes"
}

private fun verdictTone(verdict: NetworkVerdict): Tone = when (verdict) {
    NetworkVerdict.UPSTREAM, NetworkVerdict.LOCAL_LINK, NetworkVerdict.NETWORK_CORRELATED -> Tone.Warning
    NetworkVerdict.UNKNOWN -> Tone.Muted
}

/**
 * The one sentence under the verdict.
 *
 * Each names what lined up, how many spikes did, and then stops. The last clause in every case is the
 * limit of the evidence rather than a hedge: "at the same moment" is the strongest thing a timing
 * comparison can say.
 */
private fun verdictSentence(report: NetworkReport): String = when (report.verdict) {
    NetworkVerdict.UPSTREAM ->
        "${report.correlatedStutters} of ${report.stutterCount} frame-time spike(s) happened at the " +
            "same moment as a latency spike on the path beyond this device, while the router lane " +
            "stayed quiet. That names where the time went, not which server: the probe is a known " +
            "host, not the game's."
    NetworkVerdict.LOCAL_LINK ->
        "${report.correlatedStutters} of ${report.stutterCount} frame-time spike(s) happened at the " +
            "same moment as a latency spike between this device and the router. The link beyond the " +
            "router may have been fine; this record only measured the first hop."
    NetworkVerdict.NETWORK_CORRELATED ->
        "${report.correlatedStutters} of ${report.stutterCount} frame-time spike(s) happened at the " +
            "same moment as latency spikes on both lanes. Both being slow at once means the network " +
            "is the supported explanation, but not which hop caused it."
    NetworkVerdict.UNKNOWN ->
        "${report.stutterCount} frame-time spike(s) were recorded, and none of them coincided with a " +
            "network spike on either lane. The network is not supported as an explanation for them. " +
            "That is not proof the device caused them — only that this record does not show the " +
            "network doing it."
}
