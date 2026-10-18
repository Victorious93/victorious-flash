#!/usr/bin/env bash
# QEMU/OVMF UEFI HTTP Boot check for the no-root ".efi" path (URL A).
# Serves ISO with the app's real Kotlin route table (NetbootServeHarness) on 127.0.0.1:8080,
# boots OVMF with user-mode net; slirp DHCP hands out the boot URL (a real Dell needs it typed in).
# usage: netboot-qemu.sh ISO OVMF_CODE OVMF_VARS OUTDIR [TIMEOUT_S]
# env: VFLASH_SERVER_WAIT_S (default 600) = how long to wait for the harness to listen; GRADLE_USER_HOME.
set -u
ISO=$1; CODE=$2; VARS_SRC=$3; OUT=$4; T=${5:-1200}
mkdir -p "$OUT"; cp "$VARS_SRC" "$OUT/vars.fd"
HERE=$(cd "$(dirname "$0")/.." && pwd)
# Only ever clean up processes this run started: hosts (dev boxes, self-hosted runners) may run other
# Gradle builds at the same time, so no global pkill. Off CI, Gradle gets a per-checkout GRADLE_USER_HOME
# (no lock contention with other builds on the host); CI keeps the one setup-gradle configured.
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -z "${CI:-}" ]; then export GRADLE_USER_HOME="$HERE/.gradle/netboot-gradle-home"; fi
# Every process of this run carries VFLASH_NETBOOT_RUN=<tag> in its environment (the Gradle daemon and test
# JVM inherit the client's env). The forked --no-daemon daemon calls setsid itself, so killing gradlew's
# process group alone is not enough; the tag finds exactly our JVMs and nothing else.
RUN_TAG="vflash-netboot-$$-$(date +%s%N)"
SRV_PGID=""; QPID=""
ours() {
  local e
  for e in /proc/[0-9]*/environ; do
    tr '\0' '\n' < "$e" 2>/dev/null | grep -qxF "VFLASH_NETBOOT_RUN=$RUN_TAG" && { e=${e#/proc/}; echo "${e%/environ}"; }
  done
  return 0
}
cleanup() {
  [ -n "$QPID" ] && kill "$QPID" 2>/dev/null
  if [ -n "$SRV_PGID" ]; then
    kill -- "-$SRV_PGID" 2>/dev/null   # gradlew wrapper/client: own process group via setsid
    local pids; mapfile -t pids < <(ours)   # Gradle daemon, test executor, Kotlin daemon of this run
    [ "${#pids[@]}" -gt 0 ] && kill "${pids[@]}" 2>/dev/null
    SRV_PGID=""
  fi
  return 0
}
trap cleanup EXIT; trap 'exit 130' INT TERM
# --rerun: the harness reads env vars Gradle doesn't track, so an up-to-date test task would never serve.
( cd "$HERE" && VFLASH_NETBOOT_RUN="$RUN_TAG" VFLASH_SERVE_ISO="$ISO" VFLASH_SERVE_LOG="$OUT/serve.log" VFLASH_SERVE_SECONDS=$((T+120)) \
  exec setsid ./gradlew --no-daemon -q :app:testDebugUnitTest --rerun --tests '*NetbootServeHarness*' > "$OUT/gradle.log" 2>&1 ) &
SRV_PGID=$!
WAIT=${VFLASH_SERVER_WAIT_S:-600}
for _ in $(seq 1 $((WAIT/5))); do grep -q listening "$OUT/serve.log" 2>/dev/null && break; sleep 5; done
grep -q listening "$OUT/serve.log" 2>/dev/null || { echo "server did not start within ${WAIT}s"; cat "$OUT/gradle.log"; exit 1; }
ACCEL=tcg; [ -w /dev/kvm ] && ACCEL=kvm
echo "accel=$ACCEL"
SMM=(); case "$CODE" in *secboot*) SMM=(-global "driver=cfi.pflash01,property=secure,value=on"); MACH="q35,smm=on,accel=$ACCEL";; *) MACH="q35,accel=$ACCEL";; esac
timeout "$T" qemu-system-x86_64 -machine "$MACH" "${SMM[@]}" -m 3072 -smp 2 -display none -vga none -no-reboot \
  -drive if=pflash,format=raw,readonly=on,file="$CODE" -drive if=pflash,format=raw,file="$OUT/vars.fd" \
  -netdev user,id=n0,bootfile=http://10.0.2.2:8080/boot/bootx64.efi -device virtio-net-pci,netdev=n0,bootindex=1 \
  -serial file:"$OUT/serial.raw" -monitor none &
QPID=$!
need="/boot/bootx64.efi /boot//grubx64.efi grub.cfg /casper/vmlinuz /casper/initrd /ubuntu.iso"
while kill -0 $QPID 2>/dev/null; do
  ok=1; for p in $need; do grep -q "GET [^ ]*$p host=10.0.2.2" "$OUT/serve.log" || ok=0; done
  [ $ok = 1 ] && { sleep 20; break; }
  sleep 10
done
cleanup; sleep 3
tr -d '\r' < "$OUT/serial.raw" | sed 's/\x1b\[[0-9;]*[a-zA-Z]//g' > "$OUT/serial.log" 2>/dev/null
echo "== requests"; grep -v '\.lst ' "$OUT/serve.log"
echo "== serial tail"; tail -c 1500 "$OUT/serial.log"
# Every request arrives on :8080 (that is the only listener). The Host header shows whether the client
# also *names* the port: OVMF's first fetch omits it (host=10.0.2.2), shim/GRUB/casper include :8080.
fail=0; for p in $need; do
  l=$(grep -m1 "GET [^ ]*$p host=10.0.2.2" "$OUT/serve.log") || { echo "MISS $p"; fail=1; continue; }
  echo "OK   $p  [$(echo "$l" | grep -o 'host=[^ ]*')]"
done
exit $fail
