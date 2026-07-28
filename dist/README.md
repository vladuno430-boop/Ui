# Prebuilt APKs

`arena3-release.apk` — install this one. Signed with the debug key so it
sideloads without extra setup; replace the signing config in `app/build.gradle`
for a store build.

`arena3-debug.apk` — same game, debuggable, slightly larger.

Requires Android 7.0 (API 24) or newer and OpenGL ES 3.0. Enable installation
from unknown sources, then open the file.

Rebuild from source at any time with `./gradlew assembleRelease`.
