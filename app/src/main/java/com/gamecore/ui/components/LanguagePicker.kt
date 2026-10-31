package com.gamecore.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gamecore.core.i18n.AppLanguage
import com.gamecore.core.i18n.LocalStrings
import com.gamecore.ui.theme.Spacing

/**
 * The language chooser, shared by Setup and Settings.
 *
 * A dialog rather than a dropdown because there are more than fifty languages: a menu that long is a
 * scroll with no way to search it. The search field matches the native name, the English name and the
 * tag, so someone who knows the language as "Deutsch", "German" or "de" all find it.
 *
 * "Follow system" is the first row and the default. It is not a language: it means the device decides,
 * and the subtitle says what that resolves to right now, which is the fact a user actually needs before
 * choosing it. Selecting a row applies immediately — the whole app is already listening to the setting —
 * and closes; there is no separate Save, matching every other single-choice row in GameCore.
 */
@Composable
fun LanguagePickerDialog(
    selectedTag: String,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }

    val languages = remember(query) {
        val wanted = query.trim()
        if (wanted.isEmpty()) {
            AppLanguage.entries
        } else {
            AppLanguage.entries.filter { language ->
                language.nativeName.contains(wanted, ignoreCase = true) ||
                    language.englishName.contains(wanted, ignoreCase = true) ||
                    language.tag.contains(wanted, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.languageChoose) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(strings.languageSearch) },
                )
                if (languages.isEmpty()) {
                    Text(
                        text = strings.languageNoResults,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.md),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .selectableGroup(),
                    ) {
                        items(languages, key = { it.tag.ifEmpty { "system" } }) { language ->
                            val selected = if (language == AppLanguage.SYSTEM) {
                                selectedTag.isBlank()
                            } else {
                                language.tag.equals(selectedTag, ignoreCase = true)
                            }
                            LanguageRow(
                                language = language,
                                selected = selected,
                                onSelect = { onSelect(language) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        },
    )
}

@Composable
private fun LanguageRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val strings = LocalStrings.current
    val title = if (language == AppLanguage.SYSTEM) strings.languageSystem else language.nativeName
    val subtitle = if (language == AppLanguage.SYSTEM) {
        strings.languageSystemDescription
    } else {
        language.englishName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.sm),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one settings row that names the current language and opens the chooser.
 *
 * Kept as a row rather than a card of its own so it can sit in Appearance beside the other things that
 * change how the app looks. The trailing value is the native name, matching the picker: a user who
 * picked "日本語" sees "日本語" on the row, not "Japanese".
 */
@Composable
fun LanguageSettingRow(
    selectedTag: String,
    onOpen: () -> Unit,
) {
    val strings = LocalStrings.current
    val current = AppLanguage.forTag(selectedTag)
    val value = current?.takeIf { it.isExplicit }?.nativeName ?: strings.languageSystem
    NavRow(
        title = strings.settingsLanguage,
        onClick = onOpen,
        icon = androidx.compose.material.icons.Icons.Filled.Translate,
        description = strings.settingsLanguageDescription,
        trailing = value,
    )
}
