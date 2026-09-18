package uk.co.ncartmell.mtg.app.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Set once from the Application or Activity before the UI is created.
 */
object AndroidStorageContext {
    lateinit var context: Context
}

private class SharedPreferencesStorage(prefs: SharedPreferences) : Storage {
    private val prefs = prefs
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

actual fun createStorage(): Storage {
    val prefs = AndroidStorageContext.context
        .getSharedPreferences("mtg-life-tracker", Context.MODE_PRIVATE)
    return SharedPreferencesStorage(prefs)
}
