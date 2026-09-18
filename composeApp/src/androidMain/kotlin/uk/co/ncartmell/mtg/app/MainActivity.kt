package uk.co.ncartmell.mtg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import uk.co.ncartmell.mtg.app.store.AndroidStorageContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidStorageContext.context = applicationContext
        setContent { App() }
    }
}
