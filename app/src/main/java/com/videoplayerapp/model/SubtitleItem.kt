package com.videoplayerapp.model

data class SubtitleItem(
    val startTime: Long,
    val endTime: Long,
    val text: String
) {
    fun getFormattedStartTime(): String {
        val minutes = startTime / 60000
        val seconds = (startTime % 60000) / 1000
        return String.format("%02d:%02d", minutes, seconds)
    }
    
    fun isActiveAt(currentPosition: Long): Boolean {
        return currentPosition in startTime..endTime
    }
}