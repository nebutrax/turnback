package si.trax.turnback

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "turnback"
    const val TARGET_LAT = "target_lat"
    const val TARGET_LON = "target_lon"
    const val TARGET_LABEL = "target_label"
    const val DEADLINE = "deadline"
    const val BUFFER_MIN = "buffer_min"
    const val SPEED_KMH = "speed_kmh"
    const val USE_ROUTING = "use_routing"
    const val GOOGLE_KEY = "google_key"
    const val RUNNING = "running"
    const val STATUS = "status"
    const val STATUS_LEVEL = "status_level"
    const val AUTO_SPEED = "auto_speed"
    const val LEARNED_KMH = "learned_kmh"      // pace learned from previous walks
    const val MEASURED_KMH = "measured_kmh"    // pace in use during the current run
    const val MEASURED_SEC = "measured_sec"    // seconds of walking data behind it

    fun manualSpeedKmh(p: SharedPreferences): Double =
        p.getFloat(SPEED_KMH, 4.5f).coerceIn(1f, 15f).toDouble()

    /** Speed the ETA should use right now: measured while tracking, else learned, else preset. */
    fun effectiveSpeedKmh(p: SharedPreferences): Double {
        if (!p.getBoolean(AUTO_SPEED, true)) return manualSpeedKmh(p)
        if (p.getBoolean(RUNNING, false) && p.getInt(MEASURED_SEC, 0) > 0) {
            return p.getFloat(MEASURED_KMH, 0f).toDouble().takeIf { it > 0 } ?: manualSpeedKmh(p)
        }
        return learnedSpeedKmh(p) ?: manualSpeedKmh(p)
    }

    fun learnedSpeedKmh(p: SharedPreferences): Double? =
        p.getFloat(LEARNED_KMH, 0f).toDouble().takeIf { it > 0 }

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun hasTarget(p: SharedPreferences) = p.contains(TARGET_LAT) && p.contains(TARGET_LON)

    fun targetLat(p: SharedPreferences) = java.lang.Double.longBitsToDouble(p.getLong(TARGET_LAT, 0L))

    fun targetLon(p: SharedPreferences) = java.lang.Double.longBitsToDouble(p.getLong(TARGET_LON, 0L))

    fun setTarget(p: SharedPreferences, lat: Double, lon: Double, label: String) {
        p.edit()
            .putLong(TARGET_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(TARGET_LON, java.lang.Double.doubleToRawLongBits(lon))
            .putString(TARGET_LABEL, label)
            .apply()
    }
}
