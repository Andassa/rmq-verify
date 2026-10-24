#!/usr/bin/env bash
# Dependency-Check 13.0.0 ships `nvd.api.key=` and then sends that empty value as a key.
# The NVD rejects it. The upstream check that ignores a blank key is not in a release yet,
# so this removes the empty default from the jar already in the local Maven repository.
# A non-empty NVD_API_KEY still overrides it.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

jar_path="${HOME}/.m2/repository/org/owasp/dependency-check-core/13.0.0/dependency-check-core-13.0.0.jar"
[ -f "$jar_path" ] || die "dependency-check-core 13.0.0 is not downloaded yet"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -p "$jar_path" dependencycheck.properties >"$tmp/dependencycheck.properties"

if grep -q '^nvd.api.key=' "$tmp/dependencycheck.properties"; then
  grep -q '^nvd.api.key=$' "$tmp/dependencycheck.properties" \
    || die "nvd.api.key has an unexpected value; refusing to edit the jar"
  grep -v '^nvd.api.key=$' "$tmp/dependencycheck.properties" >"$tmp/patched"
  mv "$tmp/patched" "$tmp/dependencycheck.properties"
  jar_bin="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
  (cd "$tmp" && "$jar_bin" uf "$jar_path" dependencycheck.properties)
  log "removed the empty nvd.api.key default from dependency-check-core"
else
  log "nvd.api.key default is already absent"
fi

# Maven re-downloads the jar when the published checksum no longer matches.
python3 - "$jar_path" <<'PY'
import hashlib
import pathlib
import sys

jar = pathlib.Path(sys.argv[1])
data = jar.read_bytes()
jar.with_name(jar.name + ".sha1").write_text(hashlib.sha1(data).hexdigest())
jar.with_name(jar.name + ".md5").write_text(hashlib.md5(data).hexdigest())
PY
