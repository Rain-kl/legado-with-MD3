#!/usr/bin/env sh
set -eu

# Generate values for GitHub Actions secrets used by .github/workflows/cust-release.yml
# Required secrets:
# - RELEASE_KEY_STORE (base64-encoded keystore)
# - RELEASE_KEY_ALIAS
# - RELEASE_KEY_PASSWORD
# - RELEASE_STORE_PASSWORD

prompt_default() {
  prompt="$1"
  default="$2"
  printf "%s [%s]: " "$prompt" "$default" >&2
  read -r input || true
  if [ -n "${input:-}" ]; then
    printf "%s" "$input"
  else
    printf "%s" "$default"
  fi
}

prompt_secret() {
  prompt="$1"
  printf "%s: " "$prompt" >&2
  stty -echo
  read -r value || true
  stty echo
  printf "\n" >&2
  printf "%s" "$value"
}

base64_one_line() {
  file="$1"
  base64 < "$file" | tr -d '\n\r'
}

fail() {
  printf "Error: %s\n" "$1" >&2
  exit 1
}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

KEYSTORE_PATH="$(prompt_default "Keystore file path" "$REPO_ROOT/app/key.jks")"
RELEASE_KEY_ALIAS="$(prompt_default "RELEASE_KEY_ALIAS" "release")"
RELEASE_KEY_PASSWORD="$(prompt_secret "RELEASE_KEY_PASSWORD")"
RELEASE_STORE_PASSWORD="$(prompt_secret "RELEASE_STORE_PASSWORD")"

[ -n "$RELEASE_KEY_PASSWORD" ] || fail "RELEASE_KEY_PASSWORD cannot be empty"
[ -n "$RELEASE_STORE_PASSWORD" ] || fail "RELEASE_STORE_PASSWORD cannot be empty"

if [ ! -f "$KEYSTORE_PATH" ]; then
  printf "Keystore not found, generating a new one...\n" >&2
  command -v keytool >/dev/null 2>&1 || fail "keytool not found. Please install JDK 17+"

  DNAME="$(prompt_default "Certificate DName" "CN=Legado, OU=Release, O=Legado, L=NA, S=NA, C=US")"
  KEYSTORE_DIR=$(dirname -- "$KEYSTORE_PATH")
  mkdir -p "$KEYSTORE_DIR"

  keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -storetype JKS \
    -alias "$RELEASE_KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$RELEASE_STORE_PASSWORD" \
    -keypass "$RELEASE_KEY_PASSWORD" \
    -dname "$DNAME" >/dev/null

  printf "Generated keystore: %s\n" "$KEYSTORE_PATH" >&2
fi

RELEASE_KEY_STORE="$(base64_one_line "$KEYSTORE_PATH")"

OUTPUT_FILE="$REPO_ROOT/release-secrets.env"
cat > "$OUTPUT_FILE" <<EOF
RELEASE_KEY_ALIAS=$RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD=$RELEASE_KEY_PASSWORD
RELEASE_STORE_PASSWORD=$RELEASE_STORE_PASSWORD
RELEASE_KEY_STORE=$RELEASE_KEY_STORE
EOF

printf "\nGenerated: %s\n" "$OUTPUT_FILE"
printf "Copy these values into GitHub repo secrets:\n\n"
cat "$OUTPUT_FILE"

if command -v gh >/dev/null 2>&1; then
  printf "\nOptional: auto-set secrets via GitHub CLI (in this repo):\n"
  printf "  printf '%%s' \"$RELEASE_KEY_ALIAS\" | gh secret set RELEASE_KEY_ALIAS\n"
  printf "  printf '%%s' \"$RELEASE_KEY_PASSWORD\" | gh secret set RELEASE_KEY_PASSWORD\n"
  printf "  printf '%%s' \"$RELEASE_STORE_PASSWORD\" | gh secret set RELEASE_STORE_PASSWORD\n"
  printf "  printf '%%s' \"$RELEASE_KEY_STORE\" | gh secret set RELEASE_KEY_STORE\n"
fi
