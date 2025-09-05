package com.example.streamviewer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DataSource
import androidx.media3.common.C
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import com.example.streamviewer.net.SimpleUdpDataSource
import com.example.streamviewer.databinding.ActivityMainBinding
import com.example.streamviewer.util.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Media factories for different protocols
    private var udpDataSourceFactory: SimpleUdpDataSource.Factory? = null
    private var defaultMediaSourceFactory: DefaultMediaSourceFactory? = null
    private var ffmpegSessionId: Long? = null

    // Logging
    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize logging
        setupLogging()
        Logger.initialize(logFile)

        // Add share log button
        binding.btnPlay.setOnLongClickListener {
            shareLogFile()
            true
        }

        binding.btnPlay.setOnClickListener {
            val url = binding.etStreamUrl.text.toString()
            if (url.isNotBlank()) {
                hideKeyboard()
                if (player == null) {
                    initializePlayer()
                }
                playStream(url)
            } else {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLogs.setOnClickListener {
            val intent = android.content.Intent(this, LogActivity::class.java)
            intent.putExtra("log_file_path", logFile.absolutePath)
            startActivity(intent)
        }
    }

    private fun initializePlayer() {
        // Create extractors factory for MPEG-PS (Program Stream) - OBS is sending PS not TS!
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_HLS)
            .setTsExtractorTimestampSearchBytes(600_000)
        writeLog("Using DefaultExtractorsFactory with PS/TS support for OBS stream")

        // Use our improved UDP data source for multicast/unicast UDP URLs
        val udpFactory = SimpleUdpDataSource.Factory(applicationContext)

        // Standard renderers, allow extension decoders
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Store factories for later use
        this.udpDataSourceFactory = udpFactory
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(applicationContext)
        this.defaultMediaSourceFactory = DefaultMediaSourceFactory(defaultDataSourceFactory, extractorsFactory)
        writeLog("MediaSourceFactory configured with default + UDP support")

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()

        writeLog("ExoPlayer initialized successfully")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicastLockTag")
        multicastLock?.setReferenceCounted(true)

        binding.playerView.player = player
        writeLog("PlayerView configured with player")

        // PlayerView is configured - will debug surface issues through other means

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                writeLog("Playback state changed to: $stateName")
                when (playbackState) {
                    Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                    Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.progressBar.visibility = View.GONE
                val errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed"
                    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "Invalid content type - expected MPEG-TS"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Stream format error - ensure OBS is sending MPEG-TS"
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Decoder initialization failed - check codec support"
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Decoder query failed - codec not supported"
                    3003 -> "Source error - likely MPEG-TS parsing failed"
                    else -> "Playback error: ${error.message}"
                }
                writeLog("🎯 PLAYBACK ERROR DETAILS:")
                writeLog("Error code: ${error.errorCode}")
                writeLog("Error message: ${error.message}")
                writeLog("Error cause: ${error.cause?.message}")
                writeLog("Error type: ${error.cause?.javaClass?.simpleName}")

                // Log full stack trace
                error.cause?.let { cause ->
                    writeLog("🔍 FULL ERROR STACK TRACE:")
                    cause.stackTrace.forEach { element ->
                        writeLog("  at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                    }
                }

                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                writeLog("Tracks changed: ${tracks.groups.size} track groups")
                for (i in 0 until tracks.groups.size) {
                    val group = tracks.groups[i]
                    writeLog("Track group $i: ${group.length} tracks, type: ${group.type}, selected: ${group.isSelected}")
                    for (j in 0 until group.length) {
                        val track = group.getTrackFormat(j)
                        writeLog("  Track $j: ${track.sampleMimeType}, ${track.width}x${track.height}")
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                writeLog("🎬 FIRST FRAME RENDERED! Video should be visible now!")
                binding.progressBar.visibility = View.GONE
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                writeLog("📺 Video size changed: ${videoSize.width}x${videoSize.height}, rotation: ${videoSize.unappliedRotationDegrees}°, ratio: ${videoSize.pixelWidthHeightRatio}")
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                writeLog("🖼️ Surface size changed: ${width}x${height}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                writeLog("▶️ Is playing changed: $isPlaying")
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                val reason = when (playbackSuppressionReason) {
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "NONE"
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                    Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "UNSUITABLE_AUDIO"
                    else -> "UNKNOWN"
                }
                writeLog("🔇 Playback suppression: $reason")
            }

            override fun onCues(cues: MutableList<androidx.media3.common.text.Cue>) {
                if (cues.isNotEmpty()) {
                    writeLog("💬 Cues received: ${cues.size} cues")
                }
            }
        })
    }

    private fun playStream(url: String) {
        multicastLock?.acquire()

        writeLog("=== STARTING PLAYBACK ===")
        writeLog("Input URL: '$url'")

        // Check codec capabilities before starting
        checkCodecCapabilities()

        val sanitized = sanitizeObsUdpUrl(url)
        writeLog("Sanitized URL: '$sanitized'")

        val useFfmpeg = binding.cbFfmpegNormalize.isChecked
        val playUri = if (useFfmpeg) {
            // Prefer HLS output for robustness (file-based playback)
            val ffmpegInput = if (url.startsWith("udp://@")) url else url.replace("udp://", "udp://@")
            startFfmpegToHls(ffmpegInput)
        } else sanitized

        // Build a MediaItem without explicit MIME type to let extractor auto-detect
        val mediaItem = MediaItem.Builder()
            .setUri(playUri)
            .build()

        writeLog("MediaItem URI: '${mediaItem.localConfiguration?.uri}'")

        // Set media item directly (player will use appropriate factory)
        if (useFfmpeg && playUri.startsWith("file://") && playUri.endsWith(".m3u8")) {
            writeLog("Setting HLS media source")
            val hlsFactory = HlsMediaSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(this)
            )
            val hlsSource = hlsFactory.createMediaSource(mediaItem)
            player?.setMediaSource(hlsSource)
        } else {
            writeLog("Setting media item directly")
            player?.setMediaItem(mediaItem)
        }
        player?.prepare()
        player?.playWhenReady = true
        writeLog("Player prepared, playWhenReady: ${player?.playWhenReady}")
    }

    private fun startFfmpegToHls(inputUrl: String): String {
        stopFfmpeg()
        val hlsDir = File(getExternalFilesDir(null), "hls")
        hlsDir.mkdirs()
        val m3u8 = File(hlsDir, "stream.m3u8").absolutePath
        val fileUrl = "file://$m3u8"

        // Delete existing m3u8 file to ensure we wait for new one
        val m3u8File = File(m3u8)
        if (m3u8File.exists()) {
            m3u8File.delete()
            writeLog("Deleted existing m3u8 file")
        }

        // Primary: copy + bitstream filter (no encode)
        val copyCmd = (
            "-hide_banner -nostdin -fflags nobuffer -flags low_delay " +
            "-i '" + inputUrl + "' " +
            "-c:v copy -bsf:v h264_metadata=aud=insert+level=3.1 -bsf:v dump_extra " +
            "-hls_time 1 -hls_list_size 6 -hls_flags delete_segments+append_list " +
            "-y '" + m3u8 + "'"
        )
        writeLog("FFmpeg HLS primary command: $copyCmd")
        val session = FFmpegKit.executeAsync(copyCmd) { session ->
            val rc = session.returnCode
            if (ReturnCode.isSuccess(rc)) {
                writeLog("FFmpeg HLS (copy) finished successfully")
            } else if (ReturnCode.isCancel(rc)) {
                writeLog("FFmpeg HLS (copy) cancelled")
            } else {
                writeLog("FFmpeg HLS (copy) failed: code=$rc\n${'$'}{session.allLogsAsString}")
                // Fallback: transcode baseline
                val x264Cmd = (
                    "-hide_banner -nostdin -fflags nobuffer -flags low_delay " +
                    "-i '" + inputUrl + "' " +
                    "-vf scale=1280:720 -pix_fmt yuv420p -c:v libx264 -preset ultrafast -tune zerolatency " +
                    "-profile:v baseline -level:v 3.1 -g 30 -keyint_min 30 " +
                    "-x264-params scenecut=0:ref=1:bframes=0:aud=1:repeat-headers=1:force-cfr=1 " +
                    "-hls_time 1 -hls_list_size 6 -hls_flags delete_segments+append_list " +
                    "-y '" + m3u8 + "'"
                )
                writeLog("FFmpeg HLS fallback command: $x264Cmd")
                val s2 = FFmpegKit.executeAsync(x264Cmd) { s2 ->
                    val rc2 = s2.returnCode
                    if (ReturnCode.isSuccess(rc2)) {
                        writeLog("FFmpeg HLS (x264) finished successfully")
                    } else if (ReturnCode.isCancel(rc2)) {
                        writeLog("FFmpeg HLS (x264) cancelled")
                    } else {
                        writeLog("FFmpeg HLS (x264) failed: code=$rc2\n${'$'}{s2.allLogsAsString}")
                    }
                }
                ffmpegSessionId = s2.sessionId
            }
        }
        ffmpegSessionId = session.sessionId

        // Wait for m3u8 file to be created by FFmpeg
        writeLog("Waiting for m3u8 file to be created...")
        waitForM3u8File(m3u8File)

        return fileUrl
    }

    private fun waitForM3u8File(m3u8File: File) {
        val maxWaitTimeMs = 10000 // 10 seconds timeout
        val checkIntervalMs = 500L // Check every 500ms
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            if (m3u8File.exists() && m3u8File.length() > 0) {
                writeLog("✅ m3u8 file created: ${m3u8File.absolutePath} (${m3u8File.length()} bytes)")
                return
            }
            try {
                Thread.sleep(checkIntervalMs)
            } catch (e: InterruptedException) {
                writeLog("⚠️ Wait for m3u8 interrupted")
                break
            }
        }
        writeLog("⚠️ Timeout waiting for m3u8 file creation (waited ${System.currentTimeMillis() - startTime}ms)")
    }

    private fun stopFfmpeg() {
        ffmpegSessionId?.let { id ->
            writeLog("Stopping FFmpeg session: $id")
            try { com.arthenica.ffmpegkit.FFmpegKit.cancel(id) } catch (_: Exception) {}
        }
        ffmpegSessionId = null
    }

    private fun checkCodecCapabilities() {
        writeLog("🔍 Checking codec capabilities...")
        try {
            val mediaCodecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val codecs = mediaCodecList.codecInfos

            val videoCodecs = mutableListOf<String>()
            val audioCodecs = mutableListOf<String>()

            for (codec in codecs) {
                if (codec.isEncoder) continue

                val types = codec.supportedTypes
                for (type in types) {
                    when {
                        type.startsWith("video/") -> videoCodecs.add("$type (${codec.name})")
                        type.startsWith("audio/") -> audioCodecs.add("$type (${codec.name})")
                    }
                }
            }

            writeLog("📹 Supported video codecs:")
            videoCodecs.forEach { writeLog("  $it") }

            writeLog("🔊 Supported audio codecs:")
            audioCodecs.forEach { writeLog("  $it") }

            // Check specifically for MPEG-1 video support
            val hasMpeg1 = videoCodecs.any { it.contains("mpeg") || it.contains("MPEG") }
            writeLog("🎯 MPEG-1 Video support: $hasMpeg1")

            val hasMp2 = audioCodecs.any { it.contains("mp2") || it.contains("mpeg") || it.contains("MPEG") }
            writeLog("🎵 MP2 Audio support: $hasMp2")

        } catch (e: Exception) {
            writeLog("❌ Failed to check codec capabilities: ${e.message}")
        }
    }

    private fun sanitizeObsUdpUrl(input: String): String {
        writeLog("sanitizeObsUdpUrl input: '$input'")
        var result = input.trim()
        writeLog("After trim: '$result'")
        
        // OBS/FFmpeg sometimes uses "udp://@group:port" or "udp://local@dest:port".
        // Remove a leading '@' after the scheme so it's a standard URI.
        if (result.startsWith("udp://@")) {
            val before = result
            result = "udp://" + result.removePrefix("udp://@")
            writeLog("Removed @ prefix: '$before' -> '$result'")
        }
        
        writeLog("sanitizeObsUdpUrl output: '$result'")
        return result
    }

    private fun releasePlayer() {
        player?.release()
        player = null

        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etStreamUrl.windowToken, 0)
    }

    // Activity Lifecycle Management
    override fun onStart() {
        super.onStart()
        if (Util.SDK_INT >= 24) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if ((Util.SDK_INT < 24 || player == null)) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT < 24) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT >= 24) {
            releasePlayer()
        }
    }

    // Logging Methods
    private fun setupLogging() {
        try {
            val logDir = File(getExternalFilesDir(null), "logs")
            logDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            logFile = File(logDir, "streamviewer_$timestamp.log")

            writeLog("=== StreamViewer Log Started ===")
            writeLog("Device: ${android.os.Build.MODEL} (${android.os.Build.VERSION.RELEASE})")
            writeLog("App Version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            writeLog("Log File: ${logFile.absolutePath}")
            writeLog("")
        } catch (e: Exception) {
            Log.e("StreamViewer", "Failed to setup logging", e)
        }
    }

    private fun writeLog(message: String) {
        try {
            val timestamp = dateFormat.format(Date())
            val logMessage = "[$timestamp] $message\n"

            // Write to Android log
            Log.d("StreamViewer", message)

            // Write to file
            FileWriter(logFile, true).use { writer ->
                writer.write(logMessage)
            }
        } catch (e: Exception) {
            Log.e("StreamViewer", "Failed to write log", e)
        }
    }

    private fun shareLogFile() {
        try {
            if (!logFile.exists()) {
                Toast.makeText(this, "Log file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "StreamViewer Debug Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share Log File"))
            writeLog("Log file shared by user")
        } catch (e: Exception) {
            Log.e("StreamViewer", "Failed to share log file", e)
            Toast.makeText(this, "Failed to share log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}