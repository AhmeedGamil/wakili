package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDropdown
import com.ahmedgamil.wakili.core.designsystem.component.WakiliDropdownOption
import com.ahmedgamil.wakili.core.designsystem.component.WakiliSwitchRow
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.AgentControl
import com.ahmedgamil.wakili.core.model.AgentManifest
import com.ahmedgamil.wakili.feature.chat.R

/**
 * Model / controls panel — the web ModelPicker's .picker-pop: a popover anchored
 * under the topbar pill, rendered generically from the agent manifest so new
 * server-side controls appear with no changes. The AGENT is NOT here — it's
 * chosen on the home page and fixed per chat; what remains is "Continue with
 * <other agent>" at the bottom, the handoff into a new chat.
 */
@Composable
fun ControlsPopover(
    agents: List<AgentManifest>,
    agentId: String,
    controls: Map<String, String>,
    autoContinue: Boolean,
    onSwitchAgent: (String) -> Unit,
    onControl: (String, String) -> Unit,
    onAutoContinue: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // A FULL-SCREEN popup with our own scrim, instead of relying on Compose's
    // outside-tap dismissal: that fires on touch-DOWN, so a press-and-hold
    // anywhere closed the menu before the finger lifted. detectTapGestures fires
    // on RELEASE, which is what a dismissing tap should mean (and it matches the
    // web's backdrop shield). The card sits at the bottom, above the composer.
    Popup(
        popupPositionProvider = remember { FullScreenPositionProvider },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() } // on finger UP, not down
                },
            contentAlignment = Alignment.BottomStart,
        ) {
        WakiliCard(
            radius = WakiliDimens.RadiusPanel,
            contentPadding = WakiliDimens.Space12,
            modifier = Modifier
                .padding(
                    start = WakiliDimens.Space16,
                    end = WakiliDimens.Space16,
                    bottom = WakiliDimens.ComposerPickerGap,
                )
                .width(WakiliDimens.PickerPopWidth)
                .heightIn(max = WakiliDimens.PickerPopMaxHeight)
                // Taps on the card itself must not reach the scrim behind it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
            ) {
                val agent = agents.firstOrNull { it.id == agentId }
                agent?.controls?.forEach { (key, control) ->
                    ControlDropdown(key, control, controls[key], controls["model"], onControl)
                }
                // .switch-row — Auto continue: the gateway re-drives a turn this
                // chat loses to the usage limit once quota returns. On by
                // default; this switch is how a chat opts out.
                WakiliSwitchRow(
                    label = stringResource(R.string.chat_auto_continue),
                    checked = autoContinue,
                    onCheckedChange = onAutoContinue,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(min = WakiliDimens.ControlRowHeight),
                )
                // "Continue with" — this chat's agent is fixed, but the
                // conversation can move. One expandable row (like the other
                // controls) whose options are the OTHER agents; picking one opens
                // a new chat for it with the transcript attached (the handoff).
                val others = agents.filter { it.id != agentId }
                if (others.isNotEmpty()) {
                    WakiliDropdown(
                        label = stringResource(R.string.chat_continue_with_label),
                        options = others.map { WakiliDropdownOption(it.id, it.label, it.description) },
                        selected = "",
                        onSelect = { onDismiss(); onSwitchAgent(it) },
                    )
                }
            }
        }
        }
    }
}

/** Places the popup over the whole window, so there is no "outside" for
 *  Compose to auto-dismiss on touch-down; our scrim handles taps on release. */
private object FullScreenPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
private fun ControlDropdown(
    key: String,
    control: AgentControl,
    value: String?,
    model: String?,
    onControl: (String, String) -> Unit,
) {
    // A control can scope its options to the selected model (e.g. Codex's
    // effort ladder: ultra only on models that take it).
    val options = model?.let { control.optionsFor[it] } ?: control.options
    WakiliDropdown(
        label = control.label.ifEmpty { key },
        options = options.map { WakiliDropdownOption(it.value, it.label) },
        selected = value ?: control.default.orEmpty(),
        onSelect = { onControl(key, it) },
    )
}
