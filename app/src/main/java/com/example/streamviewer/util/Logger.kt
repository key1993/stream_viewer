package com.example.streamviewer.util

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Logger {
    private var logFile: File? = null
    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun initialize(file: File) {
        lock.withLock {
            logFile = file
        }
    }

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message\n"
        // Logcat
        Log.d("StreamViewer", message)
        // File
        lock.withLock {
            val file = logFile ?: return
            try {
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) {
                // Swallow file I/O errors to avoid crashing logging
            }
        }
    }
}


