package uk.co.ncartmell.mtg.app.store

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop storage: one file per key under the user's home directory.
 *
 * Writes go to a temporary file and are then moved into place, so an interrupted write
 * cannot leave a half-written profile list behind. It has to be a move: this used to copy
 * the temporary file over the target and delete it, which is three operations and, more
 * to the point, not atomic — an interrupted copy leaves exactly the half-written file the
 * temporary was there to prevent. The game in progress is written on every life change,
 * so this is also the difference between one filesystem operation per tap and three.
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
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Both files sit in the same directory, so this should not happen; a
            // filesystem that refuses anyway still gets its write.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

actual fun createStorage(): Storage =
    FileStorage(File(System.getProperty("user.home"), ".mtg-life-tracker"))
