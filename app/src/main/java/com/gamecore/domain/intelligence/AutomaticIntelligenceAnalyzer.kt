package com.gamecore.domain.intelligence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gamecore.MainActivity
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.IntelligenceAnalysisFrequency
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.SmartIntelligenceRepository
import com.gamecore.ui.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the optional game-launch analysis promised by Smart Settings.
 *
 * It adds no app watcher and no scheduler: [onGameStarted] is called by the existing configured-game
 * detector. A due run takes one device snapshot, evaluates local rules, optionally records that the
 * recommendations were generated, and posts a notification only when the user enabled it. It never
 * changes a profile or device setting; automatic application remains limited to profiles the user
 * already approved.
 */
@Singleton
class AutomaticIntelligenceAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SmartIntelligenceRepository,
    private val preferences: SecurePreferenceStore,
) {
    private val mutex = Mutex()

    suspend fun onGameStarted(
        packageName: String,
        gameLabel: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        // Re-read under the lock. Two quick launch transitions can queue here, and the first
        // successful analysis advances the timestamp before the second reaches this check.
        val settings = preferences.settings.value
        val frequency = settings.intelligenceAnalysisFrequency
        if (!intelligenceAnalysisIsDue(
                frequency,
                settings.lastIntelligenceAnalysisAtMillis,
                nowMillis,
            )
        ) {
            return@withLock
        }

        val scan = repository.scanDevice()
        val recommendations = repository.recommendationsFor(
            scan = scan,
            goal = settings.optimizationGoal,
            gamePackage = packageName,
            gameLabel = gameLabel,
            learningEnabled = settings.smartLearningEnabled,
        )
        if (settings.smartLearningEnabled) {
            repository.recordGenerated(recommendations, settings.optimizationGoal, nowMillis)
        }

        // Advance only after a complete scan and rule pass. A source failure leaves the next launch
        // eligible rather than making "daily" mean "try again tomorrow".
        preferences.updateSettings {
            it.copy(lastIntelligenceAnalysisAtMillis = nowMillis)
        }

        val actionable = recommendations.filter { it.classification.isUsable }
        if (settings.intelligenceNotificationsEnabled && actionable.isNotEmpty()) {
            postNotification(gameLabel, actionable.map { it.title })
        }
    }

    private fun postNotification(gameLabel: String, recommendationTitles: List<String>) {
        if (!NotificationChannels.areEnabled(context)) return
        val count = recommendationTitles.size
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, Destination.Intelligence.EXTERNAL)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            NotificationChannels.ID_INTELLIGENCE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.INTELLIGENCE)
            .setSmallIcon(R.drawable.ic_stat_gamecore)
            .setContentTitle(
                if (count == 1) "Performance recommendation for $gameLabel"
                else "$count performance recommendations for $gameLabel",
            )
            .setContentText(recommendationTitles.first())
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    recommendationTitles.joinToString(separator = "\n") { "• $it" },
                ),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NotificationChannels.ID_INTELLIGENCE, notification)
        }
    }
}

/** Pure cadence rule, separate from Android and persistence so it can be pinned by JVM tests. */
internal fun intelligenceAnalysisIsDue(
    frequency: IntelligenceAnalysisFrequency,
    lastAtMillis: Long,
    nowMillis: Long,
): Boolean {
    val interval = frequency.minimumIntervalMillis ?: return false
    if (lastAtMillis <= 0L) return true
    // A clock restored backwards must not suppress analysis indefinitely.
    if (nowMillis < lastAtMillis) return true
    return nowMillis - lastAtMillis >= interval
}
