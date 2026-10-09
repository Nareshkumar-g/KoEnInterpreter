package com.nars.kointerpreter.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import com.nars.kointerpreter.R
import com.nars.kointerpreter.data.Lang
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nars.kointerpreter.Entry
import com.nars.kointerpreter.EntryStatus
import com.nars.kointerpreter.InterpreterViewModel
import com.nars.kointerpreter.Mode
import com.nars.kointerpreter.RecordingEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: InterpreterViewModel, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mics by vm.externalMics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val mode by rememberUpdatedState(ui.mode)

    // Keep the screen awake while the mic is open so Android doesn't cut it off.
    val view = LocalView.current
    DisposableEffect(ui.listening) {
        view.keepScreenOn = ui.listening
        onDispose { view.keepScreenOn = false }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && mode != Mode.PUSH_TO_TALK) vm.startContinuous()
    }

    fun startWithPermissions() {
        if (hasMic()) vm.startContinuous() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // "Recording saved" message with a Share button.
    LaunchedEffect(Unit) {
        vm.recordings.collect { event ->
            when (event) {
                is RecordingEvent.Saved -> {
                    val secs = (event.durationMs / 1000).coerceAtLeast(1)
                    val r = snackbar.showSnackbar(
                        "Recording saved (${secs / 60}:${"%02d".format(secs % 60)}) to ${event.folder}",
                        actionLabel = "Share",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) {
                        val send = Intent(Intent.ACTION_SEND).setType("audio/wav")
                            .putExtra(Intent.EXTRA_STREAM, event.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(send, "Share recording")) }
                    }
                }
                is RecordingEvent.Failed -> snackbar.showSnackbar(event.message, withDismissAction = true)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    // Full-screen glow: the voice wave fills the screen behind the text while someone is speaking.
    VoiceWave(
        vm.level,
        active = ui.listening && ui.hearingSpeech,
        modifier = Modifier.fillMaxSize(),
        opacity = 0.45f,
        showLine = false,
    )
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                title = { Text("Araso", fontWeight = FontWeight.SemiBold) },
                actions = {
                    val hasEntries = entries.isNotEmpty()
                    IconButton(enabled = hasEntries, onClick = {
                        val text = vm.transcriptText()
                        if (text.isNotBlank()) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, "Share transcript"))
                        }
                    }) { Icon(Icons.Filled.Share, "Share transcript") }
                    IconButton(enabled = hasEntries, onClick = {
                        val removed = vm.clear()
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val r = snackbar.showSnackbar(
                                "Transcript cleared", actionLabel = "Undo", duration = SnackbarDuration.Short,
                            )
                            if (r == SnackbarResult.ActionPerformed) vm.restore(removed)
                        }
                    }) { Icon(Icons.Filled.DeleteSweep, "Clear transcript") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SetupPanel(
                mode = ui.mode,
                listen = settings.listen,
                mics = mics,
                micKey = settings.micKey,
                listening = ui.listening,
                onMode = vm::setMode,
                onListen = vm::setListen,
                onMic = vm::setMic,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )

            Transcript(
                entries = entries,
                mode = ui.mode,
                textScale = settings.textScale,
                onRetry = vm::retry,
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(
                visible = ui.micError != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    ui.micError.orEmpty(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Controls(
                mode = ui.mode,
                listening = ui.listening,
                hearingSpeech = ui.hearingSpeech,
                level = vm.level,
                enabled = true,
                canRecord = vm.canRecord,
                recordOn = settings.recordAudio,
                recordingStartedAt = ui.recordingStartedAt,
                onToggleRecord = { vm.setRecordAudio(!settings.recordAudio) },
                onStart = ::startWithPermissions,
                onStop = vm::stopListening,
                onPress = {
                    if (hasMic()) { vm.pttPress(); true }
                    else { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO); false }
                },
                onRelease = vm::pttRelease,
            )
        }
    }
    }
}

// ------------------------------------------------------------------ transcript

@Composable
private fun Transcript(
    entries: List<Entry>,
    mode: Mode,
    textScale: Float,
    onRetry: (Long) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()

    // Follow new text only while the user is at the bottom. Scrolling up to reread
    // pauses auto-scroll; reaching the bottom (or tapping the arrow) resumes it.
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val atEnd = !listState.canScrollForward
    LaunchedEffect(dragged, atEnd) {
        if (atEnd) follow = true else if (dragged) follow = false
    }
    val last = entries.lastOrNull()
    val lastSize = (last?.translation?.length ?: 0) + (last?.original?.length ?: 0)
    LaunchedEffect(entries.size, lastSize, follow) {
        // The footer item is the last index; scrolling to it lands at the very end.
        if (follow && entries.isNotEmpty()) listState.animateScrollToItem(entries.size)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.id }, contentType = { "entry" }) { entry ->
                EntryCard(
                    entry, textScale, onRetry,
                    Modifier.animateItem(
                        fadeInSpec = tween(260),
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        fadeOutSpec = tween(180),
                    ),
                )
            }
            item(key = "footer", contentType = "footer") { Spacer(Modifier.height(1.dp)) }
        }

        AnimatedVisibility(
            visible = entries.isEmpty(),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.Center),
        ) { EmptyState(mode) }

        AnimatedVisibility(
            visible = !follow && entries.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            SmallFloatingActionButton(onClick = { follow = true }) {
                Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest")
            }
        }
    }
}

@Composable
private fun EmptyState(mode: Mode) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The Araso ear-and-waves logo (same drawing as the app icon).
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(132.dp))
        Text("Ready to interpret", style = MaterialTheme.typography.titleMedium)
        Crossfade(targetState = mode, label = "emptyHint") { m ->
            Text(
                when (m) {
                    Mode.CONTINUOUS -> "Choose who's speaking (한국어 or English), tap the big button and talk. Each sentence is translated after a short pause."
                    Mode.PUSH_TO_TALK -> "Choose who's speaking (한국어 or English), then hold the mic button while they talk. It translates as they speak and saves a recording when you let go."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, textScale: Float, onRetry: (Long) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (entry.status) {
            EntryStatus.ERROR -> scheme.errorContainer
            EntryStatus.DONE -> scheme.surfaceContainerLow
            else -> scheme.surfaceContainerHigh
        },
        animationSpec = tween(400),
        label = "cardColor",
    )
    // Chat layout: Korean speaker on the left, English speaker on the right.
    val korean = entry.lang == Lang.KOREAN
    val accent = if (korean) Brand.Cyan else Brand.Violet
    Card(
        modifier
            .fillMaxWidth()
            .padding(start = if (korean) 0.dp else 48.dp, end = if (korean) 48.dp else 0.dp),
        // Slightly see-through, so the full-screen voice glow shows behind the text.
        colors = CardDefaults.cardColors(containerColor = container.copy(alpha = 0.82f)),
    ) {
        SelectionContainer {
            Column(
                Modifier
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                    .padding(16.dp)
            ) {
                // "한국어 → English" / "English → 한국어"
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (korean) "한국어 → English" else "English → 한국어",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
                Crossfade(
                    targetState = entry.status == EntryStatus.LISTENING,
                    animationSpec = tween(220),
                    label = "phase",
                ) { pending ->
                    if (pending) {
                        Column {
                            if (entry.original.isNotBlank()) {
                                // Live text as it's spoken.
                                Text(
                                    entry.original,
                                    fontSize = (18 * textScale).sp,
                                    lineHeight = (25 * textScale).sp,
                                    color = scheme.onSurface,
                                )
                                Spacer(Modifier.height(8.dp))
                                TypingDots(scheme.primary)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypingDots(scheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Listening",
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    } else {
                        Column {
                            when {
                                entry.translation.isNotBlank() -> TranslationText(
                                    text = entry.translation,
                                    streaming = entry.status == EntryStatus.TRANSLATING,
                                    textScale = textScale,
                                )
                                entry.status == EntryStatus.TRANSLATING -> TypingDots(scheme.primary)
                            }
                            AnimatedVisibility(
                                visible = entry.original.isNotBlank(),
                                enter = fadeIn(tween(300)) + expandVertically(),
                            ) {
                                Text(
                                    entry.original,
                                    fontSize = (15 * textScale).sp,
                                    lineHeight = (21 * textScale).sp,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = entry.status == EntryStatus.ERROR,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.error ?: "Something went wrong",
                            color = scheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRetry(entry.id) }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

/** The translation, with a soft blinking caret while it's still coming in. */
@Composable
private fun TranslationText(text: String, streaming: Boolean, textScale: Float) {
    // Toggle twice a second: cheaper than an every-frame alpha animation.
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(streaming) {
        caretOn = true
        while (streaming) {
            delay(500)
            caretOn = !caretOn
        }
    }
    val caretColor = if (streaming && caretOn) MaterialTheme.colorScheme.primary else Color.Transparent
    Text(
        buildAnnotatedString {
            append(text)
            if (streaming) withStyle(SpanStyle(color = caretColor)) { append(" ▍") }
        },
        fontSize = (22 * textScale).sp,
        lineHeight = (29 * textScale).sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Three softly pulsing dots. Alpha is applied in the draw phase, so no recomposition. */
@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(520, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 160),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(color, CircleShape)
            )
        }
    }
}

// -------------------------------------------------------------------- controls

@Composable
private fun Controls(
    mode: Mode,
    listening: Boolean,
    hearingSpeech: Boolean,
    level: StateFlow<Float>,
    enabled: Boolean,
    canRecord: Boolean,
    recordOn: Boolean,
    recordingStartedAt: Long?,
    onToggleRecord: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VoiceWave(level, active = listening, modifier = Modifier.fillMaxWidth().height(72.dp))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(listening, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Row {
                        LiveDot(active = hearingSpeech)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                AnimatedContent(
                    targetState = when {
                        !listening -> when (mode) {
                            Mode.CONTINUOUS -> "Not listening"
                            Mode.PUSH_TO_TALK -> "Hold the button while someone speaks"
                        }
                        mode == Mode.PUSH_TO_TALK && recordingStartedAt != null -> "Recording… release to translate"
                        mode == Mode.PUSH_TO_TALK -> "Listening… release to translate"
                        hearingSpeech -> "Hearing speech"
                        else -> "Listening"
                    },
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "status",
                ) { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = recordingStartedAt != null,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    Row {
                        Spacer(Modifier.width(10.dp))
                        RecBadge(recordingStartedAt ?: 0L)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canRecord) {
                    RecToggle(on = recordOn, enabled = !listening, onClick = onToggleRecord)
                    Spacer(Modifier.width(12.dp))
                }
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.92f))
                            .togetherWith(fadeOut(tween(100)))
                    },
                    contentAlignment = Alignment.Center,
                    label = "controls",
                    modifier = Modifier.weight(1f),
                ) { m ->
                    if (m == Mode.PUSH_TO_TALK) {
                        PushToTalkButton(listening, enabled, onPress, onRelease)
                    } else {
                        ContinuousButton(listening, enabled, onStart, onStop, record = canRecord && recordOn)
                    }
                }
                // Balances the REC button, so the push-to-talk circle stays centred.
                if (canRecord) Spacer(Modifier.width(68.dp))
            }
        }
    }
}

/** Round REC switch: when on, listening also records the voice to an audio file. */
@Composable
private fun RecToggle(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (on) scheme.error.copy(alpha = 0.18f) else scheme.surfaceContainerHighest, label = "recBg")
    val fg by animateColorAsState(if (on) scheme.error else scheme.onSurfaceVariant, label = "recFg")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            enabled = enabled,
            shape = CircleShape,
            color = bg,
            border = androidx.compose.foundation.BorderStroke(2.dp, fg.copy(alpha = if (enabled) 1f else 0.4f)),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.FiberManualRecord, if (on) "Recording on" else "Recording off", tint = fg, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            if (on) "REC on" else "REC off",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Red "REC 00:12" pill with a blinking dot while the voice is recorded. */
@Composable
private fun RecBadge(startedAt: Long) {
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis() - startedAt) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(1000 - elapsed % 1000) // tick exactly on the second
        }
    }
    val blink = rememberInfiniteTransition(label = "rec")
    val dotAlpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "recDot",
    )
    val scheme = MaterialTheme.colorScheme
    val secs = elapsed / 1000
    Row(
        Modifier
            .background(scheme.errorContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(scheme.error, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "REC %d:%02d".format(secs / 60, secs % 60),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onErrorContainer,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Small status dot that breathes while speech is detected. */
@Composable
private fun LiveDot(active: Boolean) {
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "dotColor",
    )
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { alpha = if (active) pulse else 1f }
            .background(color, CircleShape)
    )
}

@Composable
private fun ContinuousButton(
    listening: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    /** Recording is on: label the button for recording. */
    record: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    // Idle: the icon's cyan → violet gradient. Listening: fades to red.
    val container by animateColorAsState(if (listening) scheme.error else Color.Transparent, tween(250), label = "btn")
    val gradientAlpha by animateFloatAsState(if (listening) 0f else 1f, tween(250), label = "btnGradient")
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (listening) onStop() else onStart()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(ButtonDefaults.shape)
            .drawBehind { drawRect(Brand.Gradient, alpha = gradientAlpha) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White),
    ) {
        AnimatedContent(
            targetState = listening,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 2 })
            },
            label = "btnLabel",
        ) { on ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        on -> Icons.Filled.Stop
                        record -> Icons.Filled.FiberManualRecord
                        else -> Icons.Filled.Mic
                    },
                    null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        on && record -> "Stop & save"
                        on -> "Stop"
                        record -> "Record & Listen"
                        else -> "Start listening"
                    },
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun PushToTalkButton(
    recording: Boolean,
    enabled: Boolean,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    // Idle: the brand gradient; recording: red.
    val color by animateColorAsState(
        when {
            !enabled -> scheme.surfaceVariant
            recording -> scheme.error
            else -> Color.Transparent
        },
        tween(200),
        label = "ptt",
    )
    val gradientAlpha by animateFloatAsState(if (enabled && !recording) 1f else 0f, tween(200), label = "pttGradient")
    val scale by animateFloatAsState(
        if (recording) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pttScale",
    )
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(recording, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            PulseRings(scheme.error, Modifier.size(160.dp))
        }
        Box(
            Modifier
                .size(108.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(Brand.Gradient, CircleShape, alpha = gradientAlpha)
                .background(color, CircleShape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        if (press()) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            tryAwaitRelease()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            release()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic, "Hold to talk",
                tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(46.dp),
            )
        }
    }
}

/** Two expanding rings behind the mic while recording. Drawn in the draw phase only. */
@Composable
private fun PulseRings(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "rings")
    val a by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "ringA",
    )
    val b by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), initialStartOffset = StartOffset(700)),
        label = "ringB",
    )
    Box(
        modifier.drawBehind {
            val max = size.minDimension / 2
            val min = max * 0.66f
            for (p in floatArrayOf(a, b)) {
                drawCircle(color.copy(alpha = 0.28f * (1f - p)), radius = min + (max - min) * p)
            }
        }
    )
}
