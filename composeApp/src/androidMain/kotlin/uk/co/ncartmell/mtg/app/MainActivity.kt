package uk.co.ncartmell.mtg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import uk.co.ncartmell.mtg.app.pixels.AndroidPixels
import uk.co.ncartmell.mtg.app.store.AndroidStorageContext

class MainActivity : ComponentActivity() {

    /** Whoever asked for the permissions, waiting on the answer. */
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val answer = onPermissionResult
        onPermissionResult = null
        answer?.invoke(granted.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super, so the splash theme is in place for the first frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AndroidStorageContext.context = applicationContext
        AndroidPixels.context = applicationContext
        // Only an Activity can ask, so the link borrows this one. Nothing is requested
        // until somebody actually goes looking for a die.
        AndroidPixels.requestPermissions = { onResult ->
            onPermissionResult = onResult
            permissionLauncher.launch(AndroidPixels.permissions)
        }
        setContent { App() }
    }

    override fun onDestroy() {
        // The Activity outlives nothing here, but the object holding this reference does.
        AndroidPixels.requestPermissions = null
        super.onDestroy()
    }
}
