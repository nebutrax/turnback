package si.trax.turnback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Result of a walking-route lookup. durationS is only set when the source models walking time itself.
 * points (lat, lon) is only filled when geometry was requested.
 */
data class RouteResult(
    val distanceM: Double,
    val durationS: Double?,
    val source: String,
    val points: List<Pair<Double, Double>>? = null,
)

/** Walking routes: Google Routes API (if a key is set), else public FOSSGIS OSRM foot. Blocking. */
object Routing {
    private const val UA = "TurnBack/0.1 (Android; github.com/nebutrax/turnback)"

    /** Approximate walking speed Google's WALK durations assume; used to scale to the user's pace. */
    const val GOOGLE_KMH = 5.0

    fun walk(
        ctx: Context,
        googleKey: String?,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        geometry: Boolean = false,
    ): RouteResult? {
        if (!googleKey.isNullOrBlank()) {
            google(ctx, googleKey.trim(), fromLat, fromLon, toLat, toLon, geometry)?.let { return it }
        }
        return osrm(fromLat, fromLon, toLat, toLon, geometry)
    }

    /** Walking time in ms for a route at the user's speed. */
    fun etaMs(r: RouteResult, speedKmh: Double): Long =
        if (r.durationS != null) (r.durationS * (GOOGLE_KMH / speedKmh) * 1000.0).toLong()
        else (r.distanceM / (speedKmh / 3.6) * 1000.0).toLong()

    /** Decodes a Google/OSRM encoded polyline (precision 5). */
    fun decodePolyline(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0
        var lng = 0
        while (i < s.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < s.length)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            result = 0
            shift = 0
            if (i >= s.length) break
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < s.length)
            lng += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            out.add(lat / 1e5 to lng / 1e5)
        }
        return out
    }

    private fun osrm(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, geometry: Boolean): RouteResult? {
        val overview = if (geometry) "overview=full&geometries=polyline" else "overview=false"
        val url = URL(
            String.format(
                Locale.US,
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%.6f,%.6f?%s",
                fromLon, fromLat, toLon, toLat, overview
            )
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("code") != "Ok") return null
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return null
            val r = routes.getJSONObject(0)
            val pts = if (geometry) r.optString("geometry", "").takeIf { it.isNotEmpty() }?.let { decodePolyline(it) } else null
            RouteResult(r.getDouble("distance"), null, "OSRM", pts)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Last Google error, shown in the app so a bad key / disabled API is visible. */
    @Volatile var lastGoogleError: String? = null

    private fun google(
        ctx: Context,
        key: String,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        geometry: Boolean,
    ): RouteResult? {
        val body = JSONObject()
            .put("origin", waypoint(fromLat, fromLon))
            .put("destination", waypoint(toLat, toLon))
            .put("travelMode", "WALK")
            .toString()
        val conn = URL("https://routes.googleapis.com/directions/v2:computeRoutes").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Goog-Api-Key", key)
        conn.setRequestProperty(
            "X-Goog-FieldMask",
            if (geometry) "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline"
            else "routes.duration,routes.distanceMeters"
        )
        // Needed when the key is restricted to Android apps.
        conn.setRequestProperty("X-Android-Package", ctx.packageName)
        certSha1(ctx)?.let { conn.setRequestProperty("X-Android-Cert", it) }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                lastGoogleError = "HTTP $code " + (try {
                    JSONObject(err).getJSONObject("error").optString("message")
                } catch (e: Exception) { err.take(120) })
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                lastGoogleError = "No walking route found"
                return null
            }
            val r = routes.getJSONObject(0)
            val dist = r.optDouble("distanceMeters", Double.NaN)
            val dur = r.optString("duration", "").removeSuffix("s").toDoubleOrNull()
            if (dist.isNaN() || dur == null) {
                lastGoogleError = "Unexpected response"
                return null
            }
            lastGoogleError = null
            val pts = if (geometry) {
                r.optJSONObject("polyline")?.optString("encodedPolyline", "")
                    ?.takeIf { it.isNotEmpty() }?.let { decodePolyline(it) }
            } else null
            RouteResult(dist, dur, "Google", pts)
        } catch (e: Exception) {
            lastGoogleError = e.javaClass.simpleName + ": " + (e.message ?: "")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun waypoint(lat: Double, lon: Double) = JSONObject().put(
        "location", JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon))
    )

    private var cachedSha1: String? = null

    /** SHA-1 of the APK signing certificate, uppercase hex without colons (what Google expects). */
    @Suppress("DEPRECATION")
    fun certSha1(ctx: Context): String? {
        cachedSha1?.let { return it }
        return try {
            val pm = ctx.packageManager
            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
            sigBytes?.let { bytes ->
                MessageDigest.getInstance("SHA-1").digest(bytes)
                    .joinToString("") { String.format("%02X", it) }
                    .also { cachedSha1 = it }
            }
        } catch (e: Exception) {
            null
        }
    }
}
