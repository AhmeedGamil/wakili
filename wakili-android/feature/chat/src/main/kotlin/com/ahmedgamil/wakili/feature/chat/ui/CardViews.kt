package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.AskTabTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.AskTagTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.model.Question
import com.ahmedgamil.wakili.feature.chat.R

/**
 * Permission card — Deny / Allow once / Always, pinned above the composer
 * exactly like the web Dock (.dock .card: panel surface, accent border).
 */
@Composable
fun PermissionCardView(
    card: PendingCard.Permission,
    queuedCount: Int,
    onDecision: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    WakiliCard(
        modifier = modifier.fillMaxWidth(),
        radius = WakiliDimens.RadiusCard,
        borderColor = colors.accent,
        contentPadding = WakiliDimens.Space12,
    ) {
        // .perm-head — lock icon + "Allow tool ?  ·  +N more"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6 + WakiliDimens.BorderThin),
        ) {
            WakiliIcon(WakiliIcons.Lock, size = WakiliDimens.IconSm, tint = colors.text)
            Text(
                text = stringResource(R.string.chat_permission_title, card.tool) +
                    if (queuedCount > 0) "  ·  " + stringResource(R.string.chat_more_requests, queuedCount) else "",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        // Inline change preview — red/green diff for edits, the command for shell
        // tools (web dock cards show diffBody inside the permission card).
        if (card.input != null) {
            Spacer(Modifier.height(WakiliDimens.Space6))
            Box(
                Modifier
                    .heightIn(max = WakiliDimens.PermBodyMaxHeight)
                    .containFlingChaining()
                    .verticalScroll(rememberScrollState()),
            ) {
                ToolDiffBody(card.tool, card.input, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(WakiliDimens.Space10))
        // .perm-actions — centered Deny / Allow / Always
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8, Alignment.CenterHorizontally),
        ) {
            WakiliButton(
                text = stringResource(R.string.chat_deny),
                onClick = { onDecision("deny") },
                style = WakiliButtonStyle.Outline,
            )
            WakiliButton(
                text = stringResource(R.string.chat_allow),
                onClick = { onDecision("allow") },
                style = WakiliButtonStyle.Primary,
                contentPadding = PaddingValues(
                    horizontal = WakiliDimens.Space18,
                    vertical = WakiliDimens.Space8,
                ),
            )
            WakiliButton(
                text = stringResource(R.string.chat_always),
                onClick = { onDecision("allow_session") },
                style = WakiliButtonStyle.AccentOutline,
            )
        }
    }
}

/**
 * Question card (`ask_options`) — a faithful port of the web QuestionCard.js:
 * multi-question cards get header-labeled pill tabs with a ✓ once answered;
 * every question offers an "Other" choice that reveals free text. Answers are
 * NEVER sent by a tap alone — the button reads "Next" while an unanswered
 * question remains beyond the active one, then "Send answers".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionCardView(
    card: PendingCard.Question,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    // question index -> selected labels; Other toggle + text tracked separately.
    val selections = remember(card.id) { mutableStateMapOf<Int, Set<String>>() }
    val others = remember(card.id) { mutableStateMapOf<Int, String>() }
    val otherOn = remember(card.id) { mutableStateMapOf<Int, Boolean>() }
    var tab by remember(card.id) { mutableIntStateOf(0) }
    var submitted by remember(card.id) { mutableStateOf(false) }
    val multi = card.questions.size > 1

    fun isAnswered(i: Int): Boolean =
        !selections[i].isNullOrEmpty() || (otherOn[i] == true && !others[i].isNullOrBlank())

    fun allAnswered(): Boolean = card.questions.indices.all(::isAnswered)

    fun answerText(): String = card.questions.mapIndexed { i, q ->
        val picks = buildList {
            addAll(selections[i] ?: emptySet())
            if (otherOn[i] == true) others[i]?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val head = q.header ?: q.question.ifBlank { "Q${i + 1}" }
        "$head: ${picks.joinToString(", ")}"
    }.joinToString("\n")

    // Web advance(): submit once everything is answered, else jump forward to
    // the next unanswered question (wrapping past the end).
    fun advance() {
        if (allAnswered()) {
            if (!submitted) {
                submitted = true
                onSubmit(answerText())
            }
            return
        }
        for (k in 1..card.questions.size) {
            val j = (tab + k) % card.questions.size
            if (!isAnswered(j)) {
                tab = j
                return
            }
        }
    }

    WakiliCard(
        modifier = modifier.fillMaxWidth(),
        radius = WakiliDimens.RadiusCard,
        borderColor = colors.accent,
        contentPadding = WakiliDimens.Space12,
    ) {
        // .perm-head — help icon + "The agent is asking…"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6 + WakiliDimens.BorderThin),
        ) {
            WakiliIcon(WakiliIcons.Help, size = WakiliDimens.IconSm, tint = colors.text)
            Text(
                text = stringResource(R.string.chat_asking),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        Spacer(Modifier.height(WakiliDimens.Space6))
        if (multi) {
            // .ask-tabs — wrapping pills labeled by each question's header.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
            ) {
                card.questions.forEachIndexed { i, q ->
                    QuestionTab(
                        label = q.header ?: "Q${i + 1}",
                        active = tab == i,
                        done = isAnswered(i),
                        onClick = { tab = i },
                    )
                }
            }
            Spacer(Modifier.height(WakiliDimens.Space12))
        }
        val index = if (multi) tab else 0
        val question = card.questions[index]
        // .dock .ask-pane — only the question area scrolls; the header, tabs
        // and button stay put whatever the option count.
        Column(
            Modifier
                .heightIn(max = WakiliDimens.AskPaneMaxHeight)
                .containFlingChaining()
                .verticalScroll(rememberScrollState()),
        ) {
            // .ask-tag — a lone question still shows its header, as the small
            // uppercase accent label above the question text.
            if (!multi) {
                question.header?.let { header ->
                    Text(
                        text = header.uppercase(),
                        style = AskTagTextStyle,
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(WakiliDimens.Space4))
                }
            }
            QuestionBlock(
                question = question,
                selected = selections[index] ?: emptySet(),
                otherSelected = otherOn[index] == true,
                otherText = others[index] ?: "",
                onSelect = { label, on ->
                    val current = selections[index] ?: emptySet()
                    if (question.multiSelect) {
                        selections[index] = if (on) current + label else current - label
                    } else {
                        selections[index] = setOf(label)
                        otherOn[index] = false
                        // A pick never sends by itself. A mid-list single-select
                        // answer steps to the next unanswered question; the last
                        // one waits for "Send answers".
                        if (multi && !allAnswered()) advance()
                    }
                },
                onOtherToggle = {
                    val on = !(otherOn[index] ?: false)
                    otherOn[index] = on
                    if (on && !question.multiSelect) selections[index] = emptySet()
                },
                onOtherChange = { others[index] = it },
            )
        }
        Spacer(Modifier.height(WakiliDimens.Space10))
        // "Next" while an unanswered question remains beyond the active one
        // (multiSelect can't auto-advance); else "Send answers", enabled once
        // everything is answered (web refresh()).
        val rest = multi && card.questions.indices.any { it != index && !isAnswered(it) }
        WakiliButton(
            text = stringResource(if (rest) R.string.chat_next else R.string.chat_send_answers),
            onClick = ::advance,
            enabled = rest || allAnswered(),
            style = WakiliButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** .ask-tab — header-labeled pill; a trailing accent ✓ appears once answered. */
@Composable
private fun QuestionTab(label: String, active: Boolean, done: Boolean, onClick: () -> Unit) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (active) colors.accent.copy(alpha = WakiliDimens.AlphaAccentWash) else colors.panel2,
                CircleShape,
            )
            .border(WakiliDimens.BorderThin, if (active) colors.accent else colors.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space6),
    ) {
        Text(
            text = label,
            style = AskTabTextStyle,
            color = if (active) colors.text else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (done) {
            WakiliIcon(WakiliIcons.Check, size = WakiliDimens.IconXs, tint = colors.accent)
        }
    }
}

@Composable
private fun QuestionBlock(
    question: Question,
    selected: Set<String>,
    otherSelected: Boolean,
    otherText: String,
    onSelect: (String, Boolean) -> Unit,
    onOtherToggle: () -> Unit,
    onOtherChange: (String) -> Unit,
) {
    val colors = WakiliTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8)) {
        // .ask-q — base body size, weight 600 (NOT the 16px card-title size).
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = MaterialTheme.typography.titleMedium.fontWeight,
            ),
            color = colors.text,
            modifier = Modifier.padding(bottom = WakiliDimens.Space2),
        )
        question.options.forEach { option ->
            val isOn = option.label in selected
            AskOption(
                label = option.label,
                description = option.description,
                selected = isOn,
                multi = question.multiSelect,
                onClick = { onSelect(option.label, !isOn) },
            )
        }
        // "Other" is a choice like any other (mark + pencil, web .ask-other);
        // picking it reveals the input, already focused.
        AskOption(
            label = stringResource(R.string.chat_other_option),
            description = null,
            selected = otherSelected,
            multi = question.multiSelect,
            onClick = onOtherToggle,
            icon = WakiliIcons.Pencil,
        )
        if (otherSelected) {
            val focus = remember { FocusRequester() }
            WakiliTextField(
                value = otherText,
                onValueChange = onOtherChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                placeholder = stringResource(R.string.chat_other_placeholder),
                singleLine = true,
            )
            // The web focuses the revealed box (accent border via :focus).
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}

/** .ask-opt — selectable row with a radio (single) or checkbox (multi) mark. */
@Composable
private fun AskOption(
    label: String,
    description: String?,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = WakiliTheme.colors
    val shape = RoundedCornerShape(WakiliDimens.RadiusMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) colors.accent.copy(alpha = WakiliDimens.AlphaAccentWash) else colors.panel2,
                shape,
            )
            .border(WakiliDimens.BorderThin, if (selected) colors.accent else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
    ) {
        AskMark(selected = selected, multi = multi)
        icon?.let { WakiliIcon(it, size = WakiliDimens.IconSm, tint = colors.text) }
        Column(Modifier.weight(1f)) {
            // .ask-opt-label — base body size (15px), regular weight.
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        }
    }
}

/** .ask-mark — 18dp circle (single) or rounded square (multi), accent-filled when on. */
@Composable
private fun AskMark(selected: Boolean, multi: Boolean) {
    val colors = WakiliTheme.colors
    val shape = if (multi) RoundedCornerShape(WakiliDimens.RadiusXs) else CircleShape
    Box(
        modifier = Modifier
            .size(WakiliDimens.AskMark)
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent, shape)
            .border(
                WakiliDimens.BorderThick,
                if (selected) colors.accent else colors.border,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (multi) {
                WakiliIcon(WakiliIcons.Check, size = WakiliDimens.IconXs, tint = colors.accentInk)
            } else {
                Box(
                    Modifier
                        .size(WakiliDimens.AskMark - WakiliDimens.AskMarkInset * 2 - WakiliDimens.BorderThick * 2)
                        .background(colors.accentInk, CircleShape),
                )
            }
        }
    }
}

