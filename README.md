# MTG Life Tracker

A life tracker for Magic: The Gathering, built with Kotlin Multiplatform and Compose
Multiplatform. One shared codebase for Android, iOS and desktop.

Everybody sits around one device, so the board rearranges itself for the number of
players and every panel turns to face the person it belongs to. On the four-player board
that is a quarter turn — two players sit down each side, and their panels read along the
card's long axis, which is roughly twice the room for a life total.

## Download

Built artefacts are attached to each [release](https://github.com/ncartmell/mtg-life-tracker/releases/latest).

| Platform | File | What you need to know |
| --- | --- | --- |
| Android | `…-<version>.apk` | Android 8.0 or newer. It is sideloaded, not from the Play Store, so Android will ask you to allow installs from whichever app you download it with. |
| Windows | `…-windows.exe` | An installer. SmartScreen will warn about an unrecognised publisher — More info, then Run anyway. |
| Windows (managed) | `…-windows-msi.msi` | The same thing as an MSI, for anyone deploying it centrally. |
| macOS | `…-macos-arm64.dmg` / `…-macos-x64.dmg` | Not notarised, so Gatekeeper refuses it on a double-click. Right-click the app and choose Open the first time. |
| Linux | `…-linux.deb` | Debian and Ubuntu derivatives. |

Both are signed with my own key rather than a store identity, so both will warn you that
the developer is unknown. That is expected for a build handed out this way, and it is the
reason to check you got the file from this repository.

The desktop packager only builds for the machine it runs on, so each of those comes off
its own runner in `.github/workflows/release.yml`, which fires on a `v*` tag. The APK is
built and signed locally, because the signing key is deliberately not in CI.

iOS is not downloadable at all. Handing out an iOS build needs an Apple Developer Program
membership and TestFlight, so building it from Xcode is the only route.

## What it does

**Setting up a game**

- Pick the number of players (2–6) and the starting life total; the board layout changes
  to match
- Turn commander damage and poison counters on or off
- Fill each seat with a saved profile or a guest
- Give a player two commanders, tracked separately

**During a game**

- Life, poison, and commander damage tracked separately for every commander at the table,
  so a player with two commanders has two counters against each opponent
- Commander damage also reduces life — the rule most often got wrong when tracking by
  hand, and most of the reason this exists
- The board is all panels and no toolbar: the controls live behind one button where the
  panels meet, within reach of every seat
- Life is adjusted by tapping anywhere down the left or right third of your own panel
  rather than a small glyph; the middle third does nothing, so the card is still safe to
  touch
- Hold instead of tap and it repeats, faster the longer you hold — a twenty-point swing,
  or ten poison counters, is one press rather than twenty. The same holds for poison and
  commander damage in a player's detail view
- Players drop out automatically on zero life, ten poison, or twenty-one damage from any
  single commander
- Star, for five players: everyone sits in seat order and your opponents are the two you
  are not sitting next to. You win the moment both are out, with three players still in
  — each panel shows which two seats it is waiting on, and how many are down
- A player who is out has their colour drained away and is marked plainly, so the board
  reads at a glance; the winner is ringed and labelled on the board, not only in a dialog
- A player can be marked as unable to lose, for Platinum Angel and the like. Counters are
  not frozen underneath it — life still falls past zero — and everything that built up is
  applied the moment the flag is cleared, which is what happens when the permanent
  granting it dies
- Mill, concede, and "something just killed me" as explicit outs
- A player removed by mistake can be brought back; whatever was lethal is lifted just
  clear of its threshold, so someone restored from zero life returns on one
- Roll to decide who goes first; every seat's number lands on its own panel and the
  winner's panel is outlined, rather than a single name appearing from nowhere
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

**Release builds**

```sh
./gradlew :composeApp:assembleRelease                    # APK
./gradlew :composeApp:packageReleaseDistributionForCurrentOS   # .dmg, .msi or .deb
```

The APK is signed from `keystore.properties`, which is not in the repository — copy
`keystore.properties.example` and follow the comment in it to generate a keystore. Without
that file the release APK still builds, it is simply unsigned and will not install.

Keep the keystore and its passwords backed up. Android only installs an update over an
existing install when both are signed with the same key, so losing it means every user has
to uninstall before they can upgrade.

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
