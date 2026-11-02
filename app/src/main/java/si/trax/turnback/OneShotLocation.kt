package si.trax.turnback

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Gets a single good fix (or the best one within the timeout). Caller must hold location permission. */
class OneShotLocation(
    ctx: Context,
    private val goodAccuracy: Float,
    private val timeoutMs: Long,
    private val callback: (Location?) -> Unit,
) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var done = false

    @SuppressLint("MissingPermission")
    fun start() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.getLastKnownLocation(p)?.let {
                    if (System.currentTimeMillis() - it.time < 60_000L) consider(it)
                }
                if (!done) lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
            } catch (e: Exception) {
                // provider unavailable
            }
        }
        handler.postDelayed({ complete() }, timeoutMs)
    }

    private fun consider(l: Location) {
        val b = best
        if (b == null || l.accuracy < b.accuracy) best = l
        if (l.accuracy <= goodAccuracy) complete()
    }

    private fun complete() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        callback(best)
    }

    override fun onLocationChanged(location: Location) {
        if (!done) consider(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
