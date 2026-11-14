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

/**
 * 午睡会话的状态。
 *
 * 落盘时用小写名字，和 1.1 之前写入的字符串一模一样，升级安装不会把进行中的午睡变成空闲。
 */
enum class NapStatus {
    IDLE,
    PENDING,
    ACTIVE,
    RINGING;

    val storedName: String get() = name.lowercase()

    companion object {
        fun fromStored(value: String?): NapStatus = entries.firstOrNull { it.storedName == value } ?: IDLE
    }
}

data class NapState(
    val id: String = "",
    val status: NapStatus = NapStatus.IDLE,
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
            status = NapStatus.fromStored(prefs[Keys.status]),
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
            prefs[Keys.status] = NapStatus.PENDING.storedName
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
            if (prefs[Keys.id] == id && NapStatus.fromStored(prefs[Keys.status]) == NapStatus.PENDING) {
                prefs[Keys.status] = NapStatus.ACTIVE.storedName
                changed = true
            }
        }
        return changed
    }

    suspend fun claimAlarm(id: String): Boolean {
        var claimed = false
        context.napDataStore.edit { prefs ->
            val currentId = prefs[Keys.id] ?: ""
            val status = NapStatus.fromStored(prefs[Keys.status])
            if (currentId.isNotEmpty() && currentId == id &&
                (status == NapStatus.ACTIVE || status == NapStatus.PENDING)
            ) {
                prefs[Keys.status] = NapStatus.RINGING.storedName
                claimed = true
            }
        }
        return claimed
    }

    suspend fun finish(id: String): Boolean {
        var changed = false
        context.napDataStore.edit { prefs ->
            val currentId = prefs[Keys.id] ?: ""
            if (currentId.isNotEmpty() && currentId == id) {
                prefs[Keys.status] = NapStatus.IDLE.storedName
                prefs[Keys.id] = ""
                changed = true
            }
        }
        return changed
    }

    /** 恢复时到点时刻按单调时钟重算，墙上时钟目标必须一起写回，否则二次恢复会读到旧值。 */
    suspend fun recover(id: String, endElapsed: Long, endWall: Long): Boolean {
        var changed = false
        context.napDataStore.edit { prefs ->
            val status = NapStatus.fromStored(prefs[Keys.status])
            if (prefs[Keys.id] == id && (status == NapStatus.ACTIVE || status == NapStatus.PENDING)) {
                prefs[Keys.endElapsed] = endElapsed
                prefs[Keys.endWall] = endWall
                prefs[Keys.status] = NapStatus.ACTIVE.storedName
                changed = true
            }
        }
        return changed
    }
}
