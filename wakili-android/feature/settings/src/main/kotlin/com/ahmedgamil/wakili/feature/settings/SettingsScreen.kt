package com.ahmedgamil.wakili.feature.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.AccentPalette
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.theme.toHexString

private const val SWATCH_COLUMNS = 8

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = WakiliDimens.Space24),
    ) {
        // header
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

        // ---- appearance (web AppearanceMenu) ----
        SectionLabel(
            text = stringResource(R.string.settings_appearance),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
        // .appr-seg — theme segment with sun/moon/settings glyphs
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
        // .swatches — 8-per-row accent grid
        AccentPalette.chunked(SWATCH_COLUMNS).forEach { rowColors ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = WakiliDimens.Space16,
                        vertical = WakiliDimens.Space6,
                    ),
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
        WakiliSwitchRow(
            label = stringResource(R.string.settings_markdown),
            checked = uiState.settings.markdown,
            onCheckedChange = viewModel::setMarkdown,
            icon = WakiliIcons.Type,
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )

        // ---- language ----
        SectionLabel(
            text = stringResource(R.string.settings_language),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
        WakiliSegmentedTabs(
            tabs = listOf(SegmentedTab("English"), SegmentedTab("العربية")),
            selectedIndex = -1,
            onSelect = { viewModel.setLanguage(if (it == 0) "en" else "ar") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16),
        )

        // ---- computer (web DeviceMenu) ----
        SectionLabel(
            text = stringResource(R.string.settings_device),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
        DeviceRow(
            icon = WakiliIcons.Lock,
            label = stringResource(R.string.settings_lock),
            onClick = viewModel::lockScreen,
        )
        DeviceRow(
            icon = WakiliIcons.MonitorOff,
            label = stringResource(R.string.settings_screen_off),
            onClick = viewModel::screenOff,
        )
        DeviceRow(
            icon = WakiliIcons.Moon,
            label = stringResource(R.string.settings_lock_off),
            onClick = viewModel::lockAndOff,
        )
        uiState.deviceNotice?.let { notice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                modifier = Modifier
                    .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4)
                    .clickable { viewModel.clearNotice() },
            ) {
                WakiliIcon(
                    if (notice == "ok") WakiliIcons.Check else WakiliIcons.X,
                    size = WakiliDimens.IconSm,
                    tint = if (notice == "ok") colors.accent else colors.danger,
                )
                Text(
                    text = if (notice == "ok") {
                        stringResource(R.string.settings_done)
                    } else {
                        stringResource(R.string.settings_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice == "ok") colors.accent else colors.danger,
                )
            }
        }
        uiState.power?.let { power ->
            WakiliSwitchRow(
                label = stringResource(R.string.settings_keep_awake),
                checked = power.keepAwake,
                onCheckedChange = viewModel::keepAwake,
                icon = WakiliIcons.Zap,
                modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4),
            )
        }
        uiState.autostart?.takeIf { it.supported }?.let { autostart ->
            WakiliSwitchRow(
                label = stringResource(R.string.settings_autostart),
                checked = autostart.on,
                onCheckedChange = viewModel::setAutostart,
                icon = WakiliIcons.Power,
                modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4),
            )
        }

        // ---- connection (web EndpointMenu) ----
        SectionLabel(
            text = stringResource(R.string.settings_connection),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        )
        if (uiState.switchError) {
            Text(
                text = stringResource(R.string.settings_switch_failed),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = WakiliDimens.Space16),
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
    }
}

/** .dev-row — icon + label action row inside the device menu. */
@Composable
private fun DeviceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space11),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space8)
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space11),
    ) {
        WakiliIcon(icon, tint = colors.muted)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
    }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4)
            .alpha(if (current) WakiliDimens.AlphaCurrent else 1f)
            .clip(shape)
            .background(colors.panel2, shape)
            .border(WakiliDimens.BorderThin, colors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(WakiliDimens.Space12),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.text,
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
