package com.ahmedgamil.wakili.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.datastore.ThemeMode
import com.ahmedgamil.wakili.core.designsystem.component.SectionLabel
import com.ahmedgamil.wakili.core.designsystem.component.SegmentedTab
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSegmentedTabs
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSwitchRow
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSwitchRowStyle
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.AccentPalette
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeMetaTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.ChromeRowTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.theme.toHexString
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SWATCH_COLUMNS = 8

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConnect: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors
    // .set-tabs — Connection | Device | Other, mirroring the web Settings panel.
    // Connection is the primary settings surface and the default tab.
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        // .fp-head — back + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
        ) {
            WakiliRoundIconButton(icon = WakiliIcons.CornerUpLeft, onClick = onBack)
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        WakiliSegmentedTabs(
            tabs = listOf(
                SegmentedTab(stringResource(R.string.settings_connection)),
                SegmentedTab(stringResource(R.string.settings_device)),
                SegmentedTab(stringResource(R.string.settings_other)),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
        // .set-content — one scroll area; each tab owns the whole body.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = WakiliDimens.Space24),
        ) {
            when (tab) {
                0 -> ConnectionTab(uiState, viewModel, onOpenConnect)
                1 -> DeviceTab(uiState, viewModel)
                else -> OtherTab(uiState, viewModel)
            }
        }
    }
}

/** "Other" tab — the web appr-body: Formatting, Theme, Accent color, Language. */
@Composable
private fun OtherTab(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    // .appr-label "Formatting"
    SectionLabel(
        text = stringResource(R.string.settings_formatting),
        modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
    )
    WakiliSwitchRow(
        label = stringResource(R.string.settings_markdown),
        checked = uiState.settings.markdown,
        onCheckedChange = viewModel::setMarkdown,
        icon = WakiliIcons.Type,
        style = WakiliSwitchRowStyle.Flat,
        contentSpacing = WakiliDimens.Space11,
        textStyle = ChromeRowTextStyle,
        modifier = Modifier.padding(horizontal = WakiliDimens.Space8),
    )
    // Background notifications — enabling asks for POST_NOTIFICATIONS on 13+.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setNotifications(granted) }
    WakiliSwitchRow(
        label = stringResource(R.string.settings_notifications),
        checked = uiState.notifications,
        onCheckedChange = { on ->
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setNotifications(on)
            }
        },
        icon = WakiliIcons.Zap,
        style = WakiliSwitchRowStyle.Flat,
        contentSpacing = WakiliDimens.Space11,
        textStyle = ChromeRowTextStyle,
        modifier = Modifier.padding(horizontal = WakiliDimens.Space8),
    )
    // Floating question alerts (questions ONLY): a persistent card over other
    // apps when the agent asks something — a heads-up notification retreats in
    // seconds and is easy to miss. Needs the OS "display over other apps"
    // grant, so the toggle reflects REALITY (pref AND grant): with the pref
    // defaulting on, a toggle that showed "on" while the grant was missing
    // looked enabled yet silently never fired. It re-checks on resume, so
    // coming back from the OS page flips it on the moment it's truly granted.
    val context = androidx.compose.ui.platform.LocalContext.current
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    WakiliSwitchRow(
        label = stringResource(R.string.settings_floating_questions),
        checked = uiState.floatingQuestions && overlayGranted,
        onCheckedChange = { on ->
            viewModel.setFloatingQuestions(on)
            if (on && !overlayGranted) {
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        },
        icon = WakiliIcons.Help,
        style = WakiliSwitchRowStyle.Flat,
        contentSpacing = WakiliDimens.Space11,
        textStyle = ChromeRowTextStyle,
        labelMaxLines = 3,
        modifier = Modifier.padding(horizontal = WakiliDimens.Space8),
    )
    // .appr-label "Theme" — sun/moon/settings segment
    SectionLabel(
        text = stringResource(R.string.settings_theme),
        modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
    )
    val themeTabs = listOf(
        ThemeMode.LIGHT to SegmentedTab(stringResource(R.string.settings_theme_light), WakiliIcons.Sun),
        ThemeMode.DARK to SegmentedTab(stringResource(R.string.settings_theme_dark), WakiliIcons.Moon),
        ThemeMode.SYSTEM to SegmentedTab(stringResource(R.string.settings_theme_system), WakiliIcons.Settings),
    )
    WakiliSegmentedTabs(
        tabs = themeTabs.map { it.second },
        selectedIndex = themeTabs.indexOfFirst { it.first == uiState.settings.theme },
        onSelect = { viewModel.setTheme(themeTabs[it].first) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16),
    )
    // .appr-label "Accent color" — 8-per-row swatch grid
    SectionLabel(
        text = stringResource(R.string.settings_accent),
        modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
    )
    AccentPalette.chunked(SWATCH_COLUMNS).forEach { rowColors ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space6),
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space9),
        ) {
            rowColors.forEach { option ->
                AccentSwatch(
                    color = option.color,
                    selected = uiState.settings.accentHex.equals(option.color.toHexString(), ignoreCase = true),
                    onSelect = { viewModel.setAccent(option.color.toHexString()) },
                )
            }
        }
    }
    // .appr-label "Language"
    SectionLabel(
        text = stringResource(R.string.settings_language),
        modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
    )
    val appLanguage = AppCompatDelegate.getApplicationLocales()
        .toLanguageTags()
        .substringBefore(',')
        .substringBefore('-')
        .ifEmpty { Locale.getDefault().language }
    WakiliSegmentedTabs(
        tabs = listOf(SegmentedTab("English"), SegmentedTab("العربية")),
        selectedIndex = if (appLanguage == "ar") 1 else 0,
        onSelect = { viewModel.setLanguage(if (it == 0) "en" else "ar") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16),
    )
}

/**
 * "Device" tab — the web DeviceMenu, row for row: lock / screen-off, the
 * keep-awake and start-with-computer toggles, then the two-tap shutdown last.
 */
@Composable
private fun DeviceTab(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    DeviceActionRow(
        icon = WakiliIcons.Lock,
        label = stringResource(R.string.settings_lock),
        action = viewModel::lockScreen,
    )
    DeviceActionRow(
        icon = WakiliIcons.MonitorOff,
        label = stringResource(R.string.settings_screen_off),
        action = viewModel::screenOff,
    )
    uiState.power?.let { power ->
        WakiliSwitchRow(
            label = stringResource(R.string.settings_keep_awake),
            checked = power.keepAwake,
            onCheckedChange = viewModel::keepAwake,
            icon = WakiliIcons.Zap,
            style = WakiliSwitchRowStyle.Flat,
            contentSpacing = WakiliDimens.Space11,
            textStyle = ChromeRowTextStyle,
            modifier = Modifier.padding(horizontal = WakiliDimens.Space8),
        )
    }
    uiState.autostart?.takeIf { it.supported }?.let { autostart ->
        WakiliSwitchRow(
            label = stringResource(R.string.settings_autostart),
            checked = autostart.on,
            onCheckedChange = viewModel::setAutostart,
            icon = WakiliIcons.Power,
            style = WakiliSwitchRowStyle.Flat,
            contentSpacing = WakiliDimens.Space11,
            textStyle = ChromeRowTextStyle,
            modifier = Modifier.padding(horizontal = WakiliDimens.Space8),
        )
    }
    // Destructive, so it sits last and takes two taps — a stray touch in a
    // settings list must not be able to turn the computer off.
    DeviceConfirmRow(
        icon = WakiliIcons.PowerOff,
        label = stringResource(R.string.settings_shutdown),
        action = viewModel::shutdown,
    )
}

/** "Connection" tab — the web EndpointMenu: switch between reachable hosts. */
@Composable
private fun ConnectionTab(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenConnect: () -> Unit,
) {
    val colors = WakiliTheme.colors
    if (uiState.switchError) {
        Text(
            text = stringResource(R.string.settings_switch_failed),
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
    }
    uiState.endpoints.forEach { endpoint ->
        val current = uiState.currentHost.contains(endpoint.host)
        EndpointRow(
            label = endpoint.label,
            host = endpoint.host,
            current = current,
            enabled = !current && !uiState.switching,
            onClick = { viewModel.switchTo(endpoint) },
        )
    }
    // The react-native hosts page, over the live session: saved gateways,
    // scan/type a new one, switch between them.
    DeviceRow(
        icon = WakiliIcons.Wifi,
        label = stringResource(R.string.settings_manage_hosts),
        onClick = onOpenConnect,
    )
}

/** .dev-row — icon + label action row inside the device menu. */
@Composable
private fun DeviceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DeviceRowShell(icon = icon, label = label, onClick = onClick)
}

/** How long a finished row keeps its verdict before falling back to the idle
 *  chevron, and how long an armed shutdown row waits for the confirming tap —
 *  both the web DeviceMenu's timings. */
private const val DEVICE_STATUS_MS = 1800L
private const val DEVICE_CONFIRM_MS = 4000L

/** .dev-status — the right edge of a device row: an idle chevron until it is
 *  tapped, then how that particular action is going. */
private enum class DeviceStatus { Idle, Armed, Running, Ok, Failed }

/**
 * .dev-row — the shared shell: icon, label, and whatever the row shows at its
 * right edge. [armed] paints .dev-row.dev-danger.armed's red wash.
 */
@Composable
private fun DeviceRowShell(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    armed: Boolean = false,
    tail: (@Composable () -> Unit)? = null,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space11),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space8)
            .clip(shape)
            .background(
                if (armed) colors.danger.copy(alpha = WakiliDimens.AlphaErrorWash) else Color.Transparent,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space11),
    ) {
        WakiliIcon(icon, tint = colors.muted)
        Text(
            text = label,
            style = ChromeRowTextStyle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (tail == null) Modifier else Modifier.weight(1f),
        )
        tail?.invoke()
    }
}

/** The right edge for a row that has been tapped: "Started…", then the verdict.
 *  [doneLabel] is what success reads as ("Finished" / "Shutting down"). */
@Composable
private fun DeviceStatusTail(status: DeviceStatus, doneLabel: String) {
    val colors = WakiliTheme.colors
    when (status) {
        DeviceStatus.Idle -> WakiliIcon(
            WakiliIcons.ChevronRight,
            size = WakiliDimens.IconSm,
            tint = colors.muted,
        )

        DeviceStatus.Armed -> DeviceNote(
            stringResource(R.string.settings_confirm_again),
            colors.danger,
        )

        DeviceStatus.Running -> DeviceNote(
            stringResource(R.string.settings_started),
            colors.muted,
        )

        DeviceStatus.Ok -> DeviceNote(doneLabel, colors.accent, WakiliIcons.Check)
        DeviceStatus.Failed -> DeviceNote(
            stringResource(R.string.settings_status_failed),
            colors.danger,
            WakiliIcons.X,
        )
    }
}

/** .dev-note — the small status line, optionally led by a check or an ✕. */
@Composable
private fun DeviceNote(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
    ) {
        icon?.let { WakiliIcon(it, size = WakiliDimens.IconSm, tint = color) }
        Text(text = text, style = ChromeMetaTextStyle, color = color, maxLines = 1)
    }
}

/**
 * A one-shot device action (Lock screen / Turn off screen). One tap fires it
 * once; the row reports its own progress and refuses further taps until the
 * verdict has been shown, so a double tap can't fire it twice.
 */
@Composable
private fun DeviceActionRow(icon: ImageVector, label: String, action: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(DeviceStatus.Idle) }
    DeviceRowShell(
        icon = icon,
        label = label,
        onClick = {
            if (status == DeviceStatus.Idle) {
                scope.launch {
                    status = DeviceStatus.Running
                    status = if (action()) DeviceStatus.Ok else DeviceStatus.Failed
                    delay(DEVICE_STATUS_MS)
                    status = DeviceStatus.Idle
                }
            }
        },
        tail = { DeviceStatusTail(status, stringResource(R.string.settings_finished)) },
    )
}

/**
 * A destructive one-shot (Shut down computer). The first tap only ARMS the row
 * — it turns red and asks for a second tap, disarming itself after a few
 * seconds — so a stray touch can't shut the computer down.
 */
@Composable
private fun DeviceConfirmRow(icon: ImageVector, label: String, action: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(DeviceStatus.Idle) }
    // The confirming tap has to cancel the arming tap's timer, or it would fire
    // and drop the row back to Idle underneath a shutdown already in flight.
    var disarm by remember { mutableStateOf<Job?>(null) }
    DeviceRowShell(
        icon = icon,
        label = label,
        armed = status == DeviceStatus.Armed,
        onClick = {
            when (status) {
                DeviceStatus.Idle -> {
                    status = DeviceStatus.Armed
                    disarm = scope.launch {
                        delay(DEVICE_CONFIRM_MS)
                        status = DeviceStatus.Idle
                    }
                }

                DeviceStatus.Armed -> {
                    disarm?.cancel()
                    scope.launch {
                        status = DeviceStatus.Running
                        status = if (action()) DeviceStatus.Ok else DeviceStatus.Failed
                        delay(DEVICE_CONFIRM_MS)
                        status = DeviceStatus.Idle
                    }
                }

                else -> Unit // in flight, or still showing its verdict
            }
        },
        tail = { DeviceStatusTail(status, stringResource(R.string.settings_shutting_down)) },
    )
}

/** .ep-row — endpoint card: label, mono host, check for the current one. */
@Composable
private fun EndpointRow(
    label: String,
    host: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusLg)
    // The connection IN USE is the highlighted one (accent ring + tint, full
    // brightness); the other rows read as available options, slightly quieter.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4)
            .clip(shape)
            .background(
                if (current) {
                    colors.accent.copy(alpha = WakiliDimens.AlphaErrorWash)
                        .compositeOver(colors.panel2)
                } else {
                    colors.panel2
                },
                shape,
            )
            .border(WakiliDimens.BorderThin, if (current) colors.accent else colors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(WakiliDimens.Space12),
    ) {
        Text(
            text = label,
            style = ChromeRowTextStyle.copy(fontWeight = MaterialTheme.typography.labelLarge.fontWeight),
            color = if (current) colors.text else colors.muted,
        )
        Text(
            text = host,
            style = WakiliMono.Label,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        WakiliIcon(
            if (current) WakiliIcons.Check else WakiliIcons.ArrowUpRight,
            size = WakiliDimens.IconSm,
            tint = if (current) colors.accent else colors.muted,
        )
    }
}

/** .swatch — accent circle; the selected one gets the double ring. */
@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onSelect: () -> Unit) {
    val colors = WakiliTheme.colors
    Box(
        modifier = Modifier
            .size(WakiliDimens.Swatch)
            .let { m ->
                if (selected) {
                    m
                        .border(WakiliDimens.SwatchRing, colors.text, CircleShape)
                        .padding(WakiliDimens.SwatchRing * 2)
                } else {
                    m
                }
            }
            .clip(CircleShape)
            .background(color, CircleShape)
            .clickable(onClick = onSelect),
    )
}
