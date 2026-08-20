package com.ahmedgamil.wakili.feature.connect

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliAnchoredPopup
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectButtonTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectCurrentTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectGhostTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectHostNameTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectHostTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectInputTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectListLabelTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectSubtitleTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ConnectTitleTextStyle
import com.ahmedgamil.wakili.core.designsystem.R as DesignR

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors
    // Rename / remove flow — hoisted here so their dialogs render CENTERED over
    // the screen, not inside the cramped hosts dropdown row.
    var renamingHost by remember { mutableStateOf<SavedHostUi?>(null) }
    var removingHost by remember { mutableStateOf<SavedHostUi?>(null) }

    LaunchedEffect(uiState.connected) {
        if (uiState.connected) onConnected()
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onScanRequested()
    }

    if (uiState.scanning) {
        Box(modifier = modifier.fillMaxSize()) {
            QrScanner(
                onQr = viewModel::onQrScanned,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = WakiliDimens.Space48),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            ) {
                Text(
                    text = stringResource(R.string.connect_scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                WakiliButton(
                    text = stringResource(R.string.connect_cancel),
                    onClick = viewModel::onScanCancelled,
                    style = WakiliButtonStyle.Ghost,
                    icon = WakiliIcons.X,
                )
            }
        }
        return
    }

    // The react-native ConnectScreen, exactly: full-screen centered content on
    // the app background (no card) — logo, wordmark, then ONE sub line that
    // swaps by state (error text REPLACES it, muted like RN; then the pick-a-
    // host prompt; then the first-run scan prompt), hosts dropdown, buttons,
    // manual URL row. Scrollable so a long host list + keyboard always fits.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(WakiliDimens.Space24),
        contentAlignment = Alignment.Center,
    ) {
        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.wakili_logo),
                    contentDescription = null,
                    modifier = Modifier.size(WakiliDimens.LogoSize),
                )
                Spacer(Modifier.height(WakiliDimens.Space12))
                // RN styles.title — 28sp bold wordmark
                Text(
                    text = stringResource(R.string.connect_title),
                    style = ConnectTitleTextStyle,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(WakiliDimens.Space8))
                // RN styles.sub — one muted line, swapped by state
                val error = uiState.error
                Text(
                    text = when {
                        error != null -> stringResource(
                            when (error) {
                                ConnectError.INVALID_URL -> R.string.connect_error_invalid
                                ConnectError.NEEDS_TOKEN -> R.string.connect_error_needs_token
                                ConnectError.UNAUTHORIZED -> R.string.connect_error_unauthorized
                                ConnectError.UNREACHABLE -> R.string.connect_error_unreachable
                            },
                        )
                        uiState.hosts.isNotEmpty() -> stringResource(R.string.connect_pick_host)
                        else -> stringResource(R.string.connect_subtitle)
                    },
                    style = ConnectSubtitleTextStyle,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(WakiliDimens.Space24))
                if (uiState.connecting) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.height(WakiliDimens.Space12))
                    Text(
                        text = stringResource(R.string.connect_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                } else {
                    // Saved hosts (react-native ConnectScreen): a "Current host"
                    // dropdown; Connect acts on the selection, so retrying the
                    // current host and switching to another are the same gesture.
                    if (uiState.hosts.isNotEmpty()) {
                        HostDropdown(
                            hosts = uiState.hosts,
                            selected = uiState.selected,
                            onSelect = viewModel::selectHost,
                            onRequestRename = { renamingHost = it },
                            onRequestRemove = { removingHost = it },
                        )
                        Spacer(Modifier.height(WakiliDimens.Space12))
                        ConnectActionButton(
                            text = stringResource(R.string.connect_connect),
                            onClick = viewModel::connectSelected,
                            primary = true,
                        )
                        Spacer(Modifier.height(WakiliDimens.Space12))
                    }
                    // RN btnSecondary — flat card-colored button (Primary on first run)
                    ConnectActionButton(
                        text = stringResource(R.string.connect_scan_qr),
                        onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                        primary = uiState.hosts.isEmpty(),
                    )
                    Spacer(Modifier.height(WakiliDimens.Space12))
                    onBack?.let {
                        ConnectGhostButton(
                            text = stringResource(R.string.connect_back),
                            onClick = it,
                        )
                    }
                    Spacer(Modifier.height(WakiliDimens.Space8))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                    ) {
                        ConnectManualField(
                            value = uiState.manualUrl,
                            onValueChange = viewModel::onManualUrlChange,
                            onSubmit = viewModel::onSubmitManual,
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.connect_manual_hint),
                        )
                        ConnectGoButton(
                            text = stringResource(R.string.connect_go),
                            onClick = viewModel::onSubmitManual,
                            enabled = uiState.canSubmit,
                        )
                    }
                }
            }
    }

    renamingHost?.let { host ->
        RenameHostDialog(
            host = host,
            onRename = { viewModel.renameHost(host.profile.baseUrl, it); renamingHost = null },
            onDismiss = { renamingHost = null },
        )
    }
    removingHost?.let { host ->
        RemoveHostDialog(
            host = host,
            onRemove = { viewModel.removeHost(host.profile.baseUrl); removingHost = null },
            onDismiss = { removingHost = null },
        )
    }
}

/** Rename a saved host — an input seeded with its current name, Cancel / Save. */
@Composable
private fun RenameHostDialog(
    host: SavedHostUi,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var name by remember { mutableStateOf(host.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        shape = RoundedCornerShape(WakiliDimens.RadiusPanel),
        title = {
            Text(
                text = stringResource(R.string.connect_rename_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        },
        text = {
            ConnectManualField(
                value = name,
                onValueChange = { name = it },
                onSubmit = { onRename(name) },
                placeholder = host.host,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) {
                Text(
                    text = stringResource(R.string.connect_save),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.connect_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/** Remove a saved host — a deliberate centered confirmation (the token is lost
 *  with it), replacing the old two-tap-in-place remove. */
@Composable
private fun RemoveHostDialog(
    host: SavedHostUi,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WakiliTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        shape = RoundedCornerShape(WakiliDimens.RadiusPanel),
        title = {
            Text(
                text = stringResource(R.string.connect_remove_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.connect_remove_message, host.name),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.connect_remove),
                    color = colors.danger,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.connect_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

@Composable
private fun ConnectActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusLg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (primary) colors.accent else colors.panel, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space32, vertical = WakiliDimens.Space14),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = ConnectButtonTextStyle,
            color = if (primary) Color.White else colors.text,
        )
    }
}

@Composable
private fun ConnectGhostButton(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(WakiliDimens.Space14),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = ConnectGhostTextStyle,
            color = WakiliTheme.colors.muted,
        )
    }
}

@Composable
private fun ConnectManualField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(shape)
            .background(colors.panel, shape)
            .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space12),
        textStyle = ConnectInputTextStyle.copy(color = colors.text),
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = ConnectInputTextStyle,
                        color = colors.muted.copy(alpha = WakiliDimens.AlphaSubtle),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun ConnectGoButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else WakiliDimens.AlphaConnectDisabled)
            .clip(shape)
            .background(colors.accent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = WakiliDimens.Space18, vertical = WakiliDimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = ConnectButtonTextStyle,
            color = Color.White,
        )
    }
}

/**
 * The RN "Current host" dropdown: collapsed it's one row (the selection) so it
 * never pushes the buttons down; expanded it lists every saved host in a
 * bounded scrollable box, with a two-tap remove so a token isn't lost by a slip.
 */
@Composable
private fun HostDropdown(
    hosts: List<SavedHostUi>,
    selected: SavedHostUi?,
    onSelect: (String) -> Unit,
    onRequestRename: (SavedHostUi) -> Unit,
    onRequestRemove: (SavedHostUi) -> Unit,
) {
    val colors = WakiliTheme.colors
    var open by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(WakiliDimens.RadiusLg)
    // Open: the row keeps only its top corners, joined to the menu below (RN
    // selectOpen).
    val rowShape = if (open) {
        RoundedCornerShape(topStart = WakiliDimens.RadiusLg, topEnd = WakiliDimens.RadiusLg)
    } else {
        shape
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        Text(
            text = stringResource(R.string.connect_current_host).uppercase(),
            style = ConnectListLabelTextStyle,
            color = colors.muted.copy(alpha = WakiliDimens.AlphaSubtle),
        )
        Box(Modifier.fillMaxWidth().onGloballyPositioned { anchorSize = it.size }) {
        // Closed state: one row showing the selection.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(colors.panel, rowShape)
                .border(WakiliDimens.BorderThin, if (open) colors.accent else colors.border, rowShape)
                .clickable { open = !open }
                .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space12),
        ) {
            Column(Modifier.weight(1f).padding(end = WakiliDimens.Space10)) {
                Text(
                    text = selected?.name.orEmpty(),
                    style = ConnectHostNameTextStyle,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(WakiliDimens.Space2))
                Text(
                    text = selected?.host.orEmpty(),
                    style = ConnectHostTextStyle,
                    color = colors.muted.copy(alpha = WakiliDimens.AlphaSubtle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WakiliIcon(
                WakiliIcons.ChevronDown,
                size = WakiliDimens.IconXs,
                tint = colors.muted,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        // Open state: the options FLOAT over the buttons below in a bounded
        // scrollable box (RN's modal menu) — opening never shifts Connect /
        // Scan QR, and a tap outside dismisses.
        if (open) {
            val menuShape = RoundedCornerShape(
                bottomStart = WakiliDimens.RadiusLg,
                bottomEnd = WakiliDimens.RadiusLg,
            )
            // Same placement as the old Popup(alignment = TopStart, offset =
            // (0, anchorHeight)) — expressed as a provider so it can share the
            // app's one dismissal recipe (outside taps close on RELEASE).
            val dropHeight = anchorSize.height
            WakiliAnchoredPopup(
                positionProvider = remember(dropHeight) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.top + dropHeight)
                    }
                },
                onDismiss = { open = false },
            ) {
            Column(
                modifier = Modifier
                    .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                    .clip(menuShape)
                    .background(colors.panel)
                    .drawBehind {
                        val stroke = WakiliDimens.BorderThin.toPx()
                        val half = stroke / 2
                        drawLine(colors.accent, Offset(half, 0f), Offset(half, size.height), stroke)
                        drawLine(
                            colors.accent,
                            Offset(size.width - half, 0f),
                            Offset(size.width - half, size.height),
                            stroke,
                        )
                        drawLine(
                            colors.accent,
                            Offset(0f, size.height - half),
                            Offset(size.width, size.height - half),
                            stroke,
                        )
                    }
                    .heightIn(max = WakiliDimens.ConnectHostMenuMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                hosts.forEach { host ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (host.profile.baseUrl == selected?.profile?.baseUrl) {
                                    colors.bg
                                } else {
                                    colors.panel
                                },
                            )
                            .drawBehind {
                                val stroke = WakiliDimens.BorderThin.toPx()
                                drawLine(colors.border, Offset.Zero, Offset(size.width, 0f), stroke)
                            }
                            .clickable {
                                onSelect(host.profile.baseUrl)
                                open = false
                            }
                            .padding(start = WakiliDimens.Space14, top = WakiliDimens.Space12, bottom = WakiliDimens.Space12, end = WakiliDimens.Space6),
                    ) {
                        Column(Modifier.weight(1f).padding(end = WakiliDimens.Space10)) {
                            Text(
                                text = host.name,
                                style = ConnectHostNameTextStyle,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(WakiliDimens.Space2))
                            Text(
                                text = host.host,
                                style = ConnectHostTextStyle,
                                color = colors.muted.copy(alpha = WakiliDimens.AlphaSubtle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (host.active) {
                            Text(
                                text = stringResource(R.string.connect_current_mark).uppercase(),
                                style = ConnectCurrentTextStyle,
                                color = colors.accent,
                                modifier = Modifier.padding(end = WakiliDimens.Space8),
                            )
                        }
                        // Rename (pencil) + Remove (✕). Remove no longer confirms
                        // in-place — it opens a centered dialog, so a slip on this
                        // cramped row can't silently drop the host + its token.
                        WakiliIcon(
                            WakiliIcons.Pencil,
                            size = WakiliDimens.IconSm,
                            tint = colors.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(WakiliDimens.RadiusXs))
                                .clickable { open = false; onRequestRename(host) }
                                .padding(WakiliDimens.Space6),
                        )
                        Spacer(Modifier.width(WakiliDimens.Space2))
                        WakiliIcon(
                            WakiliIcons.Trash,
                            size = WakiliDimens.IconSm,
                            tint = colors.danger,
                            modifier = Modifier
                                .clip(RoundedCornerShape(WakiliDimens.RadiusXs))
                                .clickable { open = false; onRequestRemove(host) }
                                .padding(WakiliDimens.Space6),
                        )
                    }
                }
            }
            }
        }
        }
    }
}
