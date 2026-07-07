package com.ahmedgamil.wakili.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ahmedgamil.wakili.core.designsystem.component.SectionLabel
import com.ahmedgamil.wakili.core.designsystem.component.SegmentedTab
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSegmentedTabs
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.model.FileSource
import com.ahmedgamil.wakili.core.ui.WakiliLoading

private const val IMAGE_GRID_COLUMNS = 3

@Composable
fun FilesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var imagesTab by remember { mutableStateOf(true) }
    val colors = WakiliTheme.colors

    Column(modifier = modifier.fillMaxSize()) {
        // header + .ft-tabs
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
        ) {
            WakiliRoundIconButton(icon = WakiliIcons.CornerUpLeft, onClick = onBack)
            Text(
                text = stringResource(R.string.files_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
        }
        WakiliSegmentedTabs(
            tabs = listOf(
                SegmentedTab(stringResource(R.string.files_images), WakiliIcons.Image),
                SegmentedTab(stringResource(R.string.files_files), WakiliIcons.FileText),
            ),
            selectedIndex = if (imagesTab) 0 else 1,
            onSelect = { imagesTab = it == 0 },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16),
        )

        when {
            uiState.loading -> WakiliLoading()

            imagesTab -> LazyVerticalGrid(
                columns = GridCells.Fixed(IMAGE_GRID_COLUMNS),
                contentPadding = PaddingValues(WakiliDimens.Space12),
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                modifier = Modifier.fillMaxSize(),
            ) {
                val groups = groupedFiles(uiState.images)
                if (groups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyFilesNote(stringResource(R.string.files_empty_images))
                    }
                }
                groups.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(
                            text = stringResource(group.titleRes),
                            modifier = Modifier.padding(top = WakiliDimens.Space6),
                        )
                    }
                    // .ft-grid — square thumbs, bordered, radius 10
                    items(group.files, key = { it.token }) { file ->
                        AsyncImage(
                            model = viewModel.absoluteUrl(file),
                            contentDescription = file.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
                                .border(
                                    WakiliDimens.BorderThin,
                                    colors.border,
                                    RoundedCornerShape(WakiliDimens.RadiusMd),
                                )
                                .clickable { viewModel.download(file) },
                        )
                    }
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(WakiliDimens.Space12),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                modifier = Modifier.fillMaxSize(),
            ) {
                val groups = groupedFiles(uiState.documents)
                if (groups.isEmpty()) {
                    item { EmptyFilesNote(stringResource(R.string.files_empty_files)) }
                }
                groups.forEach { group ->
                    item {
                        SectionLabel(
                            text = stringResource(group.titleRes),
                            modifier = Modifier.padding(top = WakiliDimens.Space6, bottom = WakiliDimens.Space2),
                        )
                    }
                    items(group.files.size) { index ->
                        FileRow(
                            file = group.files[index],
                            caption = group.files[index].caption,
                            onClick = { viewModel.download(group.files[index]) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    caption: String,
    onClick: () -> Unit,
) {
    val colors = WakiliTheme.colors
    // .ft-file — bordered rows on panel-2
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .background(colors.panel2)
            .border(
                WakiliDimens.BorderThin,
                colors.border,
                RoundedCornerShape(WakiliDimens.RadiusMd),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space9),
    ) {
        WakiliIcon(WakiliIcons.FileText, size = WakiliDimens.IconSm, tint = colors.muted)
        Column(Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotEmpty()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = MaterialTheme.typography.bodySmall.fontWeight,
                    ),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        WakiliIcon(WakiliIcons.Download, size = WakiliDimens.IconSm, tint = colors.accent)
    }
}

@Composable
private fun EmptyFilesNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = WakiliTheme.colors.muted,
        modifier = Modifier.padding(WakiliDimens.Space12),
    )
}

private data class FileGroup(
    val titleRes: Int,
    val files: List<FileEntry>,
)

private fun groupedFiles(files: List<FileEntry>) = listOf(
    FileGroup(R.string.files_by_you, files.filter { it.source == FileSource.USER }),
    FileGroup(R.string.files_by_agent, files.filter { it.source == FileSource.AGENT }),
).filter { it.files.isNotEmpty() }
