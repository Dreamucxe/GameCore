package com.gamecore.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Formatters
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.energy.EnergyBudget
import com.gamecore.domain.energy.EnergyEstimate
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.Tone
import kotlinx.coroutines.flow.first

/**
 * The per-game energy card: median drain from recorded sessions, with a projection if the caller
 * supplies the current battery level.
 *
 * The engine lives in [EnergyBudget] and is pure JVM; this composable only formats the result and
 * handles the honest empty states. It does not invent milliamp-hours, design capacity, or a fake
 * number — it prints exactly what the engine returns, and when the engine refuses it prints the
 * reason rather than a dash.
 */
@Composable
fun GameEnergyCard(
    state: ProfileEditorUiState,
    modifier: Modifier = Modifier,
) {
    // The estimate is computed in the ViewModel (see ProfileEditorViewModel.refreshEnergyEstimate)
    // and read from state here. Nothing is drawn until it has been read, so the screen does not
    // flash a "not enough data" card at a game whose history is simply still loading.
    val estimate = state.energyEstimate ?: return

    PlainCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Energy budget",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Estimate from recorded sessions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (estimate) {
                is EnergyEstimate.HasData -> {
                    val data = estimate
                    val rate = data.medianPercentPerHour
                    val perHour = Formatters.percentValue(rate, decimals = 1)

                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Median drain",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "$perHour / hour",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = "Based on ${Formatters.count(data.usableSessions, "session")} " +
                                "(${Formatters.durationCoarse(data.totalMeasuredMinutes * 60_000L)} measured). " +
                                "Charging sessions excluded; profiles share the same game history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        val minutes = data.projectedMinutes
                        if (minutes != null) {
                            RowDivider(modifier = Modifier.padding(vertical = 8.dp))
                            val hours = minutes / 60L
                            val mins = minutes % 60L
                            val projection = if (hours > 0) {
                                "${hours}h ${mins}m"
                            } else {
                                "${mins}m"
                            }
                            Text(
                                text = "Projected playtime at this battery level",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = projection,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = "Estimated from the median rate and the current battery level. " +
                                    "Actual time depends on the game, the profile, and what else is running.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            RowDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "No current battery level available, so no playtime projection " +
                                    "is shown. The median drain rate above is still a measurement.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                is EnergyEstimate.NotEnoughData -> {
                    NoteBanner(
                        text = "Not enough data yet: ${estimate.reason}",
                        tone = Tone.Muted,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                EnergyEstimate.NoSessions -> {
                    NoteBanner(
                        text = "No sessions recorded for this game yet. Play a game with this profile " +
                            "and the estimate will appear here.",
                        tone = Tone.Muted,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Updates the card's estimate from the session repository and an optional current battery level.
 *
 * This is a suspend function so the caller can launch it in a viewModelScope. It does not observe —
 * the caller decides when to re-read (e.g. on screen open, on battery change, or on a manual refresh).
 * The project already has a battery reader; if that is not available here the caller can pass null.
 */
suspend fun updateEnergyEstimate(
    sessions: SessionRepository,
    packageName: String,
    currentPercent: Int?,
    onResult: (EnergyEstimate) -> Unit,
) {
    val list = sessions.sessionsFor(packageName).first()
    val finished = list.filter { it.endedAtMillis != null }
    val result = EnergyBudget.estimate(finished, currentPercent)
    onResult(result)
}