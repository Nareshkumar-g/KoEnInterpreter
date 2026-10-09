# Araso (Android)

Listens to Korean speech and shows a high-quality English translation on screen.

## Two recognition options

Switch any time in the **Listening setup** panel at the top of the main screen.

| | Everyday | Noisy place |
|---|---|---|
| Recognizer | Android's built-in (Google) | OpenAI `gpt-4o-transcribe` |
| Cost | Free | Paid per audio minute |
| Keys needed | Anthropic only | Anthropic + OpenAI |
| Live Korean text while speaking | Yes | No (text appears after each sentence) |
| Best for | Meetings, conversations, daily use | Factory floors, streets, crowds, machinery |
| Offline option | Yes (on-device, Android 12+) | No |

## Choice of translator

On first launch you pick who translates Korean into English (change it any time in Settings → Translation):

| | Claude (Anthropic) | OpenAI (GPT) | Free (no API) |
|---|---|---|---|
| Engine | `claude-sonnet-5-5` (or Opus / Haiku) | `gpt-6.1-sol` (or Astra / Luna) | Google ML Kit, on the phone |
| Cost | Pay per use | Pay per use | Free |
| Keys needed | Anthropic (+ OpenAI for Noisy place / Meeting) | OpenAI only | None (+ OpenAI for Noisy place / Meeting) |
| Works offline | No | No | Yes, after a one-time ~30 MB download |
| Quality | Most natural, uses conversation context | Very good, uses conversation context | More literal, sentence by sentence |

Claude and OpenAI use the same interpreter instructions and conversation context and stream the English word by word. The free translator applies your glossary's `Korean = English` lines before translating so names and terms still come out right.

**Completely free setup:** choose **Free** as the translator and use **Everyday** recognition (the Google app's recognizer). No account, no key, no cost.

## Three listening modes

- **Continuous:** tap *Start listening*. Each sentence is translated after the speaker pauses, like a live interpreter.
- **Push to talk:** hold the mic button while someone speaks, release to translate.
- **Meeting:** like Continuous, and it also records the whole meeting. A red REC timer shows while recording, and it keeps going with the screen off (an ongoing notification shows it's recording). When you tap *End meeting*, the app waits for the last translations and saves:
  - the audio as `Meeting YYYY-MM-DD HH.mm.m4a` in **Recordings/Araso** (Music/… on Android 10–11), about 15 MB per hour
  - a timestamped Korean/English transcript `.txt` in **Download/Araso**

  A message then offers **Share** to send both files. Meetings use the Noisy place (OpenAI) recognizer on the recorded audio, because the phone's built-in recognizer can't share the microphone with a recorder; the app asks for the OpenAI key the first time you pick Meeting.

## Choosing the microphone

In the **Listening setup** panel, tap a mic card to choose:

- **Built-in mic**: the phone or tablet's own microphone.
- **External mic**: any connected mic, listed by name as it's plugged in or paired, such as a **DJI Mic** receiver (USB-C), a USB mic, a wired headset or a Bluetooth mic. The choice is remembered and used automatically whenever that mic is connected; if it's unplugged, the app falls back to the built-in mic and says so.

Tips:
- For the DJI Mic, the **USB-C receiver** gives much better audio than a Bluetooth connection, which is limited to phone-call quality.
- **Noisy place** records the audio itself, so it always uses exactly the mic you picked. In **Everyday** mode the phone's recognizer does its own recording; Bluetooth mics are routed to it, and Android normally uses a plugged-in USB mic automatically.
- The first start with a Bluetooth mic can take about a second while the phone opens the audio link.

## Why the translation quality is high

1. **Context-aware translation.** Each sentence is translated with the previous few Korean/English sentences attached, so dropped subjects, pronouns, names and topic carry over.
2. **Interpreter prompt.** Claude translates meaning and register (honorifics → natural formal/casual English), keeps numbers and names exact, and repairs obvious speech-recognition mistakes using context.
3. **Glossary.** Settings → *Topic & glossary*: describe the setting and list terms (`자율주행 로봇 = AMR`). It's sent to the translator and, as hints, to both recognizers.
4. **Ordered pipeline.** Sentences are always translated and shown in spoken order.

## Build & run

1. Open this folder in **Android Studio** and let Gradle sync.
2. Plug in your phone (USB debugging on) and press **Run**.
3. On first launch a short setup sheet asks you to **choose your translator** (Claude or OpenAI), then asks only for the key that choice needs. Picking **Noisy place** or **Meeting** later asks for the OpenAI key if you don't have it yet. Each key is checked with the provider before it's saved, and you can change keys later in Settings.
4. Allow the microphone and start.

Min Android 8.0 (API 26). Target/compile SDK 36.

### Everyday mode requirements

- The phone needs a speech recognition service. Almost all phones use the **Google app**; if you see "Speech recognition isn't available", install or enable it.
- **Beeps:** many phones beep each time recognition starts and stops. *Mute start/stop beeps* (on by default) silences media volume while listening and restores it afterward.
- **On-device (offline):** on Android 12+, turn on *Prefer on-device recognition* and download Korean in the phone's speech recognition settings.

## Settings worth tuning

| Setting | Default | Notes |
|---|---|---|
| Translation model | `claude-sonnet-5-5` | `claude-opus-5-5` = highest quality, slower. `claude-haiku-4-5-20251001` = fastest. |
| Pause before translating | 800 ms | Lower = snappier, may split sentences. Some built-in recognizers use their own timing. |
| Mic sensitivity (Noisy place) | Normal | *Low* ignores more background noise. |
| Context sentences | 4 | More = better consistency, slightly more cost. |

## Project layout

```
app/src/main/java/com/nars/kointerpreter/
  MainActivity.kt              screen switching with slide transition
  InterpreterViewModel.kt      both engines, both modes, ordered translation pipeline
  speech/KoreanRecognizer.kt   Android built-in recognizer (Everyday)
  audio/AudioCapture.kt        mic stream for Noisy place mode
  audio/MicManager.kt          detects external mics and routes audio to the chosen one
  audio/Vad.kt                 voice activity detection / sentence splitting
  audio/WavEncoder.kt          PCM → WAV
  api/SpeechToText.kt          OpenAI transcription (Noisy place)
  api/Translator.kt            Claude Messages API (streaming) + shared interpreter prompt
  api/OpenAiTranslator.kt      OpenAI Chat Completions (streaming) translator
  api/FreeTranslator.kt        free on-device translator (Google ML Kit) + glossary swap
  data/Settings.kt             keys and preferences
  ui/MainScreen.kt             transcript, mode and engine switches, controls
  ui/VoiceWave.kt              Siri-style listening wave (colors at the top of the file)
  ui/SetupPanel.kt             front-page panel: mode, recognition and microphone
  ui/KeySetupSheet.kt          step-by-step API key setup with verification
  meeting/AacRecorder.kt       records the meeting to .m4a (AAC)
  meeting/MeetingStore.kt      saves audio + transcript to Recordings / Download
  meeting/MeetingService.kt    keeps recording with the screen off (ongoing notification)
  api/KeyChecker.kt            checks keys with OpenAI / Anthropic before saving
  ui/SettingsScreen.kt         keys, models, recognizer options, glossary
```

## Before publishing on the Play Store

API keys typed into the app live on the phone, which is fine for personal or team use. For a public release, put a small backend between the app and the APIs to hold the keys, add rate limiting, and publish a privacy policy that says speech is processed by Google (Everyday) or OpenAI (Noisy place) and text by Anthropic.
