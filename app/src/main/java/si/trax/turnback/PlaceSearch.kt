package si.trax.turnback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val address: String, val lat: Double, val lon: Double)

data class SearchResult(val places: List<Place>, val source: String, val error: String?)

/** Place search: Google Places API (New) Text Search if a key is set, else OSM Nominatim. Blocking. */
object PlaceSearch {
    private const val UA = "TurnBack/0.1 (Android; github.com/nebutrax/turnback)"

    fun search(ctx: Context, googleKey: String?, query: String, biasLat: Double?, biasLon: Double?): SearchResult {
        var gErr: String? = null
        if (!googleKey.isNullOrBlank()) {
            try {
                return SearchResult(google(ctx, googleKey.trim(), query, biasLat, biasLon), "Google", null)
            } catch (e: Exception) {
                gErr = e.message ?: e.javaClass.simpleName
            }
        }
        return try {
            SearchResult(nominatim(query, biasLat, biasLon), "OpenStreetMap", gErr)
        } catch (e: Exception) {
            SearchResult(emptyList(), "none", (gErr?.let { "Google: $it; " } ?: "") + "OSM: ${e.message}")
        }
    }

    private fun google(ctx: Context, key: String, query: String, biasLat: Double?, biasLon: Double?): List<Place> {
        val body = JSONObject()
            .put("textQuery", query)
            .put("maxResultCount", 8)
            .put("languageCode", Locale.getDefault().language)
        if (biasLat != null && biasLon != null) {
            body.put(
                "locationBias", JSONObject().put(
                    "circle", JSONObject()
                        .put("center", JSONObject().put("latitude", biasLat).put("longitude", biasLon))
                        .put("radius", 30000.0)
                )
            )
        }
        val conn = URL("https://places.googleapis.com/v1/places:searchText").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Goog-Api-Key", key)
        conn.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
        conn.setRequestProperty("X-Android-Package", ctx.packageName)
        Routing.certSha1(ctx)?.let { conn.setRequestProperty("X-Android-Cert", it) }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = try { JSONObject(err).getJSONObject("error").optString("message") } catch (e: Exception) { err.take(120) }
                throw RuntimeException("HTTP $code $msg")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = json.optJSONArray("places") ?: return emptyList()
            val out = ArrayList<Place>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val loc = p.optJSONObject("location") ?: continue
                out.add(
                    Place(
                        name = p.optJSONObject("displayName")?.optString("text", "") ?: "",
                        address = p.optString("formattedAddress", ""),
                        lat = loc.getDouble("latitude"),
                        lon = loc.getDouble("longitude"),
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun nominatim(query: String, biasLat: Double?, biasLon: Double?): List<Place> {
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=")
            .append(URLEncoder.encode(query, "UTF-8"))
            .append("&accept-language=").append(Locale.getDefault().language)
        if (biasLat != null && biasLon != null) {
            sb.append(
                String.format(
                    Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                    biasLon - 0.3, biasLat + 0.3, biasLon + 0.3, biasLat - 0.3
                )
            )
        }
        val conn = URL(sb.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Place>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val display = p.optString("display_name", "")
                val name = p.optString("name", "").ifEmpty { display.substringBefore(",") }
                val lat = p.optString("lat").toDoubleOrNull() ?: continue
                val lon = p.optString("lon").toDoubleOrNull() ?: continue
                out.add(Place(name, display, lat, lon))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
