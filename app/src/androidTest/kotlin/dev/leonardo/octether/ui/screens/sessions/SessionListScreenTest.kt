package dev.leonardo.octether.ui.screens.sessions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * L2: Tests for SearchBar and Archive FilterChip added to SessionListScreen.
 * These components are rendered inline in SessionListScreen; here we test them
 * in isolation with the same configuration to verify rendering and interaction.
 */
class SessionListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── SearchBar ──────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TestSearchBar(
        initialQuery: String = "",
        onQueryChange: (String) -> Unit = {},
        onClearClick: () -> Unit = {},
    ) {
        var query by remember { mutableStateOf(initialQuery) }
        SearchBar(
            query = query,
            onQueryChange = {
                query = it
                onQueryChange(it)
            },
            onSearch = {},
            active = false,
            onActiveChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search sessions...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        onClearClick()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            colors = SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {}
    }

    @Test
    fun searchBar_displaysPlaceholderText() {
        composeTestRule.setContent { TestSearchBar() }
        composeTestRule.onNodeWithText("Search sessions...").assertIsDisplayed()
    }

    @Test
    fun searchBar_displaysSearchIcon() {
        composeTestRule.setContent { TestSearchBar() }
        composeTestRule.onNodeWithContentDescription("Search")
            .assertIsDisplayed()
    }

    @Test
    fun searchBar_inputUpdatesQuery() {
        var receivedQuery = ""
        composeTestRule.setContent {
            TestSearchBar(onQueryChange = { receivedQuery = it })
        }

        composeTestRule.onNodeWithText("Search sessions...")
            .performTextInput("my session")
        composeTestRule.waitUntil { receivedQuery == "my session" }
    }

    // ── Archive FilterChip ──────────────────────────────────────────────

    @Composable
    private fun TestArchiveFilterChip(
        selected: Boolean = false,
        onClick: () -> Unit = {},
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text("Archived") },
            leadingIcon = {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        )
    }

    @Test
    fun archiveChip_isDisplayed() {
        composeTestRule.setContent { TestArchiveFilterChip() }
        composeTestRule.onNodeWithText("Archived").assertIsDisplayed()
    }

    @Test
    fun archiveChip_togglesOnClick() {
        var selected = false
        composeTestRule.setContent {
            TestArchiveFilterChip(selected = selected, onClick = { selected = !selected })
        }

        // Initially unselected
        composeTestRule.onNodeWithText("Archived").assertIsDisplayed()

        // Click to select
        composeTestRule.onNodeWithText("Archived").performClick()
        composeTestRule.waitUntil { selected }
    }
}
