# Publishing AirLAN on Google Play

Everything the Play Console asks for, prepared. The steps marked **you** need the
developer's own account and identity; everything else is in this folder.

| File | Use |
|---|---|
| `listing-en.md`, `listing-nl.md` | Store listing texts (name, short and full description) |
| `screenshots/en/`, `screenshots/nl/` | Phone screenshots, 1080×2160 |
| `feature-graphic.png` | Feature graphic, 1024×500 |
| `icon-512.png` | App icon, 512×512 |
| `../privacy.md` | Privacy policy, published at https://erdtsieck.github.io/airlan/privacy |

## 1. Developer account (**you**)

1. Create a personal developer account at https://play.google.com/console ($25, once).
2. Complete identity verification. Google shows the developer name and contact email on
   the listing.

## 2. Create the app

- App name: *AirLAN – local AC control*; default language English (United States).
- App or game: App. Free or paid: Free.
- Accept the declarations.

## 3. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://erdtsieck.github.io/airlan/privacy |
| Ads | No ads |
| App access | All functionality is available without special access. It needs a Mitsubishi Heavy Industries air conditioner with a WF-RAC module on the same network; without one the app shows the *Manage units* screen. Developers can run `node tools/fake-unit.mjs` from the repository as a stand-in. |
| Content rating | Questionnaire category *Utility, productivity, communication or other*; answer No to everything. Expected: Everyone / PEGI 3. |
| Target audience | 18 and over (the app is not designed for children) |
| News app | No |
| Data safety | Does the app collect or share user data? **No.** (No data leaves the phone; see the privacy policy.) |
| Government app / financial features / health | No |
| Exact alarms | If the Console asks: the core function is a switch-off timer the user sets for their air conditioner; the alarm must fire at the chosen time. The permission is requested in context and the app falls back to an inexact alarm when it is denied. |
| Local network access | If the Console asks: the app's only function is controlling air conditioners on the user's home network; it connects to nothing else. |

## 4. Store listing

Main store listing → copy from `listing-en.md`; add a translation for Dutch (nl-NL) from
`listing-nl.md`. Upload `icon-512.png`, `feature-graphic.png` and the screenshots of the
matching language. Category: *House & Home*. Contact email: required, shown publicly.

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

For every new version, raise `versionCode` (and `versionName`) in `android/app/build.gradle.kts`.

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

Run the stand-in units (`tools/fake-unit.mjs` with `--host`, `--power`, `--mode`,
`--temp`, `--indoor`, `--outdoor`), add them in the emulator by address, and capture at
1080×2400, cropped to 1080×2160: Play allows at most a 2:1 aspect ratio.
