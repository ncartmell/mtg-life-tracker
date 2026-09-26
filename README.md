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
| iOS | `…-ios-unsigned.ipa` | **Unsigned.** It will not install on its own. Re-sign it with your own Apple ID using AltStore, SideStore or Sideloadly, which is the usual route for an app that is not on the App Store. A free Apple ID gives a signature that lasts seven days before it needs refreshing. |

Both are signed with my own key rather than a store identity, so both will warn you that
the developer is unknown. That is expected for a build handed out this way, and it is the
reason to check you got the file from this repository.

The desktop packager only builds for the machine it runs on, so each of those comes off
its own runner in `.github/workflows/release.yml`, which fires on a `v*` tag. The APK is
built and signed locally, because the signing key is deliberately not in CI.

The iOS build is unsigned because signing one for a device needs an Apple Developer
Program membership. The `.ipa` itself is real and complete — what is missing is a
signature, which the sideloading tools above add using your own Apple ID.

## What it does

**Setting up a game**

- Pick a format, then the number of players it allows and a starting life total; the
  board layout changes to match
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
  rather than a small glyph. The middle third never changes life, which is why it is the
  one place a swipe can safely mean something else
- Hold instead of tap and it repeats, faster the longer you hold — a twenty-point swing,
  or ten poison counters, is one press rather than twenty. The same holds for poison and
  commander damage in a player's detail view
- Players drop out automatically on zero life, ten poison, or twenty-one damage from any
  single commander
- Five formats, all of them arrangements of teams so the engine has one win rule rather
  than five:
  - **Free-for-all** — last player standing
  - **Star** (5) — your opponents are the two you are not sitting next to; win when both
    are out, with three players still in. The board is laid out as a star so who is next
    to whom is visible
  - **Two-Headed Giant** (4) — two pairs, each sharing one life total and one set of
    poison counters, at 30 life and a poison threshold of 15
  - **Archenemy** (3–6) — seat one against everybody else
  - **Emperor** (6) — two teams of three; a team falls the moment its emperor does,
    however healthy its generals are
- Whose turn it is and how many turns have been taken, passed clockwise round the board
  as it is actually drawn — on a two-by-two board that is top-left, top-right,
  bottom-right, bottom-left, not seat order — skipping anyone who is out, with a game
  clock and a turn clock
- Energy, experience, storm, rad, the Ring and speed alongside poison, shown on a panel
  only once they are actually in play, and offered in the detail view the same way: a
  counter in play gets a row, the rest are one tap away. The Ring and speed stop at four,
  which a held press would otherwise run past in under a second
- Commander tax per commander rather than per player, so a pair of partners each climb
  their own ladder. It sits with the commanders, and shows what the next cast will cost
- The monarchy and the initiative, held by one player at a time and dropped by anyone who
  is knocked out. They are the only two designations in the game that work that way
- The city's blessing, which reads like them and is not: Ascend is permanent, and the
  whole table can have it at once, so it is a flag on each player rather than a seat
- Day and night, or neither, as one value for the table
- The Undercity, room by room, since taking the initiative is what sends you into it.
  Each player walks their own, every room carries its own text, and the branch at most
  rooms is offered as a choice rather than guessed at
- Planechase, layered over any format: the planar die — four blanks, one chaos, one
  planeswalk — and a note of whichever plane is in play
- A swipe across the middle of a panel opens that player's detail. There is no button
  competing with their name for the width
- A roll screen: the whole table's roll for first player laid out in order, tie-breaks
  shown as the separate rounds they were, and ordinary dice from d4 to d20 plus a coin.
  Several at once, since plenty of cards ask for that and adding them up by hand is the
  sort of thing the app is for, and any number of sides for the cards that want a d3 or
  a d7. Or skip the roll entirely and simply name whoever is starting — not every table
  decides this by rolling, and staging a roll to record a decision already made is silly
- A [Pixels](https://gamewithpixels.com) die, for anyone who has one. The app finds it
  over Bluetooth and takes its rolls, and none of it appears unless a die is actually
  connected — the app's own dice are untouched and still do everything they did.
  - Loose rolls land where a tapped d20 already landed, thrown by shaking something
    rather than pressing a button
  - Whatever die is connected is the die you get: the app asks it what it is when it
    connects and reads its rolls accordingly, so a d6 is reported as a d6 and a
    percentile die reads the tens printed on it rather than counting from one. A d20 is
    the fallback when a die is on firmware too old to say
  - For who goes first, the die is passed round the board in the order people are really
    sitting in — on a two-by-two board that is 0, 1, 3, 2, not seat order — and it lights
    up in the colour of whoever it is waiting on, so the table can see whose turn it is
    to roll from across the room. A tie sends it round again between the players who
    tied, and the winner's colour flashes when it settles
  - One throw reported twice as the die comes to rest is still one throw, which matters
    when the next number belongs to somebody else
  - The die is remembered, so the next game reaches for it on its own rather than
    scanning again. It also lights up in the current player's colour as the turn passes,
    and in the colour of whoever takes the monarchy or the initiative — switchable off,
    since a light that goes off every turn is either lovely or maddening depending on
    the group — and says so when it is nearly flat
  - Android and iOS only. The desktop builds have no Bluetooth and never mention dice
- Every finished game kept — seats, who won, how long — under the leaderboard, so the
  running totals can be traced back to the games behind them
- The screen is held awake while a game is on
- A launch screen on both platforms, the same ink and the same mark, rather than a white
  page while the app starts
- The game is written down as it is played, so a phone that kills the app in the
  background hands the table back the same game rather than an empty setup screen
- A player who is out has their colour drained away and is marked plainly, so the board
  reads at a glance; the winner is ringed and labelled on the board, not only in a dialog
- Counters sit in chips that fill as they approach the number that kills you, so a panel
  gets louder as its player gets closer to losing, and the detail view colours the same
  thresholds rather than leaving somebody on nineteen commander damage to subtract for
  themselves
- Whose turn it is is marked on the player's own name rather than beside it, and the
  life total is sized to the panel it is on, so a four-player board on a tablet is read
  across a room rather than leaving half the card empty
- A player can be marked as unable to lose, for Platinum Angel and the like. Counters are
  not frozen underneath it — life still falls past zero — and everything that built up is
  applied the moment the flag is cleared, which is what happens when the permanent
  granting it dies
- One button to knock a player out, for everything the app cannot see for itself
- Undo, which steps back the last change — the leaderboard and the game history with it,
  so taking back the knockout that ended a game also takes back the win it recorded. A
  held glyph is one step rather than one per repeat, because overshooting a hold is the
  ordinary way to need it and undoing that a point at a time would be no better than
  pressing the other glyph back
- A player removed by mistake can be brought back; whatever was lethal is lifted just
  clear of its threshold, so someone restored from zero life returns on one
- Roll to decide who goes first; every seat's number lands on its own panel and the
  winner's panel is outlined, rather than a single name appearing from nowhere
- Restart with the same players and settings

**Personalising**

- Every seat paints its own panel, guests included: any colour, mixed on RGB sliders or
  taken from the ten presets, laid on solid or run as a gradient into a second colour.
  A seat filled by a profile saves its panel for next time; a guest keeps it for the game
- A profile also sets what its panel says when that player is knocked out
- The last game's format, size and rules are offered again next time, so a regular group
  starts a game in one tap

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

The Pixels die follows the same idea. How a roll-off is decided is a rule, so it is
`RollOff` in `:engine`, tested there; the [protocol](https://github.com/GameWithPixels)
is byte-shuffling with no platform API in it, so it is `PixelsProtocol` in common code,
tested without a die; and what is left for each platform is a short `PixelsLink` that
only carries bytes — `android.bluetooth` on one side, CoreBluetooth on the other, and a
stub on desktop that reports the whole feature unsupported.

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

Profiles, the leaderboard, game history and the game in progress are stored locally on
each platform through a deliberately small interface:

| Platform | Backing store |
| --- | --- |
| Android | `SharedPreferences` |
| iOS | `NSUserDefaults` |
| Desktop | A JSON file under `~/.mtg-life-tracker`, written via a temporary file and moved into place atomically |

Reads are tolerant — an unreadable or missing store starts an empty profile list rather
than refusing to open. Losing a leaderboard is annoying; a life tracker that will not
start mid-game is worse.

## Not done yet

- Nothing is synced or shared between devices; each install keeps its own profiles,
  leaderboard and game history
- The iOS build is unsigned, so it needs re-signing before it will install
- The desktop builds cannot talk to a Pixels die. There is no Bluetooth in the Java
  standard library, and reaching one would mean a separate native bridge for each of
  Windows, macOS and Linux — three ways to fail, for the build least likely to be sitting
  on a table with a die next to it
- Only one die at a time. Several dice at once would let a whole table roll off
  simultaneously rather than passing one round, which is a bigger change than it looks:
  the link holds a single connection throughout
