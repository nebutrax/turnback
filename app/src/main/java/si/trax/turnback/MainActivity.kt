package si.trax.turnback

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_MAP = 10
        private const val REQ_PERMS = 11
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var targetText: TextView
    private lateinit var coordsInput: EditText
    private lateinit var btnDeadline: Button
    private lateinit var bufferInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var routingCheck: CheckBox
    private lateinit var btnStartStop: Button

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        TrackService.createChannels(this)

        statusText = findViewById(R.id.statusText)
        targetText = findViewById(R.id.targetText)
        coordsInput = findViewById(R.id.coordsInput)
        btnDeadline = findViewById(R.id.btnDeadline)
        bufferInput = findViewById(R.id.bufferInput)
        speedInput = findViewById(R.id.speedInput)
        routingCheck = findViewById(R.id.routingCheck)
        btnStartStop = findViewById(R.id.btnStartStop)

        bufferInput.setText(prefs.getInt(Prefs.BUFFER_MIN, 10).toString())
        speedInput.setText(String.format(Locale.US, "%.1f", prefs.getFloat(Prefs.SPEED_KMH, 4.5f)))
        routingCheck.isChecked = prefs.getBoolean(Prefs.USE_ROUTING, true)

        findViewById<Button>(R.id.btnHere).setOnClickListener { useCurrentLocation() }
        findViewById<Button>(R.id.btnMap).setOnClickListener { openMap() }
        findViewById<Button>(R.id.btnSetCoords).setOnClickListener {
            handleText(coordsInput.text.toString(), "Pinned point")
        }
        btnDeadline.setOnClickListener { pickDeadline() }
        btnStartStop.setOnClickListener {
            if (prefs.getBoolean(Prefs.RUNNING, false)) stopTracking() else startTracking()
        }
        findViewById<TextView>(R.id.batteryLink).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Not available on this phone")
            }
        }

        ensureDeadlineDefault()
        requestPerms()
        if (savedInstanceState == null) handleIntent(intent)
        refresh()
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
        saveSettings()
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

    private fun setTarget(lat: Double, lon: Double, label: String) {
        Prefs.setTarget(prefs, lat, lon, label)
        coordsInput.setText("")
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

    // ---- deadline ----

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

    // ---- tracking ----

    private fun saveSettings() {
        val buffer = bufferInput.text.toString().trim().toIntOrNull()?.coerceIn(0, 120) ?: 10
        val speed = speedInput.text.toString().trim().replace(',', '.').toFloatOrNull()?.coerceIn(1f, 15f) ?: 4.5f
        prefs.edit()
            .putInt(Prefs.BUFFER_MIN, buffer)
            .putFloat(Prefs.SPEED_KMH, speed)
            .putBoolean(Prefs.USE_ROUTING, routingCheck.isChecked)
            .apply()
    }

    private fun startTracking() {
        saveSettings()
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
        prefs.edit().putString(Prefs.STATUS, "Starting…").apply()
        startForegroundService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_START))
    }

    private fun restartIfRunning() {
        if (prefs.getBoolean(Prefs.RUNNING, false)) {
            saveSettings()
            startForegroundService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_START))
        }
    }

    private fun stopTracking() {
        startService(Intent(this, TrackService::class.java).setAction(TrackService.ACTION_STOP))
    }

    // ---- UI ----

    private fun refresh() {
        targetText.text = if (Prefs.hasTarget(prefs)) {
            val label = prefs.getString(Prefs.TARGET_LABEL, null) ?: "Destination"
            String.format(Locale.US, "%s\n%.5f, %.5f", label, Prefs.targetLat(prefs), Prefs.targetLon(prefs))
        } else {
            "Not set"
        }
        val d = prefs.getLong(Prefs.DEADLINE, 0L)
        val day = if (d > 0 && !DateUtils.isToday(d)) " (tomorrow)" else ""
        btnDeadline.text = "Back by ${Fmt.time(this, d)}$day"
        val running = prefs.getBoolean(Prefs.RUNNING, false)
        btnStartStop.text = if (running) "Stop" else "Start"
        val s = prefs.getString(Prefs.STATUS, null)
        if (s.isNullOrEmpty()) {
            statusText.visibility = View.GONE
        } else {
            statusText.visibility = View.VISIBLE
            statusText.text = s
        }
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
