package com.naptimer

object NapMath {
    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(1, 45)

    fun remainingMillis(endMillis: Long, nowMillis: Long): Long =
        (endMillis - nowMillis).coerceAtLeast(0L)
}
