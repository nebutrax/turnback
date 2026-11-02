package si.trax.turnback

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    companion object {
        private const val REQ_MAP = 10
        private const val REQ_PERMS = 11
        private const val BUFFER_STEP = 5
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusCard: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var searchInput: EditText
    private lateinit var targetLabel: TextView
    private lateinit var targetCoords: TextView
    private lateinit var deadlineText: TextView
    private lateinit var deadlineSub: TextView
    private lateinit var bufferText: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnRouteMap: Button
    private lateinit var btnNavigate: Button

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        TrackService.createChannels(this)

        statusCard = findViewById(R.id.statusCard)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        searchInput = findViewById(R.id.searchInput)
        targetLabel = findViewById(R.id.targetLabel)
        targetCoords = findViewById(R.id.targetCoords)
        deadlineText = findViewById(R.id.deadlineText)
        deadlineSub = findViewById(R.id.deadlineSub)
        bufferText = findViewById(R.id.bufferText)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnRouteMap = findViewById(R.id.btnRouteMap)
        btnNavigate = findViewById(R.id.btnNavigate)

        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { submitSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else false
        }
        findViewById<Button>(R.id.btnHere).setOnClickListener { useCurrentLocation() }
        findViewById<Button>(R.id.btnMap).setOnClickListener { openMap() }
        deadlineText.setOnClickListener { pickDeadline() }
        findViewById<Button>(R.id.btnBufferMinus).setOnClickListener { changeBuffer(-BUFFER_STEP) }
        findViewById<Button>(R.id.btnBufferPlus).setOnClickListener { changeBuffer(BUFFER_STEP) }
        btnStartStop.setOnClickListener {
            if (prefs.getBoolean(Prefs.RUNNING, false)) stopTracking() else startTracking()
        }
        btnRouteMap.setOnClickListener {
            if (Prefs.hasTarget(prefs)) startActivity(Intent(this, RouteMapActivity::class.java))
            else toast("Set a destination first")
        }
        btnNavigate.setOnClickListener {
            if (Prefs.hasTarget(prefs)) Nav.start(this, Prefs.targetLat(prefs), Prefs.targetLon(prefs))
            else toast("Set a destination first")
        }

        ensureDeadlineDefault()
        requestPerms()
        if (savedInstanceState == null) handleIntent(intent)
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    // ---- destination ----

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val text = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        handleText(text, "Shared place")
    }

    /** Search box: coordinates / links are used directly, anything else is a place search. */
    private fun submitSearch() {
        val text = searchInput.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        val looksLikeLink = text.contains("http", ignoreCase = true) || text.startsWith("geo:", ignoreCase = true)
        if (looksLikeLink || Geo.parse(text) != null) {
            handleText(text, "Pinned point")
        } else {
            searchPlaces(text)
        }
    }

    private fun handleText(raw: String, fallbackLabel: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val label = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains("http") && !it.startsWith("geo:") && Geo.parse(it) == null }
            ?.take(60) ?: fallbackLabel
        val hasUrl = text.contains("http")
        Geo.parse(text, strict = hasUrl)?.let { (lat, lon) ->
            setTarget(lat, lon, label)
            return
        }
        val url = Regex("""https?://\S+""").find(text)?.value
        if (url == null) {
            toast("No coordinates found")
            return
        }
        toast("Resolving link…")
        Thread {
            val r = try { Geo.resolve(url) } catch (e: Exception) { null }
            runOnUiThread {
                if (r != null) setTarget(r.first, r.second, label)
                else toast("Couldn't get coordinates from that link")
            }
        }.start()
    }

    private fun searchPlaces(q: String) {
        val bias = biasLocation()
        val key = prefs.getString(Prefs.GOOGLE_KEY, null)
        val appCtx = applicationContext
        val session = UUID.randomUUID().toString()
        toast("Searching…")
        Thread {
            val res = PlaceSearch.search(appCtx, key, q, bias?.first, bias?.second, session)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (res.places.isEmpty()) {
                    toast(if (res.error != null) "Search failed: ${res.error}" else "Nothing found")
                    return@runOnUiThread
                }
                if (res.error != null) toast("Google failed, using OpenStreetMap: ${res.error}")
                val items = res.places.map { p ->
                    if (p.address.isNotEmpty() && p.address != p.name) "${p.name}\n${p.address}" else p.name
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Results · ${res.source}")
                    .setItems(items) { _, which ->
                        val p = res.places[which]
                        val label = p.name.ifEmpty { p.address }.take(60)
                        if (p.placeId == null || key.isNullOrBlank()) {
                            setTarget(p.lat, p.lon, label)
                        } else {
                            Thread {
                                val r = try { PlaceSearch.resolve(appCtx, key, p, session) } catch (e: Exception) { null }
                                runOnUiThread {
                                    if (r != null && !r.lat.isNaN()) setTarget(r.lat, r.lon, label)
                                    else toast("Couldn't get location for that place")
                                }
                            }.start()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    /** Where to bias search results: last known position, else current destination. */
    @SuppressLint("MissingPermission")
    private fun biasLocation(): Pair<Double, Double>? {
        if (hasLocationPerm()) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.getLastKnownLocation(p)?.let { return it.latitude to it.longitude }
                } catch (e: Exception) { }
            }
        }
        return if (Prefs.hasTarget(prefs)) Prefs.targetLat(prefs) to Prefs.targetLon(prefs) else null
    }

    private fun setTarget(lat: Double, lon: Double, label: String) {
        Prefs.setTarget(prefs, lat, lon, label)
        searchInput.setText("")
        refresh()
        restartIfRunning()
        toast("Destination set")
    }

    private fun useCurrentLocation() {
        if (!hasLocationPerm()) {
            requestPerms()
            return
        }
        toast("Getting location…")
        OneShotLocation(this, 25f, 20_000L) { loc ->
            if (loc == null) toast("No location fix — is GPS on?")
            else setTarget(loc.latitude, loc.longitude, "Start point (±${loc.accuracy.toInt()} m)")
        }.start()
    }

    private fun openMap() {
        val i = Intent(this, MapPickerActivity::class.java)
        if (Prefs.hasTarget(prefs)) {
            i.putExtra(MapPickerActivity.EXTRA_LAT, Prefs.targetLat(prefs))
            i.putExtra(MapPickerActivity.EXTRA_LON, Prefs.targetLon(prefs))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_MAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null) {
            val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN)
            val lon = data.getDoubleExtra(MapPickerActivity.EXTRA_LON, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) setTarget(lat, lon, "Map point")
        }
    }

    // ---- time ----

    private fun ensureDeadlineDefault() {
        val d = prefs.getLong(Prefs.DEADLINE, 0L)
        if (d < System.currentTimeMillis() && !prefs.getBoolean(Prefs.RUNNING, false)) {
            val c = Calendar.getInstance()
            c.add(Calendar.HOUR_OF_DAY, 2)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val m = c.get(Calendar.MINUTE)
            c.add(Calendar.MINUTE, (15 - m % 15) % 15)
            prefs.edit().putLong(Prefs.DEADLINE, c.timeInMillis).apply()
        }
    }

    private fun pickDeadline() {
        val c = Calendar.getInstance()
        c.timeInMillis = prefs.getLong(Prefs.DEADLINE, System.currentTimeMillis() + 3_600_000L)
        TimePickerDialog(
            this,
            { _, h, m ->
                prefs.edit().putLong(Prefs.DEADLINE, nextOccurrence(h, m)).apply()
                refresh()
                restartIfRunning()
            },
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun nextOccurrence(h: Int, m: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis < System.currentTimeMillis() - 60_000L) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun changeBuffer(delta: Int) {
        val b = (prefs.getInt(Prefs.BUFFER_MIN, 10) + delta).coerceIn(0, 120)
        prefs.edit().putInt(Prefs.BUFFER_MIN, b).apply()
        refresh()
        restartIfRunning()
    }

    // ---- tracking ----

    private fun startTracking() {
        if (!Prefs.hasTarget(prefs)) {
            toast("Set a destination first")
            return
        }
        if (prefs.getLong(Prefs.DEADLINE, 0L) <= System.currentTimeMillis()) {
            toast("Pick a 'back by' time in the future")
            return
        }
        if (!hasLocationPerm()) {
            requestPerms()
            toast("Location permission needed")
            return
        }
        prefs.edit().putString(Prefs.STATUS, "Starting…").putInt(Prefs.STATUS_LEVEL, TrackService.LEVEL_IDLE).apply()
        startForegroundService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_START))
    }

    private fun restartIfRunning() {
        if (prefs.getBoolean(Prefs.RUNNING, false)) {
            startForegroundService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_START))
        }
    }

    private fun stopTracking() {
        startService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_STOP))
    }

    // ---- UI ----

    private fun refresh() {
        val hasTarget = Prefs.hasTarget(prefs)
        if (hasTarget) {
            targetLabel.text = prefs.getString(Prefs.TARGET_LABEL, null) ?: "Destination"
            targetLabel.setTextColor(getColor(R.color.text_primary))
            targetCoords.text = String.format(Locale.US, "%.5f, %.5f", Prefs.targetLat(prefs), Prefs.targetLon(prefs))
            targetCoords.visibility = View.VISIBLE
        } else {
            targetLabel.text = "No destination yet"
            targetLabel.setTextColor(getColor(R.color.text_secondary))
            targetCoords.visibility = View.GONE
        }

        val d = prefs.getLong(Prefs.DEADLINE, 0L)
        deadlineText.text = Fmt.time(this, d)
        deadlineSub.text = if (d > 0 && !DateUtils.isToday(d)) "tomorrow · tap to change" else "tap to change"

        val b = prefs.getInt(Prefs.BUFFER_MIN, 10)
        bufferText.text = if (b == 0) "Arrive right on time" else "Arrive $b min early"

        val running = prefs.getBoolean(Prefs.RUNNING, false)
        btnStartStop.text = if (running) "Stop tracking" else "Start tracking"
        for (v in listOf(btnRouteMap, btnNavigate)) {
            v.isEnabled = hasTarget
            v.alpha = if (hasTarget) 1f else 0.45f
        }

        val s = prefs.getString(Prefs.STATUS, null)
        if (s.isNullOrBlank()) {
            statusCard.visibility = View.GONE
        } else {
            val lines = s.lines()
            statusTitle.text = lines.first()
            val detail = lines.drop(1).joinToString("\n").trim()
            statusDetail.text = detail
            statusDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
            val tint = when (prefs.getInt(Prefs.STATUS_LEVEL, TrackService.LEVEL_IDLE)) {
                TrackService.LEVEL_OK -> R.color.status_ok
                TrackService.LEVEL_SOON -> R.color.status_soon
                TrackService.LEVEL_GO -> R.color.status_go
                else -> R.color.status_idle
            }
            statusCard.backgroundTintList = ColorStateList.valueOf(getColor(tint))
            statusCard.visibility = View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun hasLocationPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
