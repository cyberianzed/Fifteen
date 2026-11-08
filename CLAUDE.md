# Nimisham development notes for coding agents

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

Key source (`composeApp/src/commonMain/kotlin/app/nimisham/`): `Model.kt`, `Scoring.kt` (points, bounties, artifacts,
quotes), `Store.kt` (state + actions + saving), `Sync.kt`, `Analytics.kt`, `Pets.kt`, `Components.kt`, `CheckIn.kt`,
`App.kt`, `Screens.kt` (Now, Garden), `Insights.kt`, `Charts.kt`, `Settings.kt`.
JVM shared (`src/jvmShared`): time, HTTP, files. Desktop: `DesktopMain.kt` (tray, floating bar, check-in window,
autostart). Android: `AndroidApp.kt` (activities, receivers, settings), `TrackerService.kt` (notification, alarm,
overlay, widget, sync loop).

Build: `./gradlew.bat :composeApp:desktopTest` (tests + screenshots in `composeApp/build/screenshots`; README images in
`docs/screenshots`), `:composeApp:run`, `:composeApp:assembleDebug`, `:composeApp:createDistributable`.
Public releases: add `-Pnimisham.public=true` so the owner's Supabase URL/key are NOT baked in, then
`:composeApp:assembleRelease` (signed with the key named in `local.properties`) and zip the `createDistributable` folder.
Check the outputs contain no Supabase URL before uploading. `packageMsi` currently fails in WiX (light.exe exit 216).
