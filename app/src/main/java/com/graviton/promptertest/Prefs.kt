package com.graviton.promptertest

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("prompter_prefs", Context.MODE_PRIVATE)

    var fontSp: Int
        get() = sp.getInt("font_sp", 30)
        set(v) { sp.edit().putInt("font_sp", v).apply() }

    /** Opacity of the black/theme background behind the text, 0..100. */
    var opacity: Int
        get() = sp.getInt("opacity", 70)
        set(v) { sp.edit().putInt("opacity", v).apply() }

    var theme: String
        get() = sp.getString("theme", "soft") ?: "soft"
        set(v) { sp.edit().putString("theme", v).apply() }

    /** Highlight colour, 0 = use the theme default. */
    var highlight: Int
        get() = sp.getInt("highlight", 0)
        set(v) { sp.edit().putInt("highlight", v).apply() }

    /** Where the reading line sits, as % of the window height. */
    var readingLine: Int
        get() = sp.getInt("reading_line", 35)
        set(v) { sp.edit().putInt("reading_line", v).apply() }

    var micIdx: Int
        get() = sp.getInt("mic_idx", 0)
        set(v) { sp.edit().putInt("mic_idx", v).apply() }

    var scriptAware: Boolean
        get() = sp.getBoolean("script_aware", false)
        set(v) { sp.edit().putBoolean("script_aware", v).apply() }

    var showHeard: Boolean
        get() = sp.getBoolean("show_heard", true)
        set(v) { sp.edit().putBoolean("show_heard", v).apply() }

    var winX: Int
        get() = sp.getInt("win_x", -1)
        set(v) { sp.edit().putInt("win_x", v).apply() }
    var winY: Int
        get() = sp.getInt("win_y", -1)
        set(v) { sp.edit().putInt("win_y", v).apply() }
    var winW: Int
        get() = sp.getInt("win_w", -1)
        set(v) { sp.edit().putInt("win_w", v).apply() }
    var winH: Int
        get() = sp.getInt("win_h", -1)
        set(v) { sp.edit().putInt("win_h", v).apply() }

    var lastScript: String
        get() = sp.getString("last_script", "") ?: ""
        set(v) { sp.edit().putString("last_script", v).apply() }

    fun resetWindow() {
        sp.edit().remove("win_x").remove("win_y").remove("win_w").remove("win_h").apply()
    }
}
