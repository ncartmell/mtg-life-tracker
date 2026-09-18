package uk.co.ncartmell.mtg.app.store

import platform.Foundation.NSUserDefaults

private class UserDefaultsStorage : Storage {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun read(key: String): String? = defaults.stringForKey(key)
    override fun write(key: String, value: String) = defaults.setObject(value, key)
}

actual fun createStorage(): Storage = UserDefaultsStorage()
