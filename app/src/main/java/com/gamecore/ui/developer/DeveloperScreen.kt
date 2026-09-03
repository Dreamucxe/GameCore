package com.gamecore.ui.developer

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gamecore.BuildConfig
import com.gamecore.R
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * Who wrote GameCore, and where to find them.
 *
 * The last row of Settings opens this, and it is the only screen in the app whose whole job is to leave
 * it. There is nothing to load: no avatar is fetched, no repository is counted and no profile is read,
 * because the app has no network access to spend on a screen like this and the address is the whole of
 * what it has to offer. The one thing it says about itself, the version, comes from the build rather than
 * from a literal, so a release cannot ship a screen naming a different one than the APK it is in.
 *
 * The links go out through [startIntentSafely], and a device with nothing willing to open an `https`
 * address is a real device — a work profile with the browser stripped, a TV build, a ROM with the browser
 * removed. §28 does not allow that to be a crash and §32 does not allow it to be a tap that appears to do
 * nothing, so the banner names the address Android had no app for.
 */
@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)
    var failed by remember { mutableStateOf<String?>(null) }

    // Cleared on a link that does open, so the banner describes this device now rather than the last
    // address that failed on it.
    val open: (String, String) -> Unit = { name, url ->
        failed = if (context.startIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            null
        } else {
            "Nothing on this device offered to open $url. That is normally a browser, or $name's own app."
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Developer",
                subtitle = "Who made GameCore, and where to reach them",
                onBack = onBack,
            )
        }

        failed?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = { failed = null }) { Text("OK") } },
                )
            }
        }

        item { IdentityCard(modifier = padded) }

        item { LinksCard(onOpen = open, modifier = padded) }
    }
}

/**
 * The app, named and versioned, beside the handle that built it.
 *
 * The mark is the launcher's monochrome layer rather than its foreground: that layer exists to be tinted
 * by something else, so it is the one drawable in the set that takes the user's accent colour cleanly
 * instead of carrying the launcher's own background into a card.
 */
@Composable
private fun IdentityCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(46.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Built by ${DeveloperInfo.HANDLE}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The three places the developer is, each described by what is actually at the other end of it. */
@Composable
private fun LinksCard(onOpen: (String, String) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Links",
        subtitle = "These leave GameCore and open in whatever app handles the address",
        icon = Icons.Filled.Link,
        modifier = modifier,
    ) {
        LinkRow(
            title = "GitHub",
            description = "This app's repository, and the developer's other projects.",
            icon = R.drawable.ic_brand_github,
            onClick = { onOpen("GitHub", DeveloperInfo.GITHUB) },
        )
        LinkRow(
            title = "Discord",
            description = "An invite to the server, for questions, bug reports and what is being built.",
            icon = R.drawable.ic_brand_discord,
            onClick = { onOpen("Discord", DeveloperInfo.DISCORD) },
        )
        LinkRow(
            title = "LinkedIn",
            description = "The developer's professional profile.",
            icon = R.drawable.ic_brand_linkedin,
            onClick = { onOpen("LinkedIn", DeveloperInfo.LINKEDIN) },
        )
    }
}

/**
 * A row that leaves the app.
 *
 * [com.gamecore.ui.components.NavRow]'s shape deliberately, so a link reads like every other row on a
 * settings screen — but ending in an "open in new" glyph rather than that component's chevron. The chevron
 * is this app's mark for another GameCore screen, and three of them here would promise a profile page
 * inside the app rather than a handover to a browser.
 *
 * The mark on the left is the platform's own. It is decorative and carries no description: the title
 * beside it already says which platform, so a screen reader announcing the logo as well would read the
 * name twice.
 */
@Composable
private fun LinkRow(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Where the developer is, in one place.
 *
 * Three literals used on one screen would ordinarily just live at that screen's three call sites. These do
 * not, because an address is the one kind of string in this app that is wrong in a way nobody notices: a
 * mistyped handle compiles, renders, opens a browser and lands on a stranger's profile or on a "not found"
 * page that reads as the app being broken. Kept together they can be checked together, which
 * `DeveloperInfoTest` does.
 */
internal object DeveloperInfo {

    /** The developer, as they are known on all three. */
    const val HANDLE = "Dreamucxe"

    const val GITHUB = "https://github.com/Dreamucxe"

    /** An invite rather than a channel: a server the user has not joined has no channel for them to open. */
    const val DISCORD = "https://discord.gg/xgyk8ZtB"

    const val LINKEDIN = "https://linkedin.com/in/dream-ucxe-4a1a1440b"

    /** Every address the screen offers, for the test that checks their shape. */
    val all: List<String> = listOf(GITHUB, DISCORD, LINKEDIN)
}
