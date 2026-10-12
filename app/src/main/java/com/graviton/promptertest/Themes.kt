package com.graviton.promptertest

import android.graphics.Color
import android.media.MediaRecorder

class PTheme(
    val key: String,
    val label: String,
    val bg: Int,
    val done: Int,
    val up: Int,
    val cur: Int,
    val hi: Int,
    val cue: Int
)

object Themes {
    val all: List<PTheme> = listOf(
        PTheme("soft", "Soft (easy on the eyes)", Color.parseColor("#20262d"), Color.parseColor("#66727e"),
            Color.parseColor("#e4ddcc"), Color.parseColor("#ffffff"), Color.parseColor("#86bba8"), Color.parseColor("#8fbfae")),
        PTheme("neon", "Neon Dark", Color.parseColor("#06070c"), Color.parseColor("#4b5170"),
            Color.parseColor("#d5dcf5"), Color.parseColor("#ffffff"), Color.parseColor("#00e5ff"), Color.parseColor("#7c86b0")),
        PTheme("classic", "Classic Dark", Color.parseColor("#121212"), Color.parseColor("#555555"),
            Color.parseColor("#d8d8d8"), Color.parseColor("#ffffff"), Color.parseColor("#3b82f6"), Color.parseColor("#6fb1ff")),
        PTheme("white", "White", Color.parseColor("#fbfaf7"), Color.parseColor("#b9b5aa"),
            Color.parseColor("#2a2d36"), Color.parseColor("#000000"), Color.parseColor("#ffd54a"), Color.parseColor("#1d4ed8"))
    )

    fun get(key: String): PTheme = all.firstOrNull { it.key == key } ?: all[0]

    val highlightPresets: List<Pair<String, Int>> = listOf(
        Pair("Theme default", 0),
        Pair("Electric cyan", Color.parseColor("#00e5ff")),
        Pair("Magenta", Color.parseColor("#ff2bd6")),
        Pair("Lime", Color.parseColor("#7cff4f")),
        Pair("Amber", Color.parseColor("#ffc400")),
        Pair("Orange", Color.parseColor("#ff9a1f")),
        Pair("Coral", Color.parseColor("#ff6b6b")),
        Pair("Violet", Color.parseColor("#a07bff")),
        Pair("Sky blue", Color.parseColor("#4da3ff")),
        Pair("Sage green", Color.parseColor("#86bba8")),
        Pair("Soft yellow", Color.parseColor("#f2d675"))
    )
}

object MicSources {
    val list: List<Pair<String, Int>> = listOf(
        Pair("Voice recognition (recommended)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        Pair("Microphone", MediaRecorder.AudioSource.MIC),
        Pair("Camcorder", MediaRecorder.AudioSource.CAMCORDER),
        Pair("Unprocessed", MediaRecorder.AudioSource.UNPROCESSED),
        Pair("Voice communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    )
}
