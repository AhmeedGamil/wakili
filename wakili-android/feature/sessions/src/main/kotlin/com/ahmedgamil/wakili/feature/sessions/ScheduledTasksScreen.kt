package com.ahmedgamil.wakili.feature.sessions

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ahmedgamil.wakili.core.common.Toaster
import com.ahmedgamil.wakili.core.data.repository.SessionRepository
import com.ahmedgamil.wakili.core.designsystem.component.WakiliContextMenu
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliGhostIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliMenuItem
import com.ahmedgamil.wakili.core.designsystem.component.WakiliRoundIconButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcon
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.model.ScheduledJob
import com.ahmedgamil.wakili.core.model.SessionSummary
import com.ahmedgamil.wakili.core.ui.WakiliLoading
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Scheduled tasks page: every pending gateway-side job across every chat,
 * with create / edit / cancel. Reached from the home brand menu (under Select
 * project) and from a chat's "N scheduled" chip — one place to manage them
 * all instead of the old per-chat bottom sheet.
 */
data class ScheduledTasksUiState(
    val loading: Boolean = true,
    val jobs: List<ScheduledJob> = emptyList(),
    /** Recent chats for the create dialog's "Run in" picker. */
    val sessions: List<SessionSummary> = emptyList(),
)

@HiltViewModel
class ScheduledTasksViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val toaster: Toaster,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduledTasksUiState())
    val uiState: StateFlow<ScheduledTasksUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { sessionRepository.allScheduledJobs() }
                .onSuccess { jobs -> _uiState.update { it.copy(loading = false, jobs = jobs) } }
                .onFailure {
                    _uiState.update { it.copy(loading = false) }
                    toaster.show(appContext.getString(R.string.sched_load_failed))
                }
            // The create dialog's chat picker: cache answers instantly, the
            // network refresh (best effort) keeps the list current.
            runCatching { sessionRepository.cachedSessions() }.getOrNull()?.let { cached ->
                _uiState.update { it.copy(sessions = cached) }
            }
            runCatching { sessionRepository.refreshSessions() }.onSuccess { fresh ->
                _uiState.update { it.copy(sessions = fresh) }
            }
        }
    }

    /** Create a task in [sessionId] — the same gateway job a chat's + → Schedule makes. */
    fun create(sessionId: String, text: String, at: Long) {
        viewModelScope.launch {
            runCatching { sessionRepository.schedule(sessionId, text, at) }
                .onSuccess { job ->
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(job.at))
                    toaster.show(appContext.getString(R.string.sched_created, time))
                    refresh()
                }
                .onFailure { toaster.show(appContext.getString(R.string.sched_create_failed)) }
        }
    }

    fun update(job: ScheduledJob, text: String, at: Long) {
        viewModelScope.launch {
            runCatching { sessionRepository.updateScheduled(job.id, text, at) }
                .onSuccess {
                    toaster.show(appContext.getString(R.string.sched_updated))
                    refresh()
                }
                .onFailure { toaster.show(appContext.getString(R.string.sched_update_failed)) }
        }
    }

    fun delete(job: ScheduledJob) {
        // Optimistic: the row disappears now; a failure restores it via refresh.
        _uiState.update { s -> s.copy(jobs = s.jobs.filterNot { it.id == job.id }) }
        viewModelScope.launch {
            runCatching { sessionRepository.cancelScheduled(job.id) }
                .onFailure {
                    toaster.show(appContext.getString(R.string.sched_delete_failed))
                    refresh()
                }
        }
    }
}

@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScheduledTasksViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledJob?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Header, same chrome as the Files page: back, title, and + to create.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space10),
            modifier = Modifier.padding(
                horizontal = WakiliDimens.Space12,
                vertical = WakiliDimens.Space10,
            ),
        ) {
            WakiliRoundIconButton(icon = WakiliIcons.CornerUpLeft, onClick = onBack)
            Text(
                text = stringResource(R.string.sched_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            WakiliRoundIconButton(
                icon = WakiliIcons.Plus,
                onClick = { creating = true },
                contentDescription = stringResource(R.string.sched_new_task),
            )
        }

        when {
            uiState.loading -> WakiliLoading()
            uiState.jobs.isEmpty() -> EmptyTasks(onNew = { creating = true })
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WakiliDimens.Space16,
                    end = WakiliDimens.Space16,
                    bottom = WakiliDimens.Space16,
                ),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            ) {
                items(uiState.jobs, key = { it.id }) { job ->
                    TaskRow(
                        job = job,
                        onEdit = { editing = job },
                        onDelete = { viewModel.delete(job) },
                    )
                }
            }
        }
    }

    if (creating) {
        TaskDialog(
            title = stringResource(R.string.sched_new_task),
            confirmLabel = stringResource(R.string.sched_schedule),
            sessions = uiState.sessions,
            initialSessionId = uiState.sessions.firstOrNull()?.id,
            initialText = "",
            initialAt = null,
            allowEmptyText = false,
            onConfirm = { sessionId, text, at ->
                creating = false
                if (sessionId != null) viewModel.create(sessionId, text, at)
            },
            onDismiss = { creating = false },
        )
    }
    editing?.let { job ->
        TaskDialog(
            title = stringResource(R.string.sched_edit_title),
            confirmLabel = stringResource(R.string.sched_save),
            sessions = null, // a task cannot move to another chat
            initialSessionId = null,
            initialText = job.text,
            initialAt = job.at,
            // An attachments-only task legitimately has no text to require.
            allowEmptyText = job.attachmentNames.isNotEmpty(),
            onConfirm = { _, text, at ->
                editing = null
                viewModel.update(job, text, at)
            },
            onDismiss = { editing = null },
        )
    }
}

/** One pending task: time + chat, the text, any attached files; X cancels. */
@Composable
private fun TaskRow(job: ScheduledJob, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = WakiliTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WakiliDimens.RadiusLg))
            .background(colors.panel2)
            .clickable(onClick = onEdit)
            .padding(
                start = WakiliDimens.Space12,
                end = WakiliDimens.Space6,
                top = WakiliDimens.Space10,
                bottom = WakiliDimens.Space10,
            ),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space6),
            ) {
                WakiliIcon(WakiliIcons.Clock, size = WakiliDimens.IconSm, tint = colors.accent)
                Text(
                    text = formatTaskTime(job.at),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = job.sessionTitle.ifBlank { stringResource(R.string.sessions_untitled) },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (job.text.isNotEmpty()) {
                Text(
                    text = job.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (job.attachmentNames.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space4),
                ) {
                    WakiliIcon(WakiliIcons.Paperclip, size = WakiliDimens.IconXs, tint = colors.muted)
                    Text(
                        text = job.attachmentNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        WakiliGhostIconButton(
            icon = WakiliIcons.X,
            onClick = onDelete,
            iconSize = WakiliDimens.IconSm,
            contentDescription = stringResource(R.string.sched_cancel_task),
        )
    }
}

@Composable
private fun EmptyTasks(onNew: () -> Unit) {
    val colors = WakiliTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = WakiliDimens.Space24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WakiliIcon(WakiliIcons.Clock, size = WakiliDimens.IconLg * 2, tint = colors.muted)
        Spacer(Modifier.height(WakiliDimens.Space12))
        Text(
            text = stringResource(R.string.sched_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        Spacer(Modifier.height(WakiliDimens.Space6))
        Text(
            text = stringResource(R.string.sched_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = WakiliDimens.Space16),
        )
        Spacer(Modifier.height(WakiliDimens.Space16))
        WakiliButton(
            text = stringResource(R.string.sched_new_task),
            onClick = onNew,
            style = WakiliButtonStyle.Primary,
            icon = WakiliIcons.Plus,
        )
    }
}

/**
 * Create/edit dialog: optional chat picker (create only), the task text, and a
 * time picker that starts at the CURRENT time — with an honest "Runs today /
 * tomorrow at …" line, since a past time silently means tomorrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDialog(
    title: String,
    confirmLabel: String,
    sessions: List<SessionSummary>?,
    initialSessionId: String?,
    initialText: String,
    initialAt: Long?,
    allowEmptyText: Boolean,
    onConfirm: (sessionId: String?, text: String, at: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WakiliTheme.colors
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText) }
    var sessionId by remember { mutableStateOf(initialSessionId) }
    var pickerOpen by remember { mutableStateOf(false) }
    val initial = remember {
        java.util.Calendar.getInstance().apply { if (initialAt != null) timeInMillis = initialAt }
    }
    val timeState = rememberTimePickerState(
        initialHour = initial.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = initial.get(java.util.Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    // Reading hour/minute here recomposes the label as the user drags the dial.
    val runsAt = nextOccurrence(timeState.hour, timeState.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(title, color = colors.text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space12),
            ) {
                if (sessions != null) {
                    Text(
                        text = stringResource(R.string.sched_run_in),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.muted,
                    )
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(WakiliDimens.RadiusMd))
                                .background(colors.panel2)
                                .clickable { pickerOpen = true }
                                .padding(
                                    horizontal = WakiliDimens.Space12,
                                    vertical = WakiliDimens.Space10,
                                ),
                        ) {
                            Text(
                                text = sessions.firstOrNull { it.id == sessionId }?.title
                                    ?: stringResource(R.string.sessions_untitled),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            WakiliIcon(
                                WakiliIcons.ChevronDown,
                                size = WakiliDimens.IconSm,
                                tint = colors.muted,
                            )
                        }
                        WakiliContextMenu(
                            expanded = pickerOpen,
                            onDismiss = { pickerOpen = false },
                            items = sessions.take(20).map { s ->
                                WakiliMenuItem(
                                    label = s.title,
                                    icon = WakiliIcons.Bot,
                                    onClick = { sessionId = s.id; pickerOpen = false },
                                )
                            },
                        )
                    }
                }
                WakiliTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.sched_task_hint),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TimePicker(state = timeState)
                Text(
                    text = stringResource(
                        if (isToday(runsAt)) R.string.sched_runs_today else R.string.sched_runs_tomorrow,
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                            .format(java.util.Date(runsAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (text.isNotBlank() || allowEmptyText) && (sessions == null || sessionId != null),
                onClick = { onConfirm(sessionId, text.trim(), nextOccurrence(timeState.hour, timeState.minute)) },
            ) {
                Text(
                    text = confirmLabel,
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.sched_cancel),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/** Epoch millis of the next occurrence of [hour]:[minute] (past today → tomorrow). */
private fun nextOccurrence(hour: Int, minute: Int): Long =
    java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

private fun isToday(at: Long): Boolean {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) &&
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
}

/** Same-day tasks show the time alone; later ones add the short date. */
private fun formatTaskTime(at: Long): String {
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date(at))
    if (isToday(at)) return time
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date(at))
    return "$date $time"
}
