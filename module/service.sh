DEBUG=false

MODDIR=${0%/*}

cd $MODDIR

# NOTE: Do NOT use "|| exit 1" here.  The daemon may exit non-zero during
# early boot (keystore2 not yet running, APatch su not ready, etc.).  The
# restart loop must survive these transient failures and keep retrying until
# the system is fully booted and injection can succeed.
while true; do
  ./daemon "$MODDIR"
  # ensure keystore initialized
  sleep 2
done &
