package dev.zaplatin.smsforwarder

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OperationLogger {
    private const val TAG = "SmsForwarder"
    private const val MAX_LOGS = 200
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun d(tag: String, message: String) = record(Log.DEBUG, tag, message, null)

    fun w(tag: String, message: String) = record(Log.WARN, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        record(Log.ERROR, tag, message, throwable)

    private fun record(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val time = formatter.format(Date())
        val entry = "$time [$tag] $message"
        _logs.update { list ->
            val updated = list + entry
            if (updated.size > MAX_LOGS) updated.takeLast(MAX_LOGS) else updated
        }
        if (throwable != null) {
            Log.println(priority, tag, "$message\n${Log.getStackTraceString(throwable)}")
        } else {
            Log.println(priority, tag, message)
        }
    }
}
