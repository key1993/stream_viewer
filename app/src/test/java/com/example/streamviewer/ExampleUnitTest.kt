package com.example.streamviewer

import org.junit.Test
import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
    
    @Test
    fun udpUrlSanitization_removesAtPrefix() {
        // Test the URL sanitization logic for UDP URLs
        val inputUrl = "udp://@239.255.0.1:1234?ttl=1"
        val expectedOutput = "udp://239.255.0.1:1234?ttl=1"
        
        // Simulate the sanitization logic from MainActivity
        var result = inputUrl.trim()
        if (result.startsWith("udp://@")) {
            result = "udp://" + result.removePrefix("udp://@")
        }
        
        assertEquals(expectedOutput, result)
    }
    
    @Test
    fun udpUrlSanitization_leavesNormalUrlsUntouched() {
        val inputUrl = "udp://192.168.1.100:5000"
        val expectedOutput = "udp://192.168.1.100:5000"
        
        // Simulate the sanitization logic from MainActivity
        var result = inputUrl.trim()
        if (result.startsWith("udp://@")) {
            result = "udp://" + result.removePrefix("udp://@")
        }
        
        assertEquals(expectedOutput, result)
    }
    
    @Test
    fun ffmpegUrlPreparation_addsAtPrefixForMulticast() {
        val sanitizedUrl = "udp://239.255.0.1:1234?ttl=1"
        val expectedFFmpegUrl = "udp://@239.255.0.1:1234?ttl=1"
        
        // Simulate the FFmpeg URL preparation logic
        val ffmpegInput = if (sanitizedUrl.startsWith("udp://@")) {
            sanitizedUrl
        } else if (sanitizedUrl.startsWith("udp://")) {
            sanitizedUrl.replace("udp://", "udp://@")
        } else {
            sanitizedUrl
        }
        
        assertEquals(expectedFFmpegUrl, ffmpegInput)
    }
}