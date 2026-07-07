package com.ahmedgamil.wakili.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

/** .switch — 38×22 pill track, 18dp knob; accent when on, border color off. */
@Composable
fun WakiliSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else colors.border, label = "track")
    val travel = WakiliDimens.SwitchWidth - WakiliDimens.SwitchKnob - WakiliDimens.SwitchKnobInset * 2
    val offset by animateDpAsState(if (checked) travel else 0.dp, label = "knob")
    Box(
        modifier = modifier
            .size(width = WakiliDimens.SwitchWidth, height = WakiliDimens.SwitchHeight)
            .clip(CircleShape)
            .background(track)
            .let { m ->
                onCheckedChange?.let { m.clickable { it(!checked) } } ?: m
            }
            .padding(WakiliDimens.SwitchKnobInset),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(WakiliDimens.SwitchKnob)
                .background(Color.White, CircleShape),
        )
    }
}

enum class WakiliSwitchRowStyle { Panel, Flat }

/** A full-width labeled switch row (.switch-row / .md-toggle). */
@Composable
fun WakiliSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: WakiliSwitchRowStyle = WakiliSwitchRowStyle.Panel,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(if (style == WakiliSwitchRowStyle.Flat) WakiliDimens.RadiusMd else WakiliDimens.RadiusLg)
    val background = if (style == WakiliSwitchRowStyle.Flat) Color.Transparent else colors.panel2
    val border = if (style == WakiliSwitchRowStyle.Flat) null else colors.border
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .let { m -> border?.let { m.border(WakiliDimens.BorderThin, it, shape) } ?: m }
            .clickable { onCheckedChange(!checked) }
            .padding(
                horizontal = if (style == WakiliSwitchRowStyle.Flat) WakiliDimens.Space10 else WakiliDimens.Space12 + WakiliDimens.BorderThin,
                vertical = WakiliDimens.Space11,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        icon?.let { WakiliIcon(it, tint = if (checked) colors.text else colors.muted) }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) colors.text else colors.muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        WakiliSwitch(checked = checked, onCheckedChange = null)
    }
}

data class SegmentedTab(val label: String, val icon: ImageVector? = null)

/** .appr-seg / .sv-toggle / .pick-tabs — inset segmented control on panel-2. */
@Composable
fun WakiliSegmentedTabs(
    tabs: List<SegmentedTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .background(colors.panel2, RoundedCornerShape(WakiliDimens.RadiusMd))
            .padding(WakiliDimens.SegmentedPadding),
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
    ) {
        tabs.forEachIndexed { index, tab ->
            val on = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                    .background(if (on) colors.panel else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = WakiliDimens.Space9),
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (on) colors.text else colors.muted
                tab.icon?.let { WakiliIcon(it, size = WakiliDimens.IconSm, tint = tint) }
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** .fp-newname / .lg-input — bordered input on panel-2, accent border on focus. */
@Composable
fun WakiliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = WakiliDimens.Space11,
        vertical = WakiliDimens.Space9,
    ),
) {
    val colors = WakiliTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = textStyle.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .background(colors.panel2, shape)
                    .border(WakiliDimens.BorderThin, if (focused) colors.accent else colors.border, shape)
                    .padding(contentPadding),
            ) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = textStyle, color = colors.muted)
                }
                inner()
            }
        },
    )
}

/** #input / .term-input — the bare inline input used inside composer bars. */
@Composable
fun WakiliBareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = WakiliTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        maxLines = maxLines,
        textStyle = textStyle.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            Box(Modifier.padding(WakiliDimens.Space6)) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = textStyle, color = colors.muted)
                }
                inner()
            }
        },
    )
}

data class WakiliDropdownOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/** .dd — labeled dropdown: head row on panel-2, options expand inline below. */
@Composable
fun WakiliDropdown(
    label: String,
    options: List<WakiliDropdownOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(WakiliDimens.RadiusLg)
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panel2, shape)
                .border(WakiliDimens.BorderThin, colors.border, shape)
                .clickable { open = !open }
                .padding(horizontal = WakiliDimens.Space12 + WakiliDimens.BorderThin, vertical = WakiliDimens.Space11),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Text(
                text = options.firstOrNull { it.value == selected }?.label ?: selected,
                style = MaterialTheme.typography.labelLarge,
                color = colors.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            WakiliIcon(
                WakiliIcons.ChevronRight,
                size = WakiliDimens.IconXs,
                tint = colors.muted,
                modifier = Modifier.rotate(if (open) 270f else 90f),
            )
        }
        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WakiliDimens.Space4)
                    .clip(shape)
                    .background(colors.panel2, shape)
                    .border(WakiliDimens.BorderThin, colors.border, shape)
                    .padding(WakiliDimens.Space6),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2),
            ) {
                options.forEach { option ->
                    val active = option.value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(WakiliDimens.Space9))
                            .background(if (active) colors.panel else Color.Transparent)
                            .clickable {
                                open = false
                                onSelect(option.value)
                            }
                            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space10),
                        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                            )
                            option.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                )
                            }
                        }
                        if (active) {
                            WakiliIcon(WakiliIcons.Check, size = WakiliDimens.IconSm, tint = colors.accent)
                        }
                    }
                }
            }
        }
    }
}
