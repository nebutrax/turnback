package si.trax.turnback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** lat/lon are NaN for Google suggestions until resolved via [PlaceSearch.resolve]. */
data class Place(val name: String, val address: String, val lat: Double, val lon: Double, val placeId: String? = null)

data class SearchResult(val places: List<Place>, val source: String, val error: String?)

/** Place search: Google Places Autocomplete (New) if a key is set, else OSM Nominatim. Blocking. */
object PlaceSearch {
    private const val UA = "TurnBack/0.1 (Android; github.com/nebutrax/turnback)"

    fun search(ctx: Context, googleKey: String?, query: String, biasLat: Double?, biasLon: Double?, session: String): SearchResult {
        var gErr: String? = null
        if (!googleKey.isNullOrBlank()) {
            try {
                return SearchResult(google(ctx, googleKey.trim(), query, biasLat, biasLon, session), "Google", null)
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

    /**
     * Google Autocomplete (New). Results carry a placeId; coordinates are fetched with [resolve]
     * only for the picked one, using the same session token. Autocomplete within such a session is
     * billed as "Session Usage" (free), the details call as Place Details Essentials (10k/month free).
     */
    private fun google(ctx: Context, key: String, query: String, biasLat: Double?, biasLon: Double?, session: String): List<Place> {
        val body = JSONObject()
            .put("input", query)
            .put("sessionToken", session)
            .put("languageCode", Locale.getDefault().language)
        if (biasLat != null && biasLon != null) {
            body.put(
                "locationBias", JSONObject().put(
                    "circle", JSONObject()
                        .put("center", JSONObject().put("latitude", biasLat).put("longitude", biasLon))
                        .put("radius", 30000.0)
                )
            )
            body.put("origin", JSONObject().put("latitude", biasLat).put("longitude", biasLon))
        }
        val json = googleCall(ctx, key, "https://places.googleapis.com/v1/places:autocomplete", body.toString())
        val arr = json.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val pp = arr.getJSONObject(i).optJSONObject("placePrediction") ?: continue
            val id = pp.optString("placeId", "")
            if (id.isEmpty()) continue
            val sf = pp.optJSONObject("structuredFormat")
            val main = sf?.optJSONObject("mainText")?.optString("text", "") ?: ""
            val second = sf?.optJSONObject("secondaryText")?.optString("text", "") ?: ""
            val full = pp.optJSONObject("text")?.optString("text", "") ?: ""
            out.add(Place(main.ifEmpty { full }, second, Double.NaN, Double.NaN, id))
        }
        return out
    }

    /** Fetches coordinates for a Google place (Place Details Essentials: location only). Blocking. */
    fun resolve(ctx: Context, key: String, place: Place, session: String): Place {
        val id = place.placeId ?: return place
        val url = "https://places.googleapis.com/v1/places/" + URLEncoder.encode(id, "UTF-8") +
            "?sessionToken=" + URLEncoder.encode(session, "UTF-8")
        val json = googleCall(ctx, key.trim(), url, null, fieldMask = "location")
        val loc = json.getJSONObject("location")
        return place.copy(lat = loc.getDouble("latitude"), lon = loc.getDouble("longitude"))
    }

    private fun googleCall(ctx: Context, key: String, url: String, postBody: String?, fieldMask: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-Goog-Api-Key", key)
        if (fieldMask != null) conn.setRequestProperty("X-Goog-FieldMask", fieldMask)
        conn.setRequestProperty("X-Android-Package", ctx.packageName)
        Routing.certSha1(ctx)?.let { conn.setRequestProperty("X-Android-Cert", it) }
        try {
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = try { JSONObject(err).getJSONObject("error").optString("message") } catch (e: Exception) { err.take(120) }
                throw RuntimeException("HTTP $code $msg")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
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
