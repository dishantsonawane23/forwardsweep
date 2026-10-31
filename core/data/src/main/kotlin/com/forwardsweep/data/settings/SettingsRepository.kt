package com.forwardsweep.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.forwardsweep.classifier.ClassifierConfig
import com.forwardsweep.data.media.ScanScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val scope: ScanScope = ScanScope.WHATSAPP_ONLY,
    val minConfidence: Double = 0.75,
    val protectFaces: Boolean = true,
    /** Move to the system trash (recoverable 30 days) instead of deleting, where supported. */
    val useTrash: Boolean = true,
    /** Scan WhatsApp videos and GIFs too (Settings → What to scan). */
    val includeVideos: Boolean = true,
    val monthlyReminder: Boolean = true,
    val lastReminderAt: Long = 0,
) {
    fun classifierConfig() = ClassifierConfig(minConfidence = minConfidence, protectFaces = protectFaces)
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val scope = stringPreferencesKey("scope")
        val minConfidence = doublePreferencesKey("min_confidence")
        val protectFaces = booleanPreferencesKey("protect_faces")
        val useTrash = booleanPreferencesKey("use_trash")
        val includeVideos = booleanPreferencesKey("include_videos")
        val monthlyReminder = booleanPreferencesKey("monthly_reminder")
        val lastReminderAt = longPreferencesKey("last_reminder_at")
        val selectedOn = stringSetPreferencesKey("selection_on")
        val selectedOff = stringSetPreferencesKey("selection_off")
    }

    val settings: Flow<Settings> = context.dataStore.data.map(::fromPrefs)

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(fromPrefs(p))
            p[Keys.scope] = s.scope.name
            p[Keys.minConfidence] = s.minConfidence.coerceIn(0.5, 0.99)
            p[Keys.protectFaces] = s.protectFaces
            p[Keys.useTrash] = s.useTrash
            p[Keys.includeVideos] = s.includeVideos
            p[Keys.monthlyReminder] = s.monthlyReminder
            p[Keys.lastReminderAt] = s.lastReminderAt
        }
    }

    /**
     * The user's own select/deselect choices in the review screens, keyed by URI. Persisted so a
     * process restart can never re-select an image the user chose to keep.
     */
    val selectionOverrides: Flow<Map<String, Boolean>> = context.dataStore.data.map { p ->
        p[Keys.selectedOff].orEmpty().associateWith { false } + p[Keys.selectedOn].orEmpty().associateWith { true }
    }

    suspend fun saveSelectionOverrides(overrides: Map<String, Boolean>) {
        context.dataStore.edit { p ->
            p[Keys.selectedOn] = overrides.filterValues { it }.keys
            p[Keys.selectedOff] = overrides.filterValues { !it }.keys
        }
    }

    private fun fromPrefs(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            scope = p[Keys.scope]?.let { runCatching { ScanScope.valueOf(it) }.getOrNull() } ?: d.scope,
            minConfidence = p[Keys.minConfidence] ?: d.minConfidence,
            protectFaces = p[Keys.protectFaces] ?: d.protectFaces,
            useTrash = p[Keys.useTrash] ?: d.useTrash,
            includeVideos = p[Keys.includeVideos] ?: d.includeVideos,
            monthlyReminder = p[Keys.monthlyReminder] ?: d.monthlyReminder,
            lastReminderAt = p[Keys.lastReminderAt] ?: d.lastReminderAt,
        )
    }
}
