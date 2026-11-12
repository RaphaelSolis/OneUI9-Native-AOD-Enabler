#!/system/bin/sh
# Collect one diagnostic snapshot after Android boots; do not enable AOD or restart anything.
A16_V4_DIR=${0%/*}
A16_ATTEMPTS=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$A16_ATTEMPTS" -lt 90 ]; do
  sleep 2
  A16_ATTEMPTS=$((A16_ATTEMPTS + 1))
done
if [ "$(getprop sys.boot_completed)" = 1 ]; then
  sleep 5
  sh "$A16_V4_DIR/collect-test-logs.sh"
fi
