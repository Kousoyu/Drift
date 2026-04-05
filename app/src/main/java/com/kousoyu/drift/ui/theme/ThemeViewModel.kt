package com.kousoyu.drift.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ─── Theme Mode ───────────────────────────────────────────────────────────────

enum class ThemeMode {
    UNINITIALIZED,
    SYSTEM,  // Follow the OS dark/light setting
    DARK,    // Always Drift Night Black
    LIGHT;   // Always Drift Day White

    val label: String get() = when (this) {
        UNINITIALIZED -> ""
        SYSTEM -> "跟随系统"
        DARK   -> "暗夜迷彩"
        LIGHT  -> "白昼流光"
    }
}

// ─── DataStore ────────────────────────────────────────────────────────────────

private val Context.themeDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "drift_theme")

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

// ─── ViewModel ────────────────────────────────────────────────────────────────

class ThemeViewModel(private val context: Context) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.UNINITIALIZED)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    init {
        // Load persisted preference on startup
        viewModelScope.launch {
            context.themeDataStore.data
                .map { prefs ->
                    val saved = prefs[THEME_MODE_KEY] ?: ThemeMode.SYSTEM.name
                    val loaded = try { ThemeMode.valueOf(saved) } catch (e: Exception) { ThemeMode.SYSTEM }
                    if (loaded == ThemeMode.UNINITIALIZED) ThemeMode.SYSTEM else loaded
                }
                .collect { _themeMode.value = it }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            _themeMode.value = mode
            context.themeDataStore.edit { prefs ->
                prefs[THEME_MODE_KEY] = mode.name
            }
        }
    }

    // Factory so we can pass Context into the ViewModel
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ThemeViewModel(context.applicationContext) as T
        }
    }
}
