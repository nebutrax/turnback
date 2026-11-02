package si.trax.turnback

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Extracts coordinates from pasted text, geo: URIs and map links (incl. Google Maps short links). */
object Geo {
    private val strictPatterns = listOf(
        Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
        Regex("""@(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)"""),
        Regex("""[?&](?:q|ll|query|destination|center|daddr)=(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)"""),
        Regex("""geo:(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)"""),
    )
    private val loosePatterns = listOf(
        Regex("""(-?\d{1,2}\.\d+)\s*[,;]\s*(-?\d{1,3}\.\d+)"""),
        Regex("""(-?\d{1,2}\.\d+)\s+(-?\d{1,3}\.\d+)"""),
    )

    fun parse(text: String, strict: Boolean = false): Pair<Double, Double>? {
        val s = try { URLDecoder.decode(text, "UTF-8") } catch (e: Exception) { text }
        val patterns = if (strict) strictPatterns else strictPatterns + loosePatterns
        for (r in patterns) {
            for (m in r.findAll(s)) {
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lon = m.groupValues[2].toDoubleOrNull() ?: continue
                if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    return lat to lon
                }
            }
        }
        return null
    }

    /** Follows redirects of a map link and looks for coordinates. Blocking: call off the main thread. */
    fun resolve(url: String): Pair<Double, Double>? {
        var current = url
        repeat(8) {
            parse(current, strict = true)?.let { return it }
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TurnBack")
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    current = URL(URL(current), location).toString()
                } else {
                    val body = conn.inputStream.bufferedReader().use { r -> r.readText() }
                    return parse(body, strict = true)
                }
            } finally {
                conn.disconnect()
            }
        }
        return parse(current, strict = true)
    }
}
