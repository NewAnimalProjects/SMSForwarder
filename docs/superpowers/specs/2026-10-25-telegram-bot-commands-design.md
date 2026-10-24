# Telegram bot commands — design

Date: 2026-10-25
Status: approved in chat, awaiting written-spec review

## Goal

Let the authorized Telegram chats query the phone's state by sending read-only bot commands. The bot answers within a few seconds while the phone is online, including in Samsung battery saver mode.

## Decisions (agreed with the owner)

- **Scope:** read-only commands only. No commands change app state or send SMS.
- **Single consumer:** the bot is used only by this app on one phone, so the app owns `getUpdates` exclusively.
- **Freshness:** answer only commands sent at most **5 minutes** ago; older ones are skipped silently (logged).
- **Transport:** long polling `getUpdates` inside the existing `ForwarderService` (option A). WorkManager polling and FCM were rejected: too slow for the 5-minute rule, or require a server.
- **Authorization:** only chats listed in `telegram.chatIds` are served. Messages from any other chat are ignored without a reply.
- **Chats are private** (positive chat IDs). Group-specific handling is out of scope, but the `@BotName` suffix is still accepted.
- **Reply language:** Russian, matching existing notifications. `parse_mode=Markdown`, user-provided text escaped with the existing `escapeMarkdown`.
- **Command menu:** registered via `setMyCommands` when polling starts.

## Commands

| Command | Reply |
|---|---|
| `/status` | Battery % and charging state; service uptime; total SMS and not-sent count; time of the last received SMS |
| `/battery` | Battery %; charging or discharging; power source (AC / USB / wireless) when charging |
| `/last [N]` | The last N SMS, newest first: sender, received time, status, body. Default N = 5, clamped to 1..20; a non-numeric N gets the usage hint |
| `/sims` | Active SIM cards: label and number (or "номер недоступен"); a hint if the phone permission is missing |
| `/ping` | `pong` and the phone's current time |
| `/help` | The command list with one-line descriptions |
| anything else starting with `/` | "Неизвестная команда. /help — список команд" |

Messages that do not start with `/` are ignored.

Each SMS body in `/last` is truncated to 500 characters (with `…`). The whole reply is capped at 4096 characters (Telegram's limit); when the cap is reached, the remaining SMS are dropped and a line "…и ещё K" is appended. Times use the existing `TelegramClient.formatReceivedAt` (`dd.MM.yyyy HH:mm:ss`, phone time zone).

## Architecture

```
Telegram ──getUpdates (long poll, 50 s)──▶ BotUpdatePoller ──▶ BotCommandHandler ──▶ sendMessage(chatId)
                                              │                     │
                                       OffsetStore           DeviceStatusProvider
                                     (SharedPreferences)     (battery, SIM, Room)
```

### `TelegramBotApi` (new interface) and `TelegramClient` (extended)

`TelegramClient` remains the only place that talks HTTP to the Bot API and implements:

```kotlin
interface TelegramBotApi {
    suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage>
    suspend fun sendMessage(chatId: String, text: String)
    suspend fun setMyCommands(commands: List<Pair<String, String>>)
}

data class BotMessage(val updateId: Long, val chatId: String, val dateSec: Long, val text: String?)
```

- `getUpdates` is called with `allowed_updates=["message"]`. Updates without a `message` still produce a `BotMessage` with `text = null` so their `update_id` advances the offset.
- JSON is parsed with Android's built-in `org.json`, in a separate pure function `parseUpdates(json: String): List<BotMessage>` so it can be unit-tested.
- Long polling uses a client derived from the shared one (`client.newBuilder().readTimeout(60 s)`), so connection pools are shared.
- Calls are made with `enqueue` wrapped in `suspendCancellableCoroutine`; coroutine cancellation calls `call.cancel()`, so stopping the service never waits for an open long poll.
- Errors surface as `TelegramApiException(code: Int?, retryAfterSec: Int?)`. `code` is null for network errors; `retryAfterSec` comes from `parameters.retry_after` on 429.
- Existing `sendSmsToTelegram` / `sendBatteryLowToTelegram` keep their behavior and signatures.

### `BotUpdatePoller` (new, no Android dependencies)

Constructor dependencies: `TelegramBotApi`, `BotCommandHandler`, `OffsetStore`, authorized chat IDs, `clock: () -> Long`, and a `networkAvailable: Flow<Unit>` used to cut backoff short.

`suspend fun run()` — loops until cancelled:

1. On start, calls `setMyCommands` once (failure is logged, not fatal).
2. `getUpdates(offset = store.offset, timeoutSec = 50)`.
3. For each message, in order:
   1. `store.offset = updateId + 1` (persisted before handling: at-most-once processing).
   2. Skip if `chatId` is not authorized, if `clock() - dateSec * 1000 > 5 min`, or if `text` is null or does not start with `/`.
   3. `reply = handler.handle(text)`; if the handler throws, `reply = "Ошибка: <message>"`.
   4. `sendMessage(chatId, reply)`; a failure is logged and not retried.
4. Repeat.

### Error handling and backoff

| Situation | Behavior |
|---|---|
| Network error, timeout, HTTP 5xx | Exponential backoff 5 s → 10 s → … capped at 5 min; reset after any successful `getUpdates` |
| HTTP 429 | Wait `retry_after` seconds (fallback: the normal backoff) |
| HTTP 401 | Log "invalid bot token", wait 30 min |
| HTTP 409 | Log "another getUpdates consumer or webhook is active", wait 1 min |
| Other 4xx | Log, normal backoff |

Any backoff wait ends early when `networkAvailable` emits. `CancellationException` is always rethrown so the loop stops cleanly.

### `BotCommandHandler` (new, pure logic)

`fun handle(text: String): String`. Parses the first token as the command, strips an optional `@BotName` suffix, compares case-insensitively, and reads an optional argument for `/last`. All data comes from `DeviceStatusProvider`.

### `DeviceStatusProvider` (new interface) and `AndroidDeviceStatusProvider`

```kotlin
interface DeviceStatusProvider {
    fun battery(): BatteryStatus?          // percent, isCharging, plugType
    fun sims(): SimsResult                 // list of (label, number) or PermissionMissing
    suspend fun smsCounts(): Pair<Int, Int> // total, notSent
    suspend fun lastSms(limit: Int): List<SmsMessageEntity>
    fun serviceStartedAt(): Long?
    fun now(): Long
}
```

- Battery data comes from the sticky `ACTION_BATTERY_CHANGED` intent (reusing `BatteryAlerts.percentFrom`).
- SIM loading moves out of `MainActivity` into a shared helper (`SimInfoReader`) used by both `MainActivity` and the provider, so the logic is not duplicated.
- `SmsDao` gains `getLatest(limit: Int): List<SmsMessageEntity>` (`ORDER BY created_at DESC LIMIT :limit`).
- `ForwarderService` records its start time in a companion field read by `serviceStartedAt()`.

### `OffsetStore`

A small interface (`var offset: Long?`) with a SharedPreferences implementation (`bot_prefs` / `update_offset`), kept in app-private storage. Tests use an in-memory fake.

### Service integration

`ForwarderService.onCreate` launches `poller.run()` in its existing `scope`; `onDestroy` cancels the scope. The `networkAvailable` flow wraps `ConnectivityManager.registerDefaultNetworkCallback` (`onAvailable`), unregistered when the flow is cancelled. The callback requires `ACCESS_NETWORK_STATE`; it is declared explicitly in the manifest (a normal permission, granted at install without a prompt). No runtime permissions are added.

## Logging

Through `OperationLogger`: poller start/stop, every API error with its backoff, and for each handled command its name and chat ID. Message text and SMS bodies are never logged.

## Testing

JVM unit tests in `app/src/test`:

- `BotCommandHandlerTest` (fake `DeviceStatusProvider`): every command; `@BotName` suffix; case-insensitivity; `/last` with no argument, valid N, N above 20, N = 0, non-numeric N; unknown command; 500-char body truncation; 4096-char reply cap with "…и ещё K"; missing battery data; missing SIM permission.
- `BotUpdatePollerTest` (fake API, fake clock, in-memory `OffsetStore`, `kotlinx-coroutines-test` virtual time): unauthorized chat ignored; message older than 5 min ignored; non-command and `text = null` ignored but offset advanced; offset persisted before handling; handler exception produces "Ошибка: …" and the loop continues; backoff sequence and reset; 429 honors `retry_after`; 401/409 waits; network-available emission ends a backoff early; cancellation stops the loop.
- `TelegramUpdatesParserTest`: parses text messages, non-message updates, and an empty result.

New test-only dependencies (version catalog): `org.jetbrains.kotlinx:kotlinx-coroutines-test` and `org.json:json` (Android's `org.json` is stubbed on the JVM).

Manual check on the Samsung device: each command; a message from a non-authorized account gets no reply; airplane mode for 6 minutes, then commands sent during it are skipped and new ones are answered; battery saver on with the screen locked.

## Out of scope

Commands that change state (`/resend`, `/pause`), sending SMS, group chats, webhooks, multiple devices sharing one bot.
