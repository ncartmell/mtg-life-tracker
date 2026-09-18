# MTG Life Tracker

A life tracker for Magic: The Gathering, built with Kotlin Multiplatform and Compose
Multiplatform. One shared codebase for Android, iOS and desktop.

Everybody sits around one device, so the board rearranges itself for the number of
players and the panels on the far side are rotated to face them.

## What it does

**Setting up a game**

- Pick the number of players (2–6) and the starting life total; the board layout changes
  to match
- Turn commander damage and poison counters on or off
- Fill each seat with a saved profile or a guest
- Give a player two commanders, tracked separately

**During a game**

- Life, poison and per-commander damage
- Commander damage also reduces life — the rule most often got wrong when tracking by
  hand, and most of the reason this exists
- Players drop out automatically on zero life, ten poison, or twenty-one damage from any
  single commander
- Mill, concede, and "something just killed me" as explicit outs
- A player removed by mistake can be brought back
- Roll to decide who goes first; the result is shown, not hidden
- Restart with the same players and settings

**Between games**

- Profiles persist locally with a colour used for that player's panel
- Wins and losses are recorded automatically when a game finishes
- A leaderboard, ordered by wins then fewest losses — deliberately not by win rate, since
  one win from one game should not top a table

## Structure

```
engine/       Pure Kotlin. All the rules, no UI, no platform APIs. Fully tested.
composeApp/   Compose Multiplatform UI, plus per-platform storage.
```

The split is the point. Every rule — elimination thresholds, whether commander damage
reduces life, what counts as a win — lives in `:engine`, which has no Compose dependency
and whose tests run on any JDK with no emulator, simulator or Android SDK in sight.
`AppState` in the app module contains no logic beyond wiring.

## Running it

**Desktop**

```sh
./gradlew :composeApp:run
```

**Android** — open in Android Studio, or:

```sh
./gradlew :composeApp:installDebug
```

**iOS** — open `iosApp/iosApp.xcodeproj` in Xcode and run. The shared framework is built
by Gradle as part of the Xcode build.

**Tests**

```sh
./gradlew :engine:jvmTest
```

The engine tests run without an Android SDK or Xcode. If neither is installed,
`:composeApp` is skipped automatically and `:engine` still builds — see
`settings.gradle.kts`.

## Storage

Profiles are stored locally on each platform through a deliberately small interface:

| Platform | Backing store |
| --- | --- |
| Android | `SharedPreferences` |
| iOS | `NSUserDefaults` |
| Desktop | A JSON file under `~/.mtg-life-tracker`, written via a temporary file |

Reads are tolerant — an unreadable or missing store starts an empty profile list rather
than refusing to open. Losing a leaderboard is annoying; a life tracker that will not
start mid-game is worse.

## Not done yet

- No undo history beyond restoring an eliminated player
- No per-game history; only the running win/loss totals are kept
- Two-headed giant and other team formats are not modelled
