package com.example.xray

import com.example.core.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL")
}

object XrayLogManager {

    private const val MAX_LOGS = 500
    private val logQueue = ArrayDeque<String>(MAX_LOGS + 20)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @Synchronized
    fun appendLog(message: String, tag: String = "XRAY") {
        log(tag = tag, message = message, level = LogLevel.INFO)
    }

    @Synchronized
    fun d(tag: String, message: String) {
        log(tag = tag, message = message, level = LogLevel.DEBUG)
    }

    @Synchronized
    fun i(tag: String, message: String) {
        log(tag = tag, message = message, level = LogLevel.INFO)
    }

    @Synchronized
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.WARN, throwable = throwable)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.ERROR, throwable = throwable)
    }

    @Synchronized
    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.FATAL, throwable = throwable)
    }

    @Synchronized
    fun log(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        throwable: Throwable? = null
    ) {
        val timestamp = dateFormat.format(Date())
        val sanitizedMsg = SecretRedactor.redact(message)
        val levelTag = if (level != LogLevel.INFO) " [${level.label}]" else ""
        val mainLine = "[$timestamp]$levelTag [$tag] $sanitizedMsg"

        addEntry(mainLine)

        if (throwable != null) {
            val trace = SecretRedactor.formatThrowable(throwable, maxFrames = 8)
            for (line in trace.lines()) {
                if (line.isNotBlank()) {
                    addEntry("[$timestamp] [${level.label}] [$tag]   $line")
                }
            }
        }

        _logsFlow.value = ArrayList(logQueue)
    }

    private fun addEntry(entry: String) {
        if (logQueue.size >= MAX_LOGS) {
            logQueue.pollFirst()
        }
        logQueue.addLast(entry)
    }

    @Synchronized
    fun getLogs(): List<String> = ArrayList(logQueue)

    @Synchronized
    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
    }
}

