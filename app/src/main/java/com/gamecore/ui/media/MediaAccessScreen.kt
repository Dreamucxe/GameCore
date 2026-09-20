package com.gamecore.ui.media

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.permissions.GamePermission
import com.gamecore.domain.media.NowPlaying
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SecureWindow
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.readoutOf
import com.gamecore.ui.components.startIntentSafely

/**
 * The screen the overlay panel's media strip sends the user to, and the argument it has to make.
 *
 * A dedicated screen rather than a line in the permissions centre, because this is the one access in
 * GameCore whose Android-facing name is wider than its use. The switch the user is about to throw is
 * called notification access and Android's own dialog will tell them, correctly, that GameCore could read
 * every notification on the device. Nothing an app writes can narrow that switch — `getActiveSessions`
 * hands the media session list only to an app that holds it, and there is no lesser version to ask for.
 *
 * So the page does the only honest thing available: it states the capability the user is granting, shows
 * what GameCore actually does with it as live values rather than as a promise, and lists what it never
 * does. The order is deliberate — the warning is above the button, not below it.
 *
 * [SecureWindow] for the same reason [com.gamecore.ui.shizuku.ShizukuScreen] has it: the card in the
 * middle of this page is the title and artist of whatever the user is playing, which is theirs and not
 * something to leave in a screen recording.
 */
@Composable
fun MediaAccessScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaAccessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SecureWindow()
    // The switch is thrown in Settings, which tells GameCore nothing. This is the moment it can ask.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val launch: (Intent?) -> Unit = { intent ->
        if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Media controls",
                subtitle = state.summary,
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

        item { AccessCard(state = state, onOpen = { launch(viewModel.settingsIntent()) }, modifier = padded) }

        // Above the button in reading order, and only while the button is the one that turns it on. A
        // user who has already granted it has met Android's dialog and does not need it quoted back.
        if (state.isLoaded && !state.hasAccess) {
            item {
                NoteBanner(
                    text = SYSTEM_DIALOG_WARNING,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Shield,
                    modifier = padded,
                )
            }
        }

        item { ReadsCard(state = state, modifier = padded) }
        item { LimitsCard(modifier = padded) }

        item {
            SectionCard(title = "Everything else", icon = Icons.Filled.Info, modifier = padded) {
                Text(
                    text = OTHER_ACCESS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowDivider()
                NavRow(
                    title = "Permissions",
                    onClick = { onNavigate(Destination.Permissions) },
                    description = "What each one is for, and the Settings page that grants it",
                )
            }
        }
    }
}

/**
 * The switch, why it is the one Android offers, and the way to it.
 *
 * The wording comes from [GamePermission.NOTIFICATION_LISTENER] rather than from a constant in this file,
 * because the permissions centre shows the same two sentences and a second copy is a second thing to keep
 * in step — the reason [com.gamecore.ui.permissions.PermissionRow] carries the catalogue entry instead of
 * its text.
 *
 * The button is offered in both states. Every other screen in the app hides it once an access is granted,
 * which is right for a list of eight; this page is about one switch, and a user who came here to turn it
 * off should not have to go and find it in Settings themselves.
 */
@Composable
private fun AccessCard(
    state: MediaAccessUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission = GamePermission.NOTIFICATION_LISTENER
    SectionCard(
        title = permission.title,
        subtitle = permission.kind.label,
        icon = Icons.Filled.MusicNote,
        modifier = modifier,
        action = {
            when {
                !state.isLoaded -> StatusChip("Checking", Tone.Muted)
                state.hasAccess -> StatusChip("Granted", Tone.Good)
                else -> StatusChip("Not granted", Tone.Warning)
            }
        },
    ) {
        Text(
            text = permission.why,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        Text(
            text = if (state.hasAccess) GRANTED_NOTE else permission.whatBreaks,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.hasAccess) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Tone.Warning.colour()
            },
        )
        ActionRow {
            TextButton(onClick = onOpen) {
                Text(if (state.hasAccess) "Open the setting" else "Turn it on in Settings")
            }
        }
    }
}

/**
 * Everything GameCore takes from the access, as this device's current values.
 *
 * Four rows, and the claim the page is making is that this list is exhaustive — so it is rendered from
 * the same [NowPlaying] the overlay strip draws from, not from prose. A user who wants to check whether
 * an app is being read about can start it playing and watch this card, which is a stronger form of
 * "nothing else is collected" than any sentence would be.
 *
 * The three non-track states are shown as themselves rather than as empty rows. "Nothing playing" is a
 * fact about the device; a card of dashes would read as a failure.
 */
@Composable
private fun ReadsCard(state: MediaAccessUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What it reads",
        subtitle = "Live, from the session GameCore would be controlling",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
    ) {
        when {
            !state.hasAccess -> Text(
                text = READS_WITHOUT_ACCESS,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> when (val playing = state.nowPlaying) {
                null -> ReadsNote(CHECKING)
                NowPlaying.NeedsAccess -> ReadsNote(REVOKED)
                NowPlaying.Silent -> ReadsNote(NOTHING_PLAYING)
                is NowPlaying.Unavailable -> ReadsNote(playing.reason)
                is NowPlaying.Track -> TrackRows(playing)
            }
        }
        RowDivider()
        Text(
            text = READS_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The four fields, with [ABSENT] where the app publishing the session left one out.
 *
 * The artwork is described rather than drawn. It is a fifth thing GameCore receives and the card would be
 * incomplete without saying so, but this page is a disclosure and not a second media player — the strip
 * in the overlay is where the picture belongs.
 */
@Composable
private fun TrackRows(track: NowPlaying.Track) {
    ReadoutRow(readoutOf(label = "Title", value = track.title))
    ReadoutRow(readoutOf(label = "Artist", value = track.artist ?: ABSENT))
    ReadoutRow(readoutOf(label = "App", value = track.appLabel ?: ABSENT))
    ReadoutRow(
        readoutOf(
            label = "State",
            value = if (track.isPlaying) "Playing" else "Paused",
            tone = if (track.isPlaying) Tone.Good else Tone.Muted,
        ),
    )
    ReadoutRow(
        readoutOf(
            label = "Artwork",
            value = if (track.art != null) "Provided" else ABSENT,
            detail = ART_DETAIL,
            tone = Tone.Muted,
        ),
    )
}

@Composable
private fun ReadsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the access is never used for, listed before anyone has to ask.
 *
 * Modelled on the Shizuku screen's card of the same name and for the same reason: this is the screen
 * where a user decides whether to hand GameCore a capability broader than the feature needs, and the list
 * is why it is safe to. Every line here is a statement about code that does not exist — `MediaAccessService`
 * overrides no callback at all, which is checkable by anyone reading the file.
 */
@Composable
private fun LimitsCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "What it is never used for",
        icon = Icons.Filled.Shield,
        modifier = modifier,
    ) {
        LIMITS.forEachIndexed { index, limit ->
            if (index > 0) RowDivider()
            Text(text = limit, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val SYSTEM_DIALOG_WARNING =
    "Android's confirmation will say GameCore can read all your notifications, including personal " +
        "information. That is the truth about the switch, and it is the same switch every media " +
        "controller on the Play Store uses — there is no narrower one to ask for. What GameCore does " +
        "with it is the card below, and what it never does is the card under that."

private const val GRANTED_NOTE =
    "The same Settings page turns it off again, and the panel's media strip goes back to a prompt rather " +
        "than breaking. Nothing else in GameCore changes either way."

private const val READS_WITHOUT_ACCESS =
    "Nothing yet. Until the switch is on, Android refuses the request and GameCore does not know whether " +
        "anything is playing — which is why the panel shows a prompt rather than an empty player."

private const val READS_FOOTNOTE =
    "Read each time it changes and held only while the panel is open. None of it is written to storage, " +
        "added to a session record, or sent anywhere. GameCore has no analytics and no server of its own; " +
        "the ads on its own screens are served by Google and are never given any of this."

private const val ART_DETAIL =
    "Drawn as a 34 dp thumbnail in the panel, scaled down in memory and dropped when the track changes."

private const val CHECKING = "Asking Android what is playing…"

private const val NOTHING_PLAYING =
    "Nothing is playing, so there is nothing to read. The panel says the same rather than showing the " +
        "last track it saw."

private const val REVOKED =
    "The access was switched off while this screen was open. Turn it back on above and this card fills " +
        "itself in again."

private val LIMITS = listOf(
    "No notifications are read. GameCore's listener service implements none of the callbacks that " +
        "deliver them and never calls `getActiveNotifications`, so a notification's text does not reach " +
        "this app even in memory.",
    "No history. What is playing is read live and replaced by the next reading; nothing about it is " +
        "written to the database, the session record or the logs.",
    "No app list. The access is not used to learn which apps you have or which one you are using — " +
        "game detection is a separate permission, and it is the one the Permissions screen explains.",
    "No favouritism. There is no list of supported players anywhere in the app: whichever app owns the " +
        "current session is the one the buttons talk to, and GameCore never compares a package name " +
        "against one it was built with.",
    "No control beyond three buttons. Previous, play/pause and next are forwarded to the app that owns " +
        "the session, which is free to ignore them — and the panel says so when it does.",
)

private const val OTHER_ACCESS =
    "This one is only for the media strip, and GameCore works without it. The permissions that matter " +
        "for the rest of the app — the overlay, notifications, game detection — are listed together with " +
        "what each one costs you if you decline it."
