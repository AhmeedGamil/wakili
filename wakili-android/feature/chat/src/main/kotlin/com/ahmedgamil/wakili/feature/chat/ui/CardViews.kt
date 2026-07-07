package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.model.Question
import com.ahmedgamil.wakili.feature.chat.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_SUMMARY_CHARS = 500

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
        permissionSummary(card.input)?.let { summary ->
            Spacer(Modifier.height(WakiliDimens.Space6))
            Box(
                Modifier
                    .heightIn(max = WakiliDimens.PermBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                CodeBlock(text = summary)
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

/** Question card (`ask_options`) — every question stacked, Other = free text. */
@Composable
fun QuestionCardView(
    card: PendingCard.Question,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    // question index -> selected labels; separate map for the Other free text.
    val selections = remember(card.id) { mutableStateMapOf<Int, Set<String>>() }
    val others = remember(card.id) { mutableStateMapOf<Int, String>() }

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
            WakiliIcon(WakiliIcons.Help, size = WakiliDimens.IconSm, tint = colors.accent)
            Text(
                text = stringResource(R.string.chat_asking),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        card.questions.forEachIndexed { index, question ->
            Spacer(Modifier.height(if (index == 0) WakiliDimens.Space10 else WakiliDimens.Space12))
            QuestionBlock(
                question = question,
                selected = selections[index] ?: emptySet(),
                otherText = others[index] ?: "",
                onSelect = { label, on ->
                    val current = selections[index] ?: emptySet()
                    selections[index] =
                        if (question.multiSelect) {
                            if (on) current + label else current - label
                        } else {
                            setOf(label)
                        }
                },
                onOtherChange = { others[index] = it },
            )
        }
        Spacer(Modifier.height(WakiliDimens.Space10))
        val allAnswered = card.questions.indices.all { i ->
            !selections[i].isNullOrEmpty() || !others[i].isNullOrBlank()
        }
        WakiliButton(
            text = stringResource(R.string.chat_send_answers),
            onClick = {
                val answer = card.questions.mapIndexed { i, q ->
                    val picks = buildList {
                        addAll(selections[i] ?: emptySet())
                        others[i]?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    "${q.header ?: q.question}: ${picks.joinToString(", ")}"
                }.joinToString("\n")
                onSubmit(answer)
            },
            enabled = allAnswered,
            style = WakiliButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuestionBlock(
    question: Question,
    selected: Set<String>,
    otherText: String,
    onSelect: (String, Boolean) -> Unit,
    onOtherChange: (String) -> Unit,
) {
    val colors = WakiliTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8)) {
        // .ask-q
        Text(
            text = question.question,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
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
        // .ask-other-input
        WakiliTextField(
            value = otherText,
            onValueChange = onOtherChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.chat_other_option),
            singleLine = true,
        )
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
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
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

private fun permissionSummary(input: JsonObject?): String? {
    if (input == null) return null
    for (key in listOf("command", "file_path", "path", "pattern")) {
        val value = (input[key] as? JsonPrimitive)?.content
        if (!value.isNullOrBlank()) return value.take(MAX_SUMMARY_CHARS)
    }
    return null
}
