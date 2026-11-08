# Contributing to Fifteen

Thanks for wanting to help! Fifteen is a small, friendly project, so you don't need to be an expert to contribute.

## Good first contributions

- **Add-ons.** Little helpers your companion brings up in a chat bubble: posture checks, stretch breaks, a "stand up" nudge, breathing exercises, journaling prompts… The add-on system is on the [`addons`](../../tree/addons) branch.
- **Companions.** A new avatar drawn in code (see `Pets.kt`), with its own sounds (`Sound.kt`).
- **Artifacts and quotes.** New collectibles or encouraging lines in `Scoring.kt`.
- **Translations and wording.** Clearer, kinder text anywhere in the app.
- **Bug reports.** Open an issue with your phone or computer model and what happened.

## Adding an add-on

On the `addons` branch, everything lives in two files:

1. `Nudges.kt` holds the conversation. Add a `kind` (for example `"stretch"`), when it should start (in `tick`), what the companion says at each stage (in `step`), and what each answer does (in `advance`).
2. `NudgesUi.kt` holds the Add-ons tab. Add a card with an on/off switch and its settings.

Add a test in `NudgeTest.kt` that walks through the conversation. Keep it gentle: no guilt, no lost points, and easy to switch off.

## Build and test

You need JDK 17+ and the Android SDK (`sdk.dir=...` in `local.properties`).

```bash
./gradlew :composeApp:desktopTest      # tests + screenshots in composeApp/build/screenshots
./gradlew :composeApp:run              # desktop app
./gradlew :composeApp:assembleDebug    # Android APK
```

Please check your change on a narrow phone screen too. The screenshot test renders every screen at 360dp (`p360-*.png`).

## The house rules

1. **Points never go down.** Encourage, never punish.
2. **Calm and minimal.** Lots of breathing space; nothing flashy that pulls attention.
3. **Works offline.** Sync is optional.
4. **No tracking, no ads.**

By contributing, you agree your work is released under the [MIT License](LICENSE).
