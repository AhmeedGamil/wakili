package com.ahmedgamil.wakili.feature.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.designsystem.R as DesignR
import com.ahmedgamil.wakili.core.designsystem.component.WakiliAnchoredPopup
import com.ahmedgamil.wakili.core.designsystem.component.ConnectionBanner
import com.ahmedgamil.wakili.core.designsystem.component.ConnectionDot
import com.ahmedgamil.wakili.core.designsystem.component.GroupLabel
import com.ahmedgamil.wakili.core.designsystem.component.StatusDot
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliContextMenu
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDangerSpinner
import com.ahmedgamil.wakili.core.designsystem.component.WakiliGhostIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliMenuItem
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeLabelTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeMetaTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeRowTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.core.model.SessionSummary
import com.ahmedgamil.wakili.core.ui.WakiliLoading

/**
 * #sidebar — the web sidebar as a slide-in drawer: brand, "Select project",
 * Files and Settings entries (all the flat .side-files-btn style), a "Sessions"
 * header with a grouping filter popover, and the session list. Selecting a
 * session (or a group ➕) closes the drawer and opens that chat in place, exactly
 * like the web menu button. No Disconnect here — connection lives in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarDrawer(
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnect: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors
    // Long-press flow, exactly the web's: a small Rename/Delete menu on the row,
    // then a rename input or a centered delete confirmation.
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    // The ⋮ menu in the brand row collapses Select project / Scheduled tasks /
    // Files / Settings.
    var menuOpen by remember { mutableStateOf(false) }
    // No status-bar/nav-bar insets here: the NavHost already wraps this page in
    // safeDrawingPadding, so adding them again double-padded the top.
    val pullToRefreshState = rememberPullToRefreshState()
    val activeCwd = uiState.rows.firstOrNull { it.session.id == activeSessionId }
        ?.session?.effectiveCwd

    // The WHOLE sidebar is one scrollable list wrapped in pull-to-refresh:
    // dragging down from the top refreshes the session list (there is no
    // refresh button — the indicator is the feedback).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = { viewModel.refresh() },
            state = pullToRefreshState,
            indicator = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WakiliDimens.Space8,
                    top = WakiliDimens.SidebarBrandContentInset,
                    end = WakiliDimens.Space8,
                    bottom = WakiliDimens.SidebarBottomFadeHeight + WakiliDimens.Space16,
                ),
            ) {
            // Agent dropdown — the home page's top-level choice: which agent you're
            // talking to. Picks the agent for new chats AND filters the list.
            // Hidden when only one agent is registered. The ITEM always exists
            // (content conditional inside): agents load async, and an item
            // INSERTED above the anchor made the page open "slightly scrolled" —
            // growing in place keeps the top at the top.
            item(key = "agent-picker") {
                if (uiState.agents.size > 1) {
                    AgentDropdown(
                        agents = uiState.agents,
                        selected = uiState.homeAgentId,
                        onSelect = viewModel::setHomeAgent,
                    )
                }
            }

            // .sess-head — "Sessions" + grouping filter popover
            item(key = "head") {
                SessionsHeader(
                    byProject = uiState.byProject,
                    showModel = uiState.showModel,
                    onSetProject = viewModel::setView,
                    onToggleModel = viewModel::toggleShowModel,
                )
            }

            // The connection status ("Reconnecting…") now floats at the app root
            // over every screen (ConnectionBanner), so the list no longer carries
            // its own banner here.

            // .session-list
            when {
                uiState.loading -> item(key = "loading") {
                    WakiliLoading(
                        Modifier
                            .fillParentMaxWidth()
                            .height(WakiliDimens.FolderListHeight),
                    )
                }

                uiState.error -> item(key = "error") {
                    CenteredNote(stringResource(R.string.sessions_error)) {
                        WakiliButton(
                            text = stringResource(R.string.sessions_retry),
                            onClick = { viewModel.refresh() },
                            style = WakiliButtonStyle.Primary,
                        )
                    }
                }

                uiState.rows.isEmpty() -> item(key = "empty") {
                    CenteredNote(stringResource(R.string.sessions_empty))
                }

                uiState.byProject -> uiState.groups.forEach { group ->
                    val closed = (group.cwd ?: "") in uiState.collapsedGroups
                    item(key = "g:" + (group.cwd ?: "~")) {
                        GroupHead(
                            label = group.folder,
                            closed = closed,
                            count = group.rows.size,
                            onToggle = { viewModel.toggleGroup(group.cwd) },
                            onAdd = { viewModel.newChatIn(group.cwd) },
                            // Only the group whose ➕ was tapped spins; the rest
                            // just go inert until it lands.
                            creating = uiState.creating && uiState.creatingCwd == group.cwd,
                            enabled = !uiState.creating,
                        )
                    }
                    if (!closed) items(group.rows, key = { it.session.id }) { row ->
                        SessionRow(
                            row = row,
                            badge = null,
                            model = modelLabel(row.session, uiState),
                            active = row.session.id == activeSessionId,
                            deleting = row.session.id in uiState.deletingIds,
                            onClick = { onSelect(row.session.id) },
                            onRename = { renaming = row.session },
                            onDelete = { deleting = row.session },
                        )
                    }
                }

                else -> items(uiState.rows, key = { it.session.id }) { row ->
                    SessionRow(
                        row = row,
                        badge = row.session.effectiveCwd
                            ?.substringAfterLast('\\')?.substringAfterLast('/')
                            ?: stringResource(R.string.sessions_default_project),
                        model = modelLabel(row.session, uiState),
                        active = row.session.id == activeSessionId,
                        deleting = row.session.id in uiState.deletingIds,
                        onClick = { onSelect(row.session.id) },
                        onRename = { renaming = row.session },
                        onDelete = { deleting = row.session },
                    )
                }
            }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(WakiliDimens.SidebarTopFadeHeight)
                .background(
                    Brush.verticalGradient(
                        0f to colors.bg,
                        WakiliDimens.SidebarTopFadeMidStop to colors.bg.copy(
                            alpha = WakiliDimens.SidebarTopFadeMidAlpha,
                        ),
                        1f to colors.bg.copy(alpha = 0f),
                    ),
                ),
        )
        // Mirror the brand fade at the bottom. The floating primary action sits
        // above this layer while sessions can scroll cleanly beneath both.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(WakiliDimens.SidebarBottomFadeHeight)
                .background(
                    Brush.verticalGradient(
                        0f to colors.bg.copy(alpha = 0f),
                        (1f - WakiliDimens.SidebarTopFadeMidStop) to colors.bg.copy(
                            alpha = WakiliDimens.SidebarTopFadeMidAlpha,
                        ),
                        1f to colors.bg,
                    ),
                ),
        )
        PullToRefreshDefaults.Indicator(
            state = pullToRefreshState,
            isRefreshing = uiState.refreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = WakiliDimens.SidebarBrandContentInset),
        )
        // Brand block: [logo Wakili …… ☰] over a host row (● hostname). The ☰
        // menu holds Select project / Files / Settings; the host row shows the
        // connected computer and its live/down dot, and taps to switch host.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(
                    start = WakiliDimens.Space16,
                    end = WakiliDimens.Space8,
                    top = WakiliDimens.Space8,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.wakili_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.accent),
                    modifier = Modifier
                        .size(WakiliDimens.BrandLogo)
                        .graphicsLayer {
                            scaleX = WakiliDimens.BrandLogoAssetScale
                            scaleY = WakiliDimens.BrandLogoAssetScale
                        },
                )
                Text(
                    text = "Wakili",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                // ⋮ — background-colored (ghost) so it blends into the brand;
                // opens the collapsed Select project / Scheduled tasks / Files
                // / Settings menu. Vertical dots, the standard overflow affordance
                // (the old ☰ read as a nav drawer that never existed).
                Box {
                    WakiliGhostIconButton(
                        icon = WakiliIcons.MoreVertical,
                        onClick = { menuOpen = true },
                        iconSize = WakiliDimens.IconLg,
                    )
                    WakiliContextMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        alignEnd = true,
                        items = listOf(
                            WakiliMenuItem(
                                label = stringResource(R.string.sessions_select_project),
                                icon = WakiliIcons.Folder,
                                onClick = viewModel::newChat,
                            ),
                            WakiliMenuItem(
                                label = stringResource(R.string.sched_title),
                                icon = WakiliIcons.Clock,
                                onClick = onOpenScheduled,
                            ),
                            WakiliMenuItem(
                                label = filesLabel(stringResource(R.string.sessions_files), uiState.filesCount),
                                icon = WakiliIcons.FileText,
                                onClick = onOpenFiles,
                            ),
                            WakiliMenuItem(
                                label = stringResource(R.string.sessions_settings),
                                icon = WakiliIcons.Settings,
                                onClick = onOpenSettings,
                            ),
                        ),
                    )
                }
            }
            // ● hostname — connection identity; tap opens the host switcher. The
            // reconnect pill rides the END of this same row instead of floating
            // under it: the status belongs to the host it describes, and the
            // page content below no longer has to leave a gap for it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WakiliDimens.Space2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                    // fill = false so a long host name shrinks (it ellipsizes)
                    // rather than pushing the pill off the edge.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                        .clickable(onClick = onOpenConnect)
                        .padding(horizontal = WakiliDimens.Space6, vertical = WakiliDimens.Space4),
                ) {
                    ConnectionDot(connected = uiState.streamConnected || uiState.loading)
                    Text(
                        // The friendly host name (custom, else derived); the raw
                        // host:port only until a profile loads.
                        text = uiState.hostName.ifBlank { hostLabel(uiState.host) },
                        style = ChromeLabelTextStyle,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                ConnectionBanner(
                    connected = uiState.streamConnected || uiState.loading,
                    text = stringResource(R.string.sessions_reconnecting),
                    modifier = Modifier.padding(end = WakiliDimens.Space8),
                )
            }
        }
        SideNewChat(
            label = stringResource(R.string.sessions_new),
            onClick = { viewModel.newChatIn(activeCwd) },
            busy = uiState.creating && uiState.creatingCwd == activeCwd,
            enabled = !uiState.creating,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = WakiliDimens.Space16, bottom = WakiliDimens.Space16),
        )
    }

    renaming?.let { session ->
        RenameDialog(
            session = session,
            onRename = { viewModel.rename(session.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { session ->
        DeleteConfirmDialog(
            session = session,
            onDelete = {
                viewModel.delete(session.id, wasActive = session.id == activeSessionId)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** "host:port" from the gateway base URL (scheme + trailing slash stripped);
 *  a dash while nothing is connected yet. Shown next to the connection dot. */
private fun hostLabel(baseUrl: String): String {
    if (baseUrl.isBlank()) return "—"
    return baseUrl.substringAfter("://").substringBefore("/").ifBlank { baseUrl }
}

/** "Files" with the registry count appended when there is one (the pill can't
 *  ride a menu row, so the count folds into the label). */
private fun filesLabel(base: String, count: Int): String =
    if (count > 0) "$base  ·  $count" else base

/** Human model name for a row, gated by "Show model"; null when nothing to show. */
private fun modelLabel(session: SessionSummary, state: SessionsUiState): String? {
    if (!state.showModel) return null
    val model = session.model ?: return null
    val agent = state.agents.firstOrNull { it.id == session.agentId }
    val option = agent?.controls?.get("model")?.options?.firstOrNull { it.value == model }
    return option?.label ?: model
}

/**
 * Home — the app's landing page (the web sidebar-as-page): the app opens here,
 * you pick an agent and a chat, and selecting one navigates to it. It IS
 * the sidebar content, full-screen; no auto-redirect into a chat.
 */
@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnect: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        SidebarDrawer(
            activeSessionId = null,
            onSelect = onOpenChat,
            onOpenFiles = onOpenFiles,
            onOpenScheduled = onOpenScheduled,
            onOpenSettings = onOpenSettings,
            onOpenConnect = onOpenConnect,
            viewModel = viewModel,
        )
    }
}

/** Agent dropdown at the top of the home page. It chooses the agent for new
 * sessions and filters the list; the persisted selection is restored by the
 * view model on the next launch. */
@Composable
private fun AgentDropdown(
    agents: List<AgentManifest>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = WakiliTheme.colors
    val active = selected.ifBlank { agents.firstOrNull()?.id.orEmpty() }
    val current = agents.firstOrNull { it.id == active } ?: agents.first()
    var open by remember { mutableStateOf(false) }
    // Sized to its own label, not to the page: it names ONE agent, and a
    // full-width control reads as a section header for the list under it.
    Box(modifier = Modifier.padding(vertical = WakiliDimens.Space6)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
            modifier = Modifier
                .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                .background(colors.panel2)
                .border(WakiliDimens.BorderThin, colors.border, RoundedCornerShape(WakiliDimens.RadiusLg))
                .clickable { open = true }
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space11),
        ) {
            WakiliIcon(WakiliIcons.Bot, size = WakiliDimens.Icon, tint = colors.muted)
            Text(
                text = current.label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WakiliIcon(WakiliIcons.ChevronDown, size = WakiliDimens.IconSm, tint = colors.muted)
        }
        WakiliContextMenu(
            expanded = open,
            onDismiss = { open = false },
            items = agents.map { agent ->
                WakiliMenuItem(
                    label = agent.label,
                    icon = if (agent.id == active) WakiliIcons.Check else WakiliIcons.Bot,
                    onClick = { onSelect(agent.id) },
                )
            },
        )
    }
}

/** .side-files-btn — flat full-width icon+label entry; optional count pill (Files). */
@Composable
private fun SideEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    count: Int? = null,
    // Creating a chat is a round trip. On a slow link the row used to sit there
    // looking untouched until the chat suddenly opened, so the tap answers
    // immediately: the icon becomes a spinner and further taps are ignored.
    busy: Boolean = false,
) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = WakiliDimens.Space2)
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .background(colors.bg)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = WakiliDimens.Space8, vertical = WakiliDimens.Space10),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(WakiliDimens.IconLg),
                color = colors.accent,
                strokeWidth = WakiliDimens.BorderThick,
            )
        } else {
            WakiliIcon(icon, size = WakiliDimens.IconLg, tint = colors.muted)
        }
        Text(
            text = label,
            style = ChromeRowTextStyle,
            color = if (busy) colors.muted else colors.text,
            modifier = Modifier.weight(1f),
        )
        // .side-files-count — muted pill on panel-2 (--fs-meta)
        if (count != null) {
            Text(
                text = count.toString(),
                style = ChromeMetaTextStyle,
                color = colors.muted,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.panel2)
                    .padding(horizontal = WakiliDimens.Space9, vertical = WakiliDimens.Space2),
            )
        }
    }
}

/**
 * Floating bottom-end primary action, shaped as a full pill (like the composer
 * bar, not like the square-ish list rows it floats over). Its solid fill is the
 * selected theme accent, and the mirrored bottom fade keeps scrolling rows
 * legible behind it.
 */
@Composable
private fun SideNewChat(
    label: String,
    onClick: () -> Unit,
    // Spinner: this button's own round trip. A create started from a project's ➕
    // leaves this one enabled=false but NOT spinning — the spinner belongs to the
    // control that was actually tapped.
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space9),
        modifier = modifier
            .clip(CircleShape)
            .background(colors.accent, CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space12)
            .alpha(if (enabled || busy) 1f else WakiliDimens.AlphaDisabled),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(WakiliDimens.IconLg),
                color = colors.accentInk,
                strokeWidth = WakiliDimens.BorderThick,
            )
        } else {
            WakiliIcon(WakiliIcons.Plus, size = WakiliDimens.IconLg, tint = colors.accentInk)
        }
        Text(
            text = label,
            style = ChromeRowTextStyle,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentInk,
        )
    }
}

/** .sess-head — uppercase "Sessions" label + a filter button whose popover picks
 *  grouping (By project / All chats) and whether each row shows its model. */
@Composable
private fun SessionsHeader(
    byProject: Boolean,
    showModel: Boolean,
    onSetProject: (Boolean) -> Unit,
    onToggleModel: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = WakiliDimens.Space8,
                end = WakiliDimens.Space4,
                top = WakiliDimens.Space12,
                bottom = WakiliDimens.Space4,
            ),
    ) {
        Text(
            text = stringResource(R.string.sessions_header).uppercase(),
            style = ChromeLabelTextStyle,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Box {
            WakiliGhostIconButton(
                icon = WakiliIcons.Filter,
                onClick = { open = !open },
                iconSize = WakiliDimens.Icon,
            )
            if (open) {
                val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                    WakiliDimens.Space4.roundToPx()
                }
                WakiliAnchoredPopup(
                    positionProvider = remember(gapPx) { BelowEndPositionProvider(gapPx) },
                    onDismiss = { open = false },
                ) {
                    Column(
                        // The ONE popover recipe (.sess-pop): fixed width, so
                        // every sidebar menu reads identically.
                        modifier = Modifier
                            .width(WakiliDimens.ContextMenuWidth)
                            .clip(RoundedCornerShape(WakiliDimens.Radius))
                            .background(colors.panel)
                            .border(
                                WakiliDimens.BorderThin,
                                colors.border,
                                RoundedCornerShape(WakiliDimens.Radius),
                            )
                            .padding(WakiliDimens.Space6),
                        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2),
                    ) {
                        FilterItem(WakiliIcons.Folder, stringResource(R.string.sessions_view_project), byProject) {
                            onSetProject(true); open = false
                        }
                        FilterItem(WakiliIcons.Menu, stringResource(R.string.sessions_view_all), !byProject) {
                            onSetProject(false); open = false
                        }
                        HorizontalDivider(
                            color = colors.border,
                            thickness = WakiliDimens.BorderThin,
                            modifier = Modifier.padding(horizontal = WakiliDimens.Space6, vertical = WakiliDimens.Space4),
                        )
                        // Show-model toggle stays open, like the web popover.
                        FilterItem(WakiliIcons.Bot, stringResource(R.string.sessions_show_model), showModel) {
                            onToggleModel()
                        }
                    }
                }
            }
        }
    }
}

/** .sess-pop button — icon + label + accent check when active. Compact rows
 *  matching the composer's + menu (AddMenuItem), not the ChromeRow sizing. */
@Composable
private fun FilterItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
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
        WakiliIcon(icon, size = WakiliDimens.Icon, tint = colors.muted)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        WakiliIcon(
            WakiliIcons.Check,
            size = WakiliDimens.IconSm,
            tint = if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent,
        )
    }
}

/**
 * .group-head — uppercase folder label. Two SIBLING buttons on one row: the
 * title half collapses/expands the group, the trailing ➕ starts a chat there.
 * They are siblings and not nested, so each ripple stays inside its own button
 * — nested, the header's ripple washed under the ➕ and a new-chat tap looked
 * like it had hit the header. Both are GroupHeadRow tall, which makes the ➕ a
 * square matching the title button.
 */
@Composable
private fun GroupHead(
    label: String,
    closed: Boolean,
    count: Int,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    // Same round trip as the New chat row, so it answers the same way — but only
    // on the ➕ that was actually tapped.
    creating: Boolean = false,
    // A create is in flight somewhere else. This ➕ shows no spinner (it is not
    // the one working) but must not start a second chat either, so it dims and
    // stops taking taps until the round trip lands.
    enabled: Boolean = true,
) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WakiliDimens.Space8, start = WakiliDimens.Space3, end = WakiliDimens.Space3),
    ) {
        // Collapse half. Its own clip + clickable, so the ripple ends where the
        // ➕'s hit area begins. A header tap creating a session would be too
        // heavy a meaning, so this only opens/closes the group.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .weight(1f)
                .height(WakiliDimens.GroupHeadRow)
                .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                .clickable(onClick = onToggle)
                .padding(horizontal = WakiliDimens.Space8),
        ) {
            // Chevron points right when closed, down when open (rotate 90°).
            WakiliIcon(
                WakiliIcons.ChevronRight,
                size = WakiliDimens.IconSm,
                tint = colors.muted,
                modifier = Modifier.rotate(if (closed) 0f else 90f),
            )
            GroupLabel(text = label, modifier = Modifier.weight(1f))
            // Count pill, only while collapsed (web .group-count).
            if (closed) {
                Text(
                    text = count.toString(),
                    style = ChromeMetaTextStyle,
                    color = colors.muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.panel2)
                        .padding(horizontal = WakiliDimens.Space8, vertical = WakiliDimens.Space2),
                )
            }
        }
        // ➕ starts a new chat here. A square the same height as the button next
        // to it — the ripple fills that square and nothing outside it.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(WakiliDimens.GroupHeadRow)
                .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                // While this ➕ is the one working, it shows a spinner and stops
                // taking taps; the others just go inert (enabled = false).
                .then(
                    if (creating) {
                        Modifier
                    } else {
                        Modifier.clickable(enabled = enabled, onClick = onAdd)
                    },
                ),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(WakiliDimens.IconLg),
                    color = colors.accent,
                    strokeWidth = WakiliDimens.BorderThick,
                )
            } else {
                WakiliIcon(
                    WakiliIcons.Plus,
                    size = WakiliDimens.IconXl,
                    tint = if (enabled) colors.text else colors.muted,
                )
            }
        }
    }
}

/**
 * .session — flat row: title (dimmed unless active), optional folder badge under
 * it (All-chats view), an optional model pill, and a status flag at the end.
 * Deletion alone uses a leading spinner. The active row gets the panel-2
 * background. Long-press opens the web's .sess-ctx menu (Rename / red Delete).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    row: SessionRowUi,
    badge: String?,
    model: String?,
    active: Boolean,
    deleting: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WakiliTheme.colors
    val session = row.session
    val titleColor = if (active) colors.text else lerp(colors.muted, colors.text, 0.55f)
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) colors.panel2 else androidx.compose.ui.graphics.Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        ) {
            if (deleting) {
                WakiliDangerSpinner(size = WakiliDimens.SpinnerRow)
            }
            Column(Modifier.weight(1f)) {
            Text(
                text = session.title.ifEmpty { stringResource(R.string.sessions_untitled) },
                style = ChromeRowTextStyle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = ChromeMetaTextStyle,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // .s-model — pill with the model name
        if (model != null) {
            Text(
                text = model,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = WakiliDimens.SessionModelMaxWidth)
                    .clip(CircleShape)
                    .background(colors.pill)
                    .padding(horizontal = WakiliDimens.Space8, vertical = WakiliDimens.Space2),
            )
            }
            when {
                !deleting && row.pending -> WakiliIcon(WakiliIcons.Lock, size = WakiliDimens.IconSm, tint = colors.accent)
                !deleting && row.busy -> StatusDot(color = colors.accent, pulse = true)
                !deleting && row.unread -> StatusDot(color = colors.accent)
            }
        }

        if (menuOpen) {
            val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                WakiliDimens.Space4.roundToPx()
            }
            WakiliAnchoredPopup(
                positionProvider = remember(gapPx) { BelowEndPositionProvider(gapPx) },
                onDismiss = { menuOpen = false },
            ) {
                WakiliCard(
                    radius = WakiliDimens.Radius,
                    contentPadding = WakiliDimens.Space6,
                    modifier = Modifier.width(WakiliDimens.ContextMenuWidth),
                ) {
                    CtxItem(WakiliIcons.Pencil, stringResource(R.string.sessions_rename), colors.text) {
                        menuOpen = false; onRename()
                    }
                    Spacer(Modifier.height(WakiliDimens.Space2))
                    CtxItem(WakiliIcons.Trash, stringResource(R.string.sessions_delete), colors.danger) {
                        menuOpen = false; onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(WakiliDimens.Space24),
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

/** .sess-ctx row — icon + label; Delete stays red. Compact rows matching the
 *  composer's + menu (AddMenuItem), not the ChromeRow sizing. */
@Composable
private fun CtxItem(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
    ) {
        WakiliIcon(icon, size = WakiliDimens.Icon, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The web .sess-ctx rename: an input with Cancel / Save. */
@Composable
private fun RenameDialog(
    session: SessionSummary,
    onRename: (String) -> Unit,
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
                text = stringResource(R.string.sessions_rename),
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
                    text = stringResource(R.string.sessions_save),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.sessions_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * The web .confirm-overlay: destructive actions deserve space and an obvious,
 * deliberate choice — "Delete chat?" + what will be lost, Cancel / red Delete.
 */
@Composable
private fun DeleteConfirmDialog(
    session: SessionSummary,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WakiliTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        shape = RoundedCornerShape(WakiliDimens.RadiusPanel),
        title = {
            Text(
                text = stringResource(R.string.sessions_delete_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.sessions_delete_message,
                    session.title.ifEmpty { stringResource(R.string.sessions_untitled) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.sessions_delete),
                    color = colors.danger,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.sessions_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/** FolderPicker — crumb bar, directory rows, new-folder field, primary confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(uiState: SessionsUiState, viewModel: SessionsViewModel) {
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
                // A real spinner in the directory list's own slot — the bare "…"
                // read as broken, and the sheet collapsed while folders loaded.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WakiliDimens.FolderListHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = WakiliDimens.BorderThick,
                        modifier = Modifier.size(WakiliDimens.IconLg),
                    )
                }
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
private fun FolderRow(name: String, icon: ImageVector, onClick: () -> Unit) {
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

/** Drops the popover directly under the filter button, right-aligned (.sess-pop). */
private class BelowEndPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (below + popupContentSize.height <= windowSize.height) below else above
        val clampedY = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, clampedY)
    }
}
