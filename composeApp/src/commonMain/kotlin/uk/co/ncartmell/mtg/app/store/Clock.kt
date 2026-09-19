package uk.co.ncartmell.mtg.app.store

/**
 * Wall-clock time, for stamping a finished game.
 *
 * The engine stays free of it deliberately — a rules module that can tell the time is a
 * rules module whose tests depend on when they run.
 */
expect fun nowMillis(): Long
