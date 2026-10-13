#!/bin/sh
# Self-bootstrapping Gradle launcher (no wrapper jar required).
# Order: cached distribution for Gradle 8.7 -> system gradle of the same
# version -> download the distribution -> any system gradle as last resort.
set -e

GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_HOME_BASE/wrapper/dists/gradle-$GRADLE_VERSION-bin"
DIST_GRADLE="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"

version_of() {
  "$1" --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9.]*\)/\1/p' | head -n 1
}

if [ -x "$DIST_GRADLE" ]; then
  exec "$DIST_GRADLE" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  case "$(version_of gradle)" in
    "$GRADLE_VERSION"*) exec gradle "$@" ;;
  esac
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
echo "Downloading Gradle $GRADLE_VERSION ..." >&2
if command -v curl >/dev/null 2>&1; then
  curl -fSL -o "$TMP/gradle.zip" "$URL" || DOWNLOAD_FAILED=1
elif command -v wget >/dev/null 2>&1; then
  wget -O "$TMP/gradle.zip" "$URL" || DOWNLOAD_FAILED=1
else
  DOWNLOAD_FAILED=1
fi

if [ -z "$DOWNLOAD_FAILED" ]; then
  mkdir -p "$DIST_DIR"
  (cd "$DIST_DIR" && unzip -q -o "$TMP/gradle.zip")
  exec "$DIST_GRADLE" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  echo "warning: using system $(version_of gradle) instead of $GRADLE_VERSION" >&2
  exec gradle "$@"
fi

echo "error: cannot provide Gradle $GRADLE_VERSION (no curl/wget, no system gradle)" >&2
exit 1
