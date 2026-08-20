package com.ahmedgamil.wakili.feature.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.ahmedgamil.wakili.core.designsystem.component.SegmentedTab
import com.ahmedgamil.wakili.core.designsystem.component.WakiliContextMenu
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDangerSpinner
import com.ahmedgamil.wakili.core.designsystem.component.WakiliMenuItem
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSegmentedTabs
import com.ahmedgamil.wakili.core.designsystem.component.wakiliShimmer
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeControlTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeMetaTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeRowTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.FileEntry
import com.ahmedgamil.wakili.core.ui.ViewerImage
import com.ahmedgamil.wakili.core.ui.WakiliImageViewer
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
    // Tap opens the full-screen viewer (web lightbox) ON that image, but the
    // viewer holds the whole grid so you can swipe through it; download lives
    // inside it. Identified by token, not index, so a refresh that reorders or
    // drops files can't leave the viewer pointing at a different picture.
    var viewerToken by remember { mutableStateOf<String?>(null) }
    // Long-press → Delete goes through the same centered confirmation as chats.
    var confirmDelete by remember { mutableStateOf<FileEntry?>(null) }
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
        // .ft-chips — source filter over the newest-first list (web Files page).
        FilterChips(
            selected = uiState.filter,
            onSelect = viewModel::setFilter,
            modifier = Modifier.padding(
                horizontal = WakiliDimens.Space16,
                vertical = WakiliDimens.Space10,
            ),
        )

        when {
            uiState.loading -> WakiliLoading()

            // ONE newest-first list per tab — no source groups; the chips filter.
            imagesTab -> LazyVerticalGrid(
                columns = GridCells.Fixed(IMAGE_GRID_COLUMNS),
                contentPadding = PaddingValues(WakiliDimens.Space12),
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                modifier = Modifier.fillMaxSize(),
            ) {
                val images = uiState.images
                if (images.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyFilesNote(stringResource(R.string.files_empty_images))
                    }
                }
                // .ft-grid — square thumbs, bordered, radius 10
                items(images, key = { it.token }) { file ->
                    ImageThumb(
                        file = file,
                        url = viewModel.absoluteUrl(file),
                        deleting = file.token in uiState.deleting,
                        onOpen = { viewerToken = file.token },
                        onSave = { viewModel.download(file) },
                        onCopy = { viewModel.copy(file) },
                        onDelete = { confirmDelete = file },
                    )
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(WakiliDimens.Space12),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                modifier = Modifier.fillMaxSize(),
            ) {
                val documents = uiState.documents
                if (documents.isEmpty()) {
                    item { EmptyFilesNote(stringResource(R.string.files_empty_files)) }
                }
                items(documents.size) { index ->
                    val file = documents[index]
                    FileRow(
                        file = file,
                        caption = file.caption,
                        deleting = file.token in uiState.deleting,
                        onClick = { viewModel.download(file) },
                        onSave = { viewModel.download(file) },
                        onCopy = { viewModel.copy(file) },
                        onDelete = { confirmDelete = file },
                    )
                }
            }
        }
    }

    // The viewer pages over the SAME list the grid shows (current filter and
    // all), so swiping matches what's behind it. If the open image disappears
    // from that list, the viewer closes rather than jumping to a stranger.
    viewerToken?.let { token ->
        val images = uiState.images
        val index = images.indexOfFirst { it.token == token }
        if (index < 0) {
            viewerToken = null
        } else {
            WakiliImageViewer(
                images = images.map {
                    ViewerImage(url = viewModel.absoluteUrl(it), name = it.name, id = it.token)
                },
                initialIndex = index,
                onDismiss = { viewerToken = null },
                onDownload = { shown ->
                    images.firstOrNull { it.token == shown.id }?.let(viewModel::download)
                },
            )
        }
    }

    // Centered confirmation — the same dialog recipe as deleting a chat.
    confirmDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = colors.panel,
            shape = RoundedCornerShape(WakiliDimens.RadiusPanel),
            title = {
                Text(
                    text = stringResource(R.string.files_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.files_delete_message, file.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(file); confirmDelete = null }) {
                    Text(
                        text = stringResource(R.string.files_delete),
                        color = colors.danger,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(
                        text = stringResource(R.string.files_cancel),
                        color = colors.muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
        )
    }
}

/**
 * .ft-imglink — square shimmering thumb; tap opens the viewer, long-press the
 * Save / Copy / Delete menu; deleting shows the red overlay until the server
 * confirms.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(
    file: FileEntry,
    url: String,
    deleting: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var menuOpen by remember(file.token) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(colors.panel2)
            .border(WakiliDimens.BorderThin, colors.border, shape)
            .wakiliShimmer(loading)
            .combinedClickable(
                onClick = { if (!deleting) onOpen() },
                onLongClick = { if (!deleting) menuOpen = true },
            ),
    ) {
        AsyncImage(
            model = url,
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            onState = { state ->
                loading = state is AsyncImagePainter.State.Loading ||
                    state is AsyncImagePainter.State.Empty
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (deleting) {
            // .ft-deleting — dark scrim + red ring + label over the thumb
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = WakiliDimens.AlphaBlocked)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    WakiliDimens.Space6,
                    Alignment.CenterVertically,
                ),
            ) {
                WakiliDangerSpinner(size = WakiliDimens.SpinnerLg)
                Text(
                    text = stringResource(R.string.files_deleting),
                    style = ChromeMetaTextStyle,
                    color = Color.White,
                )
            }
        }
        FileMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onSave = onSave,
            onCopy = onCopy,
            onDelete = onDelete,
        )
    }
}

/** Save / Copy / red Delete — the web Files page's long-press menu. */
@Composable
private fun FileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    WakiliContextMenu(
        expanded = expanded,
        items = listOf(
            WakiliMenuItem(stringResource(R.string.files_copy), WakiliIcons.Copy, onClick = onCopy),
            WakiliMenuItem(stringResource(R.string.files_save), WakiliIcons.Download, onClick = onSave),
            WakiliMenuItem(
                stringResource(R.string.files_delete),
                WakiliIcons.Trash,
                danger = true,
                onClick = onDelete,
            ),
        ),
        onDismiss = onDismiss,
    )
}

/** .ft-file — bordered row on panel-2; the leading slot shows the red delete
 *  spinner while the server removes the file. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileEntry,
    caption: String,
    deleting: Boolean,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var menuOpen by remember(file.token) { mutableStateOf(false) }
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
            .combinedClickable(
                onClick = { if (!deleting) onClick() },
                onLongClick = { if (!deleting) menuOpen = true },
            )
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space10),
    ) {
        if (deleting) {
            WakiliDangerSpinner(size = WakiliDimens.SpinnerSm)
        } else {
            WakiliIcon(WakiliIcons.FileText, size = WakiliDimens.IconSm, tint = colors.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = ChromeRowTextStyle,
                color = if (deleting) colors.muted else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotEmpty()) {
                Text(
                    text = caption,
                    style = ChromeMetaTextStyle,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        WakiliIcon(WakiliIcons.Download, size = WakiliDimens.IconSm, tint = colors.accent)
        FileMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onSave = onSave,
            onCopy = onCopy,
            onDelete = onDelete,
        )
    }
}

/** .ft-chip row — All / By you / By agent pills; the active one fills with accent. */
@Composable
private fun FilterChips(
    selected: FileFilter,
    onSelect: (FileFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        modifier = modifier,
    ) {
        FilterChip(stringResource(R.string.files_filter_all), selected == FileFilter.ALL) { onSelect(FileFilter.ALL) }
        FilterChip(stringResource(R.string.files_by_you), selected == FileFilter.USER) { onSelect(FileFilter.USER) }
        FilterChip(stringResource(R.string.files_by_agent), selected == FileFilter.AGENT) { onSelect(FileFilter.AGENT) }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = WakiliTheme.colors
    Text(
        text = label,
        style = ChromeControlTextStyle,
        color = if (on) colors.accentInk else colors.muted,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) colors.accent else Color.Transparent)
            .border(WakiliDimens.BorderThin, if (on) colors.accent else colors.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space6),
    )
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
