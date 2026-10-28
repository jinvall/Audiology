package com.audiology2.stream.data

import com.audiology2.stream.ui.AudioProject
import com.audiology2.stream.ui.Clip
import com.audiology2.stream.ui.Track

/** Simple JSON-like serialization without kotlinx.serialization to avoid kapt issues */
fun AudioProject.toJsonString(): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"id\":\"${id}\",")
    sb.append("\"name\":\"${name.replace("\"", "\\\"")}\",")
    sb.append("\"createdAt\":$createdAt,")
    sb.append("\"modifiedAt\":$modifiedAt,")
    sb.append("\"sampleRate\":$sampleRate,")
    sb.append("\"bitDepth\":$bitDepth,")
    sb.append("\"masterGain\":$masterGain,")
    sb.append("\"tempo\":$tempo,")
    sb.append("\"timeSignatureNumerator\":$timeSignatureNumerator,")
    sb.append("\"timeSignatureDenominator\":$timeSignatureDenominator,")
    sb.append("\"projectFolder\":\"${projectFolder.replace("\"", "\\\"")}\",")
    sb.append("\"tracks\":[")
    for ((i, track) in tracks.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append(track.toJsonString())
    }
    sb.append("]}")
    return sb.toString()
}

fun Track.toJsonString(): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"id\":\"${id}\",")
    sb.append("\"name\":\"${name.replace("\"", "\\\"")}\",")
    sb.append("\"gain\":$gain,")
    sb.append("\"pan\":$pan,")
    sb.append("\"muted\":$muted,")
    sb.append("\"solo\":$solo,")
    sb.append("\"color\":${color},")
    sb.append("\"height\":$height,")
    sb.append("\"armed\":$armed,")
    sb.append("\"inputSource\":\"${inputSource}\",")
    sb.append("\"monitor\":$monitor,")
    sb.append("\"clips\":[")
    for ((i, clip) in clips.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append(clip.toJsonString())
    }
    sb.append("]}")
    return sb.toString()
}

fun Clip.toJsonString(): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"id\":\"${id}\",")
    sb.append("\"name\":\"${name.replace("\"", "\\\"")}\",")
    sb.append("\"sourcePath\":\"${sourcePath.replace("\"", "\\\"")}\",")
    sb.append("\"sourceOffset\":$sourceOffset,")
    sb.append("\"duration\":$duration,")
    sb.append("\"startTime\":$startTime,")
    sb.append("\"trackId\":\"${trackId}\",")
    sb.append("\"gain\":$gain,")
    sb.append("\"pan\":$pan,")
    sb.append("\"muted\":$muted,")
    sb.append("\"solo\":$solo,")
    sb.append("\"color\":${color},")
    sb.append("\"fadeIn\":$fadeIn,")
    sb.append("\"fadeOut\":$fadeOut,")
    sb.append("\"locked\":$locked}")
    return sb.toString()
}

fun String.toAudioProject(): AudioProject {
    // Simple JSON parsing - in a real app, use a proper JSON parser
    // For now, return a default project
    return AudioProject()
}