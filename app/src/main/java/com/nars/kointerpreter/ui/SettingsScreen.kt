package com.nars.kointerpreter.ui

import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.nars.kointerpreter.api.FreeTranslator
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
            FreeTranslatorPanel()

            HorizontalDivider()
            Section("Speech recognition")
            SwitchRow(
                title = "Mute start/stop beeps",
                subtitle = "Silences media volume while listening, so the phone's beep isn't heard each sentence.",
                checked = settings.muteBeeps,
                onChecked = { v -> onChange { it.copy(muteBeeps = v) } },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = "Prefer on-device recognition",
                    subtitle = "Works offline and keeps audio on the phone. Korean and English (India) must be downloaded in your tablet's speech recognition settings. Usually a little less accurate.",
                    checked = settings.onDeviceRecognition,
                    onChecked = { v -> onChange { it.copy(onDeviceRecognition = v) } },
                )
            }
            LabeledSlider(
                label = "Pause before translating",
                valueText = "${settings.silenceMs} ms",
                value = settings.silenceMs.toFloat(),
                range = 400f..1500f,
                onValue = { v -> onChange { it.copy(silenceMs = (v / 50).roundToInt() * 50) } },
            )
            Hint("Shorter = faster but may split sentences. Some phones' recognizers use their own pause timing.")

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
            Section("Display")
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
        Hint("Free and on-device: no account or API key, and it works offline once the Korean model is downloaded. Names and terms from your glossary are swapped in before translating.")
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
    }
}

@Composable
private fun Hint(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

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
