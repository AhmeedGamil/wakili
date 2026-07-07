package com.ahmedgamil.wakili.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ahmedgamil.wakili.core.designsystem.component.StatusDot
import com.ahmedgamil.wakili.core.designsystem.component.WakiliBareTextField
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliGhostIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliPillChip
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.dashedBorder
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliMono
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.ChatMessage
import com.ahmedgamil.wakili.core.model.PendingCard
import com.ahmedgamil.wakili.core.ui.TypingDots
import com.ahmedgamil.wakili.core.ui.WakiliLoading
import com.ahmedgamil.wakili.feature.chat.ui.CodeBlock
import com.ahmedgamil.wakili.feature.chat.ui.ControlsPopover
import com.ahmedgamil.wakili.feature.chat.ui.PartView
import com.ahmedgamil.wakili.feature.chat.ui.PermissionCardView
import com.ahmedgamil.wakili.feature.chat.ui.QuestionCardView

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenTerminal: (sessionId: String, cwd: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showControls by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::attach) }

    val itemCount = uiState.messages.size + uiState.transients.size + uiState.liveParts.size
    LaunchedEffect(itemCount, uiState.busy) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        ChatTopBar(
            uiState = uiState,
            viewModel = viewModel,
            showControls = showControls,
            onBack = onBack,
            onOpenControls = { showControls = true },
            onDismissControls = { showControls = false },
            onOpenTerminal = { onOpenTerminal(viewModel.sessionIdPublic, uiState.cwd) },
        )

        Box(Modifier.weight(1f)) {
            when {
                uiState.loading -> WakiliLoading()
                uiState.error -> ErrorPane(onRetry = viewModel::retry)
                itemCount == 0 && !uiState.busy -> EmptyGreeting()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = WakiliDimens.Space16,
                        vertical = WakiliDimens.Space8,
                    ),
                    verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space18),
                ) {
                    items(uiState.messages.size) { index ->
                        MessageView(uiState.messages[index], uiState.markdown, uiState.baseUrl)
                    }
                    items(uiState.transients.size) { index ->
                        TransientView(uiState.transients[index])
                    }
                    items(uiState.liveParts.size) { index ->
                        PartView(uiState.liveParts[index], uiState.markdown, uiState.baseUrl)
                    }
                    if (uiState.busy && uiState.liveParts.isEmpty()) {
                        item { TypingDots() }
                    }
                }
            }
        }

        CardDock(uiState, viewModel)
        QueuedChip(uiState, onCancel = viewModel::cancelQueue)
        AttachmentRow(uiState, viewModel)
        Composer(
            uiState = uiState,
            onInput = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onAttachImages = { pickFile.launch("image/*") },
            onAttachFiles = { pickFile.launch("*/*") },
            onOpenTerminal = { onOpenTerminal(viewModel.sessionIdPublic, uiState.cwd) },
        )
    }
}

/** #topbar — round icon buttons + the model-picker pill, no header bar. */
@Composable
private fun ChatTopBar(
    uiState: ChatUiState,
    viewModel: ChatViewModel,
    showControls: Boolean,
    onBack: () -> Unit,
    onOpenControls: () -> Unit,
    onDismissControls: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val colors = WakiliTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WakiliDimens.Space12, vertical = WakiliDimens.Space10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
        ) {
            // .icon-btn.round — opens the session list, like the web menu button
            WakiliRoundIconButton(
                icon = WakiliIcons.Menu,
                onClick = onBack,
                contentDescription = null,
            )
            // .picker — trigger pill + the popover anchored right below it
            val modelLabel = uiState.agent?.controls?.get("model")?.options
                ?.firstOrNull { it.value == uiState.controls["model"] }?.label
                ?: uiState.agent?.label ?: ""
            Box(Modifier.weight(1f, fill = false)) {
                WakiliPillChip(
                    text = modelLabel.ifEmpty { uiState.title.ifEmpty { stringResource(R.string.chat_untitled) } },
                    trailing = WakiliIcons.ChevronDown,
                    onClick = onOpenControls,
                    modifier = Modifier.height(WakiliDimens.RoundButton),
                )
                if (showControls) {
                    ControlsPopover(
                        agents = uiState.agents,
                        agentId = uiState.agentId,
                        controls = uiState.controls,
                        autoAllow = uiState.autoAllow,
                        onAgent = viewModel::setAgent,
                        onControl = viewModel::setControl,
                        onAutoAllow = viewModel::setAutoAllow,
                        onDismiss = onDismissControls,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (uiState.busy) {
                StatusDot(color = colors.accent, pulse = true)
            }
            WakiliRoundIconButton(
                icon = WakiliIcons.Terminal,
                onClick = onOpenTerminal,
                size = WakiliDimens.FilesButton,
                contentDescription = null,
            )
        }
        if (!uiState.connected) {
            Text(
                text = stringResource(R.string.chat_reconnecting),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = WakiliDimens.Space16),
            )
        }
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
                if (att.image && att.url != null) {
                    AsyncImage(
                        model = uiState.baseUrl.trimEnd('/') + att.url,
                        contentDescription = att.name,
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
                    ) { TypingDots() }
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
@Composable
private fun Composer(
    uiState: ChatUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachImages: () -> Unit,
    onAttachFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val colors = WakiliTheme.colors
    var addMenuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(
            start = WakiliDimens.Space16,
            end = WakiliDimens.Space16,
            top = WakiliDimens.Space8,
            bottom = WakiliDimens.Space16,
        ),
        verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
    ) {
        // .add-menu — floating list above the + button: Images / Files / Terminal
        if (addMenuOpen) {
            WakiliCard(
                radius = WakiliDimens.Radius,
                contentPadding = WakiliDimens.Space6,
                modifier = Modifier.widthIn(min = WakiliDimens.PickerMinWidth),
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
            }
        }
        // .slash-menu — Claude-CLI style command list
        if (uiState.slashMatches.isNotEmpty()) {
            WakiliCard(
                radius = WakiliDimens.RadiusPanel,
                contentPadding = WakiliDimens.Space6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = WakiliDimens.MenuMaxHeight),
            ) {
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

        // .composer-bar
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val barShape = RoundedCornerShape(WakiliDimens.RadiusComposer)
        Row(
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
        ) {
            // .btn.attach — borderless + button, opens the add menu
            ComposerCircleButton(
                icon = WakiliIcons.Plus,
                background = Color.Transparent,
                tint = colors.text,
                iconSize = WakiliDimens.IconLg,
                enabled = !uiState.blocked,
                onClick = { addMenuOpen = !addMenuOpen },
            )
            WakiliBareTextField(
                value = uiState.input,
                onValueChange = onInput,
                placeholder = stringResource(R.string.chat_input_hint),
                enabled = !uiState.blocked,
                maxLines = MAX_INPUT_LINES,
                interactionSource = interaction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = WakiliDimens.InputMaxHeight)
                    .align(Alignment.CenterVertically),
            )
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

private const val MAX_INPUT_LINES = 6

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

@Composable
private fun MessageView(
    message: ChatMessage,
    markdown: Boolean,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    val colors = WakiliTheme.colors
    when (message) {
        // .msg.user / .bubble — right-aligned, --user background, radius 20
        is ChatMessage.User -> BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val maxBubble = maxWidth * WakiliDimens.BubbleMaxWidthFraction
            Column(
                Modifier.align(Alignment.CenterEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            ) {
                if (message.text.isNotBlank()) {
                    Box(
                        Modifier
                            .widthIn(max = maxBubble)
                            .clip(RoundedCornerShape(WakiliDimens.RadiusBubble))
                            .background(colors.user)
                            .padding(horizontal = WakiliDimens.Space16, vertical = WakiliDimens.Space11),
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.text,
                        )
                    }
                }
                // .att-msg.doc — independent right-aligned attachment cards
                message.attachments.forEach { attachment ->
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
                    }
                }
            }
        }

        is ChatMessage.Assistant -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        ) {
            message.parts.forEach { PartView(it, markdown, baseUrl) }
        }
    }
}

@Composable
private fun TransientView(item: TransientItem, modifier: Modifier = Modifier) {
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

        is TransientItem.Decided -> DecisionNote(
            icon = if (item.decision == "deny") WakiliIcons.X else WakiliIcons.Check,
            text = stringResource(
                if (item.decision == "deny") R.string.chat_denied else R.string.chat_allowed,
                item.tool,
            ),
            modifier = modifier,
        )

        TransientItem.Stopped -> DecisionNote(
            icon = WakiliIcons.Square,
            text = stringResource(R.string.chat_stopped),
            modifier = modifier,
        )

        is TransientItem.Answered -> DecisionNote(
            icon = WakiliIcons.Check,
            text = stringResource(R.string.chat_answered),
            modifier = modifier,
        )
    }
}

/** .stopped-note / decided .perm-head — bordered pill note with a small icon. */
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
