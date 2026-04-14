package com.karoo.lupinecontrols.extension

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object BleDiagnosticLog {
    private const val MAX_ENTRIES = 400
    private val lock = Any()
    private val entries = ArrayDeque<String>()

    fun debug(tag: String, message: String) {
        append("D", tag, message)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let {
            val suffix = it.message?.takeIf { text -> text.isNotBlank() }?.let { text -> ": $text" } ?: ""
            "$message | ${it::class.java.simpleName}$suffix"
        } ?: message
        append("W", tag, detail)
    }

    fun snapshotText(): String = synchronized(lock) {
        entries.joinToString(separator = "\n")
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            while (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast("$timestamp $level/$tag $message")
        }
    }
}