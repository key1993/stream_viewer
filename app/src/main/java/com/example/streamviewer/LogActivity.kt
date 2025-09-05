package com.example.streamviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import java.io.OutputStream
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.streamviewer.databinding.ActivityLogBinding
import java.io.File

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var logFile: File
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadAndDisplayLogs()
            handler.postDelayed(this, 2000) // Refresh every 2 seconds
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get log file path from intent
        val logFilePath = intent.getStringExtra("log_file_path")
        if (logFilePath != null) {
            logFile = File(logFilePath)
            loadAndDisplayLogs()
            startAutoRefresh()
        }

        // Setup buttons
        binding.btnRefresh.setOnClickListener {
            loadAndDisplayLogs()
        }

        binding.btnCopy.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.btnClear.setOnClickListener {
            clearLogs()
        }

        binding.btnSaveDownloads.setOnClickListener {
            saveToDownloads()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoRefresh()
    }

    private fun startAutoRefresh() {
        handler.post(refreshRunnable)
    }

    private fun stopAutoRefresh() {
        handler.removeCallbacks(refreshRunnable)
    }

    private fun loadAndDisplayLogs() {
        try {
            if (logFile.exists()) {
                val logs = logFile.readText()
                if (logs.isNotBlank()) {
                    val lastRefresh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    binding.tvLogs.text = "Last refresh: $lastRefresh\n\n$logs"
                    binding.tvLogs.post {
                        binding.scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                    }
                } else {
                    binding.tvLogs.text = "No logs yet. Start the stream to see debug information.\n\nLast refresh: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                }
            } else {
                binding.tvLogs.text = "Log file not found: ${logFile.absolutePath}"
            }
        } catch (e: Exception) {
            binding.tvLogs.text = "Error reading logs: ${e.message}"
        }
    }

    private fun copyLogsToClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("StreamViewer Logs", binding.tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        try {
            logFile.writeText("")
            binding.tvLogs.text = "Logs cleared!"
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloads() {
        try {
            if (!logFile.exists()) {
                Toast.makeText(this, "Log file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val fileName = "streamviewer_log_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date()) + ".txt"
            val mimeType = "text/plain"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Failed to create download entry")

                resolver.openOutputStream(uri).use { out ->
                    logFile.inputStream().use { input ->
                        input.copyTo(out as OutputStream)
                    }
                }
                Toast.makeText(this, "Saved to Downloads as $fileName", Toast.LENGTH_LONG).show()
            } else {
                // Pre-Android 10 fallback: copy to app's external files and notify user to share
                val dest = java.io.File(getExternalFilesDir(null), fileName)
                logFile.copyTo(dest, overwrite = true)
                Toast.makeText(this, "Saved copy at: ${dest.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
