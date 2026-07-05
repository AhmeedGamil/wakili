package com.ahmedgamil.wakili.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliBareTextField
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme

private const val MAX_HISTORY_SHOWN = 6
private const val MAX_TERM_INPUT_LINES = 3

@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val colors = WakiliTheme.colors

    LaunchedEffect(uiState.lines.size) {
        if (uiState.lines.isNotEmpty()) listState.animateScrollToItem(uiState.lines.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // .term-head — back + accent terminal glyph + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier.padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
        ) {
            WakiliRoundIconButton(icon = WakiliIcons.CornerUpLeft, onClick = onBack)
            WakiliIcon(WakiliIcons.Terminal, tint = colors.accent)
            Text(
                text = stringResource(R.string.terminal_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        // .term-cwd — folder + mono path over a bottom border
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space8),
        ) {
            WakiliIcon(WakiliIcons.Folder, size = WakiliDimens.IconMd, tint = colors.muted)
            Text(
                text = uiState.cwd.ifEmpty { "~" },
                style = WakiliMono.Label,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)

        // .term-out
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space12),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2),
        ) {
            items(uiState.lines.size) { index ->
                TermLineView(uiState.lines[index])
            }
        }

        // "/" pops recent history, like the web terminal's command menu.
        if (uiState.input == "/" && uiState.history.isNotEmpty()) {
            WakiliCard(
                radius = WakiliDimens.RadiusPanel,
                contentPadding = WakiliDimens.Space6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WakiliDimens.Space12),
            ) {
                uiState.history.take(MAX_HISTORY_SHOWN).forEach { cmd ->
                    Text(
                        text = cmd,
                        style = WakiliMono.Small,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                            .clickable { viewModel.onInput(cmd) }
                            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
                    )
                }
            }
        }

        // .term-form — composer-style input with a round run button
        HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        ) {
            WakiliBareTextField(
                value = uiState.input,
                onValueChange = viewModel::onInput,
                placeholder = stringResource(R.string.terminal_hint),
                enabled = !uiState.running,
                maxLines = MAX_TERM_INPUT_LINES,
                textStyle = WakiliMono.Body,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = WakiliDimens.TermInputMaxHeight)
                    .align(Alignment.CenterVertically),
            )
            Box(
                modifier = Modifier
                    .size(WakiliDimens.ComposerButton)
                    .alpha(if (uiState.running) WakiliDimens.AlphaSendDisabled else 1f)
                    .clip(CircleShape)
                    .background(colors.text)
                    .clickable(enabled = !uiState.running, onClick = viewModel::run),
                contentAlignment = Alignment.Center,
            ) {
                WakiliIcon(WakiliIcons.ArrowUpRight, tint = colors.bg)
            }
        }
    }
}

/** .term-line — prompt-accent commands, plain output, red errors, ruled hints. */
@Composable
private fun TermLineView(line: TermLine) {
    val colors = WakiliTheme.colors
    when (line.kind) {
        TermLine.Kind.COMMAND -> Row(horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6)) {
            Text(
                text = stringResource(R.string.terminal_prompt),
                style = WakiliMono.Body.copy(fontWeight = MaterialTheme.typography.labelLarge.fontWeight),
                color = colors.accent,
            )
            Text(text = line.text, style = WakiliMono.Body, color = colors.text)
        }

        TermLine.Kind.OUTPUT -> Text(
            text = line.text,
            style = WakiliMono.Body,
            color = colors.text,
            modifier = Modifier.padding(bottom = WakiliDimens.Space8),
        )

        TermLine.Kind.ERROR -> Text(
            text = line.text,
            style = WakiliMono.Body,
            color = colors.termError,
            modifier = Modifier.padding(bottom = WakiliDimens.Space8),
        )

        TermLine.Kind.HINT -> Row {
            Box(
                Modifier
                    .padding(end = WakiliDimens.Space10)
                    .size(width = WakiliDimens.BorderThick, height = WakiliDimens.Space16)
                    .background(colors.accent),
            )
            Text(text = line.text, style = WakiliMono.Body, color = colors.muted)
        }
    }
}
