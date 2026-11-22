#!/bin/bash
# Bump version, build, and print the GitHub release command.
# Usage: ./release.sh 1.1.1
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
V=${1:?usage: ./release.sh 1.1.1}

CODE=$(/usr/bin/python3 - "$V" <<'PY'
import sys
p = (sys.argv[1].split(".") + ["0", "0"])[:3]
print(int(p[0]) * 10000 + int(p[1]) * 100 + int(p[2]))
PY
)

sed -i '' -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$CODE\"/" "$HERE/AndroidManifest.xml"
sed -i '' -E "s/android:versionName=\"[^\"]+\"/android:versionName=\"$V\"/" "$HERE/AndroidManifest.xml"

"$HERE/build.sh" "$HERE/build/PhoneClip.apk" >/dev/null
"$HERE/mac-app/build-app.sh" >/dev/null
DMG="$HERE/build/PhoneClip-$V.dmg"
rm -f "$DMG"
STAGE="$(mktemp -d)"
cp -R "$HERE/mac-app/build/PhoneClip.app" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -quiet -volname "PhoneClip $V" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"

echo "built $(ls -lh "$HERE/build/PhoneClip.apk" | awk '{print $5}') APK + $(ls -lh "$DMG" | awk '{print $5}') DMG for v$V (code $CODE)"

cat <<EOF

仓库里推好源码后，发布：

  gh release create v$V "$HERE/build/PhoneClip.apk" "$DMG" \\
    --repo onezion12344/phoneclip \\
    --title "PhoneClip $V" --notes "PhoneClip $V"

手机端 24 小时内会自己弹更新通知，也可以在 App 里点 Check for updates。
EOF
