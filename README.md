# SMSforwarder

SMSforwarder is a Kotlin Android app that receives incoming SMS, even in the background, stores them in a local Room database, and forwards them to Telegram via the Bot API. It also monitors the battery level and sends a Telegram alert when the charge runs low.

Each SMS is forwarded straight from the receiver within a few seconds. If that fails, WorkManager retries it with backoff; all unsent messages are also re-sent whenever the app is opened or the resend button in the UI is pressed.

## Features
- SMS reception via `SmsReceiver`, even when the app is closed. The receiver forwards each SMS to Telegram immediately (within ~7 s); WorkManager is used only as a fallback when the direct send fails.
- Always-on foreground service (`ForwarderService`, type `specialUse`) with a persistent notification. It keeps the app working in battery saver mode, watches the battery level in real time, and restarts after reboot or app update (`BootReceiver`).
- Messages are stored in `sms_forwarder.db` with status `PENDING`/`SENT`/`FAILED` and the last error text.
- Forwarding to multiple Telegram chats at once. Each message shows the sender, the SIM number that received the SMS, and the date and time the SMS arrived on the phone (see [Message format](#message-format)).
- Automatic retries through WorkManager (`SmsForwardWorker`) on network or Telegram errors. Messages older than 7 days are marked as failed and no longer retried.
- Low-battery alerts: `ForwarderService` reacts to every battery percentage change, and `BatteryMonitorWorker` re-checks every 15 minutes as a fallback. Telegram is notified when the charge drops to 50%, 20%, 15%, 10%, 5% and 2%. Each threshold fires once; alerts reset when the charge goes back above 50%.
- Read-only Telegram bot commands (`/status`, `/battery`, `/last`, `/sims`, `/ping`, `/help`), see [Bot commands](#bot-commands).
- Simple UI (`MainActivity`): permission requests (SMS, SIM, notifications), counts of stored/unsent messages, the list of SIM cards, and a live operation log.

## Message format
Forwarded SMS are sent with Telegram Markdown:

```
📩 Новая SMS
От: +66653607612
На номер: +66...
Получено: 25.10.2026 00:11:23
Текст:
...
```

- **На номер** is shown only when the SIM number can be read (requires phone permissions; some carriers do not expose it).
- **Получено** is the moment the phone received the SMS (`created_at` in the database), in the phone's time zone. Retried messages keep their original time.

## Bot commands
The bot answers read-only commands from the chats listed in `telegram.chatIds`; messages from any other chat are ignored. `ForwarderService` long-polls Telegram (`getUpdates`), so replies arrive within a few seconds while the phone is online.

| Command | Reply |
|---|---|
| `/status` | Battery, service uptime, total/unsent SMS, time of the last SMS |
| `/battery` | Battery level and charging state |
| `/last [N]` | The last N SMS (default 5, max 20); long texts are shortened |
| `/sims` | SIM cards and their numbers |
| `/ping` | `pong` and the phone's time |
| `/help` | Command list (also sent for `/start`) |

- Only commands sent within the last **5 minutes** are answered; commands queued while the phone was offline are skipped.
- The command menu ("/" button in Telegram) is registered automatically.
- The app must be the bot's only consumer: a webhook or another device polling the same bot makes Telegram return `409 Conflict`.

## Requirements
- Android Studio with JDK 21 (the Gradle daemon toolchain is resolved automatically via Foojay).
- Gradle 9.6, Android Gradle Plugin 9.4, Kotlin 2.2.
- Min SDK 24, target/compile SDK 35.
- Libraries: Room 2.8 (via kapt), WorkManager, OkHttp, kotlinx-coroutines-android, AndroidX Lifecycle.
- Permissions: `RECEIVE_SMS`, `INTERNET`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`.

## Configuration
The bot token and chat IDs live in `local.properties` in the project root. The file is git-ignored, so secrets never reach the repository:

```properties
telegram.botToken=123456:ABC...
telegram.chatIds=111111111,222222222
```

- `telegram.chatIds` is a comma-separated list; every message goes to each chat.
- No quotes around values, and no trailing spaces (they become part of the value).
- `app/build.gradle.kts` reads these keys and generates `BuildConfig.TELEGRAM_BOT_TOKEN` / `BuildConfig.TELEGRAM_CHAT_IDS`. Values are compiled into the APK, so rebuild after changing them, and do not share the APK publicly.
- If a key is missing, the app still builds, but sending fails with `Set telegram.botToken in local.properties`.

## Getting started
1. Fill in `local.properties` as described in [Configuration](#configuration).
2. Build: `./gradlew assembleDebug`.
3. Install on a connected device: `./gradlew installDebug`. The build passes `-g` to `adb install`, so all runtime permissions are granted on install. When installing from Android Studio's Run button, grant permissions in the app instead.
4. Open the app. The foreground service starts and its notification appears. If the app is not exempt from battery optimization, it asks for the exemption automatically. Allow it for reliable background work.

To target one device when several are connected, set `ANDROID_SERIAL`:

```sh
ANDROID_SERIAL=<serial> ./gradlew installDebug
```

`adb` lives in `$ANDROID_HOME/platform-tools`; add it to your `PATH` if you want to call it directly.

To watch forwarding live:

```sh
adb logcat -s SmsReceiver TelegramClient ForwarderService
```

A successful direct send logs `SMS id=… forwarded directly`.

### Samsung devices with battery saver
To keep the app running while Power saving mode is on:
- Settings → Apps → SMSforwarder → Battery → **Unrestricted**.
- Settings → Battery → Background usage limits: make sure SMSforwarder is **not** in Sleeping / Deep sleeping apps. On recent One UI versions an app set to Unrestricted may not be listed under **Never sleeping apps**; that is expected.
- Leave **Limit apps and Home screen** off in Power saving settings; it stops all non-allowed apps.

To verify over adb (standby bucket `5` means exempt):

```sh
adb shell dumpsys deviceidle whitelist | grep smsforwarder
adb shell am get-standby-bucket dev.zaplatin.smsforwarder
```

## Development commands
- `./gradlew assembleDebug` — build the debug APK.
- `./gradlew installDebug` / `uninstallDebug` — install or remove the app on connected devices.
- `./gradlew testDebugUnitTest` — JVM unit tests.
- `./gradlew connectedDebugAndroidTest` — instrumented tests on a device.
- `./gradlew lint` — Android lint.

## Structure
All sources are in `app/src/main/java/dev/zaplatin/smsforwarder`:
- `SmsForwarderApp` — application class; schedules battery monitoring and starts `ForwarderService` on startup.
- `ForwarderService` — foreground service with real-time battery monitoring; `BootReceiver` restarts it after reboot/update.
- `MainActivity` — UI, permission requests, manual WorkManager trigger, and log display.
- `SmsReceiver` — BroadcastReceiver; saves SMS to Room, forwards it directly via `goAsync()`, and enqueues `SmsForwardWorker` on failure.
- `SmsForwardWorker` — CoroutineWorker; sends up to 20 messages younger than 7 days per run.
- `BatteryAlerts` — shared low-battery threshold logic; state is kept in SharedPreferences.
- `BatteryMonitorScheduler` / `BatteryMonitorWorker` — periodic (15 min) fallback battery check.
- `TelegramClient` — OkHttp client for the Telegram Bot API; builds the message text and reads the token/chat IDs from `BuildConfig`.
- `BotUpdatePoller` — long-polls `getUpdates`, filters by chat and freshness, backs off on errors; offset kept by `SharedPreferencesOffsetStore`.
- `BotCommandHandler` — builds command replies from `DeviceStatusProvider` (`AndroidDeviceStatusProvider` on the device).
- `SimInfoReader` — reads active SIM cards for the UI and the bot.
- Room layer: `SmsMessageEntity`, `SmsDao`, `AppDatabase` (schema version 2, with migration adding the `destination` column).
- `OperationLogger` — collects log entries shown on screen and in Logcat.

Unit tests (`app/src/test`) cover battery alert thresholds, received-time formatting, Bot API parsing, bot command replies, and the update poller.
