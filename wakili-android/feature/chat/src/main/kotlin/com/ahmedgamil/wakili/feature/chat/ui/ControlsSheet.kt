package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ahmedgamil.wakili.core.designsystem.component.SectionLabel
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDropdown
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDropdownOption
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSwitchRow
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.model.AgentControl
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.feature.chat.R

/**
 * Agent / model / controls panel — the web ModelPicker's .picker-pop: a
 * popover anchored under the topbar pill, rendered generically from the agent
 * manifest so new server-side controls appear with no changes.
 */
@Composable
fun ControlsPopover(
    agents: List<AgentManifest>,
    agentId: String,
    controls: Map<String, String>,
    autoAllow: Boolean,
    onAgent: (String) -> Unit,
    onControl: (String, String) -> Unit,
    onAutoAllow: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // .picker-pop { top: calc(100% + 8px) } — drop below the 42dp trigger.
    val offsetY = with(LocalDensity.current) {
        (WakiliDimens.RoundButton + WakiliDimens.Space8).roundToPx()
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        WakiliCard(
            radius = WakiliDimens.RadiusPanel,
            contentPadding = WakiliDimens.Space10,
            modifier = Modifier
                .width(WakiliDimens.PickerPopWidth)
                .heightIn(max = WakiliDimens.PickerPopMaxHeight),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            ) {
                SectionLabel(stringResource(R.string.chat_agent))
                WakiliDropdown(
                    label = stringResource(R.string.chat_agent),
                    options = agents.map { WakiliDropdownOption(it.id, it.label, it.description) },
                    selected = agentId,
                    onSelect = onAgent,
                )
                val agent = agents.firstOrNull { it.id == agentId }
                agent?.controls?.forEach { (key, control) ->
                    ControlDropdown(key, control, controls[key], onControl)
                }
                // .switch-row — Allow always
                WakiliSwitchRow(
                    label = stringResource(R.string.chat_auto_allow),
                    checked = autoAllow,
                    onCheckedChange = onAutoAllow,
                    icon = WakiliIcons.Lock,
                )
            }
        }
    }
}

@Composable
private fun ControlDropdown(
    key: String,
    control: AgentControl,
    value: String?,
    onControl: (String, String) -> Unit,
) {
    WakiliDropdown(
        label = control.label.ifEmpty { key },
        options = control.options.map { WakiliDropdownOption(it.value, it.label) },
        selected = value ?: control.default.orEmpty(),
        onSelect = { onControl(key, it) },
    )
}
