#!/usr/bin/env bash
# QEMU/OVMF UEFI HTTP Boot check for the no-root ".efi" path (URL A).
# Serves ISO with the app's real Kotlin route table (NetbootServeHarness) on 127.0.0.1:8080,
# boots OVMF with user-mode net; slirp DHCP hands out the boot URL (a real Dell needs it typed in).
# usage: netboot-qemu.sh ISO OVMF_CODE OVMF_VARS OUTDIR [TIMEOUT_S]
set -u
ISO=$1; CODE=$2; VARS_SRC=$3; OUT=$4; T=${5:-1200}
mkdir -p "$OUT"; cp "$VARS_SRC" "$OUT/vars.fd"
HERE=$(cd "$(dirname "$0")/.." && pwd)
( cd "$HERE" && VFLASH_SERVE_ISO="$ISO" VFLASH_SERVE_LOG="$OUT/serve.log" VFLASH_SERVE_SECONDS=$((T+120)) \
  ./gradlew --no-daemon -q :app:testDebugUnitTest --tests '*NetbootServeHarness*' > "$OUT/gradle.log" 2>&1 ) &
for i in $(seq 1 120); do grep -q listening "$OUT/serve.log" 2>/dev/null && break; sleep 5; done
grep -q listening "$OUT/serve.log" || { echo "server did not start"; cat "$OUT/gradle.log"; exit 1; }
ACCEL=tcg; [ -w /dev/kvm ] && ACCEL=kvm
echo "accel=$ACCEL"
SMM=(); case "$CODE" in *secboot*) SMM=(-global driver=cfi.pflash01,property=secure,value=on); MACH="q35,smm=on,accel=$ACCEL";; *) MACH="q35,accel=$ACCEL";; esac
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
kill $QPID 2>/dev/null
pkill -f "Gradle Test Executor" 2>/dev/null; pkill -f GradleWrapperMain 2>/dev/null; sleep 3
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
