# Nimisham (നിമിഷം)

A fifteen-minute focus tracker for Windows and Android, sharing one database.

Every block (15, 30 or 60 minutes) ends with a check-in: how focused were you? Pick **✕ drifted, 50, 75, 90 or 100**,
see what you earned, then choose what's next. A calm progress bar runs in between, and a companion (a tree, a cloud
spirit or a hunter) grows with your day.

## What's in it

- **Check-in popup**: rate the block, see your points, bonuses and any artifact you found, then pick the next category,
  an optional "one thing" and 15 / 30 / 60 minutes. Categories can be added right there.
- **Windows**: tray icon with a progress ring, an always-on-top floating bar (drag it anywhere) with your companion
  beside it (click it to make it jump), check-in popup with a soft chime, starts with Windows, one instance only.
- **Android**: permanent countdown notification with quick buttons (100% / 75% / Drifted continue with the same
  category), heads-up check-in when a block ends (exact alarm, survives Doze), optional overlay over other apps (a thin
  line at the top or a small draggable pill), home-screen widget with the companion, starts after reboot, battery
  optimisation shortcut, and a burn-in-safe Focus view that keeps the screen on.
- **Companions**: Maram (tree: grows, blossoms, fruits on perfect blocks), Pulli (cloud spirit: glows, orbiting
  lights), Hunter (bulks up with level, aura colour by rank E→S). They sleep when tracking is off.
- **Insights**: day / week / month / year with focused time, points, average focus, comebacks, perfect blocks,
  longest run, breaks and finds (with change vs the previous period); points per day against your goal, focus trend
  with a moving average, time by category, rating mix, a weekday × hour heatmap and a year calendar. Hover or tap
  charts for values.
- **Garden**: pick a companion, level and rank, a forest of one companion per day, and the artifact collection.
- **Sync**: local-first JSON log on each device, pushed to and pulled from Supabase every few seconds.

## Points (no punishment)

- Per 15 minutes: 100% → 10, 90% → 9, 75% → 7, 50% → 4.
- **Drifted** gives +1 for the honest check-in and charges a **comeback**: the next block at 75%+ counts ×2.
- **Momentum**: good blocks back-to-back give ×1.2, then ×1.5. A drift pauses it.
- Random **golden blocks** (×3) and **warming eggs** (75%+ hatches a rare-or-better artifact).
- **Variable drops**: 28 artifacts in four rarities; dry spells raise the odds (pity timer).
- Breaks earn 3 points. XP never goes down. Each day starts a fresh companion.

## Set up the shared database (once, ~5 minutes)

1. Create a free project at <https://supabase.com>.
2. SQL Editor → paste `supabase/schema.sql` → Run.
3. Project Settings → API: copy the **Project URL** and the **publishable (anon) key** into `local.properties`:
   ```
   supabase.url=https://YOURPROJECT.supabase.co
   supabase.key=YOUR_PUBLISHABLE_KEY
   ```
   (They get baked into both builds, so the phone only needs your email and password. You can also paste them in
   Settings instead.)
4. Authentication → Users → **Add user** (email + password, tick auto-confirm). Or use "Create account" in the app
   and confirm the email.
5. In the app: Settings → Sync → Sign in, on both devices.

Without this, everything still works on each device separately.

## Build

JDK 17+, Android SDK (`sdk.dir` in `local.properties`).

```
./gradlew.bat :composeApp:desktopTest     # logic tests + screenshots in composeApp/build/screenshots
./gradlew.bat :composeApp:run             # desktop app
./gradlew.bat :composeApp:packageMsi      # Windows installer (autostart works from the installed app)
./gradlew.bat :composeApp:assembleDebug   # Android APK
```

On the phone after installing: allow notifications, then in Settings → On this phone tap **Allow background
running**, and turn on the floating bar if you want it (grant "display over other apps"). On Xiaomi / Oppo / Vivo /
Realme / Samsung also enable Autostart and "No restrictions" battery in the system app settings.
