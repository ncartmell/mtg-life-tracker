package uk.co.ncartmell.mtg.app

import androidx.compose.runtime.Composable

/** A desktop is not going to lock itself mid-game, so there is nothing to hold open. */
@Composable
actual fun KeepScreenAwake() = Unit
