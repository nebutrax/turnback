package si.trax.turnback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Foreground service: watches location, computes walking time back to the target and
 * alerts when it's time to head back so you arrive by the deadline (minus buffer).
 */
class TrackService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "si.trax.turnback.START"
        const val ACTION_STOP = "si.trax.turnback.STOP"
        const val CH_TRACK = "tracking"
        const val CH_ALERT = "alerts_v1"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2
        private const val TICK_MS = 30_000L
        private const val PREWARN_MS = 10 * 60_000L
        private const val RENAG_MS = 5 * 60_000L
        private const val PROGRESS_M = 200.0
        private const val ARRIVE_M = 75f
        private const val DETOUR_DEFAULT = 1.3
        private const val AUTO_STOP_AFTER_DEADLINE_MS = 3 * 3600_000L

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_TRACK, "Tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing countdown while tracking"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Head-back alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts when it's time to head back"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
                    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
    }

    private var lm: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false
    private var lastLoc: Location? = null
    private var leftTarget = false

    private var detour = DETOUR_DEFAULT
    private var lastRouteAt = 0L
    private var lastRouteLoc: Location? = null
    private var routeInFlight = false

    private var preWarned = false
    private var goAlertAt = 0L
    private var nagDist = 0.0

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown("Stopped")
            return START_NOT_STICKY
        }
        createChannels(this)
        val notif = ongoing("Starting…", "Waiting for location")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ONGOING, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ONGOING, notif)
            }
        } catch (e: Exception) {
            Prefs.get(this).edit()
                .putBoolean(Prefs.RUNNING, false)
                .putString(Prefs.STATUS, "Could not start tracking: ${e.message}")
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        // Fresh start or settings changed: reset alert state.
        preWarned = false
        goAlertAt = 0L
        nagDist = 0.0
        lastRouteAt = 0L
        lastRouteLoc = null
        leftTarget = false
        detour = DETOUR_DEFAULT
        Prefs.get(this).edit().putBoolean(Prefs.RUNNING, true).apply()

        if (!running) {
            running = true
            startLocation()
            handler.post(tick)
        } else {
            evaluate()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val mgr = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = mgr
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (mgr.isProviderEnabled(p)) {
                    mgr.requestLocationUpdates(p, 20_000L, 10f, this, Looper.getMainLooper())
                    mgr.getLastKnownLocation(p)?.let { consider(it) }
                }
            } catch (e: Exception) {
                // provider not available or permission revoked
            }
        }
    }

    private fun consider(n: Location) {
        val o = lastLoc
        if (o == null) {
            lastLoc = n
            return
        }
        val newer = n.time - o.time
        val better = when {
            newer > 60_000L -> true
            newer < -60_000L -> false
            else -> n.accuracy <= o.accuracy * (if (newer > 0) 2f else 1f)
        }
        if (better) lastLoc = n
    }

    override fun onLocationChanged(location: Location) {
        consider(location)
        evaluate()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun evaluate() {
        if (!running) return
        val p = Prefs.get(this)
        if (!Prefs.hasTarget(p)) {
            shutdown("No destination set")
            return
        }
        val tLat = Prefs.targetLat(p)
        val tLon = Prefs.targetLon(p)
        val label = p.getString(Prefs.TARGET_LABEL, null) ?: "destination"
        val deadline = p.getLong(Prefs.DEADLINE, 0L)
        val bufferMs = p.getInt(Prefs.BUFFER_MIN, 10) * 60_000L
        val speedMs = p.getFloat(Prefs.SPEED_KMH, 4.5f).coerceIn(1f, 15f) / 3.6
        val now = System.currentTimeMillis()

        if (now > deadline + AUTO_STOP_AFTER_DEADLINE_MS) {
            shutdown("Stopped (3 h past deadline)")
            return
        }

        val loc = lastLoc
        if (loc == null) {
            val t = "Waiting for GPS…"
            val x = "Back by ${Fmt.time(this, deadline)}"
            updateOngoing(t, x)
            status("$t\n$x")
            return
        }

        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, tLat, tLon, res)
        val straight = res[0].toDouble()
        if (straight > ARRIVE_M * 4) leftTarget = true
        if (straight <= ARRIVE_M) {
            if (leftTarget) {
                alert("You're back", "Arrived at $label. Tracking stopped.", loud = false)
                shutdown("Arrived ✓ (${Fmt.time(this, now)})")
            } else {
                val t = "At destination — go explore"
                val x = "Back by ${Fmt.time(this, deadline)}"
                updateOngoing(t, x)
                status("$t\n$x")
            }
            return
        }

        maybeRoute(p, loc, tLat, tLon, straight)

        val walkM = straight * detour
        val etaMs = (walkM / speedMs * 1000.0).toLong()
        val leaveAt = deadline - etaMs - bufferMs
        val untilLeave = leaveAt - now
        val lateBy = now + etaMs - deadline
        val text = "${Fmt.dist(walkM)} · ${Fmt.mins(etaMs)} walk · back by ${Fmt.time(this, deadline)}"

        val title: String
        when {
            untilLeave > PREWARN_MS -> {
                title = "Leave by ${Fmt.time(this, leaveAt)} (in ${Fmt.mins(untilLeave)})"
            }
            untilLeave > 0 -> {
                title = "Leave in ${Fmt.mins(untilLeave)} (${Fmt.time(this, leaveAt)})"
                if (!preWarned) {
                    preWarned = true
                    alert("Head back soon", "Leave in ${Fmt.mins(untilLeave)}. $text", loud = true)
                }
            }
            else -> {
                title = if (lateBy > 0) "Late by ${Fmt.mins(lateBy)} — go now!" else "Time to head back!"
                if (goAlertAt == 0L) {
                    alert(title, text, loud = true)
                    goAlertAt = now
                    nagDist = walkM
                } else if (now - goAlertAt >= RENAG_MS) {
                    // Only nag again if you're not getting closer.
                    if (nagDist - walkM < PROGRESS_M) alert(title, text, loud = true)
                    goAlertAt = now
                    nagDist = walkM
                }
            }
        }
        updateOngoing(title, text)
        status("$title\n$text")
    }

    private fun maybeRoute(
        p: android.content.SharedPreferences,
        loc: Location,
        tLat: Double,
        tLon: Double,
        straight: Double,
    ) {
        if (!p.getBoolean(Prefs.USE_ROUTING, true) || routeInFlight || straight < 150.0) return
        val now = System.currentTimeMillis()
        val moved = lastRouteLoc?.distanceTo(loc) ?: Float.MAX_VALUE
        if (now - lastRouteAt < 3 * 60_000L && moved < 150f) return
        routeInFlight = true
        lastRouteAt = now
        lastRouteLoc = loc
        val fromLat = loc.latitude
        val fromLon = loc.longitude
        executor.execute {
            val d = Routing.footDistanceMeters(fromLat, fromLon, tLat, tLon)
            handler.post {
                routeInFlight = false
                if (d != null && straight > 0) {
                    detour = (d / straight).coerceIn(1.0, 3.0)
                    evaluate()
                }
            }
        }
    }

    private fun status(s: String) {
        Prefs.get(this).edit().putString(Prefs.STATUS, s).apply()
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ongoing(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH_TRACK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent())
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif), "Stop", stop
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun updateOngoing(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, ongoing(title, text))
    }

    private fun alert(title: String, text: String, loud: Boolean) {
        val n = Notification.Builder(this, if (loud) CH_ALERT else CH_TRACK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    private fun stopLocation() {
        try { lm?.removeUpdates(this) } catch (e: Exception) { }
        lm = null
    }

    private fun shutdown(msg: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopLocation()
        Prefs.get(this).edit()
            .putBoolean(Prefs.RUNNING, false)
            .putString(Prefs.STATUS, msg)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopLocation()
        executor.shutdownNow()
        Prefs.get(this).edit().putBoolean(Prefs.RUNNING, false).apply()
        super.onDestroy()
    }
}
