package dev.field.ui

import java.util.Locale

/** Fixed-format technical readouts. Locale.ROOT: no localized digits or separators. */
object Fmt {
    fun pad2(n: Int): String = String.format(Locale.ROOT, "%02d", n)
    fun pad3(n: Int): String = String.format(Locale.ROOT, "%03d", n)

    /** 00:00:00 from milliseconds (negative clamps to zero). */
    fun clock(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** 00:00 (minutes may exceed 59) from milliseconds. */
    fun mmss(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    /** HH:MM from minutes after midnight. */
    fun hhmm(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.0f MB", bytes / 1_000_000.0)

    fun kilobytes(bytes: Long): String = String.format(Locale.ROOT, "%03d KB", maxOf(1L, bytes / 1024))
}
