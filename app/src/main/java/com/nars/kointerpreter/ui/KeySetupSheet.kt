package com.nars.kointerpreter.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Savings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.nars.kointerpreter.api.FreeTranslator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.nars.kointerpreter.data.TranslationProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nars.kointerpreter.api.KeyCheck
import com.nars.kointerpreter.api.KeyChecker
import com.nars.kointerpreter.api.KeyKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One page of the setup sheet. */
private sealed interface SetupStep {
    data object ChooseTranslator : SetupStep
    /** Free translator: download Google's on-device Korean model once. */
    data object DownloadFreeModel : SetupStep
    data class Key(val kind: KeyKind) : SetupStep
}

/**
 * Step-by-step setup shown as a bottom sheet.
 *  1. First run only: choose the translator (Claude or OpenAI).
 *  2. Then only the keys that choice needs: the OpenAI key (for OpenAI translation,
 *     Noisy place or Meeting) and/or the Anthropic key (for Claude translation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySetupSheet(
    askTranslator: Boolean,
    initialTranslator: TranslationProvider,
    /** Keys still needed if [TranslationProvider] is used; computed when the sheet opens or the choice changes. */
    keysFor: (TranslationProvider) -> List<KeyKind>,
    onTranslator: (TranslationProvider) -> Unit,
    onSave: (KeyKind, String) -> Unit,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var steps by remember {
        mutableStateOf(
            (if (askTranslator) listOf<SetupStep>(SetupStep.ChooseTranslator) else emptyList()) +
                keysFor(initialTranslator).map { SetupStep.Key(it) }
        )
    }
    if (steps.isEmpty()) return

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    fun next() {
        if (step < steps.lastIndex) step++ else closeThen(onFinished)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (steps.size > 1) {
                StepDots(count = steps.size, current = step)
                Spacer(Modifier.height(16.dp))
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(250)))
                        .togetherWith(slideOutHorizontally(tween(200)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(150)))
                },
                label = "setupStep",
            ) { index ->
                val stepLabel = if (steps.size > 1) "Step ${index + 1} of ${steps.size}" else null
                when (val current = steps.getOrNull(index)) {
                    SetupStep.ChooseTranslator -> ChooseTranslatorStep(
                        initial = initialTranslator,
                        stepLabel = stepLabel,
                        onChosen = { provider ->
                            onTranslator(provider)
                            // Only now do we know what else is needed.
                            steps = listOf<SetupStep>(SetupStep.ChooseTranslator) +
                                (if (provider == TranslationProvider.FREE) listOf(SetupStep.DownloadFreeModel) else emptyList()) +
                                keysFor(provider).map { SetupStep.Key(it) }
                            next()
                        },
                        onSkip = { closeThen(onDismiss) },
                    )
                    SetupStep.DownloadFreeModel -> DownloadModelStep(
                        stepLabel = stepLabel,
                        isLast = index == steps.lastIndex,
                        onReady = { next() },
                        onSkip = { closeThen(onDismiss) },
                    )
                    is SetupStep.Key -> KeyStep(
                        kind = current.kind,
                        stepLabel = stepLabel,
                        firstRun = !askTranslator && index == 0 && current.kind == KeyKind.ANTHROPIC,
                        isLast = index == steps.lastIndex,
                        onSaved = { key ->
                            onSave(current.kind, key)
                            next()
                        },
                        onSkip = { closeThen(onDismiss) },
                    )
                    null -> Unit
                }
            }
        }
    }
}

/** First-run page: pick Claude or OpenAI for translation. */
@Composable
private fun ChooseTranslatorStep(
    initial: TranslationProvider,
    stepLabel: String?,
    onChosen: (TranslationProvider) -> Unit,
    onSkip: () -> Unit,
) {
    var choice by remember { mutableStateOf(initial) }
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Welcome", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Spacer(Modifier.height(6.dp))
        stepLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        Text("Choose your translator", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Who should turn the Korean into English? You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        ProviderCard(
            icon = Icons.Filled.Translate,
            title = "Claude",
            subtitle = "by Anthropic",
            body = "Most natural, context-aware English. Needs an Anthropic key.",
            selected = choice == TranslationProvider.CLAUDE,
            onClick = { choice = TranslationProvider.CLAUDE },
        )
        Spacer(Modifier.height(10.dp))
        ProviderCard(
            icon = Icons.Filled.Bolt,
            title = "OpenAI",
            subtitle = "GPT",
            body = "One OpenAI key covers both speech recognition and translation.",
            selected = choice == TranslationProvider.OPENAI,
            onClick = { choice = TranslationProvider.OPENAI },
        )
        Spacer(Modifier.height(10.dp))
        ProviderCard(
            icon = Icons.Filled.Savings,
            title = "Free",
            subtitle = "no API key",
            body = "Google's on-device translator. Costs nothing and works offline, but it's more literal and translates each sentence on its own.",
            selected = choice == TranslationProvider.FREE,
            onClick = { choice = TranslationProvider.FREE },
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip) { Text("Not now") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onChosen(choice) }, modifier = Modifier.height(48.dp)) { Text("Continue") }
        }
    }
}

/** Free translator: downloads the on-device model once, with a clear status. */
@Composable
private fun DownloadModelStep(
    stepLabel: String?,
    isLast: Boolean,
    onReady: () -> Unit,
    onSkip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state by FreeTranslator.state.collectAsState()
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        if (FreeTranslator.ensureModel()) {
            delay(700) // let the check mark land
            onReady()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        stepLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.7f)) togetherWith fadeOut(tween(120)) },
                label = "modelState",
            ) { st ->
                when (st) {
                    FreeTranslator.ModelState.READY -> Box(
                        Modifier.size(64.dp).background(scheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Check, null, Modifier.size(32.dp), tint = scheme.onPrimaryContainer) }
                    FreeTranslator.ModelState.FAILED -> Box(
                        Modifier.size(64.dp).background(scheme.errorContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.CloudOff, null, Modifier.size(30.dp), tint = scheme.onErrorContainer) }
                    else -> CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 4.dp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (state) {
                FreeTranslator.ModelState.READY -> "Free translator ready"
                FreeTranslator.ModelState.FAILED -> "Download didn't finish"
                else -> "Getting the free translator ready"
            },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state == FreeTranslator.ModelState.FAILED) {
                "Check your internet connection and try again. It only needs to download once."
            } else {
                "Downloading Google's Korean → English model once (about 30 MB). After that, translation works even offline."
            },
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip) { Text("Not now") }
            Spacer(Modifier.weight(1f))
            if (state == FreeTranslator.ModelState.FAILED) {
                Button(onClick = { scope.launch { attempt++ } }, modifier = Modifier.height(48.dp)) { Text("Retry") }
            } else if (state == FreeTranslator.ModelState.READY && !isLast) {
                Text("Continuing…", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh, tween(250), label = "provBg",
    )
    val border by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0f), tween(250), label = "provBorder",
    )
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    val iconScale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "provIcon",
    )
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
        border = BorderStroke(1.5.dp, border),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                    .background(content.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = content) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = content)
                    Spacer(Modifier.width(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f))
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.85f))
            }
            AnimatedVisibility(
                visible = selected,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                Box(
                    Modifier.padding(start = 8.dp).size(22.dp).background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, "Selected", Modifier.size(15.dp), tint = scheme.onPrimary) }
            }
        }
    }
}

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "dotW")
            val color by animateColorAsState(
                if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "dotC",
            )
            Box(Modifier.width(width).height(8.dp).background(color, RoundedCornerShape(50)))
        }
    }
}

private enum class Phase { EDITING, CHECKING, DONE }

@Composable
private fun KeyStep(
    kind: KeyKind,
    stepLabel: String?,
    firstRun: Boolean,
    isLast: Boolean,
    onSaved: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(Phase.EDITING) }

    val openAi = kind == KeyKind.OPENAI
    val title = if (openAi) "Add your OpenAI key" else "Add your Anthropic key"
    val body = if (openAi) {
        "Used for OpenAI translation, and for Noisy place and Meeting, which pick out Korean speech clearly over background noise."
    } else {
        "Claude turns the Korean into natural English in every mode."
    }
    val keyUrl = if (openAi) "https://platform.openai.com/api-keys" else "https://platform.claude.com"

    fun submit() {
        val k = key.trim()
        if (k.isEmpty()) { error = "Paste your key first."; return }
        error = null
        note = null
        phase = Phase.CHECKING
        scope.launch {
            when (val result = KeyChecker.check(kind, k)) {
                is KeyCheck.Rejected -> {
                    error = result.message
                    phase = Phase.EDITING
                }
                KeyCheck.Valid, KeyCheck.Unverified -> {
                    if (result == KeyCheck.Unverified) {
                        note = "Couldn't reach the server to check it, so it's saved as is."
                    }
                    phase = Phase.DONE
                    delay(if (result == KeyCheck.Unverified) 1400 else 650) // let the check mark land
                    onSaved(k)
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (firstRun) {
            Text("Welcome", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            Spacer(Modifier.height(8.dp))
        }
        Box(
            Modifier.size(64.dp).background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (openAi) Icons.Filled.GraphicEq else Icons.Filled.Translate, null,
                tint = scheme.onPrimaryContainer, modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        stepLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))) }
        }) {
            Text(if (openAi) "Get a key at platform.openai.com" else "Get a key at platform.claude.com")
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
        }
        Spacer(Modifier.height(4.dp))

        val warning = error ?: KeyChecker.formatWarning(kind, key.trim()).takeIf { key.isNotBlank() }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim(); error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = phase == Phase.EDITING,
            label = { Text(if (openAi) "OpenAI API key" else "Anthropic API key") },
            placeholder = { Text(if (openAi) "sk-…" else "sk-ant-…") },
            isError = error != null,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.trim()?.let { pasted -> if (pasted.isNotEmpty()) { key = pasted; error = null } }
                    }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show key")
                    }
                }
            },
            supportingText = {
                AnimatedContent(
                    targetState = warning ?: note ?: "Stored only on this phone.",
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                    label = "keyHint",
                ) { Text(it) }
            },
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip, enabled = phase == Phase.EDITING) { Text("Not now") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (phase == Phase.EDITING) submit() },
                modifier = Modifier.height(48.dp),
            ) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) },
                    label = "keyButton",
                ) { p ->
                    when (p) {
                        Phase.EDITING -> Text(if (isLast) "Save and start" else "Continue")
                        Phase.CHECKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Checking…")
                        }
                        Phase.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Saved")
                        }
                    }
                }
            }
        }
    }
}
