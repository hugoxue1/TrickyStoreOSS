MODDIR=${0%/*}

# Start TrickyStore daemon in background
# APatch domainless mode does not execute service.sh, so we start
# the daemon here in post-fs-data.sh which IS executed by APatch.
#
# NOTE: Do NOT use "|| exit 1" here.  At post-fs-data stage keystore2 may
# not be running yet, and the daemon may exit non-zero (e.g. pidof empty,
# injection not ready).  A non-zero exit must NOT kill the restart loop —
# the daemon needs to keep retrying until the system is fully booted.
(
  cd "$MODDIR"
  while true; do
    ./daemon "$MODDIR"
    sleep 2
  done
) &
