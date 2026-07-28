#!/usr/bin/env bash
# Builds and runs everything that can be checked without a device:
# the simulation tests, the sound synthesis checks, and the APK build.
#
#   tools/run-tests.sh            run the tests
#   tools/run-tests.sh preview    also write preview renders to out/preview
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=$(find app/src/main/java/com/arena3/core \
           app/src/main/java/com/arena3/game \
           app/src/main/java/com/arena3/render -name '*.java')

echo "== compiling simulation and render code =="
rm -rf out
mkdir -p out
javac -nowarn -d out $SRC \
    app/src/main/java/com/arena3/audio/SoundSynth.java \
    tools/HeadlessTest.java tools/SoundTest.java tools/Preview.java \
    tools/ModelPreview.java tools/TexPreview.java

echo
echo "== simulation tests =="
java -cp out HeadlessTest

echo
echo "== sound synthesis =="
java -cp out SoundTest

if [ "${1:-}" = "preview" ]; then
    echo
    echo "== preview renders =="
    mkdir -p out/preview
    java -cp out TexPreview out/preview/textures.png
    java -cp out ModelPreview out/preview/models.png
    java -cp out Preview 0 out/preview/forge.png -820 -820 500 45 22
    java -cp out Preview 1 out/preview/crucible.png -1150 -820 560 35 20
    java -cp out Preview 2 out/preview/void.png -1300 -1300 460 45 14
fi

echo
echo "== android build =="
./gradlew :app:assembleDebug -q
ls -la app/build/outputs/apk/debug/app-debug.apk

echo
echo "all checks passed"
