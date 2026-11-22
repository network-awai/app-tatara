#!/bin/sh
# Operator stopgap for the APK pool until tatara-burst has its own [vke]-only Vultr key (AWAI-3834).
#   pool-local.sh up [MINUTES]   create awai-apk-burst, then delete it after MINUTES (default 60) or on exit
#   pool-local.sh down           delete it now and verify it is gone
#   pool-local.sh status         list the cluster's pools (id, label, plan, nodes)
# The Vultr key is read from the macOS Keychain item vultr-awai-build into this process only and passed to
# curl on stdin (--config -), never in argv, a file or the log. `up` stays in the foreground: the delete
# runs from a trap on EXIT/INT/TERM/HUP and at the deadline, and is checked against the API afterwards.
set -eu
CLUSTER=${CLUSTER:-3f1932a3-896f-417a-a1e4-9d0ef6b57711}   # VKE awai-tatara-ci (nrt)
LABEL=awai-apk-burst PLAN=${PLAN:-vc2-4c-8gb}
K=$(security find-generic-password -s vultr-awai-build -w 2>/dev/null || security find-generic-password -l vultr-awai-build -w)
api() { # method path [json]
  if [ $# -ge 3 ]; then
    printf 'header = "Authorization: Bearer %s"\nheader = "Content-Type: application/json"\n' "$K" |
      curl -fsS --config - -X "$1" "https://api.vultr.com/v2$2" --data "$3"
  else
    printf 'header = "Authorization: Bearer %s"\n' "$K" | curl -fsS --config - -X "$1" "https://api.vultr.com/v2$2"
  fi
}
pools() { api GET "/kubernetes/clusters/$CLUSTER/node-pools?per_page=100"; }
ours() { pools | python3 -I -c '
import json,sys
for p in json.load(sys.stdin)["node_pools"]:
  if p["label"]=="'"$LABEL"'" and (p.get("labels") or {}).get("awai.network/burst")=="'"$LABEL"'": print(p["id"])'; }
ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
down() {
  for id in $(ours); do
    echo "$(ts) delete pool $id $LABEL"
    api DELETE "/kubernetes/clusters/$CLUSTER/node-pools/$id" >/dev/null || echo "$(ts) delete call failed for $id; rechecking" >&2
  done
  i=0; while [ -n "$(ours)" ] && [ $i -lt 30 ]; do sleep 10; i=$((i+1)); done
  if [ -n "$(ours)" ]; then echo "$(ts) POOL STILL PRESENT: $(ours)" >&2; return 1; fi
  echo "$(ts) verified: no $LABEL pool on cluster $CLUSTER"
}
case "${1:-status}" in
  status) pools | python3 -I -c '
import json,sys
for p in json.load(sys.stdin)["node_pools"]:
  print(p["id"], p["label"], p["plan"], p["node_quantity"], p["date_created"], [n["status"] for n in p.get("nodes",[])])' ;;
  down) down ;;
  up)
    mins=${2:-60}
    if [ -n "$(ours)" ]; then echo "$(ts) pool exists: $(ours)"; else
      body='{"node_quantity":1,"min_nodes":1,"max_nodes":1,"auto_scaler":false,"label":"'"$LABEL"'","plan":"'"$PLAN"'","tag":"'"$LABEL"'",
        "labels":{"awai.network/burst":"'"$LABEL"'","awai.network/purpose":"ci-burst","awai.network/reapable":"true"},
        "taints":[{"key":"awai.network/apk","value":"build","effect":"NoSchedule"}]}'
      api POST "/kubernetes/clusters/$CLUSTER/node-pools" "$body" >/dev/null
      echo "$(ts) created pool $LABEL $PLAN: $(ours); deadline ${mins} min"
    fi
    trap 'down' EXIT INT TERM HUP
    end=$(( $(date +%s) + mins * 60 ))
    while [ "$(date +%s)" -lt "$end" ]; do
      [ -f "${STOP_FILE:-/nonexistent}" ] && { echo "$(ts) stop file seen"; break; }
      sleep 15
    done
    echo "$(ts) deadline or stop reached" ;;
  *) echo "usage: $0 up [MINUTES] | down | status" >&2; exit 64 ;;
esac
