package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import ai.hans.standard.voice.PendingDictationDelivery
import ai.hans.standard.voice.audio.SpeechAudioRoute
import ai.hans.standard.voice.audio.SpeechAudioRouteState
import ai.hans.standard.phone.keys.AndroidKeyEventObserver
import ai.hans.standard.phone.keys.ComposerKeyTranslation
import ai.hans.standard.phone.keys.ComposerKeyTranslationPolicy
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.display.DisplayMotionDecision
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

internal val ComposerCursorBlinkEnabledKey =
    SemanticsPropertyKey<Boolean>("HansComposerCursorBlinkEnabled")

private var SemanticsPropertyReceiver.composerCursorBlinkEnabled by
    ComposerCursorBlinkEnabledKey

internal val WorkingIndicatorAnimatedKey = SemanticsPropertyKey<Boolean>("HansWorkingIndicatorAnimated")
private var SemanticsPropertyReceiver.workingIndicatorAnimated by WorkingIndicatorAnimatedKey
internal val LiveRecordingIndicatorAnimatedKey = SemanticsPropertyKey<Boolean>("HansLiveRecordingIndicatorAnimated")
private var SemanticsPropertyReceiver.liveRecordingIndicatorAnimated by LiveRecordingIndicatorAnimatedKey

internal const val LIVE_START_CONFIRMATION_TIMEOUT_MILLIS = 5_000L

@Composable
fun ChatScreen(
    state: ChatUiState,
    callbacks: ChatUiCallbacks,
    modifier: Modifier = Modifier,
    sidebarSettings: SettingsUiState? = null,
    sidebarCallbacks: SettingsUiCallbacks? = null,
    sidebarRequested: Boolean = false,
    onSidebarClosed: () -> Unit = {},
    displayMotionMode: DisplayMotionMode = DisplayMotionMode.AUTOMATIC,
    liveCallMinimizedState: MutableState<Boolean> = rememberLiveCallMinimized(state.liveVoiceStatus != null),
) {
    val uiText = rememberHansTextResolver()
    val motion = rememberDisplayMotion(displayMotionMode)
    val listState = rememberLazyListState()
    val idleFocusRequester = remember { FocusRequester() }
    val composerFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var composerFocused by remember { mutableStateOf(false) }
    var composerStopFocused by remember { mutableStateOf(false) }
    var composerDictationFocused by remember { mutableStateOf(false) }
    val composerEditor = remember { ComposerEditorState(state.composer.text) }
    val currentComposerChanged by rememberUpdatedState(callbacks.onComposerChanged)
    var sidePanelOpen by remember { mutableStateOf(false) }
    var liveStartConfirmationVisible by remember { mutableStateOf(false) }
    // Keep the call surface mounted for terminal failures until the user hangs up. Otherwise
    // the status that explains the failure disappears at the exact moment it is needed.
    val liveActive = state.liveVoiceStatus != null
    var liveCallMinimized by liveCallMinimizedState
    val liveCallExpanded = liveActive && !liveCallMinimized
    val closeSidebar = {
        sidePanelOpen = false
        onSidebarClosed()
    }
    val openSidebar = {
        liveStartConfirmationVisible = false
        sidePanelOpen = true
        callbacks.onOpenSettings()
    }
    LaunchedEffect(sidebarRequested) { sidePanelOpen = sidebarRequested }
    LaunchedEffect(sidePanelOpen) {
        if (sidePanelOpen) {
            focusManager.clearFocus()
            keyboard?.hide()
        } else {
            idleFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(liveActive) {
        if (liveActive) {
            liveStartConfirmationVisible = false
            sidePanelOpen = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(liveStartConfirmationVisible) {
        if (liveStartConfirmationVisible) {
            focusManager.clearFocus()
            keyboard?.hide()
            delay(LIVE_START_CONFIRMATION_TIMEOUT_MILLIS)
            liveStartConfirmationVisible = false
        }
    }
    val dictationOwnsInput = state.dictationStatus in setOf(
        DictationUiStatus.PREPARING,
        DictationUiStatus.LISTENING,
        DictationUiStatus.FINALIZING,
    )
    val showSpeechTaskPanel = dictationOwnsInput && !liveActive
    val lastMessage = state.messages.lastOrNull()
    val dictationPreviewMessage = state.dictationPreviewMessage()
    val showTranscriptPlaceholder = state.showDictationTranscriptPlaceholder()
    val showEmptyChat = state.messages.isEmpty() && dictationPreviewMessage == null && !showTranscriptPlaceholder
    val transientRows = listOfNotNull(
        state.dictationStatus,
        state.liveVoiceStatus,
        state.isWorking.takeIf { it },
    ).size + state.pendingDictations.size
    val tailIndex = state.messages.size + transientRows + if (showEmptyChat) 1 else 0

    LaunchedEffect(
        // The aggregate revision also includes hidden events and unrelated local state.
        // Only changes to the visible tail may move a reader away from older history.
        state.messages.size,
        lastMessage?.id,
        lastMessage?.revision,
        lastMessage?.text,
        state.isWorking,
        state.dictationStatus,
        dictationPreviewMessage?.text,
        showTranscriptPlaceholder,
    ) {
        listState.scrollToItem(tailIndex)
    }

    // Parent draft changes are applied as part of this committed composition. Launching a
    // coroutine with an older echo can overwrite keys received before that coroutine resumes.
    SideEffect {
        val currentDraft = callbacks.readComposerDraft?.invoke()
        composerEditor.reconcileExternalDraft(
            external = currentDraft?.text ?: state.composer.text,
            pending = currentDraft?.dispatchPending ?: false,
        )
    }
    LaunchedEffect(composerEditor) {
        snapshotFlow { composerEditor.text }.collect {
            composerEditor.takeDraftPublication()?.let(currentComposerChanged)
        }
    }

    BackHandler(enabled = sidePanelOpen) {
        closeSidebar()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("chat_screen")
            .semantics {
                customActions = if (sidePanelOpen) {
                    emptyList()
                } else {
                    listOf(
                        CustomAccessibilityAction(label = uiText.text(R.string.ui_open_menu_ac3b2e)) {
                            openSidebar()
                            true
                        },
                    )
                }
            }
            .focusRequester(idleFocusRequester)
            .focusable()
            .pointerInput(sidePanelOpen) {
                if (!sidePanelOpen) {
                    detectDeliberateHorizontalSwipe(
                        mustStartAtRightEdge = true,
                        direction = HorizontalSwipeDirection.LEFT,
                    ) {
                        openSidebar()
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (
                    sidePanelOpen ||
                    composerFocused ||
                    composerStopFocused ||
                    composerDictationFocused ||
                    dictationOwnsInput ||
                    composerEditor.dispatchPending ||
                    !state.composer.enabled ||
                    event.type != KeyEventType.KeyDown ||
                    event.key == Key.Enter
                ) {
                    false
                } else {
                    printableCodePoint(event.nativeKeyEvent.unicodeChar)?.let { inserted ->
                        composerEditor.insertText(inserted)
                        composerEditor.takeDraftPublication()?.let(currentComposerChanged)
                        composerFocusRequester.requestFocus()
                        true
                    } ?: false
                }
            },
    ) {
        if (liveCallExpanded) {
            LiveVoiceCallScreen(
                status = checkNotNull(state.liveVoiceStatus),
                inputMuted = state.liveVoiceInputMuted,
                onInputMutedChanged = callbacks.onLiveVoiceInputMutedChanged,
                onHangUp = callbacks.onStopLiveVoice ?: callbacks.onToggleLiveVoice,
                audioRoute = state.speechAudioRoute,
                onAudioRouteRequested = callbacks.onSpeechAudioRouteRequested,
                onMinimize = { liveCallMinimized = true },
                speechFailure = state.speechFailure,
                onOpenSpeechFailureHelp = callbacks.onOpenSpeechFailureHelp,
                onDismissSpeechFailure = callbacks.onDismissSpeechFailure,
            )
        } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (liveActive) {
                LiveVoiceCallBar(
                    status = checkNotNull(state.liveVoiceStatus),
                    inputMuted = state.liveVoiceInputMuted,
                    onInputMutedChanged = callbacks.onLiveVoiceInputMutedChanged,
                    onHangUp = callbacks.onStopLiveVoice ?: callbacks.onToggleLiveVoice,
                    onExpand = { liveCallMinimized = false },
                )
            } else {
            ChatHeader(
                status = state.runtimeStatus,
                liveVoiceStatus = state.liveVoiceStatus,
                animateLiveRecording = motion.animateLiveRecording && !sidePanelOpen,
                onToggleLiveVoice = {
                    if (liveActive) {
                        (callbacks.onStopLiveVoice ?: callbacks.onToggleLiveVoice)()
                    } else {
                        liveStartConfirmationVisible = true
                    }
                },
                onOpenMenu = openSidebar,
            )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            state.speechFailure?.let { failure ->
                SpeechFailureNotice(failure, callbacks.onOpenSpeechFailureHelp, callbacks.onDismissSpeechFailure)
            }

            if (state.internetNotice.isNotBlank() || state.connectionFailureMessage.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("chat_connection_notice"),
                ) {
                    Text(
                        text = listOf(state.internetNotice, state.connectionFailureMessage)
                            .filter(String::isNotBlank).joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (state.internetNotice.isNotBlank()) {
                        TextButton(onClick = callbacks.onOpenInternetSettings) {
                            Text(stringResource(R.string.ui_internet_settings_99f923))
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat_timeline"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 18.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (showEmptyChat) {
                    item(key = "empty_chat") {
                        EmptyChat(actionKeyConfigured = state.actionKeyConfigured)
                    }
                } else {
                    items(
                        items = state.messages,
                        // App Server item ids are unique within one role, not across roles.
                        // A resumed user input and Hans answer may therefore share an id.
                        key = ::chatTimelineItemKey,
                        contentType = { it.author },
                    ) { message ->
                        MessageCard(
                            message = message,
                            onReadAloud = callbacks.onReadAssistantMessageAloud,
                        )
                    }
                }

                if (state.isWorking && !showSpeechTaskPanel) {
                    item(key = "working_indicator") {
                        WorkingIndicator(animated = motion.animateHome && !sidePanelOpen)
                    }
                }

                state.dictationStatus?.let { status ->
                    item(key = "dictation_status") {
                        if (dictationPreviewMessage != null) {
                            MessageCard(
                                message = dictationPreviewMessage,
                                onReadAloud = callbacks.onReadAssistantMessageAloud,
                                textModifier = Modifier.testTag("dictation_preview"),
                            )
                        } else if (showTranscriptPlaceholder) {
                            MessageCard(
                                message = ChatMessageUiModel(
                                    id = "dictation-awaiting-transcript",
                                    author = ChatMessageAuthor.USER,
                                    text = "",
                                    complete = false,
                                ),
                                onReadAloud = callbacks.onReadAssistantMessageAloud,
                                waitingForTranscript = true,
                            )
                        } else if (!showSpeechTaskPanel) {
                            DictationStatus(status, state.dictationInputMuted)
                        }
                    }
                }

                state.liveVoiceStatus?.let { status ->
                    item(key = "live_voice_status") {
                        LiveVoiceStatus(status)
                    }
                }

                items(state.pendingDictations, key = { "unsent-dictation-${it.id}" }) { pending ->
                    if (pending.delivery == PendingDictationDelivery.AWAITING_RECEIPT) {
                        MessageCard(
                            message = ChatMessageUiModel(
                                id = "pending-dictation-${pending.id}",
                                author = ChatMessageAuthor.USER,
                                text = pending.transcript,
                                complete = false,
                            ),
                            onReadAloud = callbacks.onReadAssistantMessageAloud,
                        )
                        return@items
                    }
                    Column(modifier = Modifier.fillMaxWidth().testTag("pending_dictation")) {
                        Text(
                            text = when (pending.delivery) {
                                PendingDictationDelivery.AWAITING_USER -> if (pending.incomplete) {
                                    uiText.text(R.string.ui_dictation_interrupted_incomplete_text_not_sent_263bf7)
                                } else {
                                    uiText.text(R.string.ui_dictation_saved_not_sent_602a2b)
                                }
                                PendingDictationDelivery.AWAITING_RECEIPT ->
                                    uiText.text(R.string.ui_waiting_for_dictation_delivery_confirmation_0d1133)
                                PendingDictationDelivery.OUTCOME_UNKNOWN ->
                                    uiText.text(R.string.ui_delivery_status_unknown_check_the_chat_before_re_a67185)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        SelectionContainer {
                            Text(pending.transcript, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row {
                            if (pending.canRetry) {
                                TextButton(onClick = { callbacks.onRetryPendingDictation(pending.id) }) {
                                    Text(if (pending.incomplete) uiText.text(R.string.ui_send_this_part_510526) else uiText.text(R.string.ui_send_079bef))
                                }
                            }
                            if (pending.canDiscard) {
                                TextButton(onClick = { callbacks.onDiscardPendingDictation(pending.id) }) {
                                    Text(stringResource(R.string.ui_discard_draft_968d8c))
                                }
                            }
                        }
                    }
                }

                item(key = "timeline_tail") {
                    Spacer(Modifier.height(1.dp))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Composer(
                cursorBlinkEnabled = motion.animateHome && !sidePanelOpen,
                state = state.composer,
                workInterrupt = state.workInterrupt,
                dictationOwnsInput = dictationOwnsInput,
                dictationStatus = state.dictationStatus,
                dictationInputMuted = state.dictationInputMuted,
                dictationControlVisible = !liveActive && !state.actionKeyConfigured,
                showSpeechTaskPanel = showSpeechTaskPanel,
                isWorking = state.isWorking,
                audioRoute = state.speechAudioRoute.takeUnless { liveActive },
                editor = composerEditor,
                publishDraft = {
                    composerEditor.takeDraftPublication()?.let(currentComposerChanged)
                },
                callbacks = callbacks,
                composerFocusRequester = composerFocusRequester,
                onComposerFocusChanged = { composerFocused = it },
                onStopButtonFocusChanged = { composerStopFocused = it },
                onDictationButtonFocusChanged = { composerDictationFocused = it },
                onReturnToIdleFocus = { idleFocusRequester.requestFocus() },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        if (sidePanelOpen) {
            ChatNavigationPanel(
                callbacks = callbacks,
                onClose = closeSidebar,
                settings = sidebarSettings,
                settingsCallbacks = sidebarCallbacks,
                displayMotion = motion,
            )
        }
        if (!liveActive && liveStartConfirmationVisible) {
            LiveVoiceStartConfirmation(
                onConfirm = {
                    liveStartConfirmationVisible = false
                    (callbacks.onStartLiveVoice ?: callbacks.onToggleLiveVoice)()
                },
                onDismiss = { liveStartConfirmationVisible = false },
            )
        }
        }
    }
}

@Composable
private fun LiveVoiceStartConfirmation(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("live_start_confirmation"),
        icon = {
            Text(
                text = "☎",
                modifier = Modifier
                    .testTag("live_start_phone_icon")
                    .semantics { contentDescription = uiText.text(R.string.ui_phone_fa6906) },
                style = MaterialTheme.typography.headlineLarge,
            )
        },
        title = { Text(stringResource(R.string.ui_call_hans_209368)) },
        text = { Text(stringResource(R.string.ui_start_a_live_voice_call_372dca)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("live_start_confirm"),
            ) { Text(stringResource(R.string.ui_call_74f357)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("live_start_cancel"),
            ) { Text(stringResource(R.string.ui_cancel_f7ff11)) }
        },
    )
}

internal fun chatTimelineItemKey(message: ChatMessageUiModel): String =
    "${message.author.name}:${message.id}"

@Composable
private fun DictationStatus(status: DictationUiStatus, inputMuted: Boolean) {
    val uiText = rememberHansTextResolver()
    Text(
        text = stringResource(if (inputMuted) R.string.dictation_microphone_muted else status.labelResource),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("dictation_status_${status.name.lowercase()}"),
        color = if (status == DictationUiStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LiveVoiceStatus(status: LiveVoiceUiStatus) {
    val uiText = rememberHansTextResolver()
    Text(
        text = stringResource(status.labelResource),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("live_voice_status_${status.name.lowercase()}"),
        color = if (status == LiveVoiceUiStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ChatHeader(
    status: RuntimeUiStatus,
    liveVoiceStatus: LiveVoiceUiStatus?,
    animateLiveRecording: Boolean,
    onToggleLiveVoice: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    val liveActive = liveVoiceStatus?.isActive == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_header")
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .testTag("hans_title")
                .semantics {
                    contentDescription = if (liveActive) {
                        uiText.text(R.string.ui_hans_end_live_voice_3f3440)
                    } else {
                        uiText.text(R.string.ui_hans_open_call_dialog_b6c733)
                    }
                }
                .clickable(
                    role = Role.Button,
                    onClick = onToggleLiveVoice,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "Hans",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    liveActive -> LiveRecordingIndicator(animated = animateLiveRecording)
                    !status.isReady -> RuntimeSleepingIndicator()
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedIconButton(
            onClick = onOpenMenu,
            modifier = Modifier.size(48.dp).testTag("open_chat_navigation")
                .semantics { contentDescription = uiText.text(R.string.ui_open_menu_ac3b2e) },
        ) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(22.dp)) {
                listOf(0.25f, 0.5f, 0.75f).forEach { y ->
                    drawLine(color, Offset(0f, size.height * y), Offset(size.width, size.height * y), 2.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun SpeechAudioRouteButton(
    state: SpeechAudioRouteState,
    onRequested: (SpeechAudioRoute) -> Unit,
) {
    val uiText = rememberHansTextResolver()
    var expanded by remember { mutableStateOf(false) }
    val label = when (state.effective) {
        SpeechAudioRoute.SPEAKER -> uiText.text(R.string.ui_speaker_8cb912)
        SpeechAudioRoute.EARPIECE -> uiText.text(R.string.ui_earpiece_def97c)
        SpeechAudioRoute.EXTERNAL -> uiText.text(R.string.ui_headset_bluetooth_ddc46b)
        SpeechAudioRoute.UNKNOWN -> uiText.text(R.string.ui_not_yet_confirmed_fa2871)
    }
    Box {
        OutlinedIconButton(
            onClick = { expanded = true },
            enabled = state.available.any { it == SpeechAudioRoute.SPEAKER || it == SpeechAudioRoute.EARPIECE },
            modifier = Modifier.size(48.dp).testTag("speech_audio_route")
                .semantics { contentDescription = uiText.text(R.string.ui_audio_output_value_change_0001a2, label) },
        ) {
            Text(when (state.effective) {
                SpeechAudioRoute.EARPIECE -> "👂"
                SpeechAudioRoute.SPEAKER -> "🔊"
                SpeechAudioRoute.EXTERNAL -> "🎧"
                SpeechAudioRoute.UNKNOWN -> "?"
            })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(SpeechAudioRoute.EARPIECE to uiText.text(R.string.ui_earpiece_def97c), SpeechAudioRoute.SPEAKER to uiText.text(R.string.ui_speaker_8cb912))
                .forEach { (route, name) ->
                    DropdownMenuItem(
                        text = { Text(if (state.effective == route) "✓ $name" else name) },
                        enabled = route in state.available,
                        onClick = { expanded = false; onRequested(route) },
                        modifier = Modifier.testTag("speech_route_${route.name.lowercase()}"),
                    )
                }
            Text(
                text = stringResource(R.string.ui_current_value_c67f39, label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("speech_route_effective"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RuntimeSleepingIndicator() {
    val uiText = rememberHansTextResolver()
    Text(
        text = "💤",
        modifier = Modifier
            .testTag("runtime_sleeping_indicator")
            .semantics { contentDescription = uiText.text(R.string.ui_hans_is_not_ready_9b069d) },
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun LiveRecordingIndicator(animated: Boolean) {
    val uiText = rememberHansTextResolver()
    // This animation exists only while Live Voice owns the microphone. The
    // composable leaves the tree at idle, so there is no permanent timer,
    // recomposition loop, or frame invalidation on the E-Ink home screen.
    // Android animations-off and an inactive window remove the transition entirely.
    val indicatorAlpha = if (animated) {
        val transition = rememberInfiniteTransition(label = "live-recording")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 760),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "live-recording-alpha",
        )
    } else {
        null
    }
    val color = MaterialTheme.colorScheme.error
    Canvas(
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer { alpha = indicatorAlpha?.value ?: 1f }
            .testTag("live_recording_indicator")
            .semantics {
                contentDescription = uiText.text(R.string.ui_live_voice_active_a5fd5b)
                liveRecordingIndicatorAnimated = animated
            },
    ) {
        drawCircle(color = color)
    }
}

@Composable
private fun ChatNavigationPanel(
    callbacks: ChatUiCallbacks,
    onClose: () -> Unit,
    settings: SettingsUiState?,
    settingsCallbacks: SettingsUiCallbacks?,
    displayMotion: DisplayMotionDecision,
) {
    val uiText = rememberHansTextResolver()
    // This panel leaves composition on close, so reopening never restores a deep group.
    var navigation by remember { mutableStateOf(SettingsNavigation()) }
    val group = navigation.group
    BackHandler(enabled = group != null) {
        navigation = navigation.backToGroups()
    }
    val panelWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.82f).coerceAtMost(360.dp)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_navigation_overlay"),
    ) {
        if (group == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.16f))
                    .testTag("chat_navigation_scrim")
                    .semantics {
                        contentDescription = uiText.text(R.string.ui_close_menu_48700e)
                        role = Role.Button
                    }
                    .clickable(onClick = onClose),
            )
        }
        Surface(
            modifier = Modifier
                .then(if (group == null) Modifier.width(panelWidth) else Modifier.fillMaxWidth())
                .fillMaxHeight()
                .testTag("chat_navigation_panel")
                .pointerInput(Unit) {
                    detectDeliberateHorizontalSwipe(
                        mustStartAtRightEdge = false,
                        direction = HorizontalSwipeDirection.RIGHT,
                        onTriggered = onClose,
                    )
                },
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (group == null) {
                        Text(
                            text = stringResource(navigation.titleResource),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    } else {
                        TextButton(
                            onClick = { navigation = navigation.backToGroups() },
                            modifier = Modifier.testTag("settings_back_to_groups"),
                        ) {
                            Text(stringResource(R.string.ui_back_548611))
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_chat_navigation"),
                    ) {
                        Text(stringResource(R.string.ui_close_b808f6))
                    }
                }
                if (group != null) {
                    Text(
                        text = stringResource(group.titleResource),
                        modifier = Modifier.padding(horizontal = 16.dp)
                            .testTag("settings_group_title"),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                key(group) {
                    if (group == null) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            NavigationPanelButton(
                                label = "Apps",
                                testTag = "open_apps",
                                onClick = {
                                    onClose()
                                    callbacks.onOpenApps()
                                },
                            )
                            NavigationPanelButton(
                                label = "Plugins",
                                testTag = "open_plugins",
                                onClick = {
                                    onClose()
                                    callbacks.onOpenPlugins()
                                },
                            )
                            NavigationPanelButton(
                                label = uiText.text(R.string.ui_automations_1a2219),
                                testTag = "open_automations",
                                onClick = {
                                    onClose()
                                    callbacks.onOpenAutomations()
                                },
                            )
                            if (settings != null && settingsCallbacks != null) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                SettingsGroupOverview(
                                    onGroupSelected = { selected ->
                                        if (selected == SettingsGroup.RUNTIME) {
                                            settingsCallbacks.onModelSettingsOpened()
                                        }
                                        if (selected == SettingsGroup.REMOTE_CONTROL) {
                                            settingsCallbacks.onRemoteControlSettingsOpened()
                                        }
                                        navigation = navigation.open(selected)
                                    },
                                )
                            }
                        }
                    } else if (settings != null && settingsCallbacks != null) {
                        SettingsContent(
                            state = settings,
                            callbacks = settingsCallbacks,
                            group = group,
                            modifier = Modifier.weight(1f),
                            displayMotion = displayMotion,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationPanelButton(
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val uiText = rememberHansTextResolver()
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

private enum class HorizontalSwipeDirection { LEFT, RIGHT }

private suspend fun PointerInputScope.detectDeliberateHorizontalSwipe(
    mustStartAtRightEdge: Boolean,
    direction: HorizontalSwipeDirection,
    onTriggered: () -> Unit,
) {
    val edgeWidth = 26.dp.toPx()
    val horizontalThreshold = 72.dp.toPx()
    val verticalAbortThreshold = 28.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        if (mustStartAtRightEdge && down.position.x < size.width - edgeWidth) {
            return@awaitEachGesture
        }
        val pointerId = down.id
        val start = down.position
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial)
                .changes
                .firstOrNull { it.id == pointerId }
                ?: break
            if (!change.pressed) break
            val delta = change.position - start
            val horizontal = when (direction) {
                HorizontalSwipeDirection.LEFT -> -delta.x
                HorizontalSwipeDirection.RIGHT -> delta.x
            }
            if (abs(delta.y) >= verticalAbortThreshold && abs(delta.y) > abs(delta.x)) {
                break
            }
            if (horizontal >= horizontalThreshold && horizontal >= abs(delta.y) * 1.35f) {
                change.consume()
                onTriggered()
                break
            }
            if (horizontal <= -horizontalThreshold) break
        }
    }
}

@Composable
private fun EmptyChat(actionKeyConfigured: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_what_s_next_02f5c8),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(if (actionKeyConfigured) R.string.voice_composer_empty_action_key
                else R.string.voice_composer_empty_microphone),
            modifier = Modifier.testTag("empty_chat_input_hint"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun MessageCard(
    message: ChatMessageUiModel,
    onReadAloud: (String) -> Unit,
    textModifier: Modifier = Modifier,
    waitingForTranscript: Boolean = false,
) {
    val uiText = rememberHansTextResolver()
    if (message.author == ChatMessageAuthor.SYSTEM) {
        Text(
            text = message.text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("message_${message.id}"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    var selectedReadAloudText by remember(message.id) { mutableStateOf<String?>(null) }
    val assistantActions = if (message.author == ChatMessageAuthor.HANS) {
        Modifier
            .pointerInput(message.id, message.revision, message.text) {
                detectTapGestures(onLongPress = { selectedReadAloudText = message.text })
            }
            .semantics {
                onLongClick(label = uiText.text(R.string.ui_response_actions_9d3844)) {
                    selectedReadAloudText = message.text
                    true
                }
            }
    } else {
        Modifier
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .align(
                    if (message.author == ChatMessageAuthor.USER) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                )
                .widthIn(max = 620.dp)
                .then(assistantActions)
                .testTag("message_${message.id}"),
            color = if (message.author == ChatMessageAuthor.USER) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.Transparent
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text(
                    text = if (message.author == ChatMessageAuthor.USER) uiText.text(R.string.ui_you_0b6722) else "Hans",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                if (waitingForTranscript && message.author == ChatMessageAuthor.USER) {
                    TranscriptWaitingDots()
                } else if (message.author == ChatMessageAuthor.HANS) {
                    AssistantRichText(
                        raw = message.text,
                        complete = message.complete,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    // User drafts/transcripts are literal input, not model-authored Markdown.
                    Text(
                        text = message.text,
                        modifier = textModifier,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (message.author == ChatMessageAuthor.HANS) {
            DropdownMenu(
                expanded = selectedReadAloudText != null,
                onDismissRequest = { selectedReadAloudText = null },
                modifier = Modifier.testTag("message_read_aloud_menu"),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_read_aloud_aa20c7)) },
                    onClick = {
                        val selected = selectedReadAloudText
                        selectedReadAloudText = null
                        if (selected != null) onReadAloud(selected)
                    },
                    modifier = Modifier
                        .testTag("message_read_aloud_action")
                        .semantics { contentDescription = uiText.text(R.string.ui_read_this_response_aloud_e9b1eb) },
                )
            }
        }
    }
}

@Composable
private fun TranscriptWaitingDots() {
    val description = stringResource(R.string.voice_composer_transcript_pending)
    val color = MaterialTheme.colorScheme.onSurface
    // Visible immediately, but never an animation or polling clock — including on E-Ink.
    Canvas(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .testTag("dictation_transcript_waiting_dots")
            .semantics { contentDescription = description },
    ) {
        val radius = 3.dp.toPx()
        listOf(radius, size.width / 2f, size.width - radius).forEach { x ->
            drawCircle(color, radius, Offset(x, size.height / 2f))
        }
    }
}

@Composable
private fun WorkingIndicator(animated: Boolean) {
    val uiText = rememberHansTextResolver()
    // The E-Ink/static branch never constructs a transition or owns an animation clock.
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "working")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_200, easing = LinearEasing)),
            label = "working-dots",
        )
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .semantics {
                contentDescription = uiText.text(R.string.ui_hans_is_working_440086)
                workingIndicatorAnimated = animated
            }
            .testTag("working_indicator"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(width = 42.dp, height = 14.dp).testTag("working_indicator_dots")) {
            val radius = 4.dp.toPx()
            val y = size.height / 2f
            val progress = phase?.value
            listOf(radius, size.width / 2f, size.width - radius).forEachIndexed { index, x ->
                val alpha = if (progress == null) 1f else {
                    (0.64 + 0.36 * cos((progress - index) * 2.0 * PI / 3.0)).toFloat()
                }
                drawCircle(color = color, radius = radius, center = Offset(x, y), alpha = alpha)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.ui_hans_is_working_440086),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    cursorBlinkEnabled: Boolean,
    state: ComposerUiState,
    workInterrupt: WorkInterruptUiState,
    dictationOwnsInput: Boolean,
    dictationStatus: DictationUiStatus?,
    dictationInputMuted: Boolean,
    dictationControlVisible: Boolean,
    showSpeechTaskPanel: Boolean,
    isWorking: Boolean,
    audioRoute: SpeechAudioRouteState?,
    editor: ComposerEditorState,
    publishDraft: () -> Unit,
    callbacks: ChatUiCallbacks,
    composerFocusRequester: FocusRequester,
    onComposerFocusChanged: (Boolean) -> Unit,
    onStopButtonFocusChanged: (Boolean) -> Unit,
    onDictationButtonFocusChanged: (Boolean) -> Unit,
    onReturnToIdleFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val maximumComposerHeight = (
        LocalConfiguration.current.screenHeightDp.dp - 150.dp
    ).coerceAtLeast(120.dp)
    val composerInputEnabled = state.enabled && !dictationOwnsInput && !editor.dispatchPending
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val pendingInputGuard = remember(editor) {
        InputTransformation {
            // Dispatch can become pending before enabled is recomposed. Keep any already
            // queued IME/key edit out of that in-flight draft without replacing the IME.
            if (editor.dispatchPending) revertAllChanges()
        }
    }

    LaunchedEffect(dictationOwnsInput) {
        if (dictationOwnsInput) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onReturnToIdleFocus()
        }
    }

    fun submit(): Boolean {
        // The shared buffer is current even when several keys and Enter arrive before the
        // next frame. Flush the parent draft before dispatch so an immediate clear is durable.
        if (!composerCanSend(
                composerInputEnabled && !editor.dispatchPending,
                editor.text,
                state.attachments.isNotEmpty(),
            )
        ) {
            return false
        }
        publishDraft()
        callbacks.onSend(editor.prepareSubmission())
        callbacks.readComposerDraft?.invoke()?.let(editor::reconcileDispatch)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onReturnToIdleFocus()
        return true
    }

    Column(modifier = modifier) {
        if (showSpeechTaskPanel) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("voice_task_composer"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeechTaskStatusPanel(
                    status = checkNotNull(dictationStatus),
                    inputMuted = dictationInputMuted,
                    isWorking = isWorking,
                    audioRoute = audioRoute,
                    onAudioRouteRequested = callbacks.onSpeechAudioRouteRequested,
                    modifier = Modifier.weight(1f),
                )
                ComposerStopButton(
                    state = workInterrupt,
                    onInterrupt = callbacks.onInterruptWork,
                    onFocusChanged = onStopButtonFocusChanged,
                )
                if (dictationControlVisible) {
                    ComposerDictationButton(
                        status = dictationStatus,
                        inputMuted = dictationInputMuted,
                        onToggle = callbacks.onToggleDictation,
                        onFocusChanged = onDictationButtonFocusChanged,
                    )
                }
            }
            return@Column
        }

        // Text read-aloud has no task microphone. Its route remains available here,
        // rather than adding a second changing control beside the header menu.
        if (audioRoute?.active == true) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("speech_output_controls"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.voice_composer_output),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SpeechAudioRouteButton(audioRoute, callbacks.onSpeechAudioRouteRequested)
            }
        }
        state.attachments.forEach { attachment ->
            AttachmentRow(
                attachment = attachment,
                onRemove = callbacks.onRemoveAttachment,
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CameraComposerButton(
                enabled = composerInputEnabled,
                callbacks = callbacks,
            )

            // Keep Foundation's native editing/caret behavior. Only E-Ink, Android
            // animations-off or an inactive window suppress blinking; respect an
            // ancestor's suppression too. This stays scoped to the home composer,
            // never Apps search, and does not alter focus or keyboard routing.
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides (cursorBlinkEnabled && LocalCursorBlinkEnabled.current),
                LocalTextSelectionColors provides colors.textSelectionColors,
            ) {
                val cursorBlinkEnabled = LocalCursorBlinkEnabled.current
                BasicTextField(
                    state = editor.textField,
                    enabled = composerInputEnabled,
                    inputTransformation = pendingInputGuard,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = maximumComposerHeight)
                        .defaultMinSize(
                            minWidth = OutlinedTextFieldDefaults.MinWidth,
                            minHeight = OutlinedTextFieldDefaults.MinHeight,
                        )
                        .testTag("composer")
                        .semantics {
                            composerCursorBlinkEnabled = cursorBlinkEnabled
                        }
                        .focusRequester(composerFocusRequester)
                        .onFocusChanged { onComposerFocusChanged(it.isFocused) }
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter && event.isShiftPressed -> {
                                    // Foundation's Shift key map has no Enter binding. Keep
                                    // Hans' newline shortcut explicit beside Enter-to-send.
                                    if (composerInputEnabled && !editor.dispatchPending) {
                                        editor.insertText("\n")
                                        publishDraft()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed
                                -> submit()
                                event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.isAltPressed -> {
                                    val observed = AndroidKeyEventObserver.observe(event.nativeKeyEvent)
                                    val translated = observed?.let(ComposerKeyTranslationPolicy::translate)
                                    if (translated is ComposerKeyTranslation.InsertAndroidUnicode) {
                                        editor.insertText(translated.text)
                                        publishDraft()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        },
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = when {
                            !composerInputEnabled -> colors.disabledTextColor
                            focused -> colors.focusedTextColor
                            else -> colors.unfocusedTextColor
                        },
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    onKeyboardAction = { submit() },
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(colors.cursorColor),
                    decorator = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = editor.text,
                            innerTextField = {
                                // Foundation 1.9 caches the blink policy in its drawing node.
                                // Recreate only that inner subtree when the effective policy
                                // changes. The outer field, editing buffer, focus, scroll state
                                // and Android input connection must all retain their identity.
                                key(cursorBlinkEnabled) { innerTextField() }
                            },
                            enabled = composerInputEnabled,
                            singleLine = false,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(uiText.text(when (dictationStatus) {
                                    DictationUiStatus.PREPARING -> R.string.presentation_dictation_preparing
                                    DictationUiStatus.LISTENING -> if (dictationInputMuted) R.string.dictation_microphone_muted
                                        else R.string.ui_recording_fbd4a1
                                    else -> R.string.ui_ask_hans_69c7ab
                                }))
                            },
                            colors = colors,
                            container = {
                                OutlinedTextFieldDefaults.Container(
                                    enabled = composerInputEnabled,
                                    isError = false,
                                    interactionSource = interactionSource,
                                    colors = colors,
                                    shape = MaterialTheme.shapes.large,
                                )
                            },
                        )
                    },
                )
            }
            ComposerStopButton(
                state = workInterrupt,
                onInterrupt = callbacks.onInterruptWork,
                onFocusChanged = onStopButtonFocusChanged,
            )
            if (dictationControlVisible) {
                ComposerDictationButton(
                    status = dictationStatus,
                    inputMuted = dictationInputMuted,
                    onToggle = callbacks.onToggleDictation,
                    onFocusChanged = onDictationButtonFocusChanged,
                )
            }
        }
    }
}

/** Event-driven, visible microphone state even when a hardware key replaces the button. */
@Composable
private fun SpeechTaskStatusPanel(
    status: DictationUiStatus,
    inputMuted: Boolean,
    isWorking: Boolean,
    audioRoute: SpeechAudioRouteState?,
    onAudioRouteRequested: (SpeechAudioRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(when {
                        status == DictationUiStatus.FINALIZING -> R.string.voice_composer_finishing
                        isWorking -> R.string.ui_hans_is_working_440086
                        status == DictationUiStatus.PREPARING -> R.string.voice_composer_starting
                        inputMuted -> R.string.voice_composer_request
                        else -> R.string.voice_composer_listening
                    }),
                    modifier = Modifier.testTag("voice_task_status"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(when {
                        status == DictationUiStatus.FINALIZING -> R.string.voice_composer_microphone_off
                        inputMuted -> R.string.voice_composer_microphone_off
                        status == DictationUiStatus.PREPARING -> R.string.voice_composer_microphone_starting
                        else -> R.string.voice_composer_microphone_on
                    }),
                    modifier = Modifier.testTag("voice_task_microphone_state"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (audioRoute?.active == true) {
                SpeechAudioRouteButton(audioRoute, onAudioRouteRequested)
            }
        }
    }
}

/** Start once, then stop and transcribe. Finalization cannot start a second recording. */
@Composable
private fun ComposerDictationButton(
    status: DictationUiStatus?,
    inputMuted: Boolean,
    onToggle: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val uiText = rememberHansTextResolver()
    val active = status == DictationUiStatus.PREPARING || status == DictationUiStatus.LISTENING
    val finishing = status == DictationUiStatus.FINALIZING
    val contentColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    // Voice control must remain reachable when recording or Codex work locks the text field.
    // Permission, ownership and pending transitions are checked by the shared command owner.
    OutlinedIconButton(
        onClick = onToggle,
        enabled = !finishing,
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            contentColor = contentColor,
        ),
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .testTag("toggle_dictation")
            .semantics {
                selected = active
                contentDescription = uiText.text(when {
                    finishing -> R.string.presentation_dictation_finalizing
                    active -> R.string.dictation_tile_stop
                    else -> R.string.voice_composer_start
                })
            },
    ) {
        Canvas(Modifier.size(24.dp)) {
            val color = contentColor.copy(alpha = if (finishing) 0.38f else 1f)
            val strokeWidth = 2.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width * 0.36f, size.height * 0.08f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.49f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.14f),
                style = Stroke(strokeWidth),
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.21f, size.height * 0.15f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.58f, size.height * 0.59f),
                style = Stroke(strokeWidth),
            )
            drawLine(color, Offset(size.width * 0.5f, size.height * 0.74f),
                Offset(size.width * 0.5f, size.height * 0.91f), strokeWidth)
            drawLine(color, Offset(size.width * 0.32f, size.height * 0.91f),
                Offset(size.width * 0.68f, size.height * 0.91f), strokeWidth)
            if (active && inputMuted) {
                drawLine(color, Offset(size.width * 0.12f, size.height * 0.08f),
                    Offset(size.width * 0.88f, size.height * 0.92f), strokeWidth)
            }
        }
    }
}

/** A static control independent of recording, draft ownership, and the Live-call lifecycle. */
@Composable
private fun ComposerStopButton(
    state: WorkInterruptUiState,
    onInterrupt: () -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
) {
    val uiText = rememberHansTextResolver()
    if (!state.visible) return
    // Close the same-frame double-click window. The runtime revision also releases this
    // latch if a fast rejection arrives before PENDING was ever rendered.
    var requested by remember(state) { mutableStateOf(false) }
    val pending = state.pending || requested
    val contentColor = MaterialTheme.colorScheme.onSurface
    OutlinedIconButton(
        onClick = {
            if (state.enabled && !requested) {
                requested = true
                if (!onInterrupt()) requested = false
            }
        },
        enabled = state.enabled && !pending,
        modifier = Modifier
            .size(52.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .testTag("interrupt_codex_work")
            .semantics {
                contentDescription = when {
                    pending -> uiText.text(R.string.ui_stop_requested_96eccb)
                    state.enabled -> uiText.text(R.string.ui_stop_codex_task_7c4b45)
                    else -> uiText.text(R.string.ui_codex_task_is_starting_80de46)
                }
            },
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawRect(contentColor.copy(alpha = if (state.enabled && !pending) 1f else 0.38f))
        }
    }
}

@Composable
private fun CameraComposerButton(
    enabled: Boolean,
    callbacks: ChatUiCallbacks,
) {
    val uiText = rememberHansTextResolver()
    OutlinedIconButton(
        onClick = callbacks.onChooseMedia,
        enabled = enabled,
        modifier = Modifier
            .size(52.dp)
            .testTag("choose_media")
            .semantics {
                contentDescription = uiText.text(R.string.ui_take_a_photo_or_video_646907)
            },
    ) {
        CameraIcon()
    }
}

internal fun printableCodePoint(
    unicodeCodePoint: Int,
): String? {
    if (
        unicodeCodePoint <= 0 ||
        !Character.isValidCodePoint(unicodeCodePoint) ||
        Character.isISOControl(unicodeCodePoint)
    ) {
        return null
    }
    return String(Character.toChars(unicodeCodePoint))
}

internal fun composerCanSend(
    enabled: Boolean,
    currentText: String,
    hasAttachments: Boolean,
): Boolean = enabled && (currentText.isNotBlank() || hasAttachments)

@Composable
private fun CameraIcon() {
    val uiText = rememberHansTextResolver()
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(27.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val bodyTop = size.height * 0.24f
        drawRoundRect(
            color = color,
            topLeft = Offset(1.dp.toPx(), bodyTop),
            size = androidx.compose.ui.geometry.Size(
                width = size.width - 2.dp.toPx(),
                height = size.height - bodyTop - 1.dp.toPx(),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = stroke,
        )
        drawCircle(
            color = color,
            radius = size.minDimension * 0.19f,
            center = Offset(size.width / 2f, size.height * 0.61f),
            style = stroke,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.31f, bodyTop),
            end = Offset(size.width * 0.39f, 2.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.39f, 2.dp.toPx()),
            end = Offset(size.width * 0.62f, 2.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.62f, 2.dp.toPx()),
            end = Offset(size.width * 0.70f, bodyTop),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: ComposerAttachmentUiModel,
    onRemove: (String) -> Unit,
) {
    val uiText = rememberHansTextResolver()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = attachment.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onRemove(attachment.id) }) {
            Text(stringResource(R.string.ui_remove_382837))
        }
    }
}
