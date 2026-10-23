package com.gamecore.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.ui.Destination
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard

/**
 * The Global Search screen.
 *
 * Material3 TextField with clear button, grouped results by [SearchCategory],
 * "no results" state, and recent/popular suggestions when empty.
 *
 * @param onOpen Callback with the route string to navigate to.
 * @param onBack Callback to dismiss the search screen (back navigation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Request focus on first composition so the keyboard appears. The request must be attached to a
    // node that is actually in the tree; SearchHeader puts `.focusRequester` on the field itself, so by
    // the time this effect runs there is something to focus.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val results = remember(query) {
        SearchIndex.search(query)
    }

    val showSuggestions = query.trim().isEmpty()

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        // Header with back arrow and search field
        SearchHeader(
            query = query,
            onQueryChange = { query = it },
            onClear = { query = "" },
            onBack = onBack,
            focusRequester = focusRequester,
            keyboardController = keyboardController,
            modifier = Modifier.fillMaxWidth(),
        )

        // Content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            if (showSuggestions) {
                SuggestionsContent(onOpen = onOpen, modifier = Modifier.fillMaxSize())
            } else if (results.isEmpty()) {
                NoResultsContent(query = query, modifier = Modifier.fillMaxSize())
            } else {
                ResultsContent(
                    results = results,
                    onOpen = onOpen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Search header: back arrow, text field with search icon and clear button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            androidx.compose.material3.TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() },
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear query",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                placeholder = {
                    Text(
                        text = "Search…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                visualTransformation = VisualTransformation.None,
            )
        }
    }
}

/**
 * Content shown when query is empty: recent/popular top-level destinations.
 */
@Composable
private fun SuggestionsContent(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = SearchIndex.recentOrPopular()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = ScreenBottomPadding),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                title = "Top Destinations",
                subtitle = "Jump straight to the main sections",
                icon = Icons.Filled.History,
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.forEach { entry ->
                    NavRow(
                        title = entry.title,
                        description = entry.description,
                        icon = iconForCategory(entry.category),
                        onClick = { onOpen(entry.route) },
                    )
                    if (entry != suggestions.last()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Categories",
                subtitle = "Browse by section",
                icon = Icons.Filled.ArrowDropDown,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SearchCategory.values().forEach { category ->
                    NavRow(
                        title = category.displayName,
                        description = "Open ${category.displayName.lowercase()} section",
                        icon = iconForCategory(category),
                        onClick = {
                            // Navigate to a representative route for this category
                            val representative = SearchIndex.entries.firstOrNull { it.category == category }
                            representative?.let { onOpen(it.route) }
                        },
                    )
                    if (category != SearchCategory.values().last()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/**
 * Content shown when query has no matches.
 */
@Composable
private fun NoResultsContent(
    query: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Search,
            title = "No results for \"$query\"",
            message = "Try a different keyword or check spelling.",
        )
    }
}

/**
 * Grouped results list.
 */
@Composable
private fun ResultsContent(
    results: List<SearchResult>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Group by category, preserving category order
    val grouped = results.groupBy { it.entry.category }
        .toList()
        .sortedBy { (category, _) -> SearchCategory.values().indexOf(category) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = ScreenBottomPadding),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        grouped.forEach { (category, categoryResults) ->
            item {
                SectionCard(
                    title = category.displayName,
                    subtitle = "${categoryResults.size} result${if (categoryResults.size != 1) "s" else ""}",
                    icon = iconForCategory(category),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    categoryResults.forEach { result ->
                        NavRow(
                            title = result.entry.title,
                            description = result.entry.description,
                            icon = iconForCategory(result.entry.category),
                            onClick = { onOpen(result.entry.route) },
                        )
                        if (result != categoryResults.last()) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Icon for a search category. */
private fun iconForCategory(category: SearchCategory): ImageVector = when (category) {
    SearchCategory.HOME -> Icons.Filled.Home
    SearchCategory.TRAINING -> Icons.Filled.SportsEsports
    SearchCategory.PERFORMANCE -> Icons.Filled.Memory
    SearchCategory.DISPLAY -> Icons.Filled.Palette
    SearchCategory.PLUGINS -> Icons.Filled.Tune
    SearchCategory.SETTINGS -> Icons.Filled.Settings
    SearchCategory.DATA -> Icons.Filled.Storage
    SearchCategory.HELP -> Icons.Filled.FitnessCenter
}
