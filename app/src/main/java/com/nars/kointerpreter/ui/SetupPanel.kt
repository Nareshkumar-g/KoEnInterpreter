package com.nars.kointerpreter.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nars.kointerpreter.Mode
import com.nars.kointerpreter.audio.MicDevice
import com.nars.kointerpreter.audio.MicKind
import com.nars.kointerpreter.data.Engine

/**
 * Front-page panel with everything you change often: listening mode, recognition
 * engine and microphone. It folds into a one-line summary while listening so the
 * transcript gets the room, and unfolds again when you stop (or tap it).
 */
@Composable
fun SetupPanel(
    mode: Mode,
    engine: Engine,
    mics: List<MicDevice>,
    micKey: String,
    listening: Boolean,
    onMode: (Mode) -> Unit,
    onEngine: (Engine) -> Unit,
    onMic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listening) { expanded = !listening }

    val chevron by animateFloatAsState(if (expanded) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "chevron")

    Card(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
            // Header: title when open, a compact summary when folded.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = if (expanded) 4.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = expanded,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.weight(1f),
                    label = "header",
                ) { open ->
                    if (open) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Listening setup", style = MaterialTheme.typography.titleSmall)
                        }
                    } else {
                        Summary(mode, engine, mics, micKey)
                    }
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    if (expanded) "Hide setup" else "Show setup",
                    Modifier.graphicsLayer { rotationZ = chevron },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(250, delayMillis = 60)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(120)) + shrinkVertically(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                Column(Modifier.padding(bottom = 14.dp)) {
                    Label("Mode")
                    SlidingSegmented(
                        options = listOf(
                            SegOption("Continuous", Icons.Filled.Autorenew),
                            SegOption("Push to talk", Icons.Filled.TouchApp),
                            SegOption("Meeting", Icons.Filled.Groups),
                        ),
                        selected = Mode.entries.indexOf(mode),
                        onSelect = { onMode(Mode.entries[it]) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    AnimatedVisibility(
                        visible = mode == Mode.MEETING,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Hint("Translates like Continuous and also records the whole meeting. When you end it, the audio and a Korean/English transcript are saved to your phone.")
                    }

                    Label("Recognition")
                    // Meetings record the audio themselves, so they always use Noisy-place recognition.
                    val meeting = mode == Mode.MEETING
                    val shownEngine = if (meeting) Engine.OPENAI else engine
                    SlidingSegmented(
                        options = listOf(
                            SegOption("Everyday", Icons.Filled.RecordVoiceOver),
                            SegOption("Noisy place", Icons.Filled.GraphicEq),
                        ),
                        selected = if (shownEngine == Engine.BUILT_IN) 0 else 1,
                        onSelect = { onEngine(if (it == 0) Engine.BUILT_IN else Engine.OPENAI) },
                        disabled = if (meeting) setOf(0) else emptySet(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    AnimatedContent(
                        targetState = if (meeting) null else engine,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                        label = "engineHint",
                    ) { e ->
                        Hint(
                            when (e) {
                                null -> "Meetings use Noisy-place recognition, because the phone's built-in recognizer can't share the microphone with the recorder."
                                Engine.BUILT_IN -> "Free, shows Korean live as people speak."
                                Engine.OPENAI -> "Best with background noise. Uses your OpenAI key."
                            }
                        )
                    }

                    Label("Microphone")
                    MicRow(mics, micKey, onMic)
                    AnimatedVisibility(
                        visible = !meeting && engine == Engine.BUILT_IN && mics.any { it.kind == MicKind.USB },
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Hint("In Everyday mode, Android usually uses a plugged-in USB mic automatically. Noisy place always uses exactly the mic you pick.")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
)

@Composable
private fun Hint(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
)

data class SegOption(val label: String, val icon: ImageVector)

/**
 * Pill selector whose highlight glides between options with a soft spring.
 * The highlight's position is read in the layout phase, so the slide doesn't
 * recompose the labels.
 */
@Composable
fun SlidingSegmented(
    options: List<SegOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    disabled: Set<Int> = emptySet(),
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(scheme.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorX by animateDpAsState(
            segmentWidth * selected,
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "segIndicator",
        )
        Box(
            Modifier
                .offset { IntOffset(indicatorX.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(scheme.primary)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, option ->
                val isSelected = i == selected
                val isDisabled = i in disabled
                val fg by animateColorAsState(
                    when {
                        isSelected -> scheme.onPrimary
                        isDisabled -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
                        else -> scheme.onSurfaceVariant
                    },
                    tween(220), label = "segFg",
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Tab, enabled = !isDisabled) {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Three options get a slightly smaller label so "Push to talk" fits on phones.
                    val compact = options.size > 2
                    Icon(option.icon, null, Modifier.size(if (compact) 16.dp else 18.dp), tint = fg)
                    Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        option.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Horizontal row of mic cards; new mics slide in as they're plugged in or paired. */
@Composable
private fun MicRow(mics: List<MicDevice>, selectedKey: String, onSelect: (String) -> Unit) {
    val savedMissing = selectedKey.isNotBlank() && mics.none { it.key == selectedKey }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "builtin") {
            MicCard(
                icon = Icons.Filled.PhoneAndroid,
                title = "Built-in",
                subtitle = "Phone / tablet mic",
                selected = selectedKey.isBlank(),
                onClick = { onSelect("") },
                modifier = Modifier.animateItem(),
            )
        }
        if (savedMissing) {
            item(key = "missing") {
                MicCard(
                    icon = Icons.Filled.ErrorOutline,
                    title = "Your mic",
                    subtitle = "Not connected · using built-in",
                    selected = true,
                    error = true,
                    onClick = {},
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(mics, key = { it.key }) { mic ->
            MicCard(
                icon = iconFor(mic.kind),
                title = mic.name,
                subtitle = kindLabel(mic.kind),
                selected = mic.key == selectedKey,
                onClick = { onSelect(mic.key) },
                modifier = Modifier.animateItem(),
            )
        }
        if (mics.isEmpty()) {
            item(key = "connect") {
                MicCard(
                    icon = Icons.Filled.Usb,
                    title = "Connect a mic",
                    subtitle = "USB-C receiver or Bluetooth",
                    selected = false,
                    dim = true,
                    onClick = {},
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun MicCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    dim: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        when {
            error -> scheme.errorContainer
            selected -> scheme.primaryContainer
            else -> scheme.surfaceContainerHighest
        },
        tween(250), label = "micBg",
    )
    val border by animateColorAsState(
        when {
            error -> scheme.error
            selected -> scheme.primary
            else -> scheme.outlineVariant.copy(alpha = if (dim) 0.6f else 0f)
        },
        tween(250), label = "micBorder",
    )
    val content = when {
        error -> scheme.onErrorContainer
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface.copy(alpha = if (dim) 0.6f else 1f)
    }
    val iconScale by animateFloatAsState(
        if (selected) 1.12f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "micIcon",
    )

    Surface(
        onClick = {
            if (!dim && !error) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        enabled = !dim,
        modifier = modifier.width(148.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = BorderStroke(1.5.dp, border),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        .background(content.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(18.dp), tint = content)
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = selected && !error,
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    Box(
                        Modifier.size(20.dp).background(scheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, "Selected", Modifier.size(14.dp), tint = scheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title, color = content, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, color = content.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Folded header: "Continuous · Everyday · DJI Mic 2". */
@Composable
private fun Summary(mode: Mode, engine: Engine, mics: List<MicDevice>, micKey: String) {
    val mic = mics.firstOrNull { it.key == micKey }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryPill(
            when (mode) {
                Mode.CONTINUOUS -> Icons.Filled.Autorenew
                Mode.PUSH_TO_TALK -> Icons.Filled.TouchApp
                Mode.MEETING -> Icons.Filled.Groups
            },
            when (mode) {
                Mode.CONTINUOUS -> "Continuous"
                Mode.PUSH_TO_TALK -> "Push to talk"
                Mode.MEETING -> "Meeting"
            },
        )
        val shownEngine = if (mode == Mode.MEETING) Engine.OPENAI else engine
        SummaryPill(
            if (shownEngine == Engine.BUILT_IN) Icons.Filled.RecordVoiceOver else Icons.Filled.GraphicEq,
            if (shownEngine == Engine.BUILT_IN) "Everyday" else "Noisy place",
        )
        SummaryPill(
            when {
                micKey.isBlank() -> Icons.Filled.PhoneAndroid
                mic == null -> Icons.Filled.ErrorOutline
                else -> iconFor(mic.kind)
            },
            when {
                micKey.isBlank() -> "Built-in mic"
                mic == null -> "Built-in mic (yours isn't connected)"
                else -> mic.name
            },
        )
    }
}

@Composable
private fun SummaryPill(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun iconFor(kind: MicKind): ImageVector = when (kind) {
    MicKind.USB -> Icons.Filled.Usb
    MicKind.WIRED -> Icons.Filled.Headset
    MicKind.BLUETOOTH -> Icons.Filled.Bluetooth
}

private fun kindLabel(kind: MicKind) = when (kind) {
    MicKind.USB -> "USB · best quality"
    MicKind.WIRED -> "Wired"
    MicKind.BLUETOOTH -> "Bluetooth"
}

