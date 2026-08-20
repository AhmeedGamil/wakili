package com.ahmedgamil.wakili.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.ahmedgamil.wakili.core.designsystem.component.WakiliAnchoredPopup
import com.ahmedgamil.wakili.core.designsystem.component.WakiliBareTextField
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliContextMenu
import com.ahmedgamil.wakili.core.designsystem.component.WakiliGhostIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliMenuItem
import com.ahmedgamil.wakili.core.designsystem.component.WakiliPillChip
import com.ahmedgamil.wakili.core.designsystem.component.ConnectionDot
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.dashedBorder
import com.ahmedgamil.wakili.core.designsystem.component.wakiliShimmer
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.data.repository.OutboxEntry
import com.ahmedgamil.wakili.core.data.repository.OutboxStatus
import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.Part
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.ui.TypingDots
import com.ahmedgamil.wakili.core.ui.WakiliImageViewer
import com.ahmedgamil.wakili.core.ui.WakiliLoading
import com.ahmedgamil.wakili.core.ui.markdown.MarkdownText
import com.ahmedgamil.wakili.feature.chat.ui.CodeBlock
import com.ahmedgamil.wakili.feature.chat.ui.ChatImage
import com.ahmedgamil.wakili.feature.chat.ui.ControlsPopover
import com.ahmedgamil.wakili.feature.chat.ui.MediaMenu
import com.ahmedgamil.wakili.feature.chat.ui.PartView
import com.ahmedgamil.wakili.feature.chat.ui.PermissionCardView
import com.ahmedgamil.wakili.feature.chat.ui.QuestionCardView
import com.ahmedgamil.wakili.feature.chat.ui.ToolCard

private val CHAT_IMAGE_RE = Regex("\\.(png|jpe?g|gif|webp|bmp)$", RegexOption.IGNORE_CASE)

/** Last path segment of a cwd, tolerant of both separators (web baseName()). */
private fun folderBaseName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

internal data class VisibleChatContent(
    val messages: List<ChatMessage>,
    val liveParts: List<Part>,
)

private data class PartExpansionKeys(
    val messageParts: List<List<String?>>,
    val transients: List<String?>,
    val liveParts: List<String?>,
)

private sealed interface HistoryUnit {
    val key: String

    data class User(
        val message: ChatMessage.User,
        override val key: String,
    ) : HistoryUnit

    data class AssistantPart(
        val part: Part,
        val expansionKey: String?,
        override val key: String,
    ) : HistoryUnit

    data class Stopped(
        val marker: StoppedMarkerUi,
        override val key: String = marker.key,
    ) : HistoryUnit
}

internal data class StoppedMarkerPlacement(
    val afterMessageIndex: Map<Int, List<StoppedMarkerUi>>,
    val unresolvedBeforeLive: List<StoppedMarkerUi>,
    val pendingLiveTail: List<StoppedMarkerUi>,
)

/** Keep each terminal note at its originating user-turn boundary. */
internal fun placeStoppedMarkers(
    messages: List<ChatMessage>,
    markers: List<StoppedMarkerUi>,
): StoppedMarkerPlacement {
    val userMessageIndexes = messages.indices.filter { messages[it] is ChatMessage.User }
    val afterMessageIndex = linkedMapOf<Int, MutableList<StoppedMarkerUi>>()
    val unresolvedBeforeLive = mutableListOf<StoppedMarkerUi>()
    val pendingLiveTail = mutableListOf<StoppedMarkerUi>()

    markers.forEach { marker ->
        if (marker.pendingLiveTail) {
            pendingLiveTail += marker
            return@forEach
        }
        val originIndex = userMessageIndexes.getOrNull(marker.userOrdinal)
        if (originIndex == null) {
            // A queued turn can start before its user row reaches refreshed
            // history. Keep the older marker before the newer live region.
            unresolvedBeforeLive += marker
            return@forEach
        }
        val nextUserIndex = userMessageIndexes.getOrNull(marker.userOrdinal + 1)
        val boundary = nextUserIndex?.minus(1) ?: messages.lastIndex
        afterMessageIndex.getOrPut(boundary) { mutableListOf() } += marker
    }

    return StoppedMarkerPlacement(
        afterMessageIndex = afterMessageIndex,
        unresolvedBeforeLive = unresolvedBeforeLive,
        pendingLiveTail = pendingLiveTail,
    )
}

/** The web virtual window uses one user message or one assistant part per unit. */
private fun buildHistoryUnits(
    messages: List<ChatMessage>,
    expansionKeys: List<List<String?>>,
    stoppedPlacement: StoppedMarkerPlacement,
): List<HistoryUnit> = buildList {
    messages.forEachIndexed { messageIndex, message ->
        when (message) {
            is ChatMessage.User -> add(
                HistoryUnit.User(
                    message = message,
                    key = "history:user:$messageIndex",
                ),
            )
            is ChatMessage.Assistant -> message.parts.forEachIndexed { partIndex, part ->
                add(
                    HistoryUnit.AssistantPart(
                        part = part,
                        expansionKey = expansionKeys
                            .getOrNull(messageIndex)
                            ?.getOrNull(partIndex),
                        key = "history:part:$messageIndex:$partIndex",
                    ),
                )
            }
        }
        stoppedPlacement.afterMessageIndex[messageIndex].orEmpty().forEach { marker ->
            add(HistoryUnit.Stopped(marker))
        }
    }
}

internal fun shouldShowWorkingDots(phase: TurnPhase, tail: Part?): Boolean =
    phase == TurnPhase.AwaitingStart || tail == null || tail is Part.Tool || tail is Part.File

/** Stable identities for card expansion across lazy-list disposal and live/history moves. */
private fun partExpansionKeys(
    messages: List<ChatMessage>,
    transients: List<TransientItem>,
    liveParts: List<Part>,
): PartExpansionKeys {
    var thinkingOrdinal = 0
    var toolOrdinal = 0

    fun next(part: Part): String? = when (part) {
        is Part.Thinking -> "thinking:${thinkingOrdinal++}"
        is Part.Tool -> {
            val ordinal = toolOrdinal++
            part.id?.takeIf { it.isNotBlank() }?.let { "tool:id:$it" }
                ?: "tool:ordinal:$ordinal"
        }
        else -> null
    }

    return PartExpansionKeys(
        messageParts = messages.map { message ->
            (message as? ChatMessage.Assistant)?.parts?.map(::next).orEmpty()
        },
        transients = transients.map { item ->
            (item as? TransientItem.Decided)?.tool?.let(::next)
        },
        liveParts = liveParts.map(::next),
    )
}

/** The decided permission card owns its tool while it is kept as a live record. */
internal fun visibleChatContent(
    messages: List<ChatMessage>,
    liveParts: List<Part>,
    liveClientMessageId: String?,
    transients: List<TransientItem>,
): VisibleChatContent {
    val decidedTools = transients.filterIsInstance<TransientItem.Decided>().map { it.tool }

    fun Part.isRepresentedByDecision(): Boolean {
        if (this !is Part.Tool) return false
        return decidedTools.any { decided ->
            if (id != null && decided.id != null) {
                id == decided.id
            } else {
                name == decided.name && input == decided.input
            }
        }
    }

    val visibleMessages = if (decidedTools.isEmpty()) {
        messages
    } else {
        messages.mapNotNull { message ->
            if (message !is ChatMessage.Assistant) return@mapNotNull message
            val parts = message.parts.filterNot { it.isRepresentedByDecision() }
            message.copy(parts = parts).takeIf { parts.isNotEmpty() }
        }
    }
    val filteredLiveParts = if (decidedTools.isEmpty()) {
        liveParts
    } else {
        liveParts.filterNot { it.isRepresentedByDecision() }
    }
    // Completion reconciliation writes persisted history and retires the live
    // copy through two StateFlows. Suppress the identical tail during that tiny
    // overlap so a tool/card never renders twice or jumps between list slots.
    val persistedTail = (visibleMessages.lastOrNull() as? ChatMessage.Assistant)?.parts
    val persistedById = liveClientMessageId != null && visibleMessages.any { message ->
        message is ChatMessage.Assistant && message.clientMessageId == liveClientMessageId
    }
    val visibleLiveParts = if (
        persistedById ||
        (liveClientMessageId == null && persistedTail != null && filteredLiveParts == persistedTail)
    ) {
        emptyList()
    } else {
        filteredLiveParts
    }
    return VisibleChatContent(
        messages = visibleMessages,
        liveParts = visibleLiveParts,
    )
}

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenTerminal: (sessionId: String, cwd: String?) -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
    onOpenScheduled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val copyingMedia by viewModel.copyingMedia.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showControls by remember { mutableStateOf(false) }
    // Full-screen image viewer (web lightbox): url to name, null = closed.
    var viewerImage by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Agent handoff opened a fresh chat for the new agent — go there.
    LaunchedEffect(uiState.openSessionId) {
        uiState.openSessionId?.let { id ->
            viewModel.onHandoffOpened()
            onOpenChat(id)
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::attach) }

    val visibleContent = remember(
        uiState.messages,
        uiState.liveParts,
        uiState.liveClientMessageId,
        uiState.transients,
    ) {
        visibleChatContent(
            uiState.messages,
            uiState.liveParts,
            uiState.liveClientMessageId,
            uiState.transients,
        )
    }
    val expansionKeys = remember(visibleContent, uiState.transients) {
        partExpansionKeys(
            visibleContent.messages,
            uiState.transients,
            visibleContent.liveParts,
        )
    }
    // LazyColumn disposes off-screen cards. Keep explicit card choices at the
    // chat level so scrolling, tool results, and live-to-history moves preserve them.
    val cardExpansion = remember(viewModel.sessionIdPublic) {
        mutableStateMapOf<String, Boolean>()
    }
    val stoppedPlacement = remember(visibleContent.messages, uiState.stoppedMarkers) {
        placeStoppedMarkers(visibleContent.messages, uiState.stoppedMarkers)
    }
    val historyUnits = remember(
        visibleContent.messages,
        expansionKeys.messageParts,
        stoppedPlacement,
    ) {
        buildHistoryUnits(
            visibleContent.messages,
            expansionKeys.messageParts,
            stoppedPlacement,
        )
    }
    val contentItemCount = historyUnits.size + uiState.transients.size +
        uiState.outbox.size + stoppedPlacement.unresolvedBeforeLive.size +
        visibleContent.liveParts.size + stoppedPlacement.pendingLiveTail.size
    val showWorkingPulse = uiState.busy && stoppedPlacement.pendingLiveTail.isEmpty()
    val listItemCount = contentItemCount + if (showWorkingPulse) 1 else 0
    val lastListIndex = listItemCount - 1
    val stickThresholdPx = with(LocalDensity.current) {
        WakiliDimens.ChatStickThreshold.roundToPx()
    }
    // Stick to the bottom only when already near it (web STICK_THRESHOLD) — a
    // user reading older messages is never yanked back down by new content.
    fun nearBottomNow(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        return when {
            info.totalItemsCount == 0 -> true
            last == null || last.index != info.totalItemsCount - 1 -> false
            else -> (
                last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
                )
                .coerceAtLeast(0) <= stickThresholdPx
        }
    }
    val nearBottom by remember(stickThresholdPx) { derivedStateOf { nearBottomNow() } }
    var stickToBottom by remember { mutableStateOf(true) }
    // Any USER gesture in the transcript updates auto-follow SYNCHRONOUSLY —
    // including drags that chain out of an open tool card's inner scroller.
    // Those never flip listState.isScrollInProgress (the list is moved inside
    // the card's scroll session), so the isScrollInProgress effect below never
    // sees them: while stuck, the geometry re-pin then yanks the list back to
    // the bottom between drag frames and the card "jerks until it settles".
    // Setting the flag inside the dispatch beats the next re-pin to it.
    val transcriptGestures = remember(stickThresholdPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    // Scrolling toward the bottom re-arms only when actually
                    // landing there; any scroll away lets go.
                    stickToBottom = consumed.y < 0f && nearBottomNow()
                }
                return Offset.Zero
            }
        }
    }
    fun updateCardExpansion(key: String?, expanded: Boolean) {
        if (key == null) return
        // Opening a card is deliberate reading/navigation. Stop auto-follow
        // before its animated remeasure so its top stays put and it grows down.
        if (expanded) stickToBottom = false
        cardExpansion[key] = expanded
    }
    val scrollInProgress = listState.isScrollInProgress
    // Only actual list movement changes the sticky state. New streamed content
    // cannot turn it off before the list has a chance to follow the new layout.
    LaunchedEffect(scrollInProgress, nearBottom) {
        if (scrollInProgress) stickToBottom = nearBottom
    }
    // The bottom landing must happen before auto-follow may re-pin.
    var restored by remember { mutableStateOf(false) }
    // Live height of the card dock under the transcript (drives the re-pin).
    var dockHeight by remember { mutableStateOf(0) }
    // Auto-follow, geometry-driven (the web scroll(): every typewriter frame,
    // thinking delta, tool card/result, file, and markdown re-render funnels
    // into one re-pin). Instead of enumerating those sources, any remeasure
    // that leaves content hanging below the viewport while stuck re-pins to
    // the true end. requestScrollToItem never cancels a gesture or fling, so
    // native overscroll and momentum survive; a plain viewport resize (the
    // keyboard opening) keeps content anchored instead, like the web's
    // deliberate no-scroll-on-resize.
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        var lastViewport = Int.MIN_VALUE
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(
                info.viewportEndOffset,
                info.totalItemsCount,
                when {
                    last == null -> 0
                    last.index != info.totalItemsCount - 1 -> Int.MAX_VALUE
                    else -> last.offset + last.size + info.afterContentPadding -
                        info.viewportEndOffset
                },
            )
        }.collect { (viewport, total, belowGap) ->
            val resized = lastViewport != Int.MIN_VALUE && viewport != lastViewport
            lastViewport = viewport
            if (resized || total == 0 || belowGap <= 0) return@collect
            if (!stickToBottom || listState.isScrollInProgress) return@collect
            // Pin the END of the last item to the viewport bottom. A last item
            // taller than the viewport needs the explicit offset — the index
            // alone would land at its TOP. When the last item isn't measured
            // yet, offset 0 over-shoots to its top and the next remeasure's
            // emission corrects with its real size.
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val size = if (lastVisible?.index == total - 1) lastVisible.size else 0
            val offset = (size + info.afterContentPadding - info.viewportEndOffset)
                .coerceAtLeast(0)
            listState.requestScrollToItem(total - 1, offset)
        }
    }
    // Discrete rows (especially tool/file cards) are inserted before the
    // persistent pulse. Pre-pin in the same composition cycle so Android does
    // not paint the card at the pulse's old viewport position for one frame and
    // only then scroll it upward. Growing text still uses the geometry observer.
    LaunchedEffect(contentItemCount, uiState.busy, restored) {
        if (
            restored && stickToBottom && !listState.isScrollInProgress &&
            lastListIndex >= 0
        ) {
            listState.requestScrollToItem(lastListIndex)
        }
    }
    // Sending always jumps to the bottom and re-engages sticking, wherever you
    // were reading (the web client's stick = true on send).
    LaunchedEffect(uiState.sendSeq) {
        if (uiState.sendSeq > 0 && lastListIndex >= 0) {
            listState.scrollToItem(lastListIndex)
            stickToBottom = true
        }
    }
    // Opening or switching to a chat always lands at the end of the transcript
    // (web behavior: saved reading positions were dropped — a restored
    // mid-history position shifts as content above it loads).
    LaunchedEffect(uiState.loading) {
        if (uiState.loading || uiState.error || restored) return@LaunchedEffect
        if (lastListIndex >= 0) listState.scrollToItem(lastListIndex)
        stickToBottom = true
        restored = true
        // That landing used the item heights of THIS moment — but images decode
        // a beat later (the 4:3 loading box grows to the real aspect), markdown
        // and cards remeasure, and during the open transition those growths
        // arrive in the same frames as viewport changes, which the geometry
        // observer below deliberately swallows as resizes. So the chat settled
        // slightly ABOVE the true end. Chase the end for a short settle window:
        // every frame, if content hangs below the viewport while still stuck,
        // re-pin — resize or not. A user gesture releases stickToBottom
        // synchronously (nested-scroll), which exits the loop immediately.
        val settleEnd = withFrameNanos { it } + 1_500_000_000L
        while (stickToBottom) {
            val frame = withFrameNanos { it }
            if (frame > settleEnd) break
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()
            if (total == 0 || last == null || listState.isScrollInProgress) continue
            val belowGap = if (last.index != total - 1) {
                Int.MAX_VALUE
            } else {
                last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
            }
            if (belowGap > 0) {
                // Pin the END of the last item, exactly like the observer.
                val size = if (last.index == total - 1) last.size else 0
                val offset = (size + info.afterContentPadding - info.viewportEndOffset)
                    .coerceAtLeast(0)
                listState.requestScrollToItem(total - 1, offset)
            }
        }
    }
    // The card dock (permission/question) sits under the transcript: a card
    // appearing shrinks the viewport and would leave the view a card-height
    // above the end. Viewport resizes deliberately keep the anchor (the
    // keyboard case) — so follow the DOCK's size explicitly and re-pin while
    // stuck, exactly like the web's ResizeObserver on #dock → repin().
    LaunchedEffect(dockHeight) {
        if (restored && stickToBottom && lastListIndex >= 0) {
            listState.scrollToItem(lastListIndex)
        }
    }

    val colors = WakiliTheme.colors
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            when {
                uiState.loading && contentItemCount == 0 && !uiState.busy -> WakiliLoading()
                uiState.error -> ErrorPane(onRetry = viewModel::retry)
                contentItemCount == 0 && !uiState.busy -> EmptyGreeting()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(transcriptGestures),
                    contentPadding = PaddingValues(
                        start = WakiliDimens.Space16,
                        end = WakiliDimens.Space16,
                        // Room to scroll clear of the floating topbar.
                        top = WakiliDimens.Space48 + WakiliDimens.Space20,
                        bottom = WakiliDimens.Space20,
                    ),
                    verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space18),
                ) {
                    items(
                        count = historyUnits.size,
                        key = { index -> historyUnits[index].key },
                    ) { index ->
                        when (val unit = historyUnits[index]) {
                            is HistoryUnit.User -> MessageView(
                                message = unit.message,
                                markdown = uiState.markdown,
                                baseUrl = uiState.baseUrl,
                                onOpenImage = { url, name -> viewerImage = url to name },
                                onDownload = viewModel::download,
                                onCopy = viewModel::copyMedia,
                                copyingUrl = copyingMedia,
                            )
                            is HistoryUnit.AssistantPart -> PartView(
                                part = unit.part,
                                markdown = uiState.markdown,
                                baseUrl = uiState.baseUrl,
                                cardExpanded = unit.expansionKey?.let(cardExpansion::get),
                                onCardExpandedChange = { expanded ->
                                    updateCardExpansion(unit.expansionKey, expanded)
                                },
                                onOpenImage = { url, name -> viewerImage = url to name },
                                onDownload = viewModel::download,
                                onCopy = viewModel::copyMedia,
                                copyingUrl = copyingMedia,
                            )
                            is HistoryUnit.Stopped -> StoppedNote(unit.marker)
                        }
                    }
                    items(uiState.transients.size) { index ->
                        val expansionKey = expansionKeys.transients[index]
                        TransientView(
                            item = uiState.transients[index],
                            cardExpanded = expansionKey?.let(cardExpansion::get),
                            onCardExpandedChange = { expanded ->
                                updateCardExpansion(expansionKey, expanded)
                            },
                        )
                    }
                    items(uiState.outbox.size) { index ->
                        OutboxRow(
                            entry = uiState.outbox[index],
                            onRetry = viewModel::retryOutbox,
                            onDiscard = viewModel::discardOutbox,
                        )
                    }
                    items(
                        count = stoppedPlacement.unresolvedBeforeLive.size,
                        key = { index -> stoppedPlacement.unresolvedBeforeLive[index].key },
                    ) { index ->
                        StoppedNote(stoppedPlacement.unresolvedBeforeLive[index])
                    }
                    items(
                        count = visibleContent.liveParts.size,
                        // Stable keys: transients/outbox rows appearing mid-turn
                        // must not shift the typewriter's saved reveal position.
                        key = { index -> "live:$index" },
                    ) { index ->
                        val expansionKey = expansionKeys.liveParts[index]
                        PartView(
                            visibleContent.liveParts[index],
                            uiState.markdown,
                            uiState.baseUrl,
                            // Only the turn's tail is still receiving deltas.
                            streaming = uiState.busy && index == visibleContent.liveParts.lastIndex,
                            initiallyRevealedChars = if (
                                index == visibleContent.liveParts.lastIndex
                            ) {
                                uiState.liveTailRevealedChars
                            } else {
                                0
                            },
                            cardExpanded = expansionKey?.let(cardExpansion::get),
                            onCardExpandedChange = { expanded ->
                                updateCardExpansion(expansionKey, expanded)
                            },
                            onOpenImage = { url, name -> viewerImage = url to name },
                            onDownload = viewModel::download,
                            onCopy = viewModel::copyMedia,
                            copyingUrl = copyingMedia,
                        )
                    }
                    // A stopped note owns the current turn's terminal tail. It
                    // stays below partial live output, then migrates—using the
                    // same key—to its history boundary after reconciliation.
                    items(
                        count = stoppedPlacement.pendingLiveTail.size,
                        key = { index -> stoppedPlacement.pendingLiveTail[index].key },
                    ) { index ->
                        StoppedNote(stoppedPlacement.pendingLiveTail[index])
                    }
                    // The working pulse stays mounted for the WHOLE turn (web
                    // workingEl): pinned under whatever has streamed so far, but
                    // hidden IN PLACE while text/thoughts are streaming — the
                    // streaming block is itself the indicator. Its space stays
                    // reserved, so toggling mid-turn never shifts the layout.
                    if (showWorkingPulse) {
                        item(key = "working-pulse") {
                            val tail = visibleContent.liveParts.lastOrNull()
                            TypingDots(
                                visible = shouldShowWorkingDots(uiState.turnPhase, tail),
                            )
                        }
                    }
                }
            }
            // Floating topbar over the list, on a soft top gradient (web #topbar:
            // solid --bg down to transparent, messages scroll underneath).
            ChatTopBar(
                onBack = onBack,
                uiState = uiState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to colors.bg,
                            WakiliDimens.TopBarFadeSolidStop to colors.bg,
                            1f to colors.bg.copy(alpha = 0f),
                        ),
                    ),
            )
        }

        Box(Modifier.onSizeChanged { dockHeight = it.height }) {
            CardDock(uiState, viewModel)
        }
        QueuedChip(uiState, onCancel = viewModel::cancelQueue)
        // The chip opens the Scheduled tasks PAGE (create/edit/cancel live
        // there) — a real screen instead of the old cramped bottom sheet.
        ScheduledChip(uiState, onManage = onOpenScheduled)
        AttachmentRow(uiState, viewModel)
        // Sending drops the keyboard (the web composer's post-send blur) so the
        // reply streams in full view.
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        Composer(
            uiState = uiState,
            onInput = viewModel::onInputChange,
            onSend = {
                viewModel.send()
                keyboard?.hide()
                focusManager.clearFocus()
            },
            onStop = viewModel::stop,
            onAttachImages = { pickFile.launch("image/*") },
            onAttachFiles = { pickFile.launch("*/*") },
            onOpenTerminal = { onOpenTerminal(viewModel.sessionIdPublic, uiState.cwd) },
            onReceiveImage = viewModel::attach,
            viewModel = viewModel,
            showControls = showControls,
            onOpenControls = { showControls = true },
            onDismissControls = { showControls = false },
        )
    }

    viewerImage?.let { (url, name) ->
        WakiliImageViewer(
            url = url,
            name = name,
            onDismiss = { viewerImage = null },
            onDownload = { viewModel.download(url, name) },
        )
    }
}

/** #topbar — floating over the messages: menu button + model pill + folder chip. */
@Composable
private fun ChatTopBar(
    onBack: () -> Unit,
    uiState: ChatUiState,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = WakiliDimens.Space10),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        ) {
            // .icon-btn.round — back to the Home page. Same glyph as the
            // terminal page's back button (CornerUpLeft) for one consistent back.
            WakiliRoundIconButton(
                icon = WakiliIcons.CornerUpLeft,
                onClick = onBack,
                contentDescription = null,
            )
            // The model picker moved to the composer — the topbar keeps only
            // back + the folder chip, so the chat header stays quiet.
            Spacer(Modifier.weight(1f))
            // .topbar-folder — read-only chip naming the chat's project folder,
            // led by the connection dot (green live / red down) so you always
            // see the link state without leaving the chat. Folder selection
            // itself lives on the Home page, like the web.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
                modifier = Modifier
                    .widthIn(max = WakiliDimens.SessionModelMaxWidth)
                    .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                    .background(colors.pill)
                    .border(
                        WakiliDimens.BorderThin,
                        colors.border,
                        RoundedCornerShape(WakiliDimens.RadiusLg),
                    )
                    .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space6),
            ) {
                ConnectionDot(connected = uiState.connected)
                Text(
                    text = uiState.cwd?.let(::folderBaseName)
                        ?: stringResource(R.string.chat_default_project),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The "Reconnecting…" text lives on the home page only now; the chat
        // shows connection state through the folder chip's dot alone.
    }
}

/** .messages:empty::before — centered greeting on a fresh chat. */
@Composable
private fun EmptyGreeting() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.chat_greeting),
            style = MaterialTheme.typography.headlineMedium,
            color = WakiliTheme.colors.text,
            modifier = Modifier.alpha(WakiliDimens.AlphaGreeting),
        )
    }
}

@Composable
private fun ErrorPane(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(WakiliDimens.Space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_load_error),
            style = MaterialTheme.typography.bodyMedium,
            color = WakiliTheme.colors.muted,
        )
        Spacer(Modifier.height(WakiliDimens.Space8))
        WakiliButton(
            text = stringResource(R.string.chat_retry),
            onClick = onRetry,
            style = WakiliButtonStyle.Primary,
        )
    }
}

/** .dock — pending permission/question cards pinned above the composer. */
@Composable
private fun CardDock(uiState: ChatUiState, viewModel: ChatViewModel) {
    val card = uiState.cards.firstOrNull() ?: return
    val colors = WakiliTheme.colors
    Column(
        Modifier.padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space4),
        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        when (card) {
            is PendingCard.Permission -> PermissionCardView(
                card = card,
                queuedCount = uiState.cards.size - 1,
                onDecision = { decision -> viewModel.answerPermission(card, decision) },
            )
            is PendingCard.Question -> QuestionCardView(
                card = card,
                onSubmit = { answer -> viewModel.answerQuestion(card, answer) },
            )
        }
        // .perm-batch — Allow all / Deny all bar for parallel gated tools.
        if (uiState.cards.count { it is PendingCard.Permission } > 1) {
            WakiliCard(
                modifier = Modifier.fillMaxWidth(),
                radius = WakiliDimens.RadiusLg,
                borderColor = colors.accent,
                contentPadding = WakiliDimens.Space8,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                ) {
                    Text(
                        text = stringResource(R.string.chat_more_requests, uiState.cards.size - 1),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = MaterialTheme.typography.labelLarge.fontWeight,
                        ),
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    WakiliButton(
                        text = stringResource(R.string.chat_allow_all),
                        onClick = { viewModel.answerAllPermissions("allow") },
                        style = WakiliButtonStyle.Primary,
                        contentPadding = PaddingValues(
                            horizontal = WakiliDimens.Space12,
                            vertical = WakiliDimens.Space6,
                        ),
                    )
                    WakiliButton(
                        text = stringResource(R.string.chat_deny_all),
                        onClick = { viewModel.answerAllPermissions("deny") },
                        style = WakiliButtonStyle.Outline,
                        contentPadding = PaddingValues(
                            horizontal = WakiliDimens.Space12,
                            vertical = WakiliDimens.Space6,
                        ),
                    )
                }
            }
        }
    }
}

/** .queued — dashed chip above the composer: clock + label + cancel. */
@Composable
private fun QueuedChip(uiState: ChatUiState, onCancel: () -> Unit) {
    if (uiState.queuedCount == 0) return
    val colors = WakiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space2)
            .background(colors.panel2, RoundedCornerShape(WakiliDimens.RadiusLg))
            .dashedBorder(colors.border, radius = WakiliDimens.RadiusLg)
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space6 + WakiliDimens.BorderThin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        WakiliIcon(WakiliIcons.Clock, size = WakiliDimens.IconSm, tint = colors.muted)
        Text(
            text = stringResource(R.string.chat_queued, uiState.queuedFirst) +
                if (uiState.queuedCount > 1) " (+${uiState.queuedCount - 1})" else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        WakiliGhostIconButton(
            icon = WakiliIcons.X,
            onClick = onCancel,
            iconSize = WakiliDimens.IconSm,
        )
    }
}

/** .scheduled — chip above the composer: N pending tasks, tap to manage. */
@Composable
private fun ScheduledChip(uiState: ChatUiState, onManage: () -> Unit) {
    if (uiState.scheduledCount == 0) return
    val colors = WakiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space2)
            .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
            .background(colors.panel2, RoundedCornerShape(WakiliDimens.RadiusLg))
            .dashedBorder(colors.border, radius = WakiliDimens.RadiusLg)
            .clickable(onClick = onManage)
            .padding(horizontal = WakiliDimens.Space11, vertical = WakiliDimens.Space6 + WakiliDimens.BorderThin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        WakiliIcon(WakiliIcons.Clock, size = WakiliDimens.IconSm, tint = colors.accent)
        Text(
            text = stringResource(R.string.chat_scheduled_chip, uiState.scheduledCount),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "+ → Schedule" dialog: WHAT will run (the composer text + attached files),
 * a time picker that starts at the CURRENT time (not 00:00), and an honest
 * "Runs today/tomorrow at …" line — a past time silently means tomorrow, so
 * the dialog says so instead of surprising later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    taskText: String,
    attachmentCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    val now = remember { java.util.Calendar.getInstance() }
    val state = rememberTimePickerState(
        initialHour = now.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = now.get(java.util.Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    val colors = WakiliTheme.colors
    // Reading hour/minute here re-renders the label as the dial moves.
    val runsAt = nextOccurrenceOf(state.hour, state.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = {
            Text(stringResource(R.string.chat_schedule_title), color = colors.text)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space12),
            ) {
                // The task being promised — so the picker is never abstract.
                if (taskText.isNotEmpty()) {
                    Text(
                        text = taskText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
                            .background(colors.panel2)
                            .padding(
                                horizontal = WakiliDimens.Space10,
                                vertical = WakiliDimens.Space8,
                            ),
                    )
                }
                if (attachmentCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
                    ) {
                        WakiliIcon(
                            WakiliIcons.Paperclip,
                            size = WakiliDimens.IconXs,
                            tint = colors.muted,
                        )
                        Text(
                            text = stringResource(R.string.chat_schedule_files, attachmentCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                }
                TimePicker(state = state)
                Text(
                    text = stringResource(
                        if (runsToday(runsAt)) {
                            R.string.chat_schedule_runs_today
                        } else {
                            R.string.chat_schedule_runs_tomorrow
                        },
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                            .format(java.util.Date(runsAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(
                    text = stringResource(R.string.chat_schedule_set),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.chat_schedule_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/** Epoch millis of the next occurrence of [hour]:[minute] (past today → tomorrow). */
private fun nextOccurrenceOf(hour: Int, minute: Int): Long =
    java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

private fun runsToday(at: Long): Boolean {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) &&
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
}

/** .chips / .att-card — 1:1 pending-attachment cards above the composer. */
@Composable
private fun AttachmentRow(uiState: ChatUiState, viewModel: ChatViewModel) {
    if (uiState.attachments.isEmpty()) return
    val colors = WakiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space2),
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        uiState.attachments.forEach { att ->
            Box(
                Modifier
                    .size(WakiliDimens.AttachmentCard)
                    .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                    .background(colors.panel2)
                    .border(
                        WakiliDimens.BorderThin,
                        if (att.failed) colors.danger else colors.border,
                        RoundedCornerShape(WakiliDimens.RadiusLg),
                    ),
            ) {
                val remoteImage = att.url?.let { uiState.baseUrl.trimEnd('/') + it }
                if (att.image && (att.uri != null || remoteImage != null)) {
                    AsyncImage(
                        model = att.uri ?: remoteImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(WakiliDimens.Space9),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        WakiliIcon(
                            if (att.image) WakiliIcons.Image else WakiliIcons.Paperclip,
                            tint = colors.accent,
                        )
                        Text(
                            text = att.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (att.uploading) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = WakiliDimens.AlphaDisabled)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            progress = { att.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(WakiliDimens.AttachmentProgressRing),
                            color = colors.accent,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            strokeWidth = WakiliDimens.AttachmentProgressStroke,
                        )
                    }
                }
                // Failed upload: tap the overlay to retry (web .att-retry).
                if (att.failed && att.uri != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = WakiliDimens.AlphaDisabled))
                            .clickable { viewModel.retryAttachment(att) },
                        contentAlignment = Alignment.Center,
                    ) {
                        WakiliIcon(WakiliIcons.RotateCw, tint = Color.White)
                    }
                }
                // .att-x — remove button, top-right
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(WakiliDimens.Space4)
                        .size(WakiliDimens.AttachRemove)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = WakiliDimens.AlphaBlocked))
                        .clickable { viewModel.removeAttachment(att) },
                    contentAlignment = Alignment.Center,
                ) {
                    WakiliIcon(WakiliIcons.X, size = WakiliDimens.IconXs, tint = Color.White)
                }
            }
        }
    }
}

/** .composer — slash menu + unified rounded pill with attach / input / send. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    uiState: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImages: () -> Unit,
    onAttachFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onReceiveImage: (android.net.Uri) -> Unit,
    viewModel: ChatViewModel,
    showControls: Boolean,
    onOpenControls: () -> Unit,
    onDismissControls: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var addMenuOpen by remember { mutableStateOf(false) }
    var scheduleDialogOpen by remember { mutableStateOf(false) }
    val inputState = rememberTextFieldState(initialText = uiState.input)
    val currentOnInput by rememberUpdatedState(onInput)
    LaunchedEffect(inputState) {
        snapshotFlow { inputState.text.toString() }.collect { currentOnInput(it) }
    }
    LaunchedEffect(uiState.input) {
        if (inputState.text.toString() != uiState.input) {
            inputState.setTextAndPlaceCursorAtEnd(uiState.input)
        }
    }
    // The web composer's contenteditable trick, natively: images committed by
    // the KEYBOARD's clipboard panel (the IME rich-content channel), pasted
    // with the long-press menu, or dropped on the field all land here and
    // become pending attachments — no extra taps. Text passes through to the
    // field untouched.
    val receiveImages = remember(onReceiveImage) {
        ReceiveContentListener { content ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            content.consume { item ->
                val uri = item.uri
                if (uri != null) {
                    onReceiveImage(uri)
                    true
                } else {
                    false
                }
            }
        }
    }
    Column(
        Modifier.padding(
            start = WakiliDimens.Space16,
            end = WakiliDimens.Space16,
            top = WakiliDimens.Space8,
            bottom = WakiliDimens.Space16,
        ),
        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        // The model name now rides INSIDE the composer box, next to +, instead of
        // floating above it — every per-turn control the user can touch sits on
        // one row together.
        val modelLabel = uiState.agent?.controls?.get("model")?.options
            ?.firstOrNull { it.value == uiState.controls["model"] }?.label
            ?: uiState.agent?.label ?: ""
        if (showControls) {
            ControlsPopover(
                agents = uiState.agents,
                agentId = uiState.agentId,
                controls = uiState.controls,
                autoContinue = uiState.autoContinue,
                // setAgent routes an empty chat in place, a non-empty one
                // through the handoff (a new chat for that agent).
                onSwitchAgent = viewModel::setAgent,
                onControl = viewModel::setControl,
                onAutoContinue = viewModel::setAutoContinue,
                onDismiss = onDismissControls,
            )
        }
        if (scheduleDialogOpen) {
            ScheduleDialog(
                taskText = uiState.input.trim(),
                attachmentCount = uiState.attachments.count { !it.uploading && !it.failed },
                onDismiss = { scheduleDialogOpen = false },
                onConfirm = { hour, minute ->
                    scheduleDialogOpen = false
                    viewModel.schedule(hour, minute)
                },
            )
        }

        // .slash-menu — Claude-CLI style command list. ALL matches, scrolling
        // inside the card's max height (the web shows the full list too — a
        // silent cap made only the first few commands reachable).
        if (uiState.slashMatches.isNotEmpty()) {
            WakiliCard(
                radius = WakiliDimens.RadiusPanel,
                contentPadding = WakiliDimens.Space6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = WakiliDimens.MenuMaxHeight),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    uiState.slashMatches.forEach { cmd ->
                        Text(
                            text = "/$cmd",
                            style = WakiliMono.Small.copy(
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontWeight = MaterialTheme.typography.labelLarge.fontWeight,
                            ),
                            color = colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                                .clickable { onInput("/$cmd ") }
                                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
                        )
                    }
                }
            }
        }

        // .composer-bar — two rows: what you write on top, everything you press
        // underneath. A ChatGPT-style pill: ALL corners rounded, floating free
        // of the screen edges, with the app's usual border (accent on focus).
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val barShape = RoundedCornerShape(WakiliDimens.RadiusComposer)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (uiState.blocked) WakiliDimens.AlphaBlocked else 1f)
                .clip(barShape)
                .background(colors.panel, barShape)
                .border(
                    WakiliDimens.BorderThin,
                    if (focused) colors.accent else colors.border,
                    barShape,
                )
                .padding(WakiliDimens.Space6),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
        ) {
            WakiliBareTextField(
                state = inputState,
                placeholder = stringResource(R.string.chat_input_hint),
                enabled = !uiState.blocked,
                maxLines = MAX_INPUT_LINES,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    // The first line sits a touch lower and further in, like
                    // ChatGPT's "Ask anything" (the decorator adds 6dp more).
                    .padding(top = WakiliDimens.Space6, start = WakiliDimens.Space4, end = WakiliDimens.Space4)
                    .heightIn(max = WakiliDimens.InputMaxHeight)
                    .contentReceiver(receiveImages),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            ) {
                // .btn.attach — borderless + button, opens the add menu
                Box {
                    ComposerCircleButton(
                        icon = WakiliIcons.Plus,
                        background = Color.Transparent,
                        tint = colors.text,
                        iconSize = WakiliDimens.IconXl,
                        enabled = !uiState.blocked,
                        onClick = { addMenuOpen = !addMenuOpen },
                    )
                    // .add-menu — compact list floating above the + button. Shares
                    // the app's one dismissal recipe (WakiliAnchoredPopup): outside
                    // taps close it on RELEASE, back closes it, and its 190dp card
                    // never spans the screen like the old inline block did.
                    if (addMenuOpen) {
                        val gapPx = with(LocalDensity.current) { WakiliDimens.Space10.roundToPx() }
                        val positionProvider = remember(gapPx) { AddMenuPositionProvider(gapPx) }
                        WakiliAnchoredPopup(
                            positionProvider = positionProvider,
                            onDismiss = { addMenuOpen = false },
                        ) {
                            WakiliCard(
                                radius = WakiliDimens.Radius,
                                contentPadding = WakiliDimens.Space6,
                                modifier = Modifier.width(WakiliDimens.PickerMinWidth),
                            ) {
                                AddMenuItem(
                                    icon = WakiliIcons.Image,
                                    label = stringResource(R.string.chat_add_images),
                                    onClick = { addMenuOpen = false; onAttachImages() },
                                )
                                AddMenuItem(
                                    icon = WakiliIcons.Paperclip,
                                    label = stringResource(R.string.chat_add_files),
                                    onClick = { addMenuOpen = false; onAttachFiles() },
                                )
                                AddMenuItem(
                                    icon = WakiliIcons.Terminal,
                                    label = stringResource(R.string.chat_add_terminal),
                                    onClick = { addMenuOpen = false; onOpenTerminal() },
                                )
                                // Schedule the typed message for later — the
                                // gateway holds it (and rides out a dead quota
                                // window), so the phone can sleep.
                                AddMenuItem(
                                    icon = WakiliIcons.Clock,
                                    label = stringResource(R.string.chat_add_schedule),
                                    onClick = {
                                        addMenuOpen = false
                                        if (viewModel.scheduleGate()) scheduleDialogOpen = true
                                    },
                                )
                            }
                        }
                    }
                }
                // Model chip — sits right beside +, opens the same controls sheet.
                // Exactly ONE flexible slot in this row: a weighted Box the chip sits
                // at the start of. Giving the chip weight(1f, fill = false) AND
                // following it with a weighted Spacer splits the free space 50/50 —
                // the chip then leaves its half unused (that's what fill = false
                // means) and the slack never reaches the Spacer, so Send stops short
                // of the row's end instead of pinning to it.
                if (modelLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
                            modifier = Modifier
                                .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
                                .clickable(enabled = !uiState.blocked, onClick = onOpenControls)
                                .padding(horizontal = WakiliDimens.Space8, vertical = WakiliDimens.Space4),
                        ) {
                            Text(
                                text = modelLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            WakiliIcon(WakiliIcons.ChevronDown, size = WakiliDimens.IconXs, tint = colors.muted)
                        }
                    }
                } else {
                    // no model name yet — the spacer is the flexible slot instead
                    Spacer(Modifier.weight(1f))
                }
                if (uiState.showStop) {
                    // .btn.send.stop — red square stop
                    ComposerCircleButton(
                        icon = WakiliIcons.Square,
                        background = colors.danger,
                        tint = Color.White,
                        enabled = true,
                        onClick = onStop,
                    )
                } else {
                    // .btn.send — text-colored disc with an arrow-up glyph
                    ComposerCircleButton(
                        icon = WakiliIcons.ArrowUp,
                        background = colors.text,
                        tint = colors.bg,
                        enabled = uiState.canSend,
                        onClick = onSend,
                    )
                }
            }
        }
    }
}

private const val MAX_INPUT_LINES = 6

/**
 * Places the add-menu directly above its anchor (the + button), left-aligned —
 * the web's `.add-menu { left: 0; bottom: calc(100% + 10px) }`. Clamped to stay
 * on screen so it never runs off the top or right edge.
 */
private class AddMenuPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** .add-item — icon + label row inside the + menu. */
@Composable
private fun AddMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = WakiliTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
    ) {
        WakiliIcon(icon, tint = colors.muted)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
        )
    }
}

@Composable
private fun ComposerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp = WakiliDimens.Icon,
) {
    Box(
        modifier = Modifier
            .size(WakiliDimens.ComposerButton)
            .alpha(if (enabled) 1f else WakiliDimens.AlphaSendDisabled)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WakiliIcon(icon, size = iconSize, tint = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageView(
    message: ChatMessage,
    markdown: Boolean,
    baseUrl: String,
    modifier: Modifier = Modifier,
    partExpansionKeys: List<String?> = emptyList(),
    cardExpansion: Map<String, Boolean> = emptyMap(),
    onCardExpansionChange: (key: String, expanded: Boolean) -> Unit = { _, _ -> },
    onOpenImage: (url: String, name: String) -> Unit = { _, _ -> },
    onDownload: (url: String, name: String) -> Unit = { _, _ -> },
    onCopy: (url: String, name: String, image: Boolean) -> Unit = { _, _, _ -> },
    copyingUrl: String? = null,
) {
    val colors = WakiliTheme.colors
    val clipboard = LocalClipboardManager.current
    when (message) {
        // .msg.user / .bubble — right-aligned, --user background, radius 20
        is ChatMessage.User -> BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            var menuOpen by remember(message) { mutableStateOf(false) }
            val maxBubble = maxWidth * WakiliDimens.BubbleMaxWidthFraction
            val maxImage = minOf(
                maxWidth * WakiliDimens.ImageMsgMaxWidthFraction,
                WakiliDimens.FileCardMaxWidth,
            )
            Column(
                Modifier.align(Alignment.CenterEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            ) {
                // Attachments — .att-msg.img image bubble for pictures (tap to
                // open full-screen), .att-msg.doc paperclip card otherwise.
                // Long-press either for Save / Copy (web attachMediaMenu).
                message.attachments.forEach { attachment ->
                    val absolute = attachment.url.takeIf { it.isNotEmpty() }?.let {
                        if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it
                    }
                    val isImage = attachment.image ||
                        CHAT_IMAGE_RE.containsMatchIn(attachment.name)
                    if (absolute != null && isImage) {
                        ChatImage(
                            url = absolute,
                            name = attachment.name,
                            maxWidth = maxImage,
                            onOpenImage = onOpenImage,
                            onDownload = onDownload,
                            onCopy = onCopy,
                            copying = copyingUrl == absolute,
                        )
                    } else {
                        var mediaMenu by remember(attachment) { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .widthIn(max = maxBubble)
                                .clip(RoundedCornerShape(WakiliDimens.Radius))
                                .background(colors.panel)
                                .border(
                                    WakiliDimens.BorderThin,
                                    colors.border,
                                    RoundedCornerShape(WakiliDimens.Radius),
                                )
                                .then(
                                    if (absolute != null) {
                                        Modifier.combinedClickable(
                                            onClick = { onDownload(absolute, attachment.name) },
                                            onLongClick = { mediaMenu = true },
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = WakiliDimens.Space14, vertical = WakiliDimens.Space11),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space9),
                        ) {
                            WakiliIcon(WakiliIcons.Paperclip, tint = colors.accent)
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (absolute != null) {
                                MediaMenu(
                                    expanded = mediaMenu,
                                    url = absolute,
                                    name = attachment.name,
                                    image = false,
                                    onDismiss = { mediaMenu = false },
                                    onDownload = onDownload,
                                    onCopy = onCopy,
                                )
                            }
                        }
                    }
                }
                if (message.text.isNotBlank()) {
                    Box(
                        Modifier
                            .widthIn(max = maxBubble)
                            .clip(RoundedCornerShape(WakiliDimens.RadiusBubble))
                            .background(colors.user)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true },
                            )
                            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space11),
                    ) {
                        if (markdown) {
                            MarkdownText(source = message.text, fillWidth = false)
                        } else {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.text,
                            )
                        }
                        WakiliContextMenu(
                            expanded = menuOpen,
                            items = listOf(
                                WakiliMenuItem(stringResource(R.string.chat_copy), WakiliIcons.Copy) {
                                    clipboard.setText(AnnotatedString(message.text))
                                },
                            ),
                            alignEnd = true,
                            onDismiss = { menuOpen = false },
                        )
                    }
                }
            }
        }

        is ChatMessage.Assistant -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        ) {
            message.parts.forEachIndexed { index, part ->
                val expansionKey = partExpansionKeys.getOrNull(index)
                PartView(
                    part = part,
                    markdown = markdown,
                    baseUrl = baseUrl,
                    cardExpanded = expansionKey?.let(cardExpansion::get),
                    onCardExpandedChange = { expanded ->
                        expansionKey?.let { onCardExpansionChange(it, expanded) }
                    },
                    onOpenImage = onOpenImage,
                    onDownload = onDownload,
                    onCopy = onCopy,
                )
            }
        }
    }
}


/**
 * A failed send: the message as a danger-tinted user bubble plus a note with
 * Retry / Discard actions (web MessageList.addOutbox).
 */
@Composable
private fun OutboxRow(
    entry: OutboxEntry,
    onRetry: (OutboxEntry) -> Unit,
    onDiscard: (OutboxEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    val retrying = entry.status == OutboxStatus.Retrying
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubble = maxWidth * WakiliDimens.BubbleMaxWidthFraction
        Column(
            Modifier.align(Alignment.CenterEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        ) {
            Box(
                Modifier
                    .widthIn(max = maxBubble)
                    .clip(RoundedCornerShape(WakiliDimens.RadiusBubble))
                    .background(colors.user)
                    .border(
                        WakiliDimens.BorderThin,
                        if (retrying) colors.accent else colors.danger,
                        RoundedCornerShape(WakiliDimens.RadiusBubble),
                    )
                    .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space11),
            ) {
                Text(
                    text = entry.text.ifBlank { entry.attachments.joinToString { it.name } },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            ) {
                Text(
                    text = stringResource(
                        if (retrying) R.string.chat_outbox_sending else R.string.chat_outbox_failed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (retrying) colors.muted else colors.danger,
                )
                if (!retrying) {
                    WakiliButton(
                        text = stringResource(R.string.chat_outbox_retry),
                        onClick = { onRetry(entry) },
                        style = WakiliButtonStyle.Outline,
                    )
                    WakiliButton(
                        text = stringResource(R.string.chat_outbox_discard),
                        onClick = { onDiscard(entry) },
                        style = WakiliButtonStyle.Outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransientView(
    item: TransientItem,
    modifier: Modifier = Modifier,
    cardExpanded: Boolean? = null,
    onCardExpandedChange: (Boolean) -> Unit = {},
) {
    val colors = WakiliTheme.colors
    when (item) {
        is TransientItem.Exec -> Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            ) {
                WakiliIcon(WakiliIcons.Terminal, size = WakiliDimens.IconSm, tint = colors.muted)
                Text(
                    text = item.command,
                    style = WakiliMono.Small,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CodeBlock(text = item.output.take(MAX_EXEC_OUTPUT), isError = !item.ok, isOutput = true)
        }

        is TransientItem.Decided -> DecidedPermissionCard(
            item = item,
            cardExpanded = cardExpanded ?: false,
            onCardExpandedChange = onCardExpandedChange,
            modifier = modifier,
        )
    }
}

@Composable
private fun StoppedNote(marker: StoppedMarkerUi, modifier: Modifier = Modifier) {
    DecisionNote(
        icon = WakiliIcons.Square,
        text = stringResource(
            if (marker.interrupted) R.string.chat_interrupted else R.string.chat_stopped,
        ),
        modifier = modifier,
    )
}

/** .stopped-note / decided .perm-head — bordered pill note with a small icon. */
/** Web .msg.perm.decided: decision header and its tool card share one record. */
@Composable
private fun DecidedPermissionCard(
    item: TransientItem.Decided,
    cardExpanded: Boolean,
    onCardExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    val denied = item.decision == "deny"
    val label = when (item.decision) {
        "deny" -> stringResource(R.string.chat_denied, item.tool.name)
        "allow_session" -> stringResource(R.string.chat_allowed_session, item.tool.name)
        else -> stringResource(R.string.chat_allowed, item.tool.name)
    }

    WakiliCard(
        modifier = modifier.fillMaxWidth(),
        radius = WakiliDimens.RadiusLg,
        borderColor = colors.border,
        contentPadding = WakiliDimens.Space8,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WakiliDimens.Space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        ) {
            WakiliIcon(
                if (denied) WakiliIcons.X else WakiliIcons.Check,
                size = WakiliDimens.IconSm,
                tint = colors.muted,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Spacer(Modifier.height(WakiliDimens.Space6))
        ToolCard(
            part = item.tool,
            expanded = cardExpanded,
            onExpandedChange = onCardExpandedChange,
        )
    }
}

@Composable
private fun DecisionNote(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
            .background(colors.panel2)
            .border(WakiliDimens.BorderThin, colors.border, RoundedCornerShape(WakiliDimens.RadiusMd))
            .padding(horizontal = WakiliDimens.Space10, vertical = WakiliDimens.Space4 + WakiliDimens.BorderThin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
    ) {
        WakiliIcon(icon, size = WakiliDimens.IconSm, tint = colors.muted)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

private const val MAX_EXEC_OUTPUT = 4000
