package uk.co.ncartmell.mtg.app

import androidx.compose.runtime.Composable

/**
 * Holds the screen on while it is composed.
 *
 * A game of Commander can sit for minutes between changes to a life total, which is
 * exactly long enough for a phone to lock itself in the middle of a turn.
 */
@Composable
expect fun KeepScreenAwake()
