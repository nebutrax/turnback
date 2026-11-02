package si.trax.turnback

import android.content.Context
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    fun time(ctx: Context, ms: Long): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ms))

    /** Minutes, rounded up. */
    fun mins(ms: Long): String {
        val m = (ms.coerceAtLeast(0L) + 59_999L) / 60_000L
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun dist(m: Double): String =
        if (m >= 1000) String.format(Locale.getDefault(), "%.1f km", m / 1000.0)
        else "${m.roundToInt()} m"
}
