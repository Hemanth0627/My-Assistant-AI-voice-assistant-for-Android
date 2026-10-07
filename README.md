# My Assistant — a Siri-style AI voice assistant for Android

A personal voice assistant for Android that listens, works out what you meant, acts on the phone,
and answers out loud. Built with Kotlin and Jetpack Compose, an offline wake word, and a FastAPI
backend that keeps the AI API key off the device.

Say **"Hey Brain"**, or long-press the power button, and ask for something.

```
"I want to watch some videos"           ->  opens YouTube
"The screen is too dark"                ->  opens display settings
"Call Amma"                             ->  confirmation dialog, then calls
"What's the latest semiconductor news"  ->  web search, spoken summary with sources
"Explain Ohm's law"                     ->  a short spoken explanation
"Battery level"                         ->  answered on the phone, works offline
```

---

## Demo

> Add 3-4 screenshots or a short screen recording here, for example:
> `![Assistant panel](docs/screenshots/panel.png)`
>
> Blur contact names and phone numbers before uploading.

---

## Features

| Area | What it does |
|---|---|
| Voice input | Android `SpeechRecognizer` with live partial text and full error handling |
| Voice output | Android TTS, offline voice selection, stop and interrupt |
| AI brain | Claude through a FastAPI backend, using tool calling |
| Phone actions | Open apps, open settings pages, time, battery |
| Calls and messages | Contact lookup on the device, always behind a confirmation dialog |
| Web search | Live answers with tappable sources, shown but never read aloud |
| Wake word | "Hey Brain", fully offline, using Vosk in a foreground service |
| Assistant panel | Compact card over any app, launched by the power button or the wake word |
| Conversation mode | Keeps listening after each answer until you say "thanks" or go quiet |
| Offline fast path | Opening apps and settings, time and battery work with no internet |
| Screen management | The screen stays awake while the assistant is working |
| Privacy | Nothing written to disk, redacted history, content-free logs, clear button |

---

## Architecture

```
            ANDROID PHONE                                 CLOUD
 +---------------------------------------+      +------------------------+
 |  Wake word (Vosk, offline)            |      |                        |
 |            |                          |      |   FastAPI backend      |
 |            v                          |      |   - app token auth     |
 |  SpeechRecognizer  ->  text           |      |   - rate limiting      |
 |            |                          |      |   - system prompt      |
 |            v                          | HTTPS|   - tool definitions   |
 |  LocalCommandParser (offline rules)   |<---->|            |           |
 |            | no match                 |      |            v           |
 |            v                          |      |     Claude API         |
 |  AIService  -------------------------->      |   (reasoning, tools,   |
 |            |                          |      |    web search)         |
 |            v                          |      +------------------------+
 |  ActionParser  (whitelist validation) |
 |            |                          |         The API key never
 |            v                          |         leaves the server.
 |  ActionManager  ->  Android intents   |
 |            |                          |
 |            v                          |
 |  TTSManager  ->  spoken reply         |
 +---------------------------------------+
```

### Request flow

1. The wake word or the mic button starts `SpeechRecognizer`.
2. `LocalCommandParser` tries to match a simple command such as "open youtube" or
   "battery level". If it matches, the action runs instantly and offline, and no request is sent.
3. Otherwise `AIService` sends the message plus recent history to the backend.
4. The backend adds the system prompt and tool definitions and calls Claude. Claude either
   answers or asks for one tool, for example `open_app("YouTube")`.
5. The backend returns `{ reply, action, sources }`.
6. `ActionParser` validates the action against the phone's own whitelist and rejects anything
   unknown or malformed.
7. `ActionManager` runs it through Android intents. Calls, messages and clearing the
   conversation first show a confirmation dialog.
8. `TTSManager` speaks the result, and conversation mode listens again.

---

## Security and privacy design

This was a design goal from the start, not an afterthought.

- **No API key in the APK.** An APK can be decompiled in seconds, so the Claude key lives only on
  the server. The app authenticates with a separate app token that can be rotated without
  touching the AI key.
- **The AI cannot execute anything.** It can only *request* one of a fixed list of actions. A
  Kotlin `sealed interface` defines that list, and `ActionParser` revalidates every request on the
  device. There is no shell access and no tool that could create one.
- **Defense in depth.** The backend filters the model's output, and the phone filters it again. A
  misconfigured or compromised backend still cannot make the phone do anything new.
- **Confirmation before consequences.** Calls, messages and clearing data always wait for a tap.
  Messages are never sent silently: the messaging app opens with the text ready and the user
  presses send, so the `SEND_SMS` permission is not needed at all.
- **Contacts never leave the phone.** Claude only sees the name you said, such as "Amma". The
  lookup and the phone number stay on the device.
- **No data at rest.** The conversation lives in memory only, `allowBackup` is off, and the
  backend stores nothing and logs only message lengths.
- **Redaction.** Long digit sequences are stripped from the history before it is resent.
- **Rate limiting** on the backend, plus a monthly spend limit on the AI account.
- **Prompt-injection safety.** Search results are data, not instructions. Even if a page said
  "call this number", the confirmation dialog still stands in the way.

---

## Tech stack

**Android**
- Kotlin, Jetpack Compose, Material 3
- MVVM with one app-scoped `ViewModel` shared by the full screen and the panel
- `SpeechRecognizer`, `TextToSpeech`, `ContactsContract`, intents
- Vosk (offline speech recognition) for the wake word, in a foreground service
- OkHttp and `org.json`
- Min SDK 26

**Backend** (separate repository)
- Python, FastAPI, Uvicorn
- Anthropic SDK with tool calling and server-side web search
- Deployed with HTTPS, secrets in environment variables

---

## Project structure

```
app/src/main/java/com/hemanth/myassistant/
├── MainActivity.kt              # full chat screen host
├── AssistPanelActivity.kt       # compact panel over other apps (ASSIST intent)
├── MyAssistantApp.kt            # app-scoped ViewModel store (one shared conversation)
├── model/                       # ChatMessage, AssistantAction whitelist, status enums
├── ui/                          # Compose screens, ViewModel, wake-word toggle, keep-screen-on
├── voice/                       # SpeechRecognizerManager, TTSManager
├── ai/                          # AIService (HTTP), ActionParser (validation)
├── actions/                     # ActionManager, AppLauncher, SettingsManager,
│                                #   CommunicationManager, DeviceInfo, LocalCommandParser
└── wakeword/                    # WakeWordService (Vosk), WakeWordBus
```

---

## Building it yourself

### 1. Backend

The backend is a small FastAPI service that holds the AI key and defines the tools. See its own
repository for the code. Locally it runs with:

```bash
uvicorn server:app --host 127.0.0.1 --port 8000 --reload
```

### 2. Secrets

Copy `local.properties.example` to `local.properties` and fill it in. That file is git-ignored,
so your URL and token never reach GitHub.

```properties
sdk.dir=/path/to/Android/Sdk
backend.url=https://your-backend.example.com
backend.token=your-app-token
```

Gradle copies these into `BuildConfig` at build time, so no secret is written in the source.

### 3. Wake-word model

The Vosk model (~40 MB) is not included in this repository. Download a small English model from
<https://alphacephei.com/vosk/models>, rename the folder to `model-en-us`, create a text file
named `uuid` inside it containing any text, and place it at:

```
app/src/main/assets/model-en-us/
```

Without it the app still works; only the wake word is unavailable.

### 4. Run

Open the project in Android Studio and press Run. For local backend development, point
`backend.url` at `http://127.0.0.1:8000` and forward the port over USB:

```bash
adb reverse tcp:8000 tcp:8000
```

### 5. Optional phone setup

- Settings -> Default apps -> Digital assistant app -> this app (power-button launch)
- Allow "Display over other apps" (lets the panel pop up when the wake word fires)
- Settings -> Apps -> this app -> Battery -> Unrestricted (keeps the wake word alive)

---

## Known limitations

- The wake word works only while the phone is unlocked and the toggle is on. Android reserves the
  low-power always-on listening hardware for system assistants.
- The wake word uses extra battery, because detection runs on the main CPU.
- Messages open the messaging app instead of sending by themselves. This is deliberate.
- On a free cloud tier the server sleeps when idle, so the first request can be slow.

---

## Roadmap

- [ ] Alarms, timers and reminders
- [ ] Search inside an app ("open FFT diagrams on YouTube")
- [ ] Barge-in: interrupt the assistant by speaking
- [ ] Settings screen and saved conversations
- [ ] Picker when several contacts match a name
- [ ] Signed release build
- [ ] Unit tests for `LocalCommandParser` and `ActionParser`

---

## License

MIT. See [LICENSE](LICENSE).

Vosk and the Vosk models are covered by their own licenses.
