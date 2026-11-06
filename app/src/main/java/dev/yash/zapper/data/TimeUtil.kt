package dev.yash.zapper.data

object TimeUtil {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** Days since 1970-01-01 for a proleptic Gregorian date. */
    fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    /** Parses XMLTV time like "20261105200000 +0530" to epoch millis, or -1. */
    fun parseXmltv(s: String): Long {
        val t = s.trim()
        if (t.length < 12) return -1
        return try {
            val y = t.substring(0, 4).toInt()
            val mo = t.substring(4, 6).toInt()
            val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt()
            val mi = t.substring(10, 12).toInt()
            val sec = if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) t.substring(12, 14).toInt() else 0
            var ms = ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * MIN + sec * 1000L
            val rest = t.substring(minOf(14, t.length)).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val oh = rest.substring(1, 3).toInt()
                val om = rest.substring(3, 5).toInt()
                val off = (oh * 60 + om) * MIN
                ms -= if (rest[0] == '+') off else -off
            }
            ms
        } catch (e: NumberFormatException) {
            -1
        }
    }

    fun floorHalfHour(t: Long): Long = t - t.mod(30 * MIN)
}
