# Telegram Bot Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the authorized Telegram chats query the phone's state with read-only bot commands (`/status`, `/battery`, `/last`, `/sims`, `/ping`, `/help`), answered within seconds.

**Architecture:** `ForwarderService` runs a `BotUpdatePoller` coroutine that long-polls `getUpdates` through `TelegramClient` (which now implements a small `TelegramBotApi` interface). Each fresh command from an authorized chat goes to `BotCommandHandler`, which builds a Russian Markdown reply from a `DeviceStatusProvider`; the reply is sent back to the same chat. Poller and handler have no Android dependencies, so they are covered by JVM tests with fakes.

**Tech Stack:** Kotlin 2.2, kotlinx-coroutines 1.8.1, OkHttp 4.12, Android `org.json`, Room 2.8, JUnit 4, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-25-telegram-bot-commands-design.md`

**Deliberate refinements of the spec** (same behavior, easier to test):
- `BotCommandHandler.handle` is `suspend` because it reads Room through `DeviceStatusProvider`'s suspend functions.
- `BotUpdatePoller` takes the handler as `handleCommand: suspend (String) -> String` plus the `commands` list for `setMyCommands`, instead of the `BotCommandHandler` class, so poller tests can use a lambda.
- `/start` (sent by Telegram when a user first opens the bot) is answered like `/help`.

## Global Constraints

- minSdk 24, compileSdk/targetSdk 35, Kotlin 2.2.10, coroutines 1.8.1, OkHttp 4.12.0 — do not bump.
- No new runtime dependencies. Test-only: `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1`, `org.json:json:20240303`.
- Read-only commands only; nothing changes app state or sends SMS.
- Only chats in `telegram.chatIds` are served; others are ignored without a reply.
- Answer only commands with `now - date ≤ 5 min`.
- Long poll `timeout=50` s; polling HTTP client `readTimeout` 60 s.
- Backoff: 5 s doubling to a 5 min cap, reset after a successful `getUpdates`; 429 → `retry_after`; 401 → 30 min; 409 → 1 min; network-available ends any wait early.
- `/last`: default 5, clamp 1..20, body truncated to 500 chars + `…`, whole reply ≤ 4096 chars with `…и ещё K`.
- Replies in Russian, `parse_mode=Markdown`; every piece of user/SMS/SIM text goes through `TelegramClient.escapeMarkdown`.
- Never log message text or SMS bodies (command name and chat ID only).
- `sendSmsToTelegram` / `sendBatteryLowToTelegram` keep their signatures and behavior.
- Secrets stay in `local.properties`; tests use fake chat IDs (`111111111`), never real ones.
- Commits: `app: <imperative summary>` (repo convention), ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Precondition:** the working tree contains earlier uncommitted work (AGP 9 upgrade, foreground service, README, etc.). Commit it separately before Task 1, with the owner's approval, so each task's commit contains only that task.

## Review Focus

1. **Markdown characters in SMS text or sender** (`a*b_c`, `+66_1`) in `/last` — the reply must still be valid Markdown, i.e. escaped; an unescaped `_` makes Telegram reject the whole reply with HTTP 400. Test in Task 3.
2. **Sloppy command input** — `/STATUS@SmsForwarderBot`, leading/trailing spaces, `/last\n3` — should work like the clean form. Test in Task 3.
3. **Phone clock behind Telegram's** — a command whose `date` is slightly in the future must be answered, not dropped. Test in Task 4.
4. **Service or process restart** — polling must resume from the stored offset so old commands are not answered twice. Test in Task 4.
5. **Long outage or missing token** (repeated errors for hours) — waits must stop growing at 5 min, never hammer the API or grow unbounded. Test in Task 4.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/dev/zaplatin/smsforwarder/TelegramBotApi.kt` | Create | `TelegramBotApi` interface, `BotMessage`, `TelegramApiException`, pure JSON helpers `parseUpdates` / `apiErrorFrom` |
| `app/src/main/java/dev/zaplatin/smsforwarder/TelegramClient.kt` | Modify | Implements `TelegramBotApi` over OkHttp; `escapeMarkdown` becomes `internal`; exposes `authorizedChatIds` |
| `app/src/main/java/dev/zaplatin/smsforwarder/DeviceStatusProvider.kt` | Create | `DeviceStatusProvider` interface and its value types (`BatteryStatus`, `PlugType`, `SimInfo`, `SimsResult`) |
| `app/src/main/java/dev/zaplatin/smsforwarder/BotCommandHandler.kt` | Create | Parses a command and builds the reply text |
| `app/src/main/java/dev/zaplatin/smsforwarder/OffsetStore.kt` | Create | `OffsetStore` interface + SharedPreferences implementation |
| `app/src/main/java/dev/zaplatin/smsforwarder/BotUpdatePoller.kt` | Create | Long-poll loop, filtering, backoff |
| `app/src/main/java/dev/zaplatin/smsforwarder/SimInfoReader.kt` | Create | Reads active SIMs (moved out of `MainActivity`) |
| `app/src/main/java/dev/zaplatin/smsforwarder/AndroidDeviceStatusProvider.kt` | Create | Android implementation of `DeviceStatusProvider` |
| `app/src/main/java/dev/zaplatin/smsforwarder/SmsDao.kt` | Modify | Add `getLatest(limit)` |
| `app/src/main/java/dev/zaplatin/smsforwarder/MainActivity.kt` | Modify | Use `SimInfoReader` |
| `app/src/main/java/dev/zaplatin/smsforwarder/ForwarderService.kt` | Modify | `startedAt`, network callback, start poller |
| `app/src/main/AndroidManifest.xml` | Modify | `ACCESS_NETWORK_STATE` |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | Modify | Test dependencies, `isReturnDefaultValues` |
| `app/src/test/java/dev/zaplatin/smsforwarder/TelegramUpdatesParserTest.kt` | Create | Parser and error-mapping tests |
| `app/src/test/java/dev/zaplatin/smsforwarder/BotCommandHandlerTest.kt` | Create | Handler tests |
| `app/src/test/java/dev/zaplatin/smsforwarder/BotUpdatePollerTest.kt` | Create | Poller tests |
| `README.md` | Modify | Document bot commands |

Test command used throughout (from the repo root):
`./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.<TestClass>"`

---

### Task 1: Bot API types, JSON parsing, and test setup

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/TelegramBotApi.kt`
- Test: `app/src/test/java/dev/zaplatin/smsforwarder/TelegramUpdatesParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface TelegramBotApi { suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage>; suspend fun sendMessage(chatId: String, text: String); suspend fun setMyCommands(commands: List<Pair<String, String>>) }`
  - `data class BotMessage(val updateId: Long, val chatId: String, val dateSec: Long, val text: String?)`
  - `class TelegramApiException(val code: Int?, val retryAfterSec: Int?, val description: String?, cause: Throwable? = null) : Exception`
  - `internal fun parseUpdates(json: String): List<BotMessage>`
  - `internal fun apiErrorFrom(code: Int, body: String): TelegramApiException`

- [ ] **Step 1: Add test dependencies and stub defaults**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
orgJson = "20240303"
```

and under `[libraries]`:

```toml
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
org-json = { group = "org.json", name = "json", version.ref = "orgJson" }
```

In `app/build.gradle.kts`, inside `android { }` after the `installation { }` block, add:

```kotlin
    testOptions {
        // OperationLogger calls android.util.Log; return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }
```

and in `dependencies { }` after `testImplementation(libs.junit)`:

```kotlin
    testImplementation(libs.coroutines.test)
    // Android's org.json is a stub on the JVM; use the real implementation in tests.
    testImplementation(libs.org.json)
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/zaplatin/smsforwarder/TelegramUpdatesParserTest.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramUpdatesParserTest {

    @Test
    fun parseUpdates_readsTextAndNonTextUpdates() {
        val json = """
            {"ok":true,"result":[
              {"update_id":100,"message":{"message_id":1,"date":1792861883,
                "chat":{"id":111111111,"type":"private"},"text":"/status"}},
              {"update_id":101,"edited_message":{"message_id":1,"date":1792861890,
                "chat":{"id":111111111},"text":"edited"}},
              {"update_id":102,"message":{"message_id":2,"date":1792861900,
                "chat":{"id":111111111},"sticker":{}}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                BotMessage(updateId = 100, chatId = "111111111", dateSec = 1792861883, text = "/status"),
                BotMessage(updateId = 101, chatId = "", dateSec = 0, text = null),
                BotMessage(updateId = 102, chatId = "111111111", dateSec = 1792861900, text = null),
            ),
            parseUpdates(json),
        )
    }

    @Test
    fun parseUpdates_emptyResult() {
        assertEquals(emptyList<BotMessage>(), parseUpdates("""{"ok":true,"result":[]}"""))
    }

    @Test
    fun apiErrorFrom_readsRetryAfter() {
        val error = apiErrorFrom(
            429,
            """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7","parameters":{"retry_after":7}}""",
        )
        assertEquals(429, error.code)
        assertEquals(7, error.retryAfterSec)
        assertEquals("Too Many Requests: retry after 7", error.description)
    }

    @Test
    fun apiErrorFrom_toleratesNonJsonBody() {
        val error = apiErrorFrom(502, "<html>Bad Gateway</html>")
        assertEquals(502, error.code)
        assertNull(error.retryAfterSec)
        assertNull(error.description)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.TelegramUpdatesParserTest"`
Expected: compilation FAIL — `Unresolved reference 'BotMessage'`, `'parseUpdates'`, `'apiErrorFrom'`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/dev/zaplatin/smsforwarder/TelegramBotApi.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import org.json.JSONObject

/** The part of the Telegram Bot API used for bot commands. Implemented by [TelegramClient]. */
interface TelegramBotApi {
    suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage>
    suspend fun sendMessage(chatId: String, text: String)
    suspend fun setMyCommands(commands: List<Pair<String, String>>)
}

/** One incoming update. For updates without a message, [chatId] is empty and [text] is null. */
data class BotMessage(
    val updateId: Long,
    val chatId: String,
    val dateSec: Long,
    val text: String?,
)

/** [code] is null for network errors; [retryAfterSec] is set for HTTP 429. */
class TelegramApiException(
    val code: Int?,
    val retryAfterSec: Int?,
    val description: String?,
    cause: Throwable? = null,
) : Exception("Telegram API error ${code ?: "network"}: $description", cause)

internal fun parseUpdates(json: String): List<BotMessage> {
    val result = JSONObject(json).getJSONArray("result")
    return (0 until result.length()).map { index ->
        val update = result.getJSONObject(index)
        val message = update.optJSONObject("message")
        BotMessage(
            updateId = update.getLong("update_id"),
            chatId = message?.optJSONObject("chat")?.optLong("id")?.toString().orEmpty(),
            dateSec = message?.optLong("date") ?: 0L,
            text = if (message != null && message.has("text")) message.getString("text") else null,
        )
    }
}

internal fun apiErrorFrom(code: Int, body: String): TelegramApiException {
    val json = runCatching { JSONObject(body) }.getOrNull()
    return TelegramApiException(
        code = code,
        retryAfterSec = json?.optJSONObject("parameters")?.optInt("retry_after", 0)?.takeIf { it > 0 },
        description = json?.optString("description")?.takeIf { it.isNotEmpty() },
    )
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.TelegramUpdatesParserTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

Then the whole suite: `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/java/dev/zaplatin/smsforwarder/TelegramBotApi.kt \
  app/src/test/java/dev/zaplatin/smsforwarder/TelegramUpdatesParserTest.kt
git commit -m "app: add Telegram bot API types and update parsing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `TelegramClient` implements `TelegramBotApi`

**Files:**
- Modify: `app/src/main/java/dev/zaplatin/smsforwarder/TelegramClient.kt`

**Interfaces:**
- Consumes: `TelegramBotApi`, `BotMessage`, `TelegramApiException`, `parseUpdates`, `apiErrorFrom` (Task 1).
- Produces:
  - `object TelegramClient : TelegramBotApi`
  - `val TelegramClient.authorizedChatIds: Set<String>`
  - `internal fun TelegramClient.escapeMarkdown(value: String): String` (was `private`)
  - existing `TelegramClient.formatReceivedAt(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String` (unchanged)

The HTTP layer has no unit tests (no mock server in the project); its pure parts were tested in Task 1, and it is exercised on the device in Task 5.

- [ ] **Step 1: Change the declaration and visibility**

In `TelegramClient.kt`:
- Change `object TelegramClient {` to `object TelegramClient : TelegramBotApi {`.
- Change `private fun escapeMarkdown(value: String): String {` to `internal fun escapeMarkdown(value: String): String {`.

- [ ] **Step 2: Add imports**

Add to the import block (keep it sorted):

```kotlin
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resumeWithException
```

- [ ] **Step 3: Add the Bot API implementation**

Insert directly after the line `private val client = OkHttpClient()`:

```kotlin
    // getUpdates holds the request open for up to 50 s, longer than OkHttp's default 10 s read timeout.
    private val pollingClient by lazy {
        client.newBuilder()
            .readTimeout(LONG_POLL_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    val authorizedChatIds: Set<String>
        get() = CHAT_IDS.toSet()

    override suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage> {
        val form = FormBody.Builder()
            .add("timeout", timeoutSec.toString())
            .add("allowed_updates", "[\"message\"]")
        if (offset != null) form.add("offset", offset.toString())
        return parseUpdates(callApi(pollingClient, "getUpdates", form.build()))
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        val form = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "Markdown")
            .build()
        callApi(client, "sendMessage", form)
    }

    override suspend fun setMyCommands(commands: List<Pair<String, String>>) {
        val json = JSONArray()
        commands.forEach { (command, description) ->
            json.put(JSONObject().put("command", command).put("description", description))
        }
        callApi(client, "setMyCommands", FormBody.Builder().add("commands", json.toString()).build())
    }

    /** Returns the response body; throws [TelegramApiException] on HTTP or network errors. */
    private suspend fun callApi(httpClient: OkHttpClient, method: String, body: RequestBody): String {
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/$method")
            .post(body)
            .build()
        return try {
            httpClient.newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiErrorFrom(response.code, text)
                text
            }
        } catch (e: IOException) {
            throw TelegramApiException(code = null, retryAfterSec = null, description = e.message, cause = e)
        }
    }

    /** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _ -> response.close() }
            }
        })
    }
```

Add next to the other constants at the bottom of the object:

```kotlin
    private const val LONG_POLL_READ_TIMEOUT_SEC = 60L
```

- [ ] **Step 4: Build and run all tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Existing `TelegramClientTest` and `TelegramUpdatesParserTest` pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zaplatin/smsforwarder/TelegramClient.kt
git commit -m "app: implement Telegram bot API calls in TelegramClient

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `DeviceStatusProvider` and `BotCommandHandler`

**Files:**
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/DeviceStatusProvider.kt`
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/BotCommandHandler.kt`
- Test: `app/src/test/java/dev/zaplatin/smsforwarder/BotCommandHandlerTest.kt`

**Interfaces:**
- Consumes: `TelegramClient.escapeMarkdown`, `TelegramClient.formatReceivedAt` (Task 2); `SmsMessageEntity`, `SmsStatus` (existing).
- Produces:
  - `interface DeviceStatusProvider { fun battery(): BatteryStatus?; fun sims(): SimsResult; suspend fun smsCounts(): Pair<Int, Int>; suspend fun lastSms(limit: Int): List<SmsMessageEntity>; fun serviceStartedAt(): Long?; fun now(): Long }`
  - `data class BatteryStatus(val percent: Int, val isCharging: Boolean, val plugType: PlugType?)`
  - `enum class PlugType(val label: String) { AC, USB, WIRELESS }`
  - `data class SimInfo(val label: String, val number: String?)`
  - `sealed interface SimsResult { data class Available(val sims: List<SimInfo>); object PermissionMissing }`
  - `class BotCommandHandler(status: DeviceStatusProvider) { suspend fun handle(text: String): String }`
  - `BotCommandHandler.COMMANDS: List<Pair<String, String>>` (names without `/`, for `setMyCommands`)

- [ ] **Step 1: Create the provider interface and value types**

Create `app/src/main/java/dev/zaplatin/smsforwarder/DeviceStatusProvider.kt`:

```kotlin
package dev.zaplatin.smsforwarder

/** Phone state read by bot commands. Android implementation: [AndroidDeviceStatusProvider]. */
interface DeviceStatusProvider {
    fun battery(): BatteryStatus?
    fun sims(): SimsResult

    /** Total stored SMS and how many are not sent yet. */
    suspend fun smsCounts(): Pair<Int, Int>

    /** Newest first. */
    suspend fun lastSms(limit: Int): List<SmsMessageEntity>
    fun serviceStartedAt(): Long?
    fun now(): Long
}

data class BatteryStatus(
    val percent: Int,
    val isCharging: Boolean,
    val plugType: PlugType?,
)

enum class PlugType(val label: String) {
    AC("сеть"),
    USB("USB"),
    WIRELESS("беспроводная"),
}

data class SimInfo(val label: String, val number: String?)

sealed interface SimsResult {
    data class Available(val sims: List<SimInfo>) : SimsResult
    object PermissionMissing : SimsResult
}
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/zaplatin/smsforwarder/BotCommandHandlerTest.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandHandlerTest {

    private val status = FakeStatus()
    private val handler = BotCommandHandler(status)

    @Test
    fun status_showsAllFields() = runTest {
        status.messages = listOf(sms(id = 1, createdAt = NOW - 60_000))

        val reply = handler.handle("/status")

        assertTrue(reply, reply.startsWith("📊 *Статус*"))
        assertTrue(reply, reply.contains("*Батарея:* 76%, заряжается (USB)"))
        assertTrue(reply, reply.contains("*Сервис работает:* 2 ч 15 мин"))
        assertTrue(reply, reply.contains("*SMS всего:* 12, не отправлено: 1"))
        assertTrue(reply, reply.contains("*Последняя SMS:* " + TelegramClient.formatReceivedAt(NOW - 60_000)))
    }

    @Test
    fun status_serviceNotStartedAndNoSms() = runTest {
        status.startedAt = null

        val reply = handler.handle("/status")

        assertTrue(reply, reply.contains("*Сервис работает:* не запущен"))
        assertTrue(reply, reply.contains("*Последняя SMS:* нет"))
    }

    @Test
    fun battery_reportsStateOrMissingData() = runTest {
        status.batteryStatus = BatteryStatus(percent = 54, isCharging = false, plugType = null)
        assertEquals("🔋 *Батарея:* 54%, не заряжается", handler.handle("/battery"))

        status.batteryStatus = null
        assertEquals("🔋 *Батарея:* нет данных", handler.handle("/battery"))
    }

    @Test
    fun last_defaultsToFive() = runTest {
        handler.handle("/last")
        assertEquals(5, status.requestedLimit)
    }

    @Test
    fun last_clampsCount() = runTest {
        handler.handle("/last 50")
        assertEquals(20, status.requestedLimit)

        handler.handle("/last 0")
        assertEquals(1, status.requestedLimit)
    }

    @Test
    fun last_nonNumericCountShowsUsage() = runTest {
        assertEquals(BotCommandHandler.LAST_USAGE_TEXT, handler.handle("/last many"))
    }

    @Test
    fun last_noMessages() = runTest {
        assertEquals("SMS пока нет.", handler.handle("/last"))
    }

    @Test
    fun last_listsMessagesNewestFirst() = runTest {
        status.messages = listOf(
            sms(id = 2, body = "second", status = SmsStatus.PENDING),
            sms(id = 1, body = "first", status = SmsStatus.SENT),
        )

        val reply = handler.handle("/last 2")

        assertTrue(reply, reply.startsWith("📨 *Последние SMS (2):*"))
        assertTrue(reply, reply.indexOf("second") < reply.indexOf("first"))
        assertTrue(reply, reply.contains("*Статус:* ожидает отправки"))
        assertTrue(reply, reply.contains("*Статус:* отправлено"))
    }

    @Test
    fun last_truncatesLongBody() = runTest {
        status.messages = listOf(sms(id = 1, body = "a".repeat(600)))

        val reply = handler.handle("/last 1")

        assertTrue(reply.contains("a".repeat(500) + "…"))
        assertFalse(reply.contains("a".repeat(501)))
    }

    @Test
    fun last_capsReplyLength() = runTest {
        status.messages = (20 downTo 1).map { sms(id = it.toLong(), body = "x".repeat(500)) }

        val reply = handler.handle("/last 20")

        assertTrue("length ${reply.length}", reply.length <= 4096)
        assertTrue(reply, Regex("…и ещё \\d+$").containsMatchIn(reply))
    }

    @Test
    fun last_escapesMarkdownInSenderAndBody() = runTest {
        status.messages = listOf(sms(id = 1, from = "+66_1", body = "a*b_c"))

        val reply = handler.handle("/last 1")

        assertTrue(reply, reply.contains("+66\\_1"))
        assertTrue(reply, reply.contains("a\\*b\\_c"))
    }

    @Test
    fun acceptsBotNameSuffixCaseAndExtraWhitespace() = runTest {
        assertTrue(handler.handle("/STATUS@SmsForwarderBot").startsWith("📊 *Статус*"))

        handler.handle("  /last   3  ")
        assertEquals(3, status.requestedLimit)

        handler.handle("/last\n4")
        assertEquals(4, status.requestedLimit)
    }

    @Test
    fun sims_listsSimsWithUnknownNumbers() = runTest {
        assertEquals(
            "📶 *SIM-карты:*\nSIM 1: +66000000001\ndtac: номер недоступен",
            handler.handle("/sims"),
        )
    }

    @Test
    fun sims_permissionMissingOrEmpty() = runTest {
        status.simsResult = SimsResult.PermissionMissing
        assertEquals("Нет доступа к данным SIM (нужно разрешение телефона).", handler.handle("/sims"))

        status.simsResult = SimsResult.Available(emptyList())
        assertEquals("SIM-карты не найдены.", handler.handle("/sims"))
    }

    @Test
    fun ping_helpStartAndUnknown() = runTest {
        assertEquals("pong · " + TelegramClient.formatReceivedAt(NOW), handler.handle("/ping"))
        assertEquals(BotCommandHandler.HELP_TEXT, handler.handle("/help"))
        assertEquals(BotCommandHandler.HELP_TEXT, handler.handle("/start"))
        assertEquals(BotCommandHandler.UNKNOWN_COMMAND_TEXT, handler.handle("/foo"))
        assertTrue(BotCommandHandler.HELP_TEXT.contains("/status"))
    }

    @Test
    fun formatDuration() {
        assertEquals("меньше минуты", BotCommandHandler.formatDuration(30_000))
        assertEquals("1 ч 1 мин", BotCommandHandler.formatDuration(61 * 60_000L))
        assertEquals("1 д 5 мин", BotCommandHandler.formatDuration((24 * 60 + 5) * 60_000L))
    }

    private fun sms(
        id: Long,
        from: String = "+66000000000",
        body: String = "hello",
        createdAt: Long = NOW - id * 60_000,
        status: String = SmsStatus.SENT,
    ) = SmsMessageEntity(
        id = id,
        from = from,
        body = body,
        createdAt = createdAt,
        status = status,
        destination = null,
        lastError = null,
    )

    private class FakeStatus : DeviceStatusProvider {
        var batteryStatus: BatteryStatus? = BatteryStatus(percent = 76, isCharging = true, plugType = PlugType.USB)
        var simsResult: SimsResult = SimsResult.Available(
            listOf(SimInfo("SIM 1", "+66000000001"), SimInfo("dtac", null)),
        )
        var messages: List<SmsMessageEntity> = emptyList()
        var startedAt: Long? = NOW - (2 * 60 + 15) * 60_000L
        var requestedLimit: Int? = null

        override fun battery() = batteryStatus
        override fun sims() = simsResult
        override suspend fun smsCounts() = 12 to 1
        override suspend fun lastSms(limit: Int): List<SmsMessageEntity> {
            requestedLimit = limit
            return messages.take(limit)
        }
        override fun serviceStartedAt() = startedAt
        override fun now() = NOW
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
```

Note: `/status` calls `lastSms(1)`, so tests that check `requestedLimit` call `/last` last.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.BotCommandHandlerTest"`
Expected: compilation FAIL — `Unresolved reference 'BotCommandHandler'`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/dev/zaplatin/smsforwarder/BotCommandHandler.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import java.util.Locale

/** Builds replies to read-only bot commands. Replies use Telegram Markdown. */
class BotCommandHandler(private val status: DeviceStatusProvider) {

    suspend fun handle(text: String): String {
        val parts = text.trim().split(WHITESPACE)
        val command = parts.first().substringBefore('@').lowercase(Locale.ROOT)
        val argument = parts.getOrNull(1)
        return when (command) {
            "/status" -> statusReply()
            "/battery" -> "🔋 *Батарея:* " + describeBattery(status.battery())
            "/last" -> lastReply(argument)
            "/sims" -> simsReply()
            "/ping" -> "pong · " + TelegramClient.formatReceivedAt(status.now())
            "/help", "/start" -> HELP_TEXT
            else -> UNKNOWN_COMMAND_TEXT
        }
    }

    private suspend fun statusReply(): String {
        val (total, notSent) = status.smsCounts()
        val last = status.lastSms(1).firstOrNull()
        val startedAt = status.serviceStartedAt()
        return buildString {
            append("📊 *Статус*\n")
            append("*Батарея:* ").append(describeBattery(status.battery())).append('\n')
            append("*Сервис работает:* ")
                .append(startedAt?.let { formatDuration(status.now() - it) } ?: "не запущен")
                .append('\n')
            append("*SMS всего:* ").append(total).append(", не отправлено: ").append(notSent).append('\n')
            append("*Последняя SMS:* ")
                .append(last?.let { TelegramClient.formatReceivedAt(it.createdAt) } ?: "нет")
        }
    }

    private fun describeBattery(battery: BatteryStatus?): String {
        if (battery == null) return "нет данных"
        val state = if (battery.isCharging) {
            "заряжается" + (battery.plugType?.let { " (${it.label})" } ?: "")
        } else {
            "не заряжается"
        }
        return "${battery.percent}%, $state"
    }

    private suspend fun lastReply(argument: String?): String {
        val limit = if (argument == null) {
            DEFAULT_LAST_COUNT
        } else {
            argument.toIntOrNull()?.coerceIn(1, MAX_LAST_COUNT) ?: return LAST_USAGE_TEXT
        }
        val messages = status.lastSms(limit)
        if (messages.isEmpty()) return "SMS пока нет."
        return joinWithinLimit(
            header = "📨 *Последние SMS (${messages.size}):*",
            entries = messages.map(::formatSms),
        )
    }

    private fun formatSms(sms: SmsMessageEntity): String {
        val body = if (sms.body.length > MAX_BODY_LENGTH) sms.body.take(MAX_BODY_LENGTH) + "…" else sms.body
        return buildString {
            append("*От:* ").append(TelegramClient.escapeMarkdown(sms.from)).append('\n')
            append("*Получено:* ").append(TelegramClient.formatReceivedAt(sms.createdAt)).append('\n')
            append("*Статус:* ").append(statusLabel(sms.status)).append('\n')
            append(TelegramClient.escapeMarkdown(body))
        }
    }

    private fun statusLabel(status: String): String = when (status) {
        SmsStatus.SENT -> "отправлено"
        SmsStatus.PENDING -> "ожидает отправки"
        SmsStatus.FAILED -> "ошибка отправки"
        else -> status
    }

    /** Appends entries while the reply fits Telegram's limit, then "…и ещё K" for the rest. */
    private fun joinWithinLimit(header: String, entries: List<String>): String {
        val result = StringBuilder(header)
        var included = 0
        for (entry in entries) {
            if (result.length + SEPARATOR.length + entry.length > MAX_REPLY_LENGTH - TAIL_RESERVE) break
            result.append(SEPARATOR).append(entry)
            included++
        }
        val skipped = entries.size - included
        if (skipped > 0) result.append(SEPARATOR).append("…и ещё ").append(skipped)
        return result.toString()
    }

    private fun simsReply(): String = when (val result = status.sims()) {
        SimsResult.PermissionMissing -> "Нет доступа к данным SIM (нужно разрешение телефона)."
        is SimsResult.Available -> if (result.sims.isEmpty()) {
            "SIM-карты не найдены."
        } else {
            "📶 *SIM-карты:*\n" + result.sims.joinToString("\n") { sim ->
                val number = sim.number?.takeIf { it.isNotBlank() } ?: "номер недоступен"
                TelegramClient.escapeMarkdown(sim.label) + ": " + TelegramClient.escapeMarkdown(number)
            }
        }
    }

    companion object {
        /** Command menu for setMyCommands: name without "/" to description. */
        val COMMANDS = listOf(
            "status" to "состояние телефона",
            "battery" to "заряд батареи",
            "last" to "последние SMS: /last N (по умолчанию 5, до 20)",
            "sims" to "SIM-карты",
            "ping" to "проверка связи",
            "help" to "список команд",
        )

        val HELP_TEXT = "Доступные команды:\n" +
            COMMANDS.joinToString("\n") { (name, description) -> "/$name — $description" }
        const val UNKNOWN_COMMAND_TEXT = "Неизвестная команда. /help — список команд"
        const val LAST_USAGE_TEXT = "Использование: /last N, где N от 1 до 20"

        private const val DEFAULT_LAST_COUNT = 5
        private const val MAX_LAST_COUNT = 20
        private const val MAX_BODY_LENGTH = 500
        private const val MAX_REPLY_LENGTH = 4096
        private const val TAIL_RESERVE = 32
        private const val SEPARATOR = "\n\n"
        private val WHITESPACE = Regex("\\s+")

        internal fun formatDuration(ms: Long): String {
            val totalMinutes = ms.coerceAtLeast(0) / 60_000
            if (totalMinutes == 0L) return "меньше минуты"
            val days = totalMinutes / (24 * 60)
            val hours = totalMinutes / 60 % 24
            val minutes = totalMinutes % 60
            return buildList {
                if (days > 0) add("$days д")
                if (hours > 0) add("$hours ч")
                if (minutes > 0) add("$minutes мин")
            }.joinToString(" ")
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.BotCommandHandlerTest"`
Expected: BUILD SUCCESSFUL, 16 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zaplatin/smsforwarder/DeviceStatusProvider.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/BotCommandHandler.kt \
  app/src/test/java/dev/zaplatin/smsforwarder/BotCommandHandlerTest.kt
git commit -m "app: add bot command handler

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `OffsetStore` and `BotUpdatePoller`

**Files:**
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/OffsetStore.kt`
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/BotUpdatePoller.kt`
- Test: `app/src/test/java/dev/zaplatin/smsforwarder/BotUpdatePollerTest.kt`

**Interfaces:**
- Consumes: `TelegramBotApi`, `BotMessage`, `TelegramApiException` (Task 1); `TelegramClient.escapeMarkdown` (Task 2); `OperationLogger` (existing).
- Produces:
  - `interface OffsetStore { var offset: Long? }`
  - `class SharedPreferencesOffsetStore(context: Context) : OffsetStore`
  - `class BotUpdatePoller(api: TelegramBotApi, handleCommand: suspend (String) -> String, commands: List<Pair<String, String>>, offsetStore: OffsetStore, authorizedChatIds: Set<String>, clock: () -> Long, networkAvailable: Flow<Unit>) { suspend fun run() }`
  - Constants in `BotUpdatePoller.Companion`: `POLL_TIMEOUT_SEC = 50`, `INITIAL_BACKOFF_MS = 5_000L`, `MAX_BACKOFF_MS = 300_000L`, `UNAUTHORIZED_WAIT_MS = 1_800_000L`, `CONFLICT_WAIT_MS = 60_000L`, `FRESHNESS_MS = 300_000L` (all `internal const val`)

- [ ] **Step 1: Create `OffsetStore`**

Create `app/src/main/java/dev/zaplatin/smsforwarder/OffsetStore.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import android.content.Context

/** Next getUpdates offset; survives process restarts so commands are not handled twice. */
interface OffsetStore {
    var offset: Long?
}

class SharedPreferencesOffsetStore(context: Context) : OffsetStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var offset: Long?
        get() = prefs.getLong(KEY_OFFSET, NO_OFFSET).takeIf { it != NO_OFFSET }
        set(value) {
            prefs.edit().putLong(KEY_OFFSET, value ?: NO_OFFSET).apply()
        }

    private companion object {
        const val PREFS_NAME = "bot_prefs"
        const val KEY_OFFSET = "update_offset"
        const val NO_OFFSET = -1L
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/dev/zaplatin/smsforwarder/BotUpdatePollerTest.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotUpdatePollerTest {

    private val api = FakeBotApi()
    private val store = InMemoryOffsetStore()
    private val network = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val handled = mutableListOf<String>()
    private var handler: suspend (String) -> String = { text ->
        handled += text
        "reply:$text"
    }

    @Test
    fun registersCommandsAndAnswersFreshCommand() = runTest {
        api.responses += { listOf(message(1, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(COMMANDS, api.registeredCommands)
        assertEquals(listOf(AUTHORIZED to "reply:/status"), api.sent)
        assertEquals(2L, store.offset)
    }

    @Test
    fun setMyCommandsFailureDoesNotStopPolling() = runTest {
        api.setCommandsFailure = TelegramApiException(500, null, "oops")
        api.responses += { listOf(message(1, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf(AUTHORIZED to "reply:/ping"), api.sent)
    }

    @Test
    fun answersSeveralMessagesInOrder() = runTest {
        api.responses += { listOf(message(1, "/status"), message(2, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf("/status", "/ping"), handled)
        assertEquals(3L, store.offset)
    }

    @Test
    fun ignoresUnauthorizedChat() = runTest {
        api.responses += { listOf(message(1, "/status", chatId = "999")) }

        startPoller()
        runCurrent()

        assertTrue(api.sent.isEmpty())
        assertEquals(2L, store.offset)
    }

    @Test
    fun freshnessWindowIsFiveMinutesInclusive() = runTest {
        api.responses += {
            listOf(
                message(1, "/old", ageMs = BotUpdatePoller.FRESHNESS_MS + 1_000),
                message(2, "/edge", ageMs = BotUpdatePoller.FRESHNESS_MS),
            )
        }

        startPoller()
        runCurrent()

        assertEquals(listOf("/edge"), handled)
    }

    @Test
    fun answersCommandDatedSlightlyInFuture() = runTest {
        api.responses += { listOf(message(1, "/status", ageMs = -30_000)) }

        startPoller()
        runCurrent()

        assertEquals(listOf("/status"), handled)
    }

    @Test
    fun ignoresNonCommandsButAdvancesOffset() = runTest {
        api.responses += { listOf(message(1, "hello"), message(2, null)) }

        startPoller()
        runCurrent()

        assertTrue(handled.isEmpty())
        assertTrue(api.sent.isEmpty())
        assertEquals(3L, store.offset)
    }

    @Test
    fun savesOffsetBeforeHandling() = runTest {
        var offsetSeenByHandler: Long? = null
        handler = {
            offsetSeenByHandler = store.offset
            "ok"
        }
        api.responses += { listOf(message(7, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(8L, offsetSeenByHandler)
    }

    @Test
    fun resumesFromStoredOffset() = runTest {
        store.offset = 42

        startPoller()
        runCurrent()

        assertEquals(42L, api.getUpdatesCalls.single().first)
    }

    @Test
    fun handlerFailureRepliesWithErrorAndContinues() = runTest {
        handler = { text -> if (text == "/status") error("boom") else "ok" }
        api.responses += { listOf(message(1, "/status"), message(2, "/ping")) }

        startPoller()
        runCurrent()

        assertEquals(listOf(AUTHORIZED to "Ошибка: boom", AUTHORIZED to "ok"), api.sent)
    }

    @Test
    fun sendFailureDoesNotStopLoop() = runTest {
        api.sendFailure = TelegramApiException(400, null, "Bad Request")
        api.responses += { listOf(message(1, "/status")) }

        startPoller()
        runCurrent()

        assertEquals(2, api.getUpdatesCalls.size)
    }

    @Test
    fun backoffDoublesAndResetsAfterSuccess() = runTest {
        repeat(3) { api.responses += { throw TelegramApiException(null, null, "offline") } }
        api.responses += { emptyList() }
        api.responses += { throw TelegramApiException(null, null, "offline") }

        startPoller()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(
            listOf(0L, 5_000L, 15_000L, 35_000L, 35_000L, 40_000L),
            api.getUpdatesCalls.map { it.second },
        )
    }

    @Test
    fun backoffIsCappedAtFiveMinutes() = runTest {
        repeat(10) { api.responses += { throw TelegramApiException(null, null, "offline") } }

        startPoller()
        advanceTimeBy(3_000_000)
        runCurrent()

        val waits = api.getUpdatesCalls.map { it.second }.zipWithNext { a, b -> b - a }
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L, 300_000L),
            waits,
        )
    }

    @Test
    fun tooManyRequestsHonorsRetryAfter() = runTest {
        api.responses += { throw TelegramApiException(429, 7, "Too Many Requests") }

        startPoller()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(7_000L, api.getUpdatesCalls[1].second)
    }

    @Test
    fun unauthorizedWaitsThirtyMinutes() = runTest {
        api.responses += { throw TelegramApiException(401, null, "Unauthorized") }

        startPoller()
        advanceTimeBy(BotUpdatePoller.UNAUTHORIZED_WAIT_MS - 1)
        runCurrent()
        assertEquals(1, api.getUpdatesCalls.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, api.getUpdatesCalls.size)
    }

    @Test
    fun conflictWaitsOneMinute() = runTest {
        api.responses += { throw TelegramApiException(409, null, "Conflict") }

        startPoller()
        advanceTimeBy(BotUpdatePoller.CONFLICT_WAIT_MS)
        runCurrent()

        assertEquals(BotUpdatePoller.CONFLICT_WAIT_MS, api.getUpdatesCalls[1].second)
    }

    @Test
    fun networkAvailableEndsWaitEarly() = runTest {
        api.responses += { throw TelegramApiException(null, null, "offline") }

        startPoller()
        runCurrent()
        advanceTimeBy(1_000)
        network.tryEmit(Unit)
        runCurrent()

        assertEquals(listOf(0L, 1_000L), api.getUpdatesCalls.map { it.second })
    }

    @Test
    fun cancellationStopsLoop() = runTest {
        val job = startPoller()
        runCurrent()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    private fun TestScope.startPoller(): Job {
        api.timeSource = { testScheduler.currentTime }
        val poller = BotUpdatePoller(
            api = api,
            handleCommand = { handler(it) },
            commands = COMMANDS,
            offsetStore = store,
            authorizedChatIds = setOf(AUTHORIZED),
            clock = { NOW_MS + testScheduler.currentTime },
            networkAvailable = network,
        )
        return backgroundScope.launch { poller.run() }
    }

    private fun message(id: Long, text: String?, chatId: String = AUTHORIZED, ageMs: Long = 0) =
        BotMessage(updateId = id, chatId = chatId, dateSec = (NOW_MS - ageMs) / 1000, text = text)

    private class FakeBotApi : TelegramBotApi {
        val responses = ArrayDeque<() -> List<BotMessage>>()
        val getUpdatesCalls = mutableListOf<Pair<Long?, Long>>()
        val sent = mutableListOf<Pair<String, String>>()
        var registeredCommands: List<Pair<String, String>>? = null
        var setCommandsFailure: Exception? = null
        var sendFailure: Exception? = null
        var timeSource: () -> Long = { 0L }

        override suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<BotMessage> {
            getUpdatesCalls += offset to timeSource()
            val next = responses.removeFirstOrNull() ?: awaitCancellation()
            return next()
        }

        override suspend fun sendMessage(chatId: String, text: String) {
            sendFailure?.let { throw it }
            sent += chatId to text
        }

        override suspend fun setMyCommands(commands: List<Pair<String, String>>) {
            setCommandsFailure?.let { throw it }
            registeredCommands = commands
        }
    }

    private class InMemoryOffsetStore : OffsetStore {
        override var offset: Long? = null
    }

    private companion object {
        const val AUTHORIZED = "111111111"
        const val NOW_MS = 1_800_000_000_000L
        val COMMANDS = listOf("status" to "состояние телефона")
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.BotUpdatePollerTest"`
Expected: compilation FAIL — `Unresolved reference 'BotUpdatePoller'`.

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/dev/zaplatin/smsforwarder/BotUpdatePoller.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-polls Telegram for bot commands and answers fresh ones from authorized chats.
 * Runs until the calling coroutine is cancelled; errors only pause it.
 */
class BotUpdatePoller(
    private val api: TelegramBotApi,
    private val handleCommand: suspend (String) -> String,
    private val commands: List<Pair<String, String>>,
    private val offsetStore: OffsetStore,
    private val authorizedChatIds: Set<String>,
    private val clock: () -> Long,
    private val networkAvailable: Flow<Unit>,
) {

    suspend fun run() {
        OperationLogger.d(TAG, "Bot polling started")
        try {
            registerCommands()
            var backoffMs = INITIAL_BACKOFF_MS
            while (true) {
                val messages = try {
                    api.getUpdates(offsetStore.offset, POLL_TIMEOUT_SEC)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val waitMs = waitAfter(e, backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    pause(waitMs)
                    continue
                }
                backoffMs = INITIAL_BACKOFF_MS
                for (message in messages) process(message)
            }
        } finally {
            OperationLogger.d(TAG, "Bot polling stopped")
        }
    }

    private suspend fun registerCommands() {
        try {
            api.setMyCommands(commands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "setMyCommands failed: ${e.message}")
        }
    }

    private suspend fun process(message: BotMessage) {
        // Saved before handling: a command is processed at most once, even if we crash mid-way.
        offsetStore.offset = message.updateId + 1
        val text = message.text ?: return
        if (message.chatId !in authorizedChatIds) {
            OperationLogger.w(TAG, "Ignored message from unauthorized chat=${message.chatId}")
            return
        }
        if (clock() - message.dateSec * 1000 > FRESHNESS_MS) {
            OperationLogger.d(TAG, "Skipped stale message update=${message.updateId}")
            return
        }
        if (!text.startsWith("/")) return

        val command = text.trim().substringBefore(' ').substringBefore('\n').substringBefore('@')
        OperationLogger.d(TAG, "Handling $command from chat=${message.chatId}")
        val reply = try {
            handleCommand(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Command $command failed", e)
            "Ошибка: " + TelegramClient.escapeMarkdown(e.message ?: e.javaClass.simpleName)
        }
        try {
            api.sendMessage(message.chatId, reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationLogger.e(TAG, "Failed to reply to $command in chat=${message.chatId}: ${e.message}")
        }
    }

    /** Logs the failure and returns how long to wait before the next getUpdates. */
    private fun waitAfter(error: Exception, backoffMs: Long): Long {
        val apiError = error as? TelegramApiException
        val waitMs = when (apiError?.code) {
            401 -> UNAUTHORIZED_WAIT_MS
            409 -> CONFLICT_WAIT_MS
            429 -> apiError.retryAfterSec?.let { it * 1000L } ?: backoffMs
            else -> backoffMs
        }
        val reason = when (apiError?.code) {
            401 -> "invalid bot token"
            409 -> "another getUpdates consumer or webhook is active"
            null -> "network error: ${error.message}"
            else -> "HTTP ${apiError.code}: ${apiError.description}"
        }
        OperationLogger.e(TAG, "getUpdates failed ($reason), retrying in ${waitMs / 1000} s")
        return waitMs
    }

    /** Waits [ms], or less if the network comes back. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { networkAvailable.first() }
    }

    companion object {
        internal const val POLL_TIMEOUT_SEC = 50
        internal const val INITIAL_BACKOFF_MS = 5_000L
        internal const val MAX_BACKOFF_MS = 300_000L
        internal const val UNAUTHORIZED_WAIT_MS = 1_800_000L
        internal const val CONFLICT_WAIT_MS = 60_000L
        internal const val FRESHNESS_MS = 300_000L
        private const val TAG = "BotUpdatePoller"
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "dev.zaplatin.smsforwarder.BotUpdatePollerTest"`
Expected: BUILD SUCCESSFUL, 18 tests passed.

Then `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/zaplatin/smsforwarder/OffsetStore.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/BotUpdatePoller.kt \
  app/src/test/java/dev/zaplatin/smsforwarder/BotUpdatePollerTest.kt
git commit -m "app: add Telegram bot update poller

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Android wiring — SIM reader, status provider, service, manifest

**Files:**
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/SimInfoReader.kt`
- Create: `app/src/main/java/dev/zaplatin/smsforwarder/AndroidDeviceStatusProvider.kt`
- Modify: `app/src/main/java/dev/zaplatin/smsforwarder/SmsDao.kt`
- Modify: `app/src/main/java/dev/zaplatin/smsforwarder/MainActivity.kt` (SIM loading, ~lines 125-205)
- Modify: `app/src/main/java/dev/zaplatin/smsforwarder/ForwarderService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `DeviceStatusProvider`, `BatteryStatus`, `PlugType`, `SimInfo`, `SimsResult`, `BotCommandHandler`, `BotCommandHandler.COMMANDS` (Task 3); `BotUpdatePoller`, `SharedPreferencesOffsetStore` (Task 4); `TelegramClient`, `TelegramClient.authorizedChatIds` (Task 2); `BatteryAlerts.percentFrom(intent: Intent): Int?` (existing).
- Produces:
  - `object SimInfoReader { fun hasPhoneStatePermission(context: Context): Boolean; fun read(context: Context): List<SimInfo> }`
  - `class AndroidDeviceStatusProvider(context: Context) : DeviceStatusProvider`
  - `SmsDao.getLatest(limit: Int): List<SmsMessageEntity>`
  - `ForwarderService.startedAt: Long?` (companion, read-only outside the service)

This task is Android glue with no JVM-testable logic; it is verified by build, lint, the unit-test suite, and the device checks in Step 9.

- [ ] **Step 1: Add `getLatest` to `SmsDao`**

In `SmsDao.kt`, after `getById`:

```kotlin
    @Query("SELECT * FROM sms_messages ORDER BY created_at DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<SmsMessageEntity>
```

- [ ] **Step 2: Create `SimInfoReader`**

Create `app/src/main/java/dev/zaplatin/smsforwarder/SimInfoReader.kt` (logic moved verbatim from `MainActivity.loadSimInfo`):

```kotlin
package dev.zaplatin.smsforwarder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Reads active SIM cards. Call off the main thread. */
object SimInfoReader {

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun read(context: Context): List<SimInfo> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        val includeNumbers = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED
        val entries = subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        return entries.mapIndexed { index, info ->
            val label = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${index + 1}"
            val number = if (includeNumbers) {
                info.number?.takeIf { it.isNotBlank() }
                    ?: telephonyManager?.line1Number?.takeIf { !it.isNullOrBlank() }
            } else {
                null
            }
            SimInfo(label, number)
        }
    }
}
```

- [ ] **Step 3: Use `SimInfoReader` in `MainActivity`**

In `MainActivity.kt`:
1. In `refreshSimInfo()`, replace `val sims = loadSimInfo()` with:

   ```kotlin
               val sims = withContext(Dispatchers.IO) { SimInfoReader.read(applicationContext) }
   ```

   and replace the mapping line

   ```kotlin
                       val number = if (info.number.isNotBlank()) info.number else getString(R.string.sim_number_unknown)
   ```

   with

   ```kotlin
                       val number = info.number?.takeIf { it.isNotBlank() } ?: getString(R.string.sim_number_unknown)
   ```

2. Delete `loadSimInfo()` (with its `@SuppressLint`), `private data class SimDisplayInfo`, and `hasPhoneNumberPermission()`.
3. Remove the now-unused imports `android.annotation.SuppressLint`, `android.telephony.SubscriptionManager`, `android.telephony.TelephonyManager`.
4. Check: `grep -n "hasPhoneNumberPermission\|SimDisplayInfo\|loadSimInfo" app/src/main/java/dev/zaplatin/smsforwarder/MainActivity.kt` prints nothing.

- [ ] **Step 4: Create `AndroidDeviceStatusProvider`**

Create `app/src/main/java/dev/zaplatin/smsforwarder/AndroidDeviceStatusProvider.kt`:

```kotlin
package dev.zaplatin.smsforwarder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class AndroidDeviceStatusProvider(private val context: Context) : DeviceStatusProvider {

    private val dao get() = AppDatabase.getInstance(context).smsDao()

    override fun battery(): BatteryStatus? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val percent = BatteryAlerts.percentFrom(intent) ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugType = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            else -> null
        }
        return BatteryStatus(percent, isCharging, plugType)
    }

    override fun sims(): SimsResult =
        if (SimInfoReader.hasPhoneStatePermission(context)) {
            SimsResult.Available(SimInfoReader.read(context))
        } else {
            SimsResult.PermissionMissing
        }

    override suspend fun smsCounts(): Pair<Int, Int> = dao.countAll() to dao.countNotSent()

    override suspend fun lastSms(limit: Int): List<SmsMessageEntity> = dao.getLatest(limit)

    override fun serviceStartedAt(): Long? = ForwarderService.startedAt

    override fun now(): Long = System.currentTimeMillis()
}
```

- [ ] **Step 5: Start the poller from `ForwarderService`**

In `ForwarderService.kt`:

1. Add imports:

   ```kotlin
   import android.net.ConnectivityManager
   import android.net.Network
   import kotlinx.coroutines.flow.MutableSharedFlow
   ```

2. Add fields after `batteryReceiver`:

   ```kotlin
       // No replay: the callback's initial onAvailable fires before any backoff and must not end it.
       private val networkAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

       private val networkCallback = object : ConnectivityManager.NetworkCallback() {
           override fun onAvailable(network: Network) {
               networkAvailable.tryEmit(Unit)
           }
       }
   ```

3. At the end of `onCreate()` (after `ContextCompat.registerReceiver(...)`):

   ```kotlin
           startedAt = System.currentTimeMillis()
           getSystemService(ConnectivityManager::class.java)
               .registerDefaultNetworkCallback(networkCallback)
           startBotPolling()
   ```

4. Add the method after `onBind`:

   ```kotlin
       private fun startBotPolling() {
           val poller = BotUpdatePoller(
               api = TelegramClient,
               handleCommand = BotCommandHandler(AndroidDeviceStatusProvider(applicationContext))::handle,
               commands = BotCommandHandler.COMMANDS,
               offsetStore = SharedPreferencesOffsetStore(applicationContext),
               authorizedChatIds = TelegramClient.authorizedChatIds,
               clock = System::currentTimeMillis,
               networkAvailable = networkAvailable,
           )
           scope.launch { poller.run() }
       }
   ```

5. In `onDestroy()`, before `scope.cancel()`:

   ```kotlin
           runCatching {
               getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
           }
           startedAt = null
   ```

6. In the `companion object`, add:

   ```kotlin
           /** When the service entered the foreground; null while it is not running. */
           @Volatile
           var startedAt: Long? = null
               private set
   ```

- [ ] **Step 6: Declare `ACCESS_NETWORK_STATE`**

In `app/src/main/AndroidManifest.xml`, after `<uses-permission android:name="android.permission.INTERNET" />`:

```xml
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 7: Build, test, lint**

Run: `./gradlew assembleDebug testDebugUnitTest lintDebug`
Expected: BUILD SUCCESSFUL; no lint errors (existing warnings are acceptable; no new warnings in the files of this task other than `InlinedApi` for `READ_PHONE_NUMBERS`, which `MainActivity` already had).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/zaplatin/smsforwarder/SimInfoReader.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/AndroidDeviceStatusProvider.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/SmsDao.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/MainActivity.kt \
  app/src/main/java/dev/zaplatin/smsforwarder/ForwarderService.kt \
  app/src/main/AndroidManifest.xml
git commit -m "app: answer Telegram bot commands from ForwarderService

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 9: Verify on the device**

With the Samsung connected (`adb devices` shows it):

1. `./gradlew installDebug`, open the app once, then run `adb logcat -s BotUpdatePoller TelegramClient ForwarderService`.
   Expected log: `Bot polling started`.
2. In Telegram, open the bot: the "/" menu lists status, battery, last, sims, ping, help.
3. Send each command. Expected: a reply within ~2 s; log `Handling /status from chat=…` per command. `/last 3` shows 3 SMS newest first; `/last abc` shows the usage line.
4. From a Telegram account that is **not** in `telegram.chatIds`, send `/status`. Expected: no reply; log `Ignored message from unauthorized chat=…`.
5. Turn on airplane mode, send `/ping` from Telegram (another device), wait 6 minutes, turn airplane mode off, then send `/ping` again. Expected: only the second `/ping` is answered; log `Skipped stale message`.
6. Turn on Power saving, lock the screen for a few minutes, send `/status`. Expected: answered within a few seconds.

---

### Task 6: Document the bot commands

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: final behavior from Tasks 1–5.
- Produces: user documentation only.

- [ ] **Step 1: Add a "Bot commands" section**

In `README.md`, insert after the "Message format" section:

```markdown
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
```

- [ ] **Step 2: Update the feature list and structure**

- In "Features", add a bullet: `- Read-only Telegram bot commands (`/status`, `/battery`, `/last`, `/sims`, `/ping`, `/help`), see [Bot commands](#bot-commands).`
- In "Requirements → Permissions", add `ACCESS_NETWORK_STATE`.
- In "Structure", add:
  - `- `BotUpdatePoller` — long-polls `getUpdates`, filters by chat and freshness, backs off on errors; offset kept by `SharedPreferencesOffsetStore`.`
  - `- `BotCommandHandler` — builds command replies from `DeviceStatusProvider` (`AndroidDeviceStatusProvider` on the device).`
  - `- `SimInfoReader` — reads active SIM cards for the UI and the bot.`
- Update the last line about unit tests to: `Unit tests (`app/src/test`) cover battery alert thresholds, received-time formatting, Bot API parsing, bot command replies, and the update poller.`

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "app: document Telegram bot commands

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
