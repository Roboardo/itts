#!/bin/sh
# Build a signed release APK and publish it as a GitHub release.
# Usage: ./release.sh <version>   e.g. ./release.sh 1.1
set -e
cd "$(dirname "$0")"

VERSION="$1"
[ -n "$VERSION" ] || { echo "usage: $0 <version>" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit your changes first" >&2; exit 1; }

GRADLE=app/build.gradle.kts
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE")
CODE=$((CODE + 1))
sed -i "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \".*\"/versionName = \"$VERSION\"/" "$GRADLE"

./gradlew assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify "$APK"
OUT="app/build/trascrivi-$VERSION.apk"
cp "$APK" "$OUT"

git commit -q -m "Release $VERSION" "$GRADLE"
git tag "v$VERSION"
git push -q origin HEAD "v$VERSION"
gh release create "v$VERSION" "$OUT" --title "Trascrivi $VERSION" --generate-notes
