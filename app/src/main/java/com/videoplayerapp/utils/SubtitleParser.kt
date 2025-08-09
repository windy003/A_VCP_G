package com.videoplayerapp.utils

import com.videoplayerapp.model.SubtitleItem
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

object SubtitleParser {
    
    private val SRT_TIME_PATTERN = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
    )
    
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
}