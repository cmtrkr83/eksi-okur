package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.example.data.model.EntryItem
import com.example.data.model.EntrySegment

@Composable
fun EntryCard(
    entry: EntryItem,
    isBookmarked: Boolean,
    expandSpoilersByDefault: Boolean,
    onBkzClick: (targetUrl: String, targetTopic: String) -> Unit,
    onAuthorClick: (authorSlug: String) -> Unit,
    onFavoriteClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onTopicHeaderClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeSmartBkz by remember { mutableStateOf<EntrySegment.Bkz?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("entry_card_${entry.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (!entry.topicTitle.isNullOrBlank() && onTopicHeaderClick != null && entry.topicUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTopicHeaderClick(entry.topicUrl) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.topicTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            EntryRichBody(
                segments = entry.segments,
                expandSpoilersByDefault = expandSpoilersByDefault,
                onBkzClick = { bkz ->
                    if (bkz.isSmartBkz && !bkz.tooltipText.isNullOrBlank()) {
                        activeSmartBkz = bkz
                    } else {
                        onBkzClick(bkz.targetUrl, bkz.targetTopic)
                    }
                },
                onUrlClick = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            // Entry Metadata & Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Favorite + Bookmark + Share
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onFavoriteClick)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("entry_fav_${entry.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (entry.isFavorited) Icons.Rounded.WaterDrop else Icons.Outlined.WaterDrop,
                            contentDescription = "Favorilere ekle",
                            tint = if (entry.isFavorited) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        if (entry.favoriteCount > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = entry.favoriteCount.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.isFavorited) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = onBookmarkClick,
                        modifier = Modifier.testTag("entry_bookmark_${entry.id}")
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Yer iminden kaldır" else "Entry'yi kaydet",
                            tint = if (isBookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val shareText = buildString {
                                append(entry.plainText.take(280))
                                if (entry.plainText.length > 280) append("…")
                                append("\n\n— @${entry.author}\nhttps://eksisozluk.com/entry/${entry.id}")
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Entry'yi Paylaş"))
                        },
                        modifier = Modifier.testTag("entry_share_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Paylaş",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Right: Author & Date
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onAuthorClick(entry.authorSlug) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("entry_author_${entry.id}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.author,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (entry.createdAt.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                append(entry.createdAt)
                                if (!entry.editedAt.isNullOrBlank()) append(" ~ ${entry.editedAt}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    activeSmartBkz?.let { smart ->
        AlertDialog(
            onDismissRequest = { activeSmartBkz = null },
            title = {
                Text(
                    text = "Akıllı Bkz (*)",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = smart.tooltipText.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = smart.targetUrl
                        val topic = smart.targetTopic
                        activeSmartBkz = null
                        onBkzClick(url, topic)
                    }
                ) {
                    Text("Başlığa Git")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeSmartBkz = null }) {
                    Text("Kapat")
                }
            }
        )
    }
}

@Composable
fun EntryRichBody(
    segments: List<EntrySegment>,
    expandSpoilersByDefault: Boolean,
    onBkzClick: (EntrySegment.Bkz) -> Unit,
    onUrlClick: (String) -> Unit,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    linkColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    // Group contiguous inline segments between Spoiler blocks
    val inlineBuffer = mutableListOf<EntrySegment>()
    val blocks = mutableListOf<Any>() // List<EntrySegment> or EntrySegment.Spoiler
    for (seg in segments) {
        if (seg is EntrySegment.Spoiler) {
            if (inlineBuffer.isNotEmpty()) {
                blocks.add(inlineBuffer.toList())
                inlineBuffer.clear()
            }
            blocks.add(seg)
        } else {
            inlineBuffer.add(seg)
        }
    }
    if (inlineBuffer.isNotEmpty()) {
        blocks.add(inlineBuffer.toList())
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is EntrySegment.Spoiler -> {
                    SpoilerBlock(
                        spoiler = block,
                        initiallyExpanded = expandSpoilersByDefault,
                        onBkzClick = onBkzClick,
                        onUrlClick = onUrlClick
                    )
                }
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val inlineSegments = block as List<EntrySegment>
                    InlineSegmentText(
                        segments = inlineSegments,
                        onBkzClick = onBkzClick,
                        onUrlClick = onUrlClick,
                        textColor = textColor,
                        linkColor = linkColor
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineSegmentText(
    segments: List<EntrySegment>,
    onBkzClick: (EntrySegment.Bkz) -> Unit,
    onUrlClick: (String) -> Unit,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    linkColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    val linkAmber = MaterialTheme.colorScheme.secondary

    val annotated = remember(segments, linkColor, linkAmber) {
        buildAnnotatedString {
            segments.forEachIndexed { index, seg ->
                when (seg) {
                    is EntrySegment.Text -> append(seg.text)
                    is EntrySegment.Bkz -> {
                        val linkStyles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                fontWeight = if (seg.isSmartBkz) FontWeight.Bold else FontWeight.Medium,
                                textDecoration = if (seg.isSmartBkz) TextDecoration.None else TextDecoration.Underline
                            )
                        )
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "bkz_$index",
                                styles = linkStyles,
                                linkInteractionListener = { onBkzClick(seg) }
                            )
                        ) {
                            append(seg.displayText)
                        }
                    }
                    is EntrySegment.ExternalUrl -> {
                        val linkStyles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkAmber,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "url_$index",
                                styles = linkStyles,
                                linkInteractionListener = { onUrlClick(seg.url) }
                            )
                        ) {
                            append(seg.displayText)
                        }
                    }
                    is EntrySegment.Spoiler -> Unit
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = textColor
    )
}

@Composable
private fun SpoilerBlock(
    spoiler: EntrySegment.Spoiler,
    initiallyExpanded: Boolean,
    onBkzClick: (EntrySegment.Bkz) -> Unit,
    onUrlClick: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (expanded) "Spoiler'ı Gizle" else "Spoiler'ı Göster",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    EntryRichBody(
                        segments = spoiler.segments,
                        expandSpoilersByDefault = true,
                        onBkzClick = onBkzClick,
                        onUrlClick = onUrlClick
                    )
                }
            }
        }
    }
}
