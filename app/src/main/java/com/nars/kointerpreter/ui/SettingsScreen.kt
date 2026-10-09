package com.nars.kointerpreter.ui

import android.os.Build
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.nars.kointerpreter.api.FreeTranslator
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Translate
import com.nars.kointerpreter.data.TranslationProvider
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nars.kointerpreter.data.Settings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Translation")
            SlidingSegmented(
                options = listOf(
                    SegOption("Claude", Icons.Filled.Translate),
                    SegOption("OpenAI", Icons.Filled.Bolt),
                    SegOption("Free", Icons.Filled.Savings),
                ),
                selected = TranslationProvider.entries.indexOf(settings.translator),
                onSelect = { i ->
                    onChange { it.copy(translator = TranslationProvider.entries[i], translatorChosen = true) }
                },
            )
            AnimatedContent(
                targetState = settings.translator,
                transitionSpec = { (fadeIn(tween(220)) togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false)) },
                label = "translatorOptions",
            ) { provider ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (provider == TranslationProvider.CLAUDE) {
                        Hint("Claude by Anthropic: the most natural, context-aware English.")
                        KeyField("Anthropic API key", settings.anthropicKey) { v ->
                            onChange { it.copy(anthropicKey = v) }
                        }
                        ModelField(
                            label = "Claude model",
                            value = settings.claudeModel,
                            options = Settings.CLAUDE_MODELS,
                            onValue = { v -> onChange { it.copy(claudeModel = v) } },
                        )
                        Hint("Sonnet 5.5: best balance of quality and speed. Opus 5.5: highest quality, slower. Haiku 4.5: fastest and cheapest.")
                    } else if (provider == TranslationProvider.FREE) {
                        FreeTranslatorPanel()
                    } else {
                        Hint("OpenAI GPT: one OpenAI key covers both recognition and translation. The key is set under OpenAI below.")
                        ModelField(
                            label = "OpenAI translation model",
                            value = settings.openAiTranslationModel,
                            options = Settings.OPENAI_TRANSLATION_MODELS,
                            onValue = { v -> onChange { it.copy(openAiTranslationModel = v) } },
                        )
                        Hint("GPT-6.1 Sol: near top quality at moderate cost. GPT-6 Astra: highest quality, slower and pricier. GPT-6 Luna: fastest and cheapest.")
                    }
                }
            }

            HorizontalDivider()
            Section("OpenAI")
            KeyField("OpenAI API key", settings.openAiKey) { v ->
                onChange { it.copy(openAiKey = v) }
            }
            Hint("Used for Noisy place and Meeting recognition, and for translation when OpenAI is selected above.")

            HorizontalDivider()
            Section("Speech recognition")
            Hint("Choose Everyday or Noisy place, the listening mode and the microphone on the main screen. These are the options behind them.")

            SubSection("Everyday options · built-in recognizer (free)")
            SwitchRow(
                title = "Mute start/stop beeps",
                subtitle = "Silences media volume while listening, so the phone's beep isn't heard each sentence.",
                checked = settings.muteBeeps,
                onChecked = { v -> onChange { it.copy(muteBeeps = v) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = "Prefer on-device recognition",
                    subtitle = "Works offline and keeps audio on the phone. Korean must be downloaded in your phone's speech recognition settings. Usually a little less accurate.",
                    checked = settings.onDeviceRecognition,
                    onChecked = { v -> onChange { it.copy(onDeviceRecognition = v) } },
                )
            }

            SubSection("Noisy place and Meeting options · OpenAI")
            ModelField(
                label = "Speech recognition model",
                value = settings.sttModel,
                options = Settings.STT_MODELS,
                onValue = { v -> onChange { it.copy(sttModel = v) } },
            )
            LabeledSlider(
                label = "Mic sensitivity",
                valueText = when {
                    settings.sensitivity < 2f -> "High (quiet rooms)"
                    settings.sensitivity < 3.5f -> "Normal"
                    else -> "Low (very loud places)"
                },
                // Slider left = more sensitive → invert the threshold factor.
                value = 6.5f - settings.sensitivity,
                range = 1.5f..5f,
                onValue = { v -> onChange { it.copy(sensitivity = 6.5f - v) } },
            )
            Hint("Lower sensitivity ignores more background noise. Only used in Noisy place mode.")

            SubSection("Both engines")
            LabeledSlider(
                label = "Pause before translating",
                valueText = "${settings.silenceMs} ms",
                value = settings.silenceMs.toFloat(),
                range = 400f..1500f,
                onValue = { v -> onChange { it.copy(silenceMs = (v / 50).roundToInt() * 50) } },
            )
            Hint("Shorter = faster but may split sentences. Some phones' built-in recognizers use their own pause timing.")
            Hint("Keys are stored only on this phone. Get them at platform.claude.com and platform.openai.com.")

            HorizontalDivider()
            Section("Topic & glossary")
            OutlinedTextField(
                value = settings.glossary,
                onValueChange = { v -> onChange { it.copy(glossary = v) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Context, names and terms") },
                placeholder = {
                    Text("Factory meeting about AMR robots\n자율주행 로봇 = AMR\n김민준 = Kim Min-jun")
                },
            )
            Hint("Helps both recognition and translation get names and jargon right. Describe the setting and list terms, one per line.")

            HorizontalDivider()
            Section("Translation & display")
            LabeledSlider(
                label = "Context sentences",
                valueText = "${settings.contextSize}",
                value = settings.contextSize.toFloat(),
                range = 0f..8f,
                steps = 7,
                onValue = { v -> onChange { it.copy(contextSize = v.roundToInt()) } },
            )
            Hint("How many earlier sentences Claude sees, for pronouns, names and topic.")
            LabeledSlider(
                label = "Text size",
                valueText = "${(settings.textScale * 100).roundToInt()}%",
                value = settings.textScale,
                range = 0.8f..1.8f,
                onValue = { v -> onChange { it.copy(textScale = v) } },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
)

@Composable
private fun SubSection(title: String) = Text(
    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 4.dp),
)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Status and download button for the free on-device translator. */
@Composable
private fun FreeTranslatorPanel() {
    val state by FreeTranslator.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { FreeTranslator.refreshState() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hint("Google's on-device translator: no API key and no cost, and it works offline. It's more literal than Claude or GPT and translates each sentence on its own, so names and terms from your glossary are swapped in before translating.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "freeState",
                modifier = Modifier.weight(1f),
            ) { st ->
                Text(
                    when (st) {
                        FreeTranslator.ModelState.READY -> "Korean model downloaded · ready offline"
                        FreeTranslator.ModelState.DOWNLOADING -> "Downloading Korean model (about 30 MB)…"
                        FreeTranslator.ModelState.FAILED -> "Download failed. Check your connection."
                        FreeTranslator.ModelState.NOT_DOWNLOADED -> "Korean model not downloaded yet (about 30 MB)"
                        FreeTranslator.ModelState.UNKNOWN -> "Checking…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (st == FreeTranslator.ModelState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (state == FreeTranslator.ModelState.NOT_DOWNLOADED || state == FreeTranslator.ModelState.FAILED) {
                Button(onClick = { scope.launch { FreeTranslator.ensureModel() } }) { Text("Download") }
            }
        }
        Hint("Speech recognition with Everyday mode is also free, so this combination needs no API at all. Noisy place and Meeting still use OpenAI for recognition.")
    }
}

@Composable
private fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun KeyField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show key")
            }
        },
    )
}

@Composable
private fun ModelField(label: String, value: String, options: List<String>, onValue: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                FilterChip(selected = value == opt, onClick = { onValue(opt) }, label = { Text(opt) })
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValue: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range, steps = steps)
    }
}
