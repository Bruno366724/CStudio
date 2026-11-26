package com.example.cstudio.color

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

class RecentColorsRepository(
    private val preferences: SharedPreferences
) {
    fun getRecentColors(maxItems: Int = DEFAULT_MAX_HISTORY_ITEMS): List<Int> {
        require(maxItems >= 0)
        if (maxItems == 0) return emptyList()
        val stored = preferences.getString(KEY_RECENT_COLORS, null) ?: return emptyList()
        val colors = try {
            JSONArray(stored)
        } catch (_: JSONException) {
            return emptyList()
        }

        val result = ArrayList<Int>(minOf(colors.length(), maxItems))
        val seen = HashSet<Int>()
        for (index in 0 until colors.length()) {
            val color = (colors.opt(index) as? String)?.let(ColorUtils::parseHex) ?: continue
            if (seen.add(color)) result.add(color)
            if (result.size == maxItems) break
        }
        return result
    }

    fun addColor(argb: Int, maxItems: Int = DEFAULT_MAX_HISTORY_ITEMS) {
        require(maxItems >= 0)
        val colors = buildList {
            if (maxItems > 0) {
                add(argb)
                getRecentColors(maxItems).filterTo(this) { it != argb }
                truncate(maxItems)
            }
        }
        saveColors(colors)
    }

    fun clearHistory() {
        preferences.edit().remove(KEY_RECENT_COLORS).apply()
    }

    fun getLastColor(defaultColor: Int): Int {
        val stored = preferences.getString(KEY_LAST_COLOR, null) ?: return defaultColor
        return ColorUtils.parseHex(stored) ?: defaultColor
    }

    fun saveLastColor(argb: Int) {
        preferences.edit()
            .putString(KEY_LAST_COLOR, ColorUtils.toHex(argb, includeAlpha = true))
            .apply()
    }

    private fun saveColors(colors: List<Int>) {
        val serialized = JSONArray().apply {
            colors.forEach { put(ColorUtils.toHex(it, includeAlpha = true)) }
        }
        preferences.edit().putString(KEY_RECENT_COLORS, serialized.toString()).apply()
    }

    private fun MutableList<Int>.truncate(maxItems: Int) {
        while (size > maxItems) removeAt(lastIndex)
    }

    private companion object {
        const val KEY_RECENT_COLORS = "cstudio_recent_colors"
        const val KEY_LAST_COLOR = "cstudio_last_color"
        const val DEFAULT_MAX_HISTORY_ITEMS = 12
    }
}
