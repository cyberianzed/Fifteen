# Fifteen (formerly Nimisham) development notes for coding agents

One shared Kotlin Compose Multiplatform app for Windows desktop and Android phones.

Invariants:
1. Points never go down. A drift (0) earns +1 and a comeback bounty; momentum pauses rather than resets; XP only grows.
2. Local-first: every action works offline and writes the local JSON log; sync is best-effort.
3. Sync is one Supabase table `records` (see `supabase/schema.sql`): upsert dirty records, pull by server `seq`,
   last write wins by `updatedAt`. Never commit a Supabase key; it lives in `local.properties` (baked into builds).
4. Rewards are decided once by the device that rated the block (seeded by block id) and synced, never re-rolled.
5. Only one live block: newest start wins after a merge (`SyncEngine.normalizeLive`).
6. Minimal, dark, lots of space; the mellow-yellow accent (`Ink.mellow`). Companions are drawn in code (`Pets.kt`) so
   the Android widget can render the same art via `CanvasDrawScope`.
7. No runtime calls to Anthropic/Claude APIs.
8. The floating companion is the main surface: a big pet by default (double-click/double-tap for the extended bar),
   right-click/long-press to hide it or turn Fifteen off (only while resting). A hidden pet comes back when its
   block ends or a new block starts (`Store.checkPetReturn`). Add-ons only start and show while this device's pet
   is out (`NudgeEngine.petOut`), and their timers restart when it comes back, so nothing piles up.
9. Android package is `app.fifteen` (was `app.nimisham`); desktop data lives in `%APPDATA%\Fifteen` and moves over
   from `%APPDATA%\Nimisham` once.

Key source (`composeApp/src/commonMain/kotlin/app/fifteen/`): `Model.kt`, `Scoring.kt` (points, bounties, artifacts,
quotes), `Store.kt` (state + actions + saving), `Sync.kt`, `Analytics.kt`, `Pets.kt`, `Components.kt`, `CheckIn.kt`,
`App.kt`, `Screens.kt` (Now, Garden), `Insights.kt`, `Charts.kt`, `Settings.kt`.
JVM shared (`src/jvmShared`): time, HTTP, files. Desktop: `DesktopMain.kt` (tray, floating bar, check-in window,
autostart). Android: `AndroidApp.kt` (activities, receivers, settings), `TrackerService.kt` (notification, alarm,
line/pill overlay, widget, sync loop), `PetOverlay.kt` (the floating pet chat head, its menu and add-on chat).

Build: `./gradlew.bat :composeApp:desktopTest` (tests + screenshots in `composeApp/build/screenshots`; README images in
`docs/screenshots`), `:composeApp:run`, `:composeApp:assembleDebug`, `:composeApp:createDistributable`.
Public releases: add `-Pfifteen.public=true` so the owner's Supabase URL/key are NOT baked in, then
`:composeApp:assembleRelease` (signed with the key named in `local.properties`) and zip the `createDistributable` folder.
Check the outputs contain no Supabase URL before uploading. Windows installer: `packaging/windows/fifteen.iss` (Inno Setup 6, `ISCC.exe /DAppVersion=x.y.z`) wraps the `createDistributable` folder into `composeApp/build/installer/Fifteen-Setup.exe`; ship it next to the zip. jpackage's `packageMsi`/`packageExe` fail (WiX light.exe exit 216: ICE validation needs VBScript, which this Windows build lacks).
