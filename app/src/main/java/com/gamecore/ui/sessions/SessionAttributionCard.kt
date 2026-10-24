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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.domain.diagnostics.StutterAttribution
import com.gamecore.domain.diagnostics.StutterFinding
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.Tone

/**
 * What the frame-time spikes can honestly be attributed to.
 *
 * This answers the question a player actually asks after a bad session: was that me, the device, or
 * the network? It answers it only from what the session recorded. [SessionConditionTracker] decides
 * when a spike is worth one row, [StutterAttribution] decides what it might mean; this composable
 * decides neither. It draws the report it was handed, and when the device could not measure frame
 * timing at all it says exactly that rather than drawing an empty section that reads as "no stutter".
 */
@Composable
fun SessionAttributionCard(
    detail: SessionReportDetail,
    modifier: Modifier = Modifier,
) {
    val report = remember(detail) { StutterAttribution.attribute(detail.samples, detail.events) }

    if (!report.hasOutliers) {
        NoteBanner(
            text = StutterAttribution.explainsAbsence(detail.samples, detail.events)
                ?: "No frame-time spikes were recorded for this session.",
            tone = Tone.Muted,
            modifier = modifier,
        )
        return
    }

    PlainCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Frame-time spikes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "What the record says happened around them. Correlations are named as " +
                    "correlations, and a spike nothing explains is shown as unexplained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            report.findings.forEachIndexed { index, finding ->
                if (index > 0) RowDivider()
                FindingRow(finding = finding)
            }
        }
    }
}

@Composable
private fun FindingRow(finding: StutterFinding, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = elapsed(finding.elapsedMillis),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = finding.frameDurationMillis?.let { "p99 ${it.toInt()} ms" }
                    ?: finding.detail
                    ?: "Frame-time spike",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (finding.isUnexplained) {
            Text(
                text = "No recorded condition explains this one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            finding.causes.forEach { cause ->
                Text(
                    text = "• ${cause.kind.label} · ${cause.confidence.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = cause.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Offsets into a session, never clock times: mm:ss under an hour, h:mm:ss above it. */
private fun elapsed(millis: Long): String {
    val total = millis.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
