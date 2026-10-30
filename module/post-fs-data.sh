MODDIR=${0%/*}

# Start TrickyStore daemon in background
# APatch domainless mode does not execute service.sh, so we start
# the daemon here in post-fs-data.sh which IS executed by APatch.
#
# [v3.1.1-ckb1 升级修复] 上游新架构的 TEE probe 依赖 keystore2 服务：
# post-fs-data 阶段 keystore2 尚未启动 → probe 抛 IllegalStateException →
# FAILED 结果被 cachedProbeResult 永久缓存 → AUTO 目标全部落到 GENERATE
# （软件签名证书，Hunter TEE check 必检出）且 daemon 退出。
# 因此：等 init.svc.keystore=running 后再启动 daemon（上限 120s 防死等）。
i=0
while [ $i -lt 120 ]; do
  [ "$(getprop init.svc.keystore 2>/dev/null)" = "running" ] && break
  sleep 1
  i=$((i + 1))
done

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
