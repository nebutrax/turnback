package si.trax.turnback

import android.location.Location
import android.location.LocationManager

/**
 * Estimates walking pace from GPS fixes.
 *
 * Uses the fix's Doppler speed when it is reliable, otherwise distance / time between consecutive
 * good fixes. Only walking-range samples count: standing still and vehicle speeds are ignored, so
 * waiting at a crossing or taking a bus doesn't skew the pace. The last [WINDOW_SECONDS] of walking
 * are kept (walking time, not wall-clock, so a long café stop doesn't erase the estimate).
 *
 * A prior (preset or previously learned pace) carries the weight of [PRIOR_SECONDS] of measurement,
 * so the estimate starts at the prior and converges to the measured pace after a couple of minutes.
 */
class Pace(private val priorMs: Double) {
    companion object {
        const val MIN_WALK_MS = 0.5   // 1.8 km/h — slower is standing / shuffling
        const val MAX_WALK_MS = 2.3   // 8.3 km/h — faster is running or a vehicle
        const val WINDOW_SECONDS = 15 * 60.0
        const val PRIOR_SECONDS = 90.0
        const val MAX_ACCURACY_M = 30f
    }

    private class Sample(val speed: Double, val seconds: Double)

    private val samples = ArrayDeque<Sample>()
    private var total = 0.0
    private var last: Location? = null

    /** Feed every fix; returns true if a walking sample was recorded. */
    fun add(loc: Location): Boolean {
        if (loc.provider != LocationManager.GPS_PROVIDER) return false
        if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCURACY_M) return false
        val prev = last
        last = loc
        if (prev == null) return false
        val dt = (loc.time - prev.time) / 1000.0
        if (dt < 3.0 || dt > 120.0) return false

        val doppler = loc.hasSpeed() && (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond <= 1.5f)
        val v = if (doppler) loc.speed.toDouble() else prev.distanceTo(loc) / dt
        if (v < MIN_WALK_MS || v > MAX_WALK_MS) return false

        samples.addLast(Sample(v, dt))
        total += dt
        while (total > WINDOW_SECONDS && samples.size > 1) {
            total -= samples.removeFirst().seconds
        }
        return true
    }

    /** Seconds of walking data currently in the window. */
    fun measuredSeconds(): Double = total

    /** Pure measured pace in m/s, or null if nothing measured yet. */
    fun measuredMs(): Double? {
        if (total <= 0.0) return null
        return samples.sumOf { it.speed * it.seconds } / total
    }

    /** Prior blended with measurements, in m/s. */
    fun speedMs(): Double {
        val m = measuredMs() ?: return priorMs
        return (priorMs * PRIOR_SECONDS + m * total) / (PRIOR_SECONDS + total)
    }
}
