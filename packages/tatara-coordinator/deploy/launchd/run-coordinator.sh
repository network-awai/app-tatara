#!/bin/sh
# launchd entry for the Tatara coordinator (AWAI-3521).
# The admin token is read from a 0600 file, never from the plist.
# No GitHub token is passed: the coordinator leases one from kagitaba-minter.
set -eu
TATARA_HOME="${TATARA_HOME:-$HOME/.tatara/coordinator}"
token_file="$TATARA_HOME/admin-token"
# find -perm works the same with BSD and GNU userlands (stat flags do not).
[ -n "$(find "$token_file" -prune -type f -perm 600 2>/dev/null)" ] || {
  echo "run-coordinator: $token_file must exist with mode 600" >&2; exit 78; }
TATARA_ADMIN_TOKEN=$(cat "$token_file")
export TATARA_ADMIN_TOKEN
unset TATARA_GITHUB_TOKEN GITHUB_TOKEN GH_TOKEN
cd "$TATARA_HOME/app/packages/tatara-coordinator"
exec bb serve "$TATARA_HOME/coordinator.edn"
