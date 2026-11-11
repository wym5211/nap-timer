package com.naptimer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.napDataStore by preferencesDataStore(name = "nap")

data class NapState(
    val id: String = "",
    val status: String = "idle",
    val minutes: Int = 20,
    val sound: Boolean = true,
    val soundId: String = SleepSound.CALM.id,
    val endElapsed: Long = 0,
    val endWall: Long = 0,
)

class NapStore(private val context: Context) {
    private object Keys {
        val id = stringPreferencesKey("id")
        val status = stringPreferencesKey("status")
        val minutes = intPreferencesKey("minutes")
        val sound = booleanPreferencesKey("sound")
        val soundId = stringPreferencesKey("sound_id")
        val endElapsed = longPreferencesKey("end_elapsed")
        val endWall = longPreferencesKey("end_wall")
    }

    val state: Flow<NapState> = context.napDataStore.data.map { prefs ->
        NapState(
            id = prefs[Keys.id] ?: "",
            status = prefs[Keys.status] ?: "idle",
            minutes = NapMath.clampMinutes(prefs[Keys.minutes] ?: 20),
            sound = prefs[Keys.sound] ?: true,
            soundId = SleepSound.fromId(prefs[Keys.soundId]).id,
            endElapsed = prefs[Keys.endElapsed] ?: 0,
            endWall = prefs[Keys.endWall] ?: 0,
        )
    }

    suspend fun current(): NapState = state.first()

    suspend fun prepare(id: String, minutes: Int, sound: Boolean, soundId: String, endElapsed: Long, endWall: Long) {
        context.napDataStore.edit { prefs ->
            prefs[Keys.id] = id
            prefs[Keys.status] = "pending"
            prefs[Keys.minutes] = NapMath.clampMinutes(minutes)
            prefs[Keys.sound] = sound
            prefs[Keys.soundId] = SleepSound.fromId(soundId).id
            prefs[Keys.endElapsed] = endElapsed
            prefs[Keys.endWall] = endWall
        }
    }

    suspend fun activate(id: String): Boolean {
        var changed = false
        context.napDataStore.edit { prefs ->
            if (prefs[Keys.id] == id && prefs[Keys.status] == "pending") {
                prefs[Keys.status] = "active"
                changed = true
            }
        }
        return changed
    }

    suspend fun claimAlarm(id: String): Boolean {
        var claimed = false
        context.napDataStore.edit { prefs ->
            val status = prefs[Keys.status]
            if (NapMath.shouldRing(prefs[Keys.id] ?: "", id, status == "active" || status == "pending")) {
                prefs[Keys.status] = "ringing"
                claimed = true
            }
        }
        return claimed
    }

    suspend fun finish(id: String): Boolean {
        var changed = false
        context.napDataStore.edit { prefs ->
            if (NapMath.isCurrentSession(prefs[Keys.id] ?: "", id)) {
                prefs[Keys.status] = "idle"
                prefs[Keys.id] = ""
                changed = true
            }
        }
        return changed
    }

    suspend fun recover(id: String, endElapsed: Long): Boolean {
        var changed = false
        context.napDataStore.edit { prefs ->
            if (prefs[Keys.id] == id && (prefs[Keys.status] == "active" || prefs[Keys.status] == "pending")) {
                prefs[Keys.endElapsed] = endElapsed
                prefs[Keys.status] = "active"
                changed = true
            }
        }
        return changed
    }
}
