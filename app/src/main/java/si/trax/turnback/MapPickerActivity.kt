package si.trax.turnback

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MapPickerActivity : Activity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
    }

    private lateinit var map: MapView
    private var myLoc: MyLocationNewOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = File(filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(cacheDir, "osmdroid-tiles")

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
        }

        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val start = if (!lat.isNaN() && !lon.isNaN()) GeoPoint(lat, lon)
        else lastKnown() ?: GeoPoint(46.0569, 14.5058)
        map.controller.setCenter(start)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
            overlay.enableMyLocation()
            map.overlays.add(overlay)
            myLoc = overlay
        }

        val root = FrameLayout(this)
        root.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val cross = ImageView(this).apply { setImageResource(R.drawable.ic_crosshair) }
        root.addView(cross, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        val credit = TextView(this).apply {
            text = "© OpenStreetMap contributors"
            textSize = 10f
            setTextColor(0xFF333333.toInt())
            setBackgroundColor(0xAAFFFFFF.toInt())
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        root.addView(
            credit,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            setBackgroundColor(0xCCFFFFFF.toInt())
        }
        val me = Button(this).apply {
            text = "My location"
            setOnClickListener {
                val p = myLoc?.myLocation
                if (p != null) map.controller.animateTo(p)
                else Toast.makeText(this@MapPickerActivity, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            }
        }
        val ok = Button(this).apply {
            text = "Use this point"
            setOnClickListener {
                val c = map.mapCenter
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_LAT, c.latitude).putExtra(EXTRA_LON, c.longitude)
                )
                finish()
            }
        }
        bar.addView(me, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ok, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): GeoPoint? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.getLastKnownLocation(p)?.let { return GeoPoint(it.latitude, it.longitude) }
            } catch (e: Exception) { }
        }
        return null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        map.onResume()
        myLoc?.enableMyLocation()
    }

    override fun onPause() {
        myLoc?.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        map.onDetach()
        super.onDestroy()
    }
}
