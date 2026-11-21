#!/bin/sh
# Negative control for the step sandbox (AWAI-3799). Run inside a live runner pod:
#   kubectl -n tatara cp negative-control.sh tatara-runner-0-0:/tmp/nc.sh
#   kubectl -n tatara exec tatara-runner-0-0 -- sh /tmp/nc.sh
# It builds the step argv with the runner's own tatara.runner.core/wrap-network (:none, :unshare,
# :step-user 20000) and runs probes as the step. Every line must say PASS; the script exits 1 otherwise.
set -eu
cat > /tmp/nc-probes.sh <<'EOF'
fail=0
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
eff=$(awk '/^CapEff/{print $2}' /proc/self/status); bnd=$(awk '/^CapBnd/{print $2}' /proc/self/status)
[ "$eff" = 0000000000000000 ] && ok "CapEff is 0" || bad "CapEff is $eff"
[ "$bnd" = 0000000000000000 ] && ok "CapBnd is 0" || bad "CapBnd is $bnd"
grep -q '^NoNewPrivs:[[:space:]]*1' /proc/self/status && ok "no_new_privs set" || bad "no_new_privs not set"
# The pod's namespaces are those of PID 1 (the runner). A step must not enter them.
for ns in net mnt user; do
  nsenter --$ns=/proc/1/ns/$ns true 2>/dev/null && bad "setns into the pod $ns namespace succeeded" || ok "setns into the pod $ns namespace refused"
done
cat /var/lib/tatara/identity.edn >/dev/null 2>&1 && bad "identity readable" || ok "identity unreadable"
cat /proc/1/environ >/dev/null 2>&1 && bad "runner environment readable" || ok "runner environment unreadable"
ip link add tatara-x type dummy 2>/dev/null && bad "created a link" || ok "cannot create network links"
[ "$(ip -o link | wc -l)" = 1 ] && ok "only loopback visible" || bad "more than loopback visible"
mount -t tmpfs none /mnt 2>/dev/null && bad "mount succeeded" || ok "mount refused"
unshare --user --map-root-user sh -c 'nsenter --net=/proc/1/ns/net true' 2>/dev/null && bad "nested userns entered the pod netns" || ok "nested userns cannot enter the pod netns"
exit $fail
EOF
chmod 755 /tmp/nc-probes.sh
cd /opt/tatara/packages/tatara-runner
exec bb -e '
(require (quote [tatara.runner.core :as c]) (quote [babashka.process :as p]))
(let [argv (c/wrap-network ["sh" "/tmp/nc-probes.sh"] :none :unshare {:uid 20000 :gid 20000})
      r (apply p/shell {:continue true :out :inherit :err :inherit :dir "/tmp"} argv)]
  (System/exit (:exit r)))'
