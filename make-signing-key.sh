#!/bin/sh
# Creates the release signing key (once) outside the repo. BACK IT UP: without
# this key you can never publish an update that installs over earlier releases.
set -e
cd "$(dirname "$0")"
KEYS_DIR=${CPZ_KEYS_DIR:-$HOME/.android-signing/cardputerzero-remote}
if [ -f "$KEYS_DIR/release.jks" ]; then
    echo "Key already exists: $KEYS_DIR/release.jks"
    exit 0
fi
mkdir -p "$KEYS_DIR"
chmod 700 "$KEYS_DIR"
docker build -q -t cpz-android docker > /dev/null
PASS=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
docker run --rm -u "$(id -u):$(id -g)" -e KS_PASS="$PASS" -v "$KEYS_DIR:/keys" cpz-android \
    keytool -genkeypair -keystore /keys/release.jks -storetype PKCS12 \
    -alias cardputerzero-remote -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=CardputerZero Remote" -storepass:env KS_PASS -keypass:env KS_PASS
umask 077
cat > "$KEYS_DIR/keystore.properties" <<PROPS
storeFile=release.jks
storePassword=$PASS
keyAlias=cardputerzero-remote
keyPassword=$PASS
PROPS
chmod 600 "$KEYS_DIR/release.jks" "$KEYS_DIR/keystore.properties"
echo "Created $KEYS_DIR/release.jks (password in keystore.properties next to it)."
echo "Back up that whole folder somewhere safe."
