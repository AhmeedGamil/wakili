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
import com.ahmedgamil.wakili.core.designsystem.component.SegmentedTab
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSegmentedTabs
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
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

            // .ft-grid — square thumbs, bordered, radius 10
            imagesTab -> LazyVerticalGrid(
                columns = GridCells.Fixed(IMAGE_GRID_COLUMNS),
                contentPadding = PaddingValues(WakiliDimens.Space12),
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.images, key = { it.token }) { file ->
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

            // .ft-file — bordered rows on panel-2
            else -> LazyColumn(
                contentPadding = PaddingValues(WakiliDimens.Space12),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.documents.size) { index ->
                    val file = uiState.documents[index]
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
                            .clickable { viewModel.download(file) }
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
                            Text(
                                text = stringResource(
                                    if (file.source == FileSource.USER) R.string.files_by_you else R.string.files_by_agent,
                                ) + if (file.caption.isNotEmpty()) " · " + file.caption else "",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = MaterialTheme.typography.bodySmall.fontWeight,
                                ),
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        WakiliIcon(WakiliIcons.Download, size = WakiliDimens.IconSm, tint = colors.accent)
                    }
                }
            }
        }
    }
}
