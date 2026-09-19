package uk.co.ncartmell.mtg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import uk.co.ncartmell.mtg.app.store.AndroidStorageContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super, so the splash theme is in place for the first frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AndroidStorageContext.context = applicationContext
        setContent { App() }
    }
}
