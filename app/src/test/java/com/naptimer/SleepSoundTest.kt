package com.naptimer

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepSoundTest {
    @Test
    fun selectedSoundCanBeRestoredById() {
        assertEquals(SleepSound.CALM, SleepSound.fromId("calm"))
        assertEquals(SleepSound.RAIN, SleepSound.fromId("rain"))
        assertEquals(SleepSound.OCEAN, SleepSound.fromId("ocean"))
        assertEquals(SleepSound.PINK_NOISE, SleepSound.fromId("pink_noise"))
    }

    @Test
    fun oldOrUnknownSoundSettingsFallBackToCalm() {
        assertEquals(SleepSound.CALM, SleepSound.fromId(null))
        assertEquals(SleepSound.CALM, SleepSound.fromId("unknown"))
    }

    @Test
    fun selectedMusicTracksCanBeRestoredById() {
        listOf("city_sleep", "moonlight", "meditation_03").forEach { id ->
            assertEquals(id, SleepSound.fromId(id).id)
        }
    }

}
