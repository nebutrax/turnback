package si.trax.turnback

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Walking route distance from the public FOSSGIS OSRM foot instance. Blocking. */
object Routing {
    fun footDistanceMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double? {
        val url = URL(
            String.format(
                Locale.US,
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false",
                fromLon, fromLat, toLon, toLat
            )
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "TurnBack/0.1 (Android; github.com/nebutrax/turnback)")
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("code") != "Ok") return null
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) null else routes.getJSONObject(0).getDouble("distance")
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
