MODDIR=${0%/*}
rm -f "$MODDIR/.daemon.lock" 2>/dev/null

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
  # [v3.1.1-ckb1 竞态修复] keystore2 注入与 system_server 启动存在竞态：
  # system_server 初始化会同步调 keystore2（LockdownVpnTracker→exist），若撞上
  # 注入进行中的 ptrace attach，事务永久卡死 → watchdog 杀 system_server
  # → MIUI 动画阶段开机循环（2026-10-30 实证，release 版时序变快后必现）。
  # 后台等 sys.boot_completed=1（不阻塞启动阶段）后再开始注入。
  j=0
  while [ $j -lt 300 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    sleep 1
    j=$((j + 1))
  done
  while true; do
    ./daemon "$MODDIR"
    sleep 2
  done
) &
