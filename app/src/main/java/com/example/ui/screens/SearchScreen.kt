package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Topic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.UiResult
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.TopicListItem
import com.example.ui.components.WebAuthDialog
import com.example.ui.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenTopic: (slugOrQuery: String) -> Unit,
    onOpenAuthor: (authorSlug: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var challengeUrl by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "sözlükte ara",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            placeholder = { Text("Başlık veya @yazar ara…") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Temizle")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    if (state.query.trim().startsWith("@")) {
                        onOpenAuthor(state.query.trim().removePrefix("@"))
                    } else {
                        viewModel.submitSearch()
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_input_field")
        )

        if (state.isAutocompleteLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val searchRes = state.searchResult
            if (state.hasSearched && searchRes != null) {
                when (searchRes) {
                    is UiResult.Loading -> LoadingCenterView("Sözlükte aranıyor…")
                    is UiResult.Error -> ErrorStateView(
                        message = searchRes.message,
                        isCloudflareChallenge = searchRes.isCloudflareChallenge,
                        onRetry = { viewModel.submitSearch() },
                        onOpenBrowserVerify = searchRes.challengeUrl?.let { url ->
                            { challengeUrl = url }
                        }
                    )
                    is UiResult.Success -> {
                        if (searchRes.data.isEmpty()) {
                            EmptyEditorialView(
                                title = "Sonuç bulunamadı",
                                subtitle = "\"${state.query}\" için başlık bulunamadı. Yine de doğrudan başlığa gitmeyi deneyebilirsiniz."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(searchRes.data, key = { "${it.slugUrl}_${it.title}" }) { item ->
                                    TopicListItem(
                                        topic = item,
                                        onClick = { onOpenTopic(item.slugUrl) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    val ac = state.autocomplete
                    if (state.query.isNotBlank()) {
                        item(key = "direct_jump") {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (state.query.trim().startsWith("@")) {
                                            "${state.query.trim()} yazarına git"
                                        } else {
                                            "\"${state.query.trim()}\" başlığını aç veya ara"
                                        },
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.clickable {
                                    keyboard?.hide()
                                    val q = state.query.trim()
                                    if (q.startsWith("@")) {
                                        onOpenAuthor(q.removePrefix("@"))
                                    } else {
                                        onOpenTopic(q)
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }

                    if (ac.titles.isNotEmpty()) {
                        item(key = "ac_titles_header") {
                            Text(
                                text = "Önerilen Başlıklar",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        items(ac.titles, key = { "title_$it" }) { title ->
                            ListItem(
                                headlineContent = { Text(title) },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.Topic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.clickable {
                                    keyboard?.hide()
                                    viewModel.onQueryChanged(title)
                                    onOpenTopic(title)
                                }
                            )
                        }
                    }

                    if (ac.nicks.isNotEmpty()) {
                        item(key = "ac_nicks_header") {
                            Text(
                                text = "Yazarlar",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        items(ac.nicks, key = { "nick_$it" }) { nick ->
                            ListItem(
                                headlineContent = { Text("@$nick") },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                },
                                modifier = Modifier.clickable {
                                    keyboard?.hide()
                                    onOpenAuthor(nick)
                                }
                            )
                        }
                    }

                    if (state.query.isBlank() && history.isNotEmpty()) {
                        item(key = "history_section") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Son Aramalar",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { viewModel.clearHistory() }) {
                                        Icon(
                                            Icons.Rounded.DeleteSweep,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Temizle")
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    history.forEach { entry ->
                                        InputChip(
                                            selected = false,
                                            onClick = {
                                                keyboard?.hide()
                                                viewModel.submitSearch(entry.query)
                                            },
                                            label = { Text(entry.query) },
                                            trailingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Sil",
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clickable { viewModel.deleteHistoryItem(entry.query) }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else if (state.query.isBlank() && history.isEmpty()) {
                        item(key = "search_empty_prompt") {
                            EmptyEditorialView(
                                title = "Sözlükte Keşfet",
                                subtitle = "Aramak istediğiniz başlığı yazın ya da @yazar adı girerek doğrudan yazar profiline gidin.",
                                modifier = Modifier.height(380.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    challengeUrl?.let { url ->
        WebAuthDialog(
            initialUrl = url,
            title = "Ekşi Sözlük Doğrulama",
            onDismiss = { challengeUrl = null },
            onCompleted = { _ ->
                challengeUrl = null
                viewModel.submitSearch()
            }
        )
    }
}
