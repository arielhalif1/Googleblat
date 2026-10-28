# Googleblat

A personal Android app store. Only the apps listed in [`catalog.json`](catalog.json) can be installed, and installing anything else is made hard but still possible.

It works by becoming the phone's **device owner**, the mechanism companies use for managed phones. While **locked** (the normal state):

- **Google Play is paused.** It can't be opened, but its background services keep running, so banking apps' integrity checks still work. Tapping *Install from Google Play* in Googleblat opens Play on that one app's page and keeps Play usable for 10 minutes. Every night from 03:00 to 04:00, Play is open so it can update your apps.
- **Apps that aren't on the list get hidden** as soon as they install, even ones installed through Play during that 10-minute window. Hiding keeps the app's data. System apps, keyboards, launchers, accessibility services and device admins are never hidden.
- **Sideloading is blocked:** "install unknown apps" is off for every app, USB debugging is off (so no `adb install`), Second space and work profiles are blocked, and Xiaomi GetApps is hidden.
- **Apps from other sources work too.** The store installs GitHub, F-Droid or direct-download apps itself, after checking each APK's signing certificate against the one pinned in the catalog.

**Unlocking** takes the password, then a 60-second wait. Everything above is lifted for 15 minutes. While unlocked, the store lists apps outside the catalog so you can choose to keep them. The store can't be uninstalled, force-stopped, or have its data cleared from Settings. You can only remove it from inside the app while unlocked, or with a factory reset.

> If you forget the password, the only way out is a factory reset. Back up your photos and other data.

## Build

The toolchain lives in your home folder: JDK 17 in `~/.local/toolchain`, Android SDK in `~/Library/Android/sdk`.

```sh
export JAVA_HOME=$(ls -d ~/.local/toolchain/jdk-17*/Contents/Home)
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"   # adb
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with `keystore/googleblat.jks`. The passwords are in `keystore.properties`. Both files are git-ignored. **Back them up.** If you lose the key, the store can't update itself, and you have to remove it and set it up again.

## Install on the phone (one time)

Device-owner mode can only be turned on while the phone has **no accounts** and **only one user**. You add your accounts back afterwards.

1. **Developer options:** Settings → About phone → tap *OS version* 7 times.
2. Settings → Additional settings → Developer options. Turn on **USB debugging**, **Install via USB** and **USB debugging (Security settings)**. Xiaomi requires you to be signed in to a Xiaomi account (and sometimes to have a SIM inserted) to turn these on, so do this step *before* step 3.
3. **Remove every account:** Settings → Accounts & sync. Remove the Google account(s) and the Xiaomi account. To remove the Xiaomi account you may need to turn off *Find device* first.
4. **Remove extra users:** turn off *Second space* (Settings → Special features / Privacy) and delete any **Dual apps** (Settings → Apps → Dual apps). Dual apps creates a hidden user, which makes the next step fail.
5. Connect the phone to the Mac and run:
   ```sh
   adb install app/build/outputs/apk/release/app-release.apk
   adb shell dpm set-device-owner com.googleblat.store/.AdminReceiver
   ```
   If it says *"already several users"* or *"already some accounts"*, go back to steps 3 and 4. To find what's left, run `adb shell pm list users` and `adb shell dumpsys account`.
6. Add your Google and Xiaomi accounts back. Sign in to Play now, while it's still open.
7. Open **Googleblat** on the phone and choose a password. The next screen lists your current apps that aren't in the catalog: tick the ones to keep, then tap **Lock now**.

Optional: you could give the password to someone you trust instead of keeping it yourself.

## Adding an app

Edit `catalog.json` on GitHub. The phone reads it from
`https://raw.githubusercontent.com/arielhalif1/Googleblat/main/catalog.json`, so **this repo must be public**, or you need to change `CATALOG_URL` in `app/build.gradle.kts`.

For a Play app, the name and package are enough. The package is the `id=` part of the app's Play link (`play.google.com/store/apps/details?id=com.waze`):

```json
{ "name": "Waze", "packageName": "com.waze", "description": "Navigation" }
```

For an app Googleblat downloads itself, add a `source` and the pinned certificate:

```json
{
  "name": "Some app",
  "packageName": "org.example.app",
  "certSha256": ["<signing cert SHA-256>"],
  "source": { "type": "github", "repo": "owner/name", "asset": "\\.apk$" }
}
```

Source types:

| `type`   | Fields                                       | Gets                                                   |
|----------|----------------------------------------------|--------------------------------------------------------|
| `play`   | — (the default when `source` is left out)    | opens Google Play on the app's page                    |
| `github` | `repo` (`owner/name`), `asset` (regex)       | the matching asset in the latest GitHub release        |
| `fdroid` | —                                            | F-Droid's suggested version of `packageName`           |
| `url`    | `url`                                        | that exact URL                                         |

**Getting `certSha256`** (non-Play sources only): download the APK on the Mac and run `tools/cert-sha256.sh the.apk`. Or skip that and tap *Install* with an empty list: the store refuses to install, but it shows the APK's signer so you can copy it into the catalog. Only pin a certificate you trust. That one value is what stops anyone from slipping you a different APK.

`block` holds extra package names to hide while locked, for example another preinstalled store. Removing an app from the catalog hides it at the next check.

## Updating the store itself

Bump `versionCode` in `app/build.gradle.kts`, build, and attach `app-release.apk` to a new GitHub release. Then tap *Update* on the Googleblat entry.

## Limitations and things to check on the phone

- **Pausing Play:** if Android refuses to pause Play, the store hides it instead and shows a warning. In that case, banking apps that rely on Play integrity checks may fail while locked. Tell me if you see that warning.
- **Updates:** apps that say "update required" open Play, which is paused. Use *Update on Google Play* in Googleblat instead. Whether Play also auto-updates in the background while paused isn't confirmed. The nightly window covers it either way.
- **Timing:** an app installed from Play during the 10-minute window gets hidden right away if Googleblat is running, and otherwise at the next check, within about 15 minutes. The alarms are inexact and can run a few minutes late in deep sleep.
- **Direct downloads:** only single-APK apps are supported.
- **Factory reset** always removes Googleblat. That's on purpose: it is the last-resort way out.
