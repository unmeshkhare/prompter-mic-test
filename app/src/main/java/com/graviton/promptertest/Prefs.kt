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
        get() = sp.getBoolean("script_aware", true)
        set(v) { sp.edit().putBoolean("script_aware", v).apply() }

    var showHeard: Boolean
        get() = sp.getBoolean("show_heard", true)
        set(v) { sp.edit().putBoolean("show_heard", v).apply() }

    /** How many words the highlight runs ahead of what the recogniser has matched (hides recogniser delay). */
    var lead: Int
        get() = sp.getInt("lead", 2)
        set(v) { sp.edit().putInt("lead", v).apply() }

    /** Window geometry per screen orientation: x, y, width, height (null = never saved). */
    fun getGeo(land: Boolean): IntArray? {
        val k = if (land) "l" else "p"
        if (!sp.contains("geo_${k}_w")) return null
        return intArrayOf(
            sp.getInt("geo_${k}_x", -1), sp.getInt("geo_${k}_y", 0),
            sp.getInt("geo_${k}_w", 0), sp.getInt("geo_${k}_h", 0)
        )
    }

    fun setGeo(land: Boolean, x: Int, y: Int, w: Int, h: Int) {
        val k = if (land) "l" else "p"
        sp.edit().putInt("geo_${k}_x", x).putInt("geo_${k}_y", y)
            .putInt("geo_${k}_w", w).putInt("geo_${k}_h", h).apply()
    }

    /** Text rotation in degrees (0/90/180/270), remembered per screen orientation. */
    fun getRot(land: Boolean): Int = sp.getInt(if (land) "rot_l" else "rot_p", 0)

    fun setRot(land: Boolean, r: Int) {
        sp.edit().putInt(if (land) "rot_l" else "rot_p", r).apply()
    }

    var lastScript: String
        get() = sp.getString("last_script", "") ?: ""
        set(v) { sp.edit().putString("last_script", v).apply() }

    fun resetWindow() {
        val e = sp.edit()
        for (k in listOf("p", "l")) {
            e.remove("geo_${k}_x").remove("geo_${k}_y").remove("geo_${k}_w").remove("geo_${k}_h")
            e.remove("rot_$k")
        }
        e.apply()
    }
}
