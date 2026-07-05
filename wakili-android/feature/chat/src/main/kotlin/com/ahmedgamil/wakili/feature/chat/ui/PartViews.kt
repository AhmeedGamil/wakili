package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.ThinkTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.ui.markdown.MarkdownText
import com.ahmedgamil.wakili.feature.chat.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val IMAGE_RE = Regex("\\.(png|jpe?g|gif|webp|bmp)$", RegexOption.IGNORE_CASE)
private val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/** One assistant part: text / thinking / tool / file — the web MessageList's segments. */
@Composable
fun PartView(
    part: Part,
    markdown: Boolean,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    when (part) {
        is Part.Text ->
            if (markdown) {
                MarkdownText(source = part.text, modifier = modifier.fillMaxWidth())
            } else {
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = WakiliTheme.colors.text,
                    modifier = modifier.fillMaxWidth(),
                )
            }

        is Part.Thinking -> ThinkingView(part, modifier)

        is Part.Tool -> ToolCard(part, modifier)

        is Part.File -> FileCard(part, baseUrl, modifier)
    }
}

/** .thoughts — bulb + "Thoughts" summary; body is italic, muted, left-ruled. */
@Composable
private fun ThinkingView(part: Part.Thinking, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = WakiliTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            modifier = Modifier
                .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                .clickable { expanded = !expanded }
                .padding(vertical = WakiliDimens.Space2),
        ) {
            WakiliIcon(WakiliIcons.Bulb, size = WakiliDimens.IconSm, tint = colors.muted)
            Text(
                text = stringResource(R.string.chat_thoughts),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        if (expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = WakiliDimens.Space6)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier
                        .width(WakiliDimens.BorderThick)
                        .fillMaxHeight()
                        .background(colors.border),
                )
                Text(
                    text = part.text,
                    style = ThinkTextStyle,
                    color = colors.muted,
                    modifier = Modifier.padding(start = WakiliDimens.Space10),
                )
            }
        }
    }
}

/** .tool-card — collapsible: caret + per-tool icon + one-line title; body reveals I/O. */
@Composable
fun ToolCard(part: Part.Tool, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = WakiliTheme.colors
    WakiliCard(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space9),
        ) {
            WakiliIcon(
                WakiliIcons.ChevronRight,
                size = WakiliDimens.IconXs,
                tint = colors.muted,
                modifier = Modifier
                    .padding(top = WakiliDimens.Space4)
                    .rotate(if (expanded) 90f else 0f),
            )
            WakiliIcon(headIcon(part.name), size = WakiliDimens.IconSm, tint = colors.muted)
            Text(
                text = headLabel(part.name, part.input),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = MaterialTheme.typography.labelLarge.fontWeight),
                color = colors.text,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (part.isError) {
                Text(
                    text = stringResource(R.string.chat_tool_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
            }
        }
        if (expanded) {
            HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = WakiliDimens.ToolBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                part.input?.let { input ->
                    CodeBlock(
                        text = prettyInput(input),
                        modifier = Modifier.fillMaxWidth(),
                        bare = true,
                    )
                }
                part.output?.takeIf { it.isNotBlank() }?.let { output ->
                    CodeBlock(
                        text = output.take(MAX_OUTPUT_CHARS),
                        modifier = Modifier.fillMaxWidth(),
                        isError = part.isError,
                        isOutput = true,
                        bare = true,
                    )
                }
            }
        }
    }
}

/** .file-card — image preview plus a download-style file link, panel surface. */
@Composable
private fun FileCard(part: Part.File, baseUrl: String, modifier: Modifier = Modifier) {
    val colors = WakiliTheme.colors
    val absolute = part.url?.let { if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it }
    WakiliCard(
        modifier = modifier,
        radius = WakiliDimens.Radius,
        contentPadding = WakiliDimens.Space10,
    ) {
        if (absolute != null && IMAGE_RE.containsMatchIn(part.name)) {
            AsyncImage(
                model = absolute,
                contentDescription = part.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = WakiliDimens.ImageMaxHeight)
                    .clip(RoundedCornerShape(WakiliDimens.RadiusMd)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6 + WakiliDimens.BorderThin),
            modifier = Modifier.padding(top = WakiliDimens.Space8),
        ) {
            WakiliIcon(WakiliIcons.Download, size = WakiliDimens.Icon, tint = colors.accent)
            Text(
                text = part.name,
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (part.caption.isNotEmpty()) {
            Text(
                text = part.caption,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = WakiliDimens.Space6),
            )
        }
    }
}

private const val MAX_OUTPUT_CHARS = 4000
private const val MAX_VALUE_CHARS = 500

/**
 * .diff pre / .diff-out — monospace body; output gets the muted wash, errors
 * the danger wash with a heavy left border. [bare] renders without its own
 * card chrome (used inside the tool card body).
 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isOutput: Boolean = false,
    bare: Boolean = false,
) {
    val colors = WakiliTheme.colors
    val background = when {
        isError -> colors.danger.copy(alpha = WakiliDimens.AlphaErrorWash)
        isOutput -> colors.muted.copy(alpha = WakiliDimens.AlphaMutedWash)
        bare -> colors.panel
        else -> colors.panel2
    }
    val shape = RoundedCornerShape(if (bare) 0.dp else WakiliDimens.RadiusMd)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, shape)
            .let { m ->
                if (isError) {
                    m.leftRule(colors.danger)
                } else if (!bare) {
                    m.border(WakiliDimens.BorderThin, colors.border, shape)
                } else {
                    m
                }
            }
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space8)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            style = WakiliMono.Small,
            color = if (isError) colors.danger else colors.text,
        )
    }
}

/** 3px colored left border, like .diff-add / .diff-del / .diff-out.err. */
private fun Modifier.leftRule(color: Color): Modifier = drawBehind {
    drawRect(
        color = color,
        size = androidx.compose.ui.geometry.Size(WakiliDimens.BorderHeavy.toPx(), size.height),
    )
}

// Header icon per tool family — same mapping as the web toolCard.js headIcon().
private fun headIcon(name: String) = when {
    name == "Bash" || name == "PowerShell" -> WakiliIcons.Terminal
    name in EDIT_TOOLS -> WakiliIcons.Pencil
    name == "Read" -> WakiliIcons.FileText
    name == "Grep" -> WakiliIcons.Search
    name == "Glob" -> WakiliIcons.Folder
    name == "Task" || name == "Agent" -> WakiliIcons.Bot
    else -> WakiliIcons.Wrench
}

/** Tool + the path/command it touches, like the web headLabel(). */
private fun headLabel(name: String, input: JsonObject?): String {
    val summary = toolSummary(input)
    return if (summary.isNullOrBlank()) name else "$name  $summary"
}

/** Subtitle from the input — path, command, or pattern, like the web cards. */
private fun toolSummary(input: JsonObject?): String? {
    if (input == null) return null
    for (key in listOf("file_path", "command", "pattern", "path", "description")) {
        val value = (input[key] as? JsonPrimitive)?.content
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun prettyInput(input: JsonObject): String =
    input.entries.joinToString("\n") { (k, v) ->
        val value = (v as? JsonPrimitive)?.content ?: v.toString()
        "$k: ${value.take(MAX_VALUE_CHARS)}"
    }
