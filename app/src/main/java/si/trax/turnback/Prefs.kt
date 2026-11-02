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
    const val RUNNING = "running"
    const val STATUS = "status"

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
