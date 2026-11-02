package si.trax.turnback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/** Hand-off to Google Maps walking navigation. */
object Nav {
    private const val MAPS_PKG = "com.google.android.apps.maps"

    fun navIntent(lat: Double, lon: Double): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", lat, lon)))
            .setPackage(MAPS_PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun webIntent(lat: Double, lon: Double): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                String.format(
                    Locale.US,
                    "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=walking&dir_action=navigate",
                    lat, lon
                )
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(ctx: Context, lat: Double, lon: Double) {
        try {
            ctx.startActivity(navIntent(lat, lon))
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(webIntent(lat, lon))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(ctx, "No maps app found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
