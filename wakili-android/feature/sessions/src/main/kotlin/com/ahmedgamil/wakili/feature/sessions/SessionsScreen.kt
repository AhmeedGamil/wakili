package com.ahmedgamil.wakili.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.designsystem.component.GroupLabel
import com.ahmedgamil.wakili.core.designsystem.component.SegmentedTab
import com.ahmedgamil.wakili.core.designsystem.component.StatusDot
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSegmentedTabs
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.SessionSummary
import com.ahmedgamil.wakili.core.ui.WakiliLoading

@Composable
fun SessionsScreen(
    onDisconnected: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SessionSummary?>(null) }
    val colors = WakiliTheme.colors

    LaunchedEffect(uiState.disconnected) {
        if (uiState.disconnected) onDisconnected()
    }
    LaunchedEffect(uiState.openSessionId) {
        uiState.openSessionId?.let {
            viewModel.onOpened()
            onOpenSession(it)
        }
    }
    LaunchedEffect(Unit) { viewModel.refresh(silent = true) }

    Column(modifier = modifier.fillMaxSize()) {
        Header(uiState = uiState)

        // .side-head — the primary "Select project" button starts a new chat.
        WakiliButton(
            text = stringResource(R.string.sessions_select_project),
            onClick = viewModel::newChat,
            style = WakiliButtonStyle.Primary,
            icon = WakiliIcons.Folder,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space4),
        )

        // .side-files-btn — full-width Files entry on the panel-2 background.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space4)
                .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
                .background(colors.panel)
                .clickable(onClick = onOpenFiles)
                .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space9),
        ) {
            WakiliIcon(WakiliIcons.FileText, tint = colors.muted)
            Text(
                text = stringResource(R.string.sessions_files),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
            )
        }

        // .sv-toggle — All chats / By project
        WakiliSegmentedTabs(
            tabs = listOf(
                SegmentedTab(stringResource(R.string.sessions_view_all)),
                SegmentedTab(stringResource(R.string.sessions_view_project)),
            ),
            selectedIndex = if (uiState.byProject) 1 else 0,
            onSelect = { index -> if ((index == 1) != uiState.byProject) viewModel.toggleView() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space4),
        )

        if (!uiState.streamConnected && !uiState.loading) {
            Text(
                text = stringResource(R.string.sessions_stream_down),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space2),
            )
        }

        Column(Modifier.weight(1f)) {
            when {
                uiState.loading -> WakiliLoading()

                uiState.error -> CenteredNote(stringResource(R.string.sessions_error)) {
                    WakiliButton(
                        text = stringResource(R.string.sessions_retry),
                        onClick = { viewModel.refresh() },
                        style = WakiliButtonStyle.Primary,
                    )
                }

                uiState.rows.isEmpty() -> CenteredNote(stringResource(R.string.sessions_empty))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = WakiliDimens.Space8,
                        vertical = WakiliDimens.Space4,
                    ),
                ) {
                    if (uiState.byProject) {
                        uiState.groups.forEach { group ->
                            item(key = "g:" + (group.cwd ?: "~")) {
                                GroupHead(
                                    label = group.folder,
                                    onAdd = { viewModel.newChatIn(group.cwd) },
                                )
                            }
                            items(group.rows, key = { it.session.id }) { row ->
                                SessionRow(
                                    row = row,
                                    showFolder = false,
                                    onClick = { onOpenSession(row.session.id) },
                                    onLongClick = { editing = row.session },
                                )
                            }
                        }
                    } else {
                        items(uiState.rows, key = { it.session.id }) { row ->
                            SessionRow(
                                row = row,
                                showFolder = true,
                                onClick = { onOpenSession(row.session.id) },
                                onLongClick = { editing = row.session },
                            )
                        }
                    }
                }
            }
        }

        // .side-foot — ghost buttons above a top border
        HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space8, vertical = WakiliDimens.Space10),
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        ) {
            WakiliButton(
                text = stringResource(R.string.sessions_settings),
                onClick = onOpenSettings,
                style = WakiliButtonStyle.Ghost,
                icon = WakiliIcons.Settings,
                modifier = Modifier.weight(1f),
            )
            WakiliButton(
                text = stringResource(R.string.sessions_disconnect),
                onClick = viewModel::disconnect,
                style = WakiliButtonStyle.Ghost,
                icon = WakiliIcons.Power,
                modifier = Modifier.weight(1f),
            )
        }
    }

    editing?.let { session ->
        EditSessionDialog(
            session = session,
            onRename = { viewModel.rename(session.id, it); editing = null },
            onDelete = { viewModel.delete(session.id); editing = null },
            onDismiss = { editing = null },
        )
    }

    if (uiState.pickingFolder) {
        FolderPickerSheet(uiState, viewModel)
    }
}

@Composable
private fun Header(uiState: SessionsUiState) {
    val colors = WakiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
    ) {
        Text(
            text = stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        Text(
            text = uiState.host,
            style = WakiliMono.Label,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** .group-head — uppercase folder label; the whole row starts a chat there. */
@Composable
private fun GroupHead(label: String, onAdd: () -> Unit) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WakiliDimens.Space12)
            .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
            .clickable(onClick = onAdd)
            .padding(
                horizontal = WakiliDimens.Space11,
                vertical = WakiliDimens.Space8,
            ),
    ) {
        GroupLabel(text = label, modifier = Modifier.weight(1f))
        WakiliIcon(WakiliIcons.Plus, size = WakiliDimens.IconLg, tint = colors.muted)
    }
}

/** .session — flat row: title, folder badge below, status dot/lock at the end. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    row: SessionRowUi,
    showFolder: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    val session = row.session
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space9),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = session.title.ifEmpty { stringResource(R.string.sessions_untitled) },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // .s-badge — agent · folder
            Text(
                text = listOfNotNull(
                    session.agentId,
                    if (showFolder) {
                        session.effectiveCwd?.substringAfterLast('\\')?.substringAfterLast('/')
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = MaterialTheme.typography.bodySmall.fontWeight,
                ),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            // .s-pending — waiting on you
            row.pending -> WakiliIcon(WakiliIcons.Lock, size = WakiliDimens.IconSm, tint = colors.accent)
            // .s-busy — working, pulses
            row.busy -> StatusDot(color = colors.accent, pulse = true)
            // .s-unread — finished in the background
            row.unread -> StatusDot(color = colors.accent)
        }
    }
}

@Composable
private fun CenteredNote(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(WakiliDimens.Space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = WakiliTheme.colors.muted,
        )
        action?.let {
            Spacer(Modifier.height(WakiliDimens.Space12))
            it()
        }
    }
}

@Composable
private fun EditSessionDialog(
    session: SessionSummary,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var title by remember { mutableStateOf(session.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        shape = RoundedCornerShape(WakiliDimens.RadiusPanel),
        title = {
            Text(
                text = stringResource(R.string.sessions_edit),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        },
        text = {
            WakiliTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(title.trim()) }, enabled = title.isNotBlank()) {
                Text(
                    text = stringResource(R.string.sessions_rename),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.sessions_delete),
                    color = colors.danger,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/** FolderPicker — crumb bar, directory rows, new-folder field, primary confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(uiState: SessionsUiState, viewModel: SessionsViewModel) {
    val colors = WakiliTheme.colors
    var newFolder by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelFolderPicker,
        containerColor = colors.panel,
        shape = RoundedCornerShape(
            topStart = WakiliDimens.RadiusPanel,
            topEnd = WakiliDimens.RadiusPanel,
        ),
    ) {
        Column(Modifier.padding(horizontal = WakiliDimens.Space16)) {
            Text(
                text = stringResource(R.string.sessions_pick_project),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            // .fp-crumb
            Text(
                text = uiState.folderListing?.path?.ifEmpty { stringResource(R.string.sessions_roots) }
                    ?: stringResource(R.string.sessions_roots),
                style = WakiliMono.Label,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = WakiliDimens.Space8),
            )
            HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)
            val listing = uiState.folderListing
            if (uiState.folderLoading) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(WakiliDimens.Space12),
                )
            } else if (listing != null) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(WakiliDimens.FolderListHeight)
                        .padding(vertical = WakiliDimens.Space6),
                ) {
                    listing.parent?.let { parent ->
                        item {
                            FolderRow(
                                name = "..",
                                icon = WakiliIcons.CornerUpLeft,
                                onClick = { viewModel.browseFolder(parent.ifEmpty { null }) },
                            )
                        }
                    }
                    items(listing.dirs, key = { it.path }) { dir ->
                        FolderRow(
                            name = dir.name,
                            icon = WakiliIcons.Folder,
                            onClick = { viewModel.browseFolder(dir.path) },
                        )
                    }
                }
                // .fp-new — create a folder inside the current one
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                ) {
                    WakiliTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        placeholder = stringResource(R.string.sessions_new_folder),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    WakiliButton(
                        text = stringResource(R.string.sessions_create),
                        onClick = { viewModel.createFolder(newFolder.trim()); newFolder = "" },
                        style = WakiliButtonStyle.Ghost,
                        icon = WakiliIcons.Plus,
                        enabled = newFolder.isNotBlank() && !listing.path.isNullOrEmpty(),
                    )
                }
                // .fp-foot
                WakiliButton(
                    text = stringResource(R.string.sessions_use_folder),
                    onClick = viewModel::useFolder,
                    style = WakiliButtonStyle.Primary,
                    enabled = !listing.path.isNullOrEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = WakiliDimens.Space12),
                )
            }
            Spacer(Modifier.height(WakiliDimens.Space20))
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
    ) {
        WakiliIcon(icon, size = WakiliDimens.IconMd, tint = colors.muted)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
