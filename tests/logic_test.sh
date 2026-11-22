#!/bin/bash
# JVM tests for Android classes that do not touch the framework at runtime
# (OTA version comparison, hashing). Runs on the host, no device needed.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAR=$(ls /opt/homebrew/share/android-commandlinetools/platforms/android-*/android.jar 2>/dev/null | tail -1)
[ -n "$JAR" ] || { echo "android.jar not found"; exit 1; }

if [ ! -d "$ROOT/build/compiled" ]; then
  "$ROOT/build.sh" "$ROOT/build/PhoneClip.apk" >/dev/null
fi

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/ai/onezion/phoneclip"
cat > "$T/ai/onezion/phoneclip/LogicTest.java" <<'JAVA'
package ai.onezion.phoneclip;

public class LogicTest {
    static int pass, fail;
    static void check(String n, boolean c) {
        (c ? System.out : System.err).println((c ? "PASS  " : "FAIL  ") + n);
        if (c) pass++; else fail++;
    }
    public static void main(String[] a) {
        check("v1.4.17 > 1.4.16", Update.isNewer("v1.4.17", "1.4.16"));
        check("1.4.16 not newer than 1.4.17", !Update.isNewer("1.4.16", "1.4.17"));
        check("equal is not newer", !Update.isNewer("v1.4.17", "1.4.17"));
        check("1.4.9 < 1.4.10 numeric", !Update.isNewer("1.4.9", "1.4.10"));
        check("1.10 > 1.9", Update.isNewer("v1.10", "1.9"));
        check("suffix ignored", Update.isNewer("v1.4.17-beta", "1.4.16"));
        String h = Media.sha1("hello".getBytes());
        check("sha1 is 40 hex", h.length() == 40 && h.matches("[0-9a-f]{40}"));
        check("sha1 deterministic", h.equals(Media.sha1("hello".getBytes())));
        check("sha1 differs", !h.equals(Media.sha1("hellp".getBytes())));
        System.out.println("RESULT: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
JAVA

javac --release 17 -cp "$JAR:$ROOT/build/compiled" -d "$T" \
  "$T/ai/onezion/phoneclip/LogicTest.java"
java -cp "$JAR:$ROOT/build/compiled:$T" ai.onezion.phoneclip.LogicTest
