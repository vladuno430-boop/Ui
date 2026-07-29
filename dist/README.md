# Prebuilt APK

`X71-Night-Run-debug.apk` — X71 Night Run, built from this branch.

| | |
|---|---|
| Size | 2.6 MB |
| Package | `com.nightrun.x71.debug` |
| Requires | Android 7.0 (API 24) and OpenGL ES 3.0 |
| Signing | debug keystore — sideload only, not for publication |

SHA-256: `87f678eeb1f13bbb525b3930589e1e028dc0f033b6ee3ebabe185aa6768e8eb5`

## Installing

Download it to the phone, tap it, and allow installation from unknown sources
when Android asks. Or over ADB:

```sh
adb install -r X71-Night-Run-debug.apk
```

## Rebuilding it

This file is a convenience copy, not a build artefact anything depends on. To
produce it yourself, see [../docs/ANDROID.md](../docs/ANDROID.md):

```sh
cd android && ./gradlew assembleDebug
```

The Gradle build copies `game/` into the APK's assets first, so the APK always
matches the source in this repository at the commit it was built from.
