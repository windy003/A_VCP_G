package com.videoplayerapp.utils

import com.videoplayerapp.model.SubtitleItem
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

object SubtitleParser {
    
    private val SRT_TIME_PATTERN = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
    )
    
    private val VTT_TIME_PATTERN = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})"
    )
    
    fun parseSubtitles(inputStream: InputStream): List<SubtitleItem> {
        try {
            // Mark the stream to detect format
            if (!inputStream.markSupported()) {
                // If mark is not supported, we need to read the content differently
                val content = inputStream.bufferedReader().use { it.readText() }
                if (content.trim().isEmpty()) {
                    throw IllegalArgumentException("Subtitle file is empty")
                }
                
                val format = detectFormat(content)
                android.util.Log.d("SubtitleParser", "Detected format: $format")
                
                return when (format) {
                    SubtitleFormat.VTT -> parseVTT(ByteArrayInputStream(content.toByteArray()))
                    SubtitleFormat.SRT -> parseSRT(ByteArrayInputStream(content.toByteArray()))
                }
            } else {
                inputStream.mark(1024)
                val header = ByteArray(1024)
                val bytesRead = inputStream.read(header)
                if (bytesRead <= 0) {
                    throw IllegalArgumentException("Subtitle file is empty or cannot be read")
                }
                inputStream.reset()
                
                val headerString = String(header, 0, bytesRead, Charsets.UTF_8)
                val format = detectFormat(headerString)
                android.util.Log.d("SubtitleParser", "Detected format: $format")
                
                return when (format) {
                    SubtitleFormat.VTT -> parseVTT(inputStream)
                    SubtitleFormat.SRT -> parseSRT(inputStream)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SubtitleParser", "Error parsing subtitles", e)
            throw e
        }
    }
    
    private fun detectFormat(content: String): SubtitleFormat {
        return if (content.trim().startsWith("WEBVTT")) {
            SubtitleFormat.VTT
        } else {
            SubtitleFormat.SRT
        }
    }
    
    private enum class SubtitleFormat {
        SRT, VTT
    }
    
    fun parseSRT(inputStream: InputStream): List<SubtitleItem> {
        val subtitles = mutableListOf<SubtitleItem>()
        
        try {
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var line: String?
            var currentSubtitle: SubtitleItemBuilder? = null
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!.trim()
                
                when {
                    currentLine.isEmpty() -> {
                        currentSubtitle?.let { builder ->
                            if (builder.isValid()) {
                                subtitles.add(builder.build())
                            }
                        }
                        currentSubtitle = null
                    }
                    
                    currentLine.matches("\\d+".toRegex()) -> {
                        currentSubtitle = SubtitleItemBuilder()
                    }
                    
                    SRT_TIME_PATTERN.matcher(currentLine).matches() -> {
                        currentSubtitle?.parseTimestamp(currentLine)
                    }
                    
                    else -> {
                        currentSubtitle?.appendText(currentLine)
                    }
                }
            }
            
            // Handle last subtitle if file doesn't end with empty line
            currentSubtitle?.let { builder ->
                if (builder.isValid()) {
                    subtitles.add(builder.build())
                }
            }
            
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return subtitles.sortedBy { it.startTime }
    }
    
    fun parseVTT(inputStream: InputStream): List<SubtitleItem> {
        val subtitles = mutableListOf<SubtitleItem>()
        
        try {
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var line: String?
            var currentSubtitle: VTTSubtitleItemBuilder? = null
            var isFirstLine = true
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!.trim()
                
                when {
                    isFirstLine -> {
                        if (!currentLine.startsWith("WEBVTT")) {
                            throw IllegalArgumentException("Invalid VTT file: missing WEBVTT header. Expected 'WEBVTT' at the beginning of file.")
                        }
                        isFirstLine = false
                    }
                    
                    currentLine.isEmpty() -> {
                        currentSubtitle?.let { builder ->
                            if (builder.isValid()) {
                                subtitles.add(builder.build())
                            }
                        }
                        currentSubtitle = null
                    }
                    
                    VTT_TIME_PATTERN.matcher(currentLine).matches() -> {
                        currentSubtitle = VTTSubtitleItemBuilder()
                        currentSubtitle.parseTimestamp(currentLine)
                    }
                    
                    currentLine.startsWith("NOTE") || currentLine.startsWith("STYLE") -> {
                        // Skip VTT metadata and style blocks
                        continue
                    }
                    
                    else -> {
                        // Skip cue identifiers (optional lines before timestamps)
                        if (currentSubtitle == null && !currentLine.contains("-->")) {
                            continue
                        }
                        currentSubtitle?.appendText(currentLine)
                    }
                }
            }
            
            // Handle last subtitle if file doesn't end with empty line
            currentSubtitle?.let { builder ->
                if (builder.isValid()) {
                    subtitles.add(builder.build())
                }
            }
            
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return subtitles.sortedBy { it.startTime }
    }
    
    private class SubtitleItemBuilder {
        private var startTime: Long = 0
        private var endTime: Long = 0
        private val textBuilder = StringBuilder()
        
        fun parseTimestamp(timeString: String) {
            val matcher = SRT_TIME_PATTERN.matcher(timeString)
            if (matcher.find()) {
                startTime = parseTime(
                    matcher.group(1)!!.toInt(), // hours
                    matcher.group(2)!!.toInt(), // minutes
                    matcher.group(3)!!.toInt(), // seconds
                    matcher.group(4)!!.toInt()  // milliseconds
                )
                
                endTime = parseTime(
                    matcher.group(5)!!.toInt(), // hours
                    matcher.group(6)!!.toInt(), // minutes
                    matcher.group(7)!!.toInt(), // seconds
                    matcher.group(8)!!.toInt()  // milliseconds
                )
            }
        }
        
        fun appendText(text: String) {
            if (textBuilder.isNotEmpty()) {
                textBuilder.append("\n")
            }
            textBuilder.append(text)
        }
        
        fun isValid(): Boolean {
            return startTime < endTime && textBuilder.isNotEmpty()
        }
        
        fun build(): SubtitleItem {
            return SubtitleItem(startTime, endTime, textBuilder.toString().trim())
        }
        
        private fun parseTime(hours: Int, minutes: Int, seconds: Int, milliseconds: Int): Long {
            return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + milliseconds
        }
    }
    
    private class VTTSubtitleItemBuilder {
        private var startTime: Long = 0
        private var endTime: Long = 0
        private val textBuilder = StringBuilder()
        
        fun parseTimestamp(timeString: String) {
            val matcher = VTT_TIME_PATTERN.matcher(timeString)
            if (matcher.find()) {
                startTime = parseTime(
                    matcher.group(1)!!.toInt(), // hours
                    matcher.group(2)!!.toInt(), // minutes
                    matcher.group(3)!!.toInt(), // seconds
                    matcher.group(4)!!.toInt()  // milliseconds
                )
                
                endTime = parseTime(
                    matcher.group(5)!!.toInt(), // hours
                    matcher.group(6)!!.toInt(), // minutes
                    matcher.group(7)!!.toInt(), // seconds
                    matcher.group(8)!!.toInt()  // milliseconds
                )
            }
        }
        
        fun appendText(text: String) {
            if (textBuilder.isNotEmpty()) {
                textBuilder.append("\n")
            }
            // Remove VTT formatting tags like <c.classname>, <b>, <i>, etc.
            val cleanText = text.replace(Regex("<[^>]+>"), "")
            textBuilder.append(cleanText)
        }
        
        fun isValid(): Boolean {
            return startTime < endTime && textBuilder.isNotEmpty()
        }
        
        fun build(): SubtitleItem {
            return SubtitleItem(startTime, endTime, textBuilder.toString().trim())
        }
        
        private fun parseTime(hours: Int, minutes: Int, seconds: Int, milliseconds: Int): Long {
            return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + milliseconds
        }
    }
}