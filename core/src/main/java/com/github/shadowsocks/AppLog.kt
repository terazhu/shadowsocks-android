package com.github.shadowsocks

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object AppLog {
    private val logs = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_LOGS = 500

    fun log(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $level/$tag: $message"
        logs.add(logEntry)
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log("E", tag, msg)
    }

    fun logWarn(tag: String, message: String) {
        log("W", tag, message)
    }

    fun logInfo(tag: String, message: String) {
        log("I", tag, message)
    }

    fun logDebug(tag: String, message: String) {
        log("D", tag, message)
    }

    fun getLogs(): List<String> = logs.toList()

    fun clearLogs() {
        logs.clear()
    }
}
