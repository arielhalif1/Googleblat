# Googleblat

A personal Android app store that only installs the apps listed in [`catalog.json`](catalog.json), and makes installing anything else hard but still possible.

It works by becoming the phone's **device owner**, the mechanism companies use for managed phones. While **locked** (the normal state), the store:

- blocks "install unknown apps" for every app, including browsers and file managers
- turns off USB debugging, so `adb install` doesn't work either
- blocks Second space and work profiles, which could otherwise get around the block
- hides Google Play and Xiaomi GetApps (they can still be brought back)
- installs and updates catalog apps silently, after checking each APK's signing certificate against the one pinned in the catalog

**Unlocking** takes the password, then a 60-second wait. It lifts all of the above for 15 minutes and then locks again automatically. The store can't be uninstalled, force-stopped, or have its data cleared from Settings. You can only remove it from inside the app while it's unlocked, or with a factory reset.

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
6. Open **Googleblat** on the phone and choose a password. The phone is now locked.
7. Add your Google and Xiaomi accounts back.

Optional: you could give the password to someone you trust instead of keeping it yourself.

## Adding an app

Edit `catalog.json` on GitHub. The phone reads it from
`https://raw.githubusercontent.com/arielhalif1/Googleblat/main/catalog.json`, so **this repo must be public**, or you need to change `CATALOG_URL` in `app/build.gradle.kts`.

```json
{
  "name": "Signal",
  "packageName": "org.thoughtcrime.securesms",
  "description": "Messenger",
  "certSha256": ["<signing cert SHA-256>"],
  "source": { "type": "url", "url": "https://updates.signal.org/android/Signal-Android-website-prod-universal-release-7.x.apk" }
}
```

Source types:

| `type`   | Fields                                       | Gets                                                   |
|----------|----------------------------------------------|--------------------------------------------------------|
| `github` | `repo` (`owner/name`), `asset` (regex)       | the matching asset in the latest GitHub release        |
| `fdroid` | —                                            | F-Droid's suggested version of `packageName`           |
| `url`    | `url`                                        | that exact URL                                         |

**Getting `certSha256`:** download the APK on the Mac and run `tools/cert-sha256.sh the.apk`. Or skip that and tap *Install* with an empty list: the store refuses to install, but it shows the APK's signer so you can copy it into the catalog. Only pin a certificate you trust. That one value is what stops anyone from slipping you a different APK.

`block` holds extra package names to hide while locked, for example another preinstalled store.

## Updating the store itself

Bump `versionCode` in `app/build.gradle.kts`, build, and attach `app-release.apk` to a new GitHub release. Then tap *Update* on the Googleblat entry.

## Limitations

- Only single-APK apps are supported. Split APKs and app bundles from Play aren't.
- Google Play Services stays installed, so apps that need it keep working. Apps that only exist on Play won't be available. Use an alternative source, or unlock and sideload them.
- The relock alarm is inexact. In deep sleep it can fire a few minutes late. Opening the app or rebooting also relocks.
- A factory reset always removes Googleblat. That's on purpose: it is the last-resort way out.
