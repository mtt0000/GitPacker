package com.example.gitpacker

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun saveThemeMode(mode: String) {
        sharedPreferences.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getThemeMode(): String {
        return sharedPreferences.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
