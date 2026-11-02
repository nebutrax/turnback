package si.trax.turnback

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var speedText: TextView
    private lateinit var routingSwitch: Switch
    private lateinit var googleKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Settings"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs.get(this)

        speedText = findViewById(R.id.speedText)
        routingSwitch = findViewById(R.id.routingSwitch)
        googleKeyInput = findViewById(R.id.googleKeyInput)

        findViewById<Button>(R.id.btnSpeedMinus).setOnClickListener { changeSpeed(-0.5f) }
        findViewById<Button>(R.id.btnSpeedPlus).setOnClickListener { changeSpeed(0.5f) }
        routingSwitch.isChecked = prefs.getBoolean(Prefs.USE_ROUTING, true)
        routingSwitch.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.USE_ROUTING, on).apply() }

        googleKeyInput.setText(prefs.getString(Prefs.GOOGLE_KEY, ""))
        findViewById<Button>(R.id.btnTestKey).setOnClickListener { testGoogleKey() }
        findViewById<TextView>(R.id.certText).text =
            "Package: $packageName\nSHA-1: ${Routing.certSha1(this)?.chunked(2)?.joinToString(":") ?: "?"}"

        findViewById<android.view.View>(R.id.batteryRow).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Not available on this phone")
            }
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        findViewById<TextView>(R.id.versionText).text = "TurnBack ${version ?: ""}".trim()

        showSpeed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        saveKey()
        super.onPause()
    }

    private fun changeSpeed(delta: Float) {
        val v = (prefs.getFloat(Prefs.SPEED_KMH, 4.5f) + delta).coerceIn(1f, 15f)
        prefs.edit().putFloat(Prefs.SPEED_KMH, v).apply()
        showSpeed()
    }

    private fun showSpeed() {
        speedText.text = String.format(Locale.getDefault(), "%.1f km/h", prefs.getFloat(Prefs.SPEED_KMH, 4.5f))
    }

    private fun saveKey() {
        prefs.edit().putString(Prefs.GOOGLE_KEY, googleKeyInput.text.toString().trim()).apply()
    }

    private fun testGoogleKey() {
        saveKey()
        val key = prefs.getString(Prefs.GOOGLE_KEY, null)
        if (key.isNullOrBlank()) {
            toast("Enter a key first")
            return
        }
        // Test route: from the destination (or Ljubljana centre) ~500 m north.
        val lat = if (Prefs.hasTarget(prefs)) Prefs.targetLat(prefs) else 46.0569
        val lon = if (Prefs.hasTarget(prefs)) Prefs.targetLon(prefs) else 14.5058
        toast("Testing…")
        val appCtx = applicationContext
        Thread {
            val r = Routing.walk(appCtx, key, lat, lon, lat + 0.0045, lon)
            val msg = when {
                r == null -> "Google failed: ${Routing.lastGoogleError ?: "unknown"}"
                r.source != "Google" -> "Google failed (${Routing.lastGoogleError ?: "unknown"}), OSRM fallback works"
                else -> "Google OK: ${Fmt.dist(r.distanceM)}, ${Fmt.mins(((r.durationS ?: 0.0) * 1000).toLong())}"
            }
            runOnUiThread { if (!isDestroyed) Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
