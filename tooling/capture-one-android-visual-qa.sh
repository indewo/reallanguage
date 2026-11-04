#!/bin/zsh
set -euo pipefail

ADB="/Users/eric/Library/Android/sdk/platform-tools/adb"
DESTINATION="${1:?preview destination is required}"
OUTPUT="${2:?output PNG path is required}"

mkdir -p "${OUTPUT:h}"
"$ADB" shell am force-stop com.reallanguage.app
"$ADB" shell am start -W -n com.reallanguage.app/.development.DevelopmentPreviewActivity \
  --es preview_destination "$DESTINATION" >/dev/null
sleep 0.5
"$ADB" exec-out screencap -p > "$OUTPUT"

echo "captured=$DESTINATION output=$OUTPUT"
