package com.naptimer

enum class SleepSound(val id: String, val label: String, val attribution: String? = null) {
    CALM("calm", "柔和旋律"),
    RAIN(
        "rain",
        "雨声（实录）",
        "Rain Background - Mike Koenig\n来源：https://soundbible.com/2011-Rain-Background.html\n许可：https://creativecommons.org/licenses/by/3.0/\n已交叉淡化并转换格式",
    ),
    OCEAN(
        "ocean",
        "海浪（实录）",
        "Crisp Ocean Waves - Mike Koenig\n来源：https://soundbible.com/1936-Crisp-Ocean-Waves.html\n许可：https://creativecommons.org/licenses/by/3.0/\n已交叉淡化并转换格式",
    ),
    PINK_NOISE("pink_noise", "粉红噪音"),
    CITY_SLEEP(
        "city_sleep",
        "City of Sleep",
        "City of Sleep - Matthew C. Wright (www.matthewcwright.com)\n来源：https://matthewcwright.com/1ymr8\n许可：https://creativecommons.org/licenses/by/4.0/",
    ),
    MOONLIGHT(
        "moonlight",
        "Wading in Moonlight",
        "Wading in Moonlight - Matthew C. Wright (www.matthewcwright.com)\n来源：https://ffm.to/xoy59o7\n许可：https://creativecommons.org/licenses/by/4.0/",
    ),
    MEDITATION_03(
        "meditation_03",
        "Meditation Impromptu 03",
        "Meditation Impromptu 03 - Kevin MacLeod (incompetech.com)\n来源：https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100161\n许可：https://creativecommons.org/licenses/by/4.0/",
    ),
    ;

    companion object {
        fun fromId(id: String?): SleepSound = entries.firstOrNull { it.id == id } ?: CALM
    }
}

fun SleepSound.resourceId(): Int = when (this) {
    SleepSound.CALM -> R.raw.calm
    SleepSound.RAIN -> R.raw.rain
    SleepSound.OCEAN -> R.raw.ocean
    SleepSound.PINK_NOISE -> R.raw.pink_noise
    SleepSound.CITY_SLEEP -> R.raw.city_sleep
    SleepSound.MOONLIGHT -> R.raw.moonlight
    SleepSound.MEDITATION_03 -> R.raw.meditation_03
}
