package com.naptimer

object NapMath {
    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(1, 45)

    fun remainingMillis(endMillis: Long, nowMillis: Long): Long =
        (endMillis - nowMillis).coerceAtLeast(0L)

    fun shouldRing(currentId: String, eventId: String, active: Boolean): Boolean =
        active && isCurrentSession(currentId, eventId)

    fun isCurrentSession(currentId: String, eventId: String): Boolean =
        currentId.isNotEmpty() && currentId == eventId

    fun restartRemainingMillis(endWallMillis: Long, nowWallMillis: Long): Long =
        remainingMillis(endWallMillis, nowWallMillis)
}
