package dev.gab8bit.madovai

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class MadovaiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // osmdroid: identify ourselves per the OSM tile usage policy, and keep the tile
        // cache inside app-private storage (no storage permission needed).
        Configuration.getInstance().apply {
            load(this@MadovaiApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = Config.OSM_USER_AGENT
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        container = AppContainer(this)
    }
}
