MODDIR=${0%/*}

# Start TrickyStore daemon in background
# APatch domainless mode does not execute service.sh, so we start
# the daemon here in post-fs-data.sh which IS executed by APatch.
(
  cd "$MODDIR"
  while true; do
    ./daemon "$MODDIR" || exit 1
    sleep 2
  done
) &
