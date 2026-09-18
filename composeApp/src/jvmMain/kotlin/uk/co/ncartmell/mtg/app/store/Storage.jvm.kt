package uk.co.ncartmell.mtg.app.store

import java.io.File

/**
 * Desktop storage: one file per key under the user's home directory.
 *
 * Writes go to a temporary file and are then moved into place, so an interrupted write
 * cannot leave a half-written profile list behind.
 */
private class FileStorage(private val directory: File) : Storage {

    init {
        directory.mkdirs()
    }

    override fun read(key: String): String? =
        File(directory, "$key.json").takeIf { it.isFile }?.readText()

    override fun write(key: String, value: String) {
        val target = File(directory, "$key.json")
        val temp = File(directory, "$key.json.tmp")
        temp.writeText(value)
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }
}

actual fun createStorage(): Storage =
    FileStorage(File(System.getProperty("user.home"), ".mtg-life-tracker"))
