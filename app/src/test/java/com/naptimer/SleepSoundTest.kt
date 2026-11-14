package com.naptimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34, 35])
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

    /**
     * 真正驱动播放的是 entries[].resId -> res/raw 的对齐（NapAudioService 的播放路径）。
     * 遍历枚举真源而不是硬编码 id 列表：新增声音、改名或资源缺失时测试才不会继续全绿。
     */
    @Test
    fun everySoundPointsAtARealNonEmptyRawResource() {
        val resources = RuntimeEnvironment.getApplication().resources
        SleepSound.entries.forEach { entry ->
            assertSame(entry, SleepSound.fromId(entry.id))
            val descriptor = resources.openRawResourceFd(entry.resId)
                ?: throw AssertionError("${entry.id} 缺少 res/raw 音频资源")
            descriptor.use {
                assertTrue("${entry.id} 指向的音频资源是空文件", it.length > 0)
            }
        }
    }
}
