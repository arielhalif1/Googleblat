#!/bin/sh
# Prints the signing-certificate SHA-256 of an APK, for "certSha256" in catalog.json.
# Usage: tools/cert-sha256.sh some-app.apk
set -e
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
"$APKSIGNER" verify --print-certs "$1" | sed -n 's/.*certificate SHA-256 digest: //p' | sort -u
