package uk.co.ncartmell.mtg.app.pixels

/**
 * Desktop gets no dice.
 *
 * There is no Bluetooth in the Java standard library, and reaching one through a native
 * bridge would mean a different one for each of Windows, macOS and Linux — three platform
 * halves and three ways to fail, for the build least likely to be sitting on a table with
 * a die next to it. The app reports the feature as unsupported and never mentions it.
 */
actual fun createPixelsLink(): PixelsLink = UnsupportedPixelsLink()
