MODDIR=${0%/*}
rm -f "$MODDIR/.daemon.lock" 2>/dev/null

# [2026-11-05 热修 v5 终版] TrickyStoreOSS v3.1.1-ckb1 @ 定制 APatch
# 事故与修复详见 docs/工程项目总览/217 号档。三件修复：
# ① 双命名等待：init.svc.keystore2 || init.svc.keystore（A13/MI9 通吃；
#    v3.1.1 原版写死 init.svc.keystore——MI9 MIUI-A11 专写，A13 无此属性死等 120s）
# ② 零阻塞：全部等待+守护循环在后台（定制 APatch deferred 模块阶段 6s 看门狗，
#    超时=AP_MODULE_STAGE_FAILED+批量禁用全部非 early-loader 模块）
# ③ unshare 孤儿化：PPID=1 存活（ckbsmart 同款实证）
# ④ 循环单实例守卫：防多次开机/手动跑叠加守护实例
# ⑤ 自愈层：熔断指纹看门器——≥3 模块 disable 且全部同秒创建 = apd 熔断签名
#    （单模块/非同秒 = 用户主动行为，绝不干预），自动摘除+审计落盘

LOOP_PID_FILE="$MODDIR/.daemon_loop.pid"
if [ -f "$LOOP_PID_FILE" ]; then
    OLD=$(cat "$LOOP_PID_FILE" 2>/dev/null)
    [ -n "$OLD" ] && [ -d "/proc/$OLD" ] && exit 0
fi

unshare -m sh -c "cd $MODDIR && (
  echo \$\$ > $LOOP_PID_FILE
  # —— 自愈层：熔断指纹看门器（与守护循环同生命周期）——
  (
    while true; do
      sleep 5
      FILES=\$(ls /data/adb/modules/*/disable 2>/dev/null)
      [ \$(echo \"\$FILES\" | grep -c disable) -ge 3 ] || continue
      TSU=\$(stat -c %Y \$FILES 2>/dev/null | sort -u | wc -l)
      if [ \"\$TSU\" = \"1\" ]; then
        echo \"[\$(date)] CIRCUIT_BREAKER batch-disable signature -> removing: \$FILES\" >> /data/local/tmp/disable_audit.log
        rm -f \$FILES
      fi
    done
  ) &
  # —— keystore 双命名等待 + boot_completed 竞态等待 + daemon 守护循环 ——
  i=0
  while [ \$i -lt 120 ]; do
    [ \"\$(getprop init.svc.keystore2 2>/dev/null)\" = \"running\" ] && break
    [ \"\$(getprop init.svc.keystore 2>/dev/null)\" = \"running\" ] && break
    sleep 1
    i=\$((i + 1))
  done
  j=0
  while [ \$j -lt 300 ]; do
    [ \"\$(getprop sys.boot_completed 2>/dev/null)\" = \"1\" ] && break
    sleep 1
    j=\$((j + 1))
  done
  while true; do
    ./daemon \"\$MODDIR\"
    sleep 2
  done
) &" </dev/null >/dev/null 2>&1
