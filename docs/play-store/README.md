# Publishing AirLAN on Google Play

Everything the Play Console asks for, prepared. The steps marked **you** need the
developer's own account and identity; everything else is in this folder.

The store texts and images live in `fastlane/metadata/android/<locale>/` at the root of
the repository (`en-US` and `nl-NL`), the layout both Google Play and F-Droid read. They are
the single source: edit them there, then copy them into the Play Console.

| File (per locale) | Use |
|---|---|
| `title.txt`, `short_description.txt`, `full_description.txt` | Store listing texts (Play: name max 30, short description max 80, full description max 4000 characters) |
| `changelogs/<versionCode>.txt` | Release notes for that version (max 500 characters) |
| `images/phoneScreenshots/` | Phone screenshots, 1080×1920 (9:16) |
| `en-US/images/featureGraphic.png` | Feature graphic, 1024×500 |
| `en-US/images/icon.png` | App icon, 512×512 |
| `docs/privacy.md` | Privacy policy, published at https://erdtsieck.github.io/airlan/privacy |

## 1. Developer account (**you**)

1. Create a personal developer account at https://play.google.com/console ($25, once).
2. Complete identity verification. Google shows the developer name and contact email on
   the listing.

## 2. Create the app

- App name: *AirLAN – AC remote*; package name `io.github.erdtsieck.airlan` (must match `applicationId`, cannot change later); default language English (United States).
- App or game: App. Free or paid: Free.
- Accept the declarations.

## 3. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://erdtsieck.github.io/airlan/privacy |
| Ads | No ads |
| App access | All functionality is available without special access. The app controls Mitsubishi Heavy Industries air conditioners with a WF-RAC module on the same network. Without one, tap *Try a demo unit* on the *Manage units* screen (shown on first start when no unit is found): it adds a simulated air conditioner, marked *Demo*, with every function of a real one, including the switch-off timer. The demo unit needs no permission, also not *Nearby devices* on Android 17 and newer. |
| Content rating | Questionnaire category *Utility, productivity, communication or other*; answer No to everything. Expected: Everyone / PEGI 3. |
| Target audience | 18 and over (the app is not designed for children) |
| News app | No |
| Data safety | Does the app collect or share user data? **No.** (No data leaves the phone; see the privacy policy.) |
| Government app / financial features / health | No |
| Exact alarms | If the Console asks: the core function is a switch-off timer the user sets for their air conditioner; the alarm must fire at the chosen time. The permission is requested in context and the app falls back to an inexact alarm when it is denied. |
| Local network access | If the Console asks: the app's only function is controlling air conditioners on the user's home network; it connects to nothing else. |

## 4. Store listing

Main store listing → copy the texts from `fastlane/metadata/android/en-US/`; add a translation
for Dutch (nl-NL) from `nl-NL/`. Upload `en-US/images/icon.png`, `en-US/images/featureGraphic.png`
and the screenshots of the matching language. Category: *House & Home*. Contact email:
required, shown publicly. Website: https://github.com/erdtsieck/airlan. Privacy policy:
https://erdtsieck.github.io/airlan/privacy.

## 5. Release

Build the bundle (needs the upload key, see below):

```sh
cd android
./gradlew :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

1. **Closed testing first.** New personal developer accounts must run a closed test with
   at least 12 testers who stay opted in for 14 days in a row before production can be
   requested. Testing → Closed testing → create a track, upload the `.aab`, add testers by
   email (or a Google Group), share the opt-in link.
2. Let Google manage the app signing key (Play App Signing) when asked; the key in this
   project is the *upload* key.
3. After 14 days: Dashboard → *Apply for production* and answer the questions about the
   test, then create a production release with the same bundle (or a newer one).

For every new version, raise `versionCode` (and `versionName`) in `android/app/build.gradle.kts`,
add `fastlane/metadata/android/{en-US,nl-NL}/changelogs/<versionCode>.txt`, and tag the release
commit `v<versionName>` (for example `v0.3.0`): F-Droid picks up new versions from those tags.

## Upload key

The upload key is not in the repository. Gradle reads `~/.airlan/signing.properties`
(or the file in `AIRLAN_SIGNING`):

```properties
storeFile=C:/Users/<you>/.airlan/airlan-upload.jks
storePassword=…
keyAlias=airlan-upload
keyPassword=…
```

Keep a backup of both files, for example in a password manager. If the upload key is lost,
Play support can reset it, but that takes days.

## Recreating the screenshots

`tools/play-screenshots.py` makes all ten (five per language: two units, the airflow card,
managing units, dark) on an emulator, against three stand-in units (`tools/fake-unit.mjs`);
its header says how to start them. It captures at 1080×2400 and crops to 1080×1920, the top
or, for the airflow card, the bottom: Play requires phone screenshots in 16:9 or 9:16.
