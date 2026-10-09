# Araso (Android)

Listens to Korean speech and shows an English translation on screen. Completely free: no account, no API key, no cost.

- **Recognition:** Android's built-in recognizer (the Google app), with live Korean text while people speak.
- **Translation:** Google ML Kit, on the phone. The Korean model (about 30 MB) downloads once on first launch; after that translation works offline. Your glossary's `Korean = English` lines are applied before translating so names and terms come out right.

## Two listening modes

- **Continuous:** tap *Start listening*. Each sentence is translated after the speaker pauses, like a live interpreter.
- **Push to talk:** hold the mic button while someone speaks, release to translate.

## Choosing the microphone

In the **Listening setup** panel, tap a mic card to choose:

- **Built-in mic**: the phone or tablet's own microphone.
- **External mic**: any connected mic, listed by name as it's plugged in or paired, such as a **DJI Mic** receiver (USB-C), a USB mic, a wired headset or a Bluetooth mic. The choice is remembered and used automatically whenever that mic is connected; if it's unplugged, the app falls back to the built-in mic and says so.

Tips:
- For the DJI Mic, the **USB-C receiver** gives much better audio than a Bluetooth connection, which is limited to phone-call quality.
- Bluetooth mics are routed to the recognizer; Android normally uses a plugged-in USB mic automatically.
- The first start with a Bluetooth mic can take about a second while the phone opens the audio link.

## Glossary

Settings → *Topic & glossary*: list terms one per line (`자율주행 로봇 = AMR`). The Korean side is sent to the recognizer as hints, and each pair is swapped in before translating.

## Build & run

1. Open this folder in **Android Studio** and let Gradle sync.
2. Plug in your phone or tablet (USB debugging on) and press **Run**, or run `./gradlew :app:installDebug`.
3. Allow the microphone and start.

Min Android 8.0 (API 26). Target/compile SDK 36.

### Requirements

- The phone needs a speech recognition service. Almost all phones use the **Google app**; if you see "Speech recognition isn't available", install or enable it.
- **Beeps:** many phones beep each time recognition starts and stops. *Mute start/stop beeps* (on by default) silences media volume while listening and restores it afterward.
- **On-device (offline) recognition:** on Android 12+, turn on *Prefer on-device recognition* and download Korean in the phone's speech recognition settings.

## Settings worth tuning

| Setting | Default | Notes |
|---|---|---|
| Pause before translating | 800 ms | Lower = snappier, may split sentences. Some recognizers use their own timing. |
| Text size | 100% | Larger text for reading at a distance. |

## Project layout

```
app/src/main/java/com/nars/kointerpreter/
  MainActivity.kt              screen switching with slide transition
  InterpreterViewModel.kt      both modes, ordered translation pipeline
  speech/KoreanRecognizer.kt   Android built-in recognizer
  audio/MicManager.kt          detects external mics and routes audio to the chosen one
  api/FreeTranslator.kt        on-device translator (Google ML Kit) + glossary swap
  data/Settings.kt             preferences
  ui/MainScreen.kt             transcript, mode switch, controls
  ui/VoiceWave.kt              Siri-style listening wave (colors at the top of the file)
  ui/SetupPanel.kt             front-page panel: mode and microphone
  ui/SettingsScreen.kt         translator model, recognizer options, glossary, text size
```
