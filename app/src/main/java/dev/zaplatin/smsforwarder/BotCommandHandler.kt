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
