#!/usr/bin/env bash
# Headless build of BudsWidget.apk — no Gradle / Android Studio needed.
#
# Needs: JDK 17+, Kotlin compiler (kotlinc), Android build-tools (aapt, zipalign, apksigner,
#        and d8 — or dx / dalvik-exchange), android.jar for API 34.
#
# Env overrides:
#   KOTLINC       path to kotlinc                 (default: kotlinc from PATH)
#   ANDROID_JAR   path to android.jar (API 34)    (default: ./android.jar)
#   KEYSTORE      signing keystore                (default: ./release.jks, created if missing)
#   KS_PASS       keystore + key password         (default: android)
set -euo pipefail
cd "$(dirname "$0")"

KOTLINC="${KOTLINC:-kotlinc}"
ANDROID_JAR="${ANDROID_JAR:-android.jar}"
KEYSTORE="${KEYSTORE:-release.jks}"
KS_PASS="${KS_PASS:-android}"
OUT=build

[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR (set ANDROID_JAR)"; exit 1; }
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] kotlinc"
# Lambdas / SAM conversions as real classes: the default invokedynamic form crashes on Android.
"$KOTLINC" src/*.kt -cp "$ANDROID_JAR" -jvm-target 1.8 -no-reflect \
    -Xlambdas=class -Xsam-conversions=class -d "$OUT/classes" 2>&1 | grep -v '^warning' || true
[ -f "$OUT/classes/app/budswidget/SonyService.class" ] || { echo "compile failed"; exit 1; }
jar cf "$OUT/app.jar" -C "$OUT/classes" .

echo "[2/5] kotlin stdlib"
STDLIB="${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar}"
cp "$STDLIB" "$OUT/stdlib.jar"
# multi-release entries break the old dx; harmless to drop for d8 too
zip -q -d "$OUT/stdlib.jar" 'META-INF/versions/*' 'module-info.class' >/dev/null 2>&1 || true

echo "[3/5] dex"
if command -v d8 >/dev/null; then
    d8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/app.jar" "$OUT/stdlib.jar"
elif command -v dalvik-exchange >/dev/null; then
    dalvik-exchange --dex --no-strict --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/app.jar" "$OUT/stdlib.jar"
else
    dx --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/app.jar" "$OUT/stdlib.jar"
fi

echo "[4/5] package"
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT/dex" && aapt add ../unsigned.apk classes*.dex >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias budswidget \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Buds Widget" >/dev/null 2>&1
    echo "      created $KEYSTORE (keep it: updates must be signed with the same key)"
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
    --out "$OUT/BudsWidget.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/BudsWidget.apk"
echo "done: $OUT/BudsWidget.apk"
 
