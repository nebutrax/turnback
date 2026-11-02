package si.trax.turnback

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Live map: you, the destination and the walking route back, with hand-off to Google Maps. */
class RouteMapActivity : Activity() {

    companion object {
        private const val AUTO_REFRESH_MS = 60_000L
        private const val REFRESH_MOVE_M = 150f
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var routeCheck: CheckBox
    private lateinit var routeLine: Polyline
    private var myLoc: MyLocationNewOverlay? = null
    private val handler = Handler(Looper.getMainLooper())

    private var tLat = 0.0
    private var tLon = 0.0
    private var fetching = false
    private var zoomedOnce = false
    private var hasRoute = false
    private var lastFetchFrom: Location? = null

    private val autoRefresh = object : Runnable {
        override fun run() {
            val me = myLoc?.myLocation
            if (me != null) {
                val here = Location("me").apply { latitude = me.latitude; longitude = me.longitude }
                val prev = lastFetchFrom
                if (prev == null || prev.distanceTo(here) > REFRESH_MOVE_M) fetchRoute()
            }
            handler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        if (!Prefs.hasTarget(prefs)) {
            Toast.makeText(this, "Set a destination first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        tLat = Prefs.targetLat(prefs)
        tLon = Prefs.targetLon(prefs)
        title = "Route back"
        OsmSetup.init(this)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(tLat, tLon))
        }

        routeLine = Polyline(map).apply {
            outlinePaint.color = 0xFF1E88E5.toInt()
            outlinePaint.strokeWidth = dp(6).toFloat()
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isEnabled = false
        }
        map.overlays.add(routeLine)

        val target = Marker(map).apply {
            position = GeoPoint(tLat, tLon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = prefs.getString(Prefs.TARGET_LABEL, null) ?: "Destination"
        }
        map.overlays.add(target)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
            overlay.enableMyLocation()
            overlay.runOnFirstFix { runOnUiThread { fetchRoute() } }
            map.overlays.add(overlay)
            myLoc = overlay
        }

        // ---- layout ----
        val root = FrameLayout(this)
        root.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        info = TextView(this).apply {
            text = if (myLoc == null) "Location permission missing" else "Waiting for GPS…"
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setBackgroundColor(surface(0xEE))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val credit = TextView(this).apply {
            text = "© OpenStreetMap contributors"
            textSize = 10f
            setTextColor(getColor(R.color.text_secondary))
            setBackgroundColor(surface(0xB0))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val creditLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        creditLp.bottomMargin = dp(76)
        root.addView(credit, creditLp)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
            setBackgroundColor(surface(0xEE))
        }
        routeCheck = CheckBox(this).apply {
            text = "Route"
            setTextColor(getColor(R.color.text_primary))
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                routeLine.isEnabled = checked && hasRoute
                map.invalidate()
            }
        }
        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener { fetchRoute() }
        }
        val me = Button(this).apply {
            text = "Me"
            setOnClickListener {
                val p = myLoc?.myLocation
                if (p != null) map.controller.animateTo(p)
                else Toast.makeText(this@RouteMapActivity, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            }
        }
        val nav = Button(this).apply {
            text = "Navigate"
            setOnClickListener { Nav.start(this@RouteMapActivity, tLat, tLon) }
        }
        bar.addView(routeCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(me, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        bar.addView(refresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(nav, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun fetchRoute() {
        if (fetching || isFinishing) return
        val me = myLoc?.myLocation
        if (me == null) {
            info.text = "Waiting for GPS…"
            return
        }
        fetching = true
        info.text = "Loading route…"
        val fLat = me.latitude
        val fLon = me.longitude
        lastFetchFrom = Location("me").apply { latitude = fLat; longitude = fLon }
        val online = prefs.getBoolean(Prefs.USE_ROUTING, true)
        val key = prefs.getString(Prefs.GOOGLE_KEY, null)
        val appCtx = applicationContext
        Thread {
            val r = if (online) Routing.walk(appCtx, key, fLat, fLon, tLat, tLon, geometry = true) else null
            runOnUiThread {
                fetching = false
                if (!isDestroyed) showRoute(r, fLat, fLon)
            }
        }.start()
    }

    private fun showRoute(r: RouteResult?, fLat: Double, fLon: Double) {
        val speed = Prefs.effectiveSpeedKmh(prefs)
        val bufferMs = prefs.getInt(Prefs.BUFFER_MIN, 10) * 60_000L
        val deadline = prefs.getLong(Prefs.DEADLINE, 0L)
        val res = FloatArray(1)
        Location.distanceBetween(fLat, fLon, tLat, tLon, res)
        val rr = r ?: RouteResult(res[0] * 1.3, null, "straight-line estimate", null)

        val eta = Routing.etaMs(rr, speed)
        val leaveAt = deadline - eta - bufferMs
        val now = System.currentTimeMillis()
        val leave = when {
            deadline <= now -> "deadline passed"
            leaveAt > now -> "leave by ${Fmt.time(this, leaveAt)} (in ${Fmt.mins(leaveAt - now)})"
            else -> "leave now"
        }
        val gErr = if (!prefs.getString(Prefs.GOOGLE_KEY, null).isNullOrBlank()) Routing.lastGoogleError else null
        info.text = buildString {
            append("${Fmt.dist(rr.distanceM)} · ${Fmt.mins(eta)} at ${String.format(java.util.Locale.getDefault(), "%.1f", speed)} km/h · ${rr.source}\n")
            append("Back by ${Fmt.time(this@RouteMapActivity, deadline)} → $leave")
            if (gErr != null) append("\nGoogle: $gErr")
        }

        val me = GeoPoint(fLat, fLon)
        val dest = GeoPoint(tLat, tLon)
        val pts = rr.points?.map { GeoPoint(it.first, it.second) }?.takeIf { it.size >= 2 } ?: listOf(me, dest)
        routeLine.setPoints(pts)
        hasRoute = true
        routeLine.outlinePaint.pathEffect =
            if (rr.points == null) DashPathEffect(floatArrayOf(dp(10).toFloat(), dp(8).toFloat()), 0f) else null
        routeLine.isEnabled = routeCheck.isChecked
        map.invalidate()

        if (!zoomedOnce) {
            zoomedOnce = true
            val box = BoundingBox.fromGeoPoints(pts + me + dest)
            val zoom = { map.zoomToBoundingBox(box, true, dp(72)) }
            if (map.isLayoutOccurred) zoom() else map.addOnFirstLayoutListener { _, _, _, _, _ -> zoom() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Card colour with the given alpha (0–255). */
    private fun surface(alpha: Int) = (getColor(R.color.card_bg) and 0x00FFFFFF) or (alpha shl 24)

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        map.onResume()
        myLoc?.enableMyLocation()
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        if (::map.isInitialized) {
            myLoc?.disableMyLocation()
            map.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::map.isInitialized) map.onDetach()
        super.onDestroy()
    }
}
