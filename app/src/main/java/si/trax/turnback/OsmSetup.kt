package si.trax.turnback

import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

object OsmSetup {
    fun init(ctx: Context) {
        val cfg = Configuration.getInstance()
        cfg.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        cfg.userAgentValue = ctx.packageName
        cfg.osmdroidBasePath = File(ctx.filesDir, "osmdroid")
        cfg.osmdroidTileCache = File(ctx.cacheDir, "osmdroid-tiles")
    }
}
