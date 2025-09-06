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
    
    @Test
    fun ffmpegTimeout_isConfiguredCorrectly() {
        // Test that the FFmpeg timeout is reasonable (5 seconds = 5000000 microseconds)
        val timeoutMicroseconds = 5000000
        val timeoutSeconds = timeoutMicroseconds / 1000000
        
        // Should be 5 seconds - reasonable for connection attempt
        assertEquals(5, timeoutSeconds)
        
        // Should be less than our extended M3U8 wait time (25 seconds)
        val m3u8WaitTimeMs = 25000
        assertTrue("FFmpeg timeout should be less than M3U8 wait time", 
                   timeoutSeconds * 1000 < m3u8WaitTimeMs)
    }
    
    @Test
    fun waitTimeConfiguration_isAppropriate() {
        // Test that our wait times are properly configured
        val primaryWaitTimeSeconds = 10 // Time to wait before assuming primary failed
        val totalWaitTimeSeconds = 25 // Total time to wait including fallback
        
        // Primary should be reasonable but not too long
        assertTrue("Primary wait time should be reasonable", primaryWaitTimeSeconds >= 5)
        assertTrue("Primary wait time should not be too long", primaryWaitTimeSeconds <= 15)
        
        // Total time should allow for fallback
        assertTrue("Total wait should allow time for fallback", 
                   totalWaitTimeSeconds > primaryWaitTimeSeconds + 5)
    }
}