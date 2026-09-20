package com.ahmedgamil.wakili.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.ThinkTextStyle
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliContextMenu
import com.ahmedgamil.wakili.core.designsystem.component.WakiliMenuItem
import com.ahmedgamil.wakili.core.designsystem.component.wakiliShimmer
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.ui.markdown.MarkdownText
import com.ahmedgamil.wakili.core.ui.rememberTypewriterText
import com.ahmedgamil.wakili.feature.chat.R
import com.ahmedgamil.wakili.feature.chat.asPlainText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val IMAGE_RE = Regex("\\.(png|jpe?g|gif|webp|bmp)$", RegexOption.IGNORE_CASE)
private val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")

/** One assistant part: text / thinking / tool / file / qa — the web MessageList's segments. */
@Composable
fun PartView(
    part: Part,
    markdown: Boolean,
    baseUrl: String,
    modifier: Modifier = Modifier,
    /** True for the tail of a live turn — enables the typewriter + thinking pulse. */
    streaming: Boolean = false,
    initiallyRevealedChars: Int = 0,
    /** Null means the user has not chosen a state yet. */
    cardExpanded: Boolean? = null,
    onCardExpandedChange: (Boolean) -> Unit = {},
    onOpenImage: (url: String, name: String) -> Unit = { _, _ -> },
    onDownload: (url: String, name: String) -> Unit = { _, _ -> },
    onCopy: (url: String, name: String, image: Boolean) -> Unit = { _, _, _ -> },
    /** URL currently being copied to the clipboard (progress overlay on its card). */
    copyingUrl: String? = null,
) {
    when (part) {
        is Part.Text -> SelectionContainer(modifier = modifier.fillMaxWidth()) {
            val text = rememberTypewriterText(
                target = part.text,
                enabled = streaming,
                initiallyRevealedChars = initiallyRevealedChars,
            )
            if (markdown) {
                MarkdownText(source = text, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = WakiliTheme.colors.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is Part.Thinking -> SelectionContainer(modifier = modifier.fillMaxWidth()) {
            ThinkingView(
                part = part,
                streaming = streaming,
                initiallyRevealedChars = initiallyRevealedChars,
                expandedPreference = cardExpanded,
                onExpandedChange = onCardExpandedChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is Part.Tool -> ToolCard(
            part = part,
            expanded = cardExpanded ?: false,
            onExpandedChange = onCardExpandedChange,
            modifier = modifier,
        )

        is Part.File -> FileCard(part, baseUrl, modifier, onOpenImage, onDownload, onCopy, copyingUrl)

        is Part.Qa -> QaRecordView(part, modifier)
    }
}

/** Save / Copy long-press menu for one media card (web attachMediaMenu). */
@Composable
internal fun MediaMenu(
    expanded: Boolean,
    url: String,
    name: String,
    image: Boolean,
    onDismiss: () -> Unit,
    onDownload: (url: String, name: String) -> Unit,
    onCopy: (url: String, name: String, image: Boolean) -> Unit,
) {
    WakiliContextMenu(
        expanded = expanded,
        items = listOf(
            WakiliMenuItem(stringResource(R.string.chat_copy), WakiliIcons.Copy) {
                onCopy(url, name, image)
            },
            WakiliMenuItem(stringResource(R.string.chat_save), WakiliIcons.Download) {
                onDownload(url, name)
            },
        ),
        alignEnd = true,
        onDismiss = onDismiss,
    )
}

/** Standalone chat media shared by user uploads and agent-generated images. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatImage(
    url: String,
    name: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    onOpenImage: (url: String, name: String) -> Unit = { _, _ -> },
    onDownload: (url: String, name: String) -> Unit = { _, _ -> },
    onCopy: (url: String, name: String, image: Boolean) -> Unit = { _, _, _ -> },
    /** Copy-to-clipboard fetch in flight for THIS image — show the progress scrim. */
    copying: Boolean = false,
) {
    var mediaMenu by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    var imageAspect by remember(url) { mutableStateOf(WakiliDimens.ChatImageLoadingAspect) }
    val shape = RoundedCornerShape(WakiliDimens.RadiusCard)
    val imageWidth = minOf(maxWidth, WakiliDimens.ImageMaxHeight * imageAspect)
    val imageHeight = imageWidth / imageAspect
    val density = LocalDensity.current
    val context = LocalContext.current
    val requestWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
    val requestHeightPx = with(density) {
        WakiliDimens.ImageMaxHeight.roundToPx()
    }.coerceAtLeast(1)
    // Decode for the largest box this card can occupy, not the temporary 4:3
    // loading box. Portrait images can grow taller after their aspect ratio is
    // known; sizing the request from the placeholder would upscale a soft bitmap.
    val imageRequest = remember(url, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(url)
            .size(requestWidthPx, requestHeightPx)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .build()
    }
    Box(
        modifier = modifier
            .size(imageWidth, imageHeight)
            .clip(shape)
            .wakiliShimmer(loading)
            .combinedClickable(
                onClick = { onOpenImage(url, name) },
                onLongClick = { mediaMenu = true },
            ),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            onState = { state ->
                loading = state is AsyncImagePainter.State.Loading ||
                    state is AsyncImagePainter.State.Empty
                if (state is AsyncImagePainter.State.Success) {
                    val image = state.result.image
                    if (image.width > 0 && image.height > 0) {
                        imageAspect = image.width.toFloat() / image.height.toFloat()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Copy-in-progress scrim (the image fetch for the clipboard can take
        // seconds): dimmed image, spinner + label, until the toast lands.
        if (copying) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = WakiliDimens.BorderThick,
                    modifier = Modifier.size(WakiliDimens.Icon * 2),
                )
                Spacer(Modifier.height(WakiliDimens.Space8))
                Text(
                    text = stringResource(R.string.chat_copying),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
        MediaMenu(
            expanded = mediaMenu,
            url = url,
            name = name,
            image = true,
            onDismiss = { mediaMenu = false },
            onDownload = onDownload,
            onCopy = onCopy,
        )
    }
}

/**
 * .msg.ask.decided — the answered-question record, inline in the transcript at
 * the position the answer happened: muted check + "Answered" header, then one
 * bold line per question ("Header: answer"), like the web Dock's archive.
 */
@Composable
private fun QaRecordView(part: Part.Qa, modifier: Modifier = Modifier) {
    val colors = WakiliTheme.colors
    WakiliCard(
        modifier = modifier.fillMaxWidth(),
        radius = WakiliDimens.RadiusCard,
        contentPadding = WakiliDimens.Space12,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6 + WakiliDimens.BorderThin),
        ) {
            WakiliIcon(WakiliIcons.Check, size = WakiliDimens.IconSm, tint = colors.muted)
            Text(
                text = stringResource(R.string.chat_answered),
                style = MaterialTheme.typography.titleMedium,
                color = colors.muted,
            )
        }
        part.answer.split("\n").filter { it.isNotBlank() }.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.padding(top = WakiliDimens.Space10),
            )
        }
    }
}

/**
 * .thoughts — bulb + summary; body is italic, muted, left-ruled. While the
 * thinking is still streaming the summary pulses "Thinking…" and the body is
 * open. Finishing only changes the label to "Thoughts"; the open state stays
 * untouched, exactly like the web details element.
 */
@Composable
private fun ThinkingView(
    part: Part.Thinking,
    streaming: Boolean,
    initiallyRevealedChars: Int,
    expandedPreference: Boolean?,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasText = part.text.isNotBlank()
    val expanded = hasText && (expandedPreference ?: streaming)
    // Persist the automatic live-open choice. When streaming ends, it remains
    // open until the user closes it. An empty thought never opens.
    LaunchedEffect(streaming, hasText, expandedPreference) {
        if (streaming && hasText && expandedPreference == null) {
            onExpandedChange(true)
        }
    }
    val colors = WakiliTheme.colors
    val pulse = rememberInfiniteTransition(label = "thinking")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = ThinkPulseAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ThinkPulseMillis),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            modifier = Modifier
                .clip(RoundedCornerShape(WakiliDimens.RadiusSm))
                .clickable(enabled = hasText) { onExpandedChange(!expanded) }
                .padding(vertical = WakiliDimens.Space2)
                .alpha(if (streaming) pulseAlpha else 1f),
        ) {
            WakiliIcon(WakiliIcons.Bulb, size = WakiliDimens.IconSm, tint = colors.muted)
            Text(
                text = stringResource(
                    if (streaming) R.string.chat_thinking else R.string.chat_thoughts,
                ),
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
                    text = rememberTypewriterText(
                        target = part.text,
                        enabled = streaming,
                        initiallyRevealedChars = initiallyRevealedChars,
                    ),
                    style = ThinkTextStyle,
                    color = colors.muted,
                    modifier = Modifier.padding(start = WakiliDimens.Space10),
                )
            }
        }
    }
}

private const val ThinkPulseAlpha = 0.45f
private const val ThinkPulseMillis = 900

/** .tool-card — collapsible: caret + per-tool icon + one-line title; body reveals I/O. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolCard(
    part: Part.Tool,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val colors = WakiliTheme.colors
    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
        ToolCardBody(
            part = part,
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            onLongPress = { menuOpen = true },
        )
        // "What happened" summary below the card (web .tool-badge) — empty stays hidden.
        val badge = remember(part.name, part.input, part.output) {
            toolBadge(part.name, part.input, part.output)
        }
        if (badge.isNotEmpty()) {
            Text(
                text = badge,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(start = WakiliDimens.Space4, top = WakiliDimens.Space4),
            )
        }
        }
        WakiliContextMenu(
            expanded = menuOpen,
            items = listOf(
                WakiliMenuItem(stringResource(R.string.chat_copy), WakiliIcons.Copy) {
                    clipboard.setText(AnnotatedString(part.asPlainText()))
                },
            ),
            onDismiss = { menuOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCardBody(
    part: Part.Tool,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = WakiliTheme.colors
    WakiliCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
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
            val scrollState = rememberScrollState()
            HorizontalDivider(color = colors.border, thickness = WakiliDimens.BorderThin)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = WakiliDimens.ToolBodyMaxHeight)
                    // Drags chain to the chat list like browser scrolling; fling
                    // momentum stops at the card edge (containFlingChaining).
                    .containFlingChaining()
                    .verticalScroll(scrollState)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                ToolDiffBody(part.name, part.input, modifier = Modifier.fillMaxWidth())
                part.output?.takeIf { it.isNotBlank() }?.let { output ->
                    CodeBlock(
                        text = output,
                        modifier = Modifier.fillMaxWidth(),
                        isError = part.isError,
                        isOutput = true,
                        bare = true,
                        wrap = true,
                    )
                }
            }
        }
    }
}

/** Standalone image media, or a compact download card for non-image files. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    part: Part.File,
    baseUrl: String,
    modifier: Modifier = Modifier,
    onOpenImage: (url: String, name: String) -> Unit = { _, _ -> },
    onDownload: (url: String, name: String) -> Unit = { _, _ -> },
    onCopy: (url: String, name: String, image: Boolean) -> Unit = { _, _, _ -> },
    copyingUrl: String? = null,
) {
    val colors = WakiliTheme.colors
    val absolute = part.url?.let { if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it }
    if (absolute != null && IMAGE_RE.containsMatchIn(part.name)) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxImageWidth = minOf(
                maxWidth * WakiliDimens.ImageMsgMaxWidthFraction,
                WakiliDimens.FileCardMaxWidth,
            )
            ChatImage(
                url = absolute,
                name = part.name,
                maxWidth = maxImageWidth,
                onOpenImage = onOpenImage,
                onDownload = onDownload,
                onCopy = onCopy,
                copying = copyingUrl == absolute,
            )
        }
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxFileWidth = minOf(
            maxWidth * WakiliDimens.BubbleMaxWidthFraction,
            WakiliDimens.FileCardMaxWidth,
        )
        var linkMenu by remember(part) { mutableStateOf(false) }
        // The WHOLE card is the hit target (the file name alone was a tiny tap
        // area); long-press still opens Save / Copy.
        WakiliCard(
            modifier = Modifier
                .widthIn(max = maxFileWidth)
                .then(
                    if (absolute != null) {
                        // Clip BEFORE the clickable: WakiliCard's own clip sits
                        // inside this chain, so a bare clickable here ripples in
                        // the unclipped rect — square corners flashing around
                        // the rounded card on press.
                        Modifier
                            .clip(RoundedCornerShape(WakiliDimens.Radius))
                            .combinedClickable(
                                onClick = { onDownload(absolute, part.name) },
                                onLongClick = { linkMenu = true },
                            )
                    } else {
                        Modifier
                    },
                ),
            radius = WakiliDimens.Radius,
            contentPadding = WakiliDimens.Space12,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6 + WakiliDimens.BorderThin),
            ) {
                WakiliIcon(WakiliIcons.Download, size = WakiliDimens.Icon, tint = colors.accent)
                Text(
                    text = part.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (absolute != null) {
                    MediaMenu(
                        expanded = linkMenu,
                        url = absolute,
                        name = part.name,
                        image = false,
                        onDismiss = { linkMenu = false },
                        onDownload = onDownload,
                        onCopy = onCopy,
                    )
                }
            }
            if (part.caption.isNotEmpty()) {
                Text(
                    text = part.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = WakiliDimens.Space6),
                )
            }
        }
    }
}

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
    wrap: Boolean = false,
) {
    val colors = WakiliTheme.colors
    val background = when {
        isError -> colors.danger.copy(alpha = WakiliDimens.AlphaErrorWash)
        isOutput -> colors.muted.copy(alpha = WakiliDimens.AlphaMutedWash)
        bare -> colors.panel
        else -> colors.panel2
    }
    val shape = RoundedCornerShape(if (bare) 0.dp else WakiliDimens.RadiusMd)
    val horizontalBehavior = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
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
            .then(horizontalBehavior),
    ) {
        Text(
            text = text,
            style = WakiliMono.Small,
            color = if (isError) colors.danger else colors.text,
            softWrap = wrap,
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

// Claude's WebSearch/WebFetch and codex's web_search item all count as web;
// Antigravity's search_web/read_url_content join them.
private val WEB_TOOL_RE = Regex("^web_?(search|fetch)|^(search_web|read_url_content)$", RegexOption.IGNORE_CASE)

// Antigravity's file tools, by family — they keep their own names (the agent
// reports no contents/diffs, so they can't pose as Read/Edit), but share the icons.
private val AGY_EDIT_TOOLS = setOf("write_to_file", "replace_file_content", "multi_replace_file_content", "sed_file")
private val PATTERN_TOOLS = setOf("Grep", "grep_search", "find_by_name")

// Header icon per tool family — same mapping as the web toolCard.js headIcon().
private fun headIcon(name: String) = when {
    name == "Bash" || name == "PowerShell" -> WakiliIcons.Terminal
    name in EDIT_TOOLS || name in AGY_EDIT_TOOLS -> WakiliIcons.Pencil
    name == "Read" || name == "view_file" -> WakiliIcons.FileText
    name == "Grep" || name == "grep_search" -> WakiliIcons.Search
    name == "Glob" || name == "find_by_name" || name == "list_dir" -> WakiliIcons.Folder
    name == "Task" || name == "Agent" -> WakiliIcons.Bot
    WEB_TOOL_RE.containsMatchIn(name) -> WakiliIcons.Globe
    else -> WakiliIcons.Wrench
}

private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/** Header text — a faithful port of the web headLabel(): per-tool subtitles. */
private fun headLabel(name: String, input: JsonObject?): String {
    if (name == "Bash") return input.str("description")?.let { "Bash · $it" } ?: "Bash"
    // Grep/Glob are about the pattern, not a single file — surface it.
    input.str("pattern")?.let { pattern ->
        if (name in PATTERN_TOOLS) {
            val where = input.str("path")?.let { " (in $it)" }.orEmpty()
            return "$name \"$pattern\"$where"
        }
        if (name == "Glob") return "Glob pattern: \"$pattern\""
    }
    val file = input.str("file_path") ?: input.str("notebook_path") ?: input.str("path")
    if (file != null) return "$name · $file${lineRange(name, input)}"
    // Web tools are about the query/URL — surface it so the card isn't a bare name.
    input.str("query")?.let { return "$name · $it" }
    input.str("url")?.let { return "$name · $it" }
    input.str("description")?.let { return "$name · $it" }
    input.str("command")?.let { return "$name · ${it.trim()}" }
    return name
}

/** "(lines a-b)" suffix for a partial Read, like the web lineRange(). */
private fun lineRange(name: String, input: JsonObject?): String {
    if (name != "Read") return ""
    val offset = input.str("offset")?.toIntOrNull()
    val limit = input.str("limit")?.toIntOrNull()
    return when {
        offset != null && limit != null -> " (lines $offset-${offset + limit})"
        offset != null -> " (from line $offset)"
        limit != null -> " (first $limit lines)"
        else -> ""
    }
}

private fun prettyInput(input: JsonObject): String =
    input.entries.joinToString("\n") { (k, v) ->
        val value = (v as? JsonPrimitive)?.content ?: v.toString()
        "$k: $value"
    }

// ---- diff body (web toolCard.js diffBody) ----

private enum class DiffKind { Add, Del, Cmd }

/** The change an edit tool is making, as removed/added blocks — or the command. */
private fun diffBlocks(name: String, input: JsonObject?): List<Pair<DiffKind, String>> {
    fun del(s: String?) = s?.takeIf { it.isNotEmpty() }?.let { DiffKind.Del to it }
    fun add(s: String?) = s?.takeIf { it.isNotEmpty() }?.let { DiffKind.Add to it }
    return when (name) {
        "Write" -> listOfNotNull(add(input.str("content")))
        "Edit" -> listOfNotNull(del(input.str("old_string")), add(input.str("new_string")))
        "MultiEdit" -> (input?.get("edits") as? JsonArray).orEmpty().flatMap { edit ->
            val o = edit as? JsonObject
            listOfNotNull(del(o.str("old_string")), add(o.str("new_string")))
        }
        "NotebookEdit" -> listOfNotNull(add(input.str("new_source")))
        else -> emptyList()
    }
}

/**
 * The expanded card body: red/green diff for edit tools, the command for shell
 * tools, key/value lines otherwise. Shared by tool cards and permission cards.
 */
@Composable
fun ToolDiffBody(name: String, input: JsonObject?, modifier: Modifier = Modifier) {
    val blocks = remember(name, input) {
        diffBlocks(name, input).ifEmpty {
            val fallback = input.str("command")?.trim()
                ?: input?.let { prettyInput(it) }.orEmpty()
            listOf(DiffKind.Cmd to fallback)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { (kind, text) -> DiffPre(kind, text) }
    }
}

/** .diff-add / .diff-del / .diff-cmd — tinted mono blocks with a heavy left rule. */
@Composable
private fun DiffPre(kind: DiffKind, text: String) {
    val colors = WakiliTheme.colors
    val rule = when (kind) {
        DiffKind.Add -> colors.diffAdd
        DiffKind.Del -> colors.danger
        DiffKind.Cmd -> null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { m ->
                if (rule != null) {
                    m.background(rule.copy(alpha = WakiliDimens.AlphaDiffWash)).leftRule(rule)
                } else {
                    m
                }
            }
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space8),
    ) {
        Text(text = text, style = WakiliMono.Small, color = colors.text, softWrap = true)
    }
}

// ---- badges (web toolCard.js inputBadge/outputBadge) ----

private fun lineCount(s: String?): Int = if (s.isNullOrEmpty()) 0 else s.split("\n").size

/** Net line change of an edit: "Modified" when even, otherwise grew/shrank. */
private fun editDelta(added: Int, removed: Int): String {
    val d = added - removed
    if (d == 0) return "Modified"
    val n = kotlin.math.abs(d)
    return (if (d > 0) "Added " else "Removed ") + n + (if (n == 1) " line" else " lines")
}

private fun inputBadge(name: String, input: JsonObject?): String = when (name) {
    "Write" -> "${lineCount(input.str("content"))} lines"
    "NotebookEdit" -> "${lineCount(input.str("new_source"))} lines"
    "Edit" -> editDelta(lineCount(input.str("new_string")), lineCount(input.str("old_string")))
    "MultiEdit" -> {
        val edits = (input?.get("edits") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (edits.isEmpty()) {
            ""
        } else {
            editDelta(
                edits.sumOf { lineCount(it.str("new_string")) },
                edits.sumOf { lineCount(it.str("old_string")) },
            )
        }
    }
    else -> ""
}

private fun outputBadge(name: String, output: String?): String {
    if (output.isNullOrEmpty()) return ""
    return when (name) {
        "Read" -> "${lineCount(output)} lines"
        "Grep" -> "${lineCount(output)} lines of output"
        "Glob" -> "${output.split("\n").count { it.isNotBlank() }} matches"
        else -> ""
    }
}

private fun toolBadge(name: String, input: JsonObject?, output: String?): String =
    inputBadge(name, input).ifEmpty { outputBadge(name, output) }
