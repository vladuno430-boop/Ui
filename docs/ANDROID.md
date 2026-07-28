# Building the Android app

The `android/` project is a thin native shell: one activity holding a WebView
that loads the game out of the APK's assets. All the game code stays in
`game/`, which the Gradle build copies into `app/src/main/assets/game/` before
every compile — so there is one source of truth and nothing to keep in sync by
hand.

## Requirements

- Android Studio Ladybug or newer, or a command-line Android SDK with
  platform 35 and build-tools installed
- JDK 17
- Gradle wrapper is committed; use `./gradlew`

## Build

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # straight onto a connected device
./gradlew assembleRelease        # unsigned; add your own signingConfig
```

If `ANDROID_HOME` is not set, create `android/local.properties`:

```
sdk.dir=/path/to/Android/sdk
```

## How the shell works

`MainActivity` serves the game through `WebViewAssetLoader` at
`https://appassets.androidplatform.net/game/`, not a `file://` URL. That matters:
ES modules, `localStorage` and the service worker all need a real secure origin,
and `file://` gives none of them.

The rest of it is housekeeping the game cannot do for itself:

- landscape lock, keep-screen-on, immersive full screen with cutout support
- back button maps to pause → menu → exit rather than killing the activity
- the run pauses when the app goes to the background

`minSdk` is 24. The manifest requires GLES 3.0, which is what WebGL 2 needs; on
a device without it the game shows its own "WebGL 2 not available" screen rather
than crashing.

## Playing without building

The same directory is a working PWA. Serve `game/` over HTTPS and install it
from Chrome — it declares landscape, full-screen display and precaches its whole
shell, so it runs offline afterwards. For local testing:

```sh
cd game && npx http-server -p 8080 .
```

WebGL 2 in a plain browser tab does not get the same GPU priority as a native
app; expect a few frames per second more from the APK on the same phone.

## Performance

Three quality presets change render scale, shadow map size, bloom iterations
and draw distance. The game also samples its own frame times for the first
couple of seconds of a run and steps the preset down once if it is not holding
up. Everything is procedural, so there is nothing to stream and no load pause
beyond the initial mesh build.
