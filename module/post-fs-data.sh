MODDIR=${0%/*}
rm -f "$MODDIR/.daemon.lock" 2>/dev/null

# [2026-11-05 热修 v6 终版] TrickyStoreOSS v3.1.1-ckb1 @ 定制 APatch（MI9+A13 双机形态）
# 事故与修复详见 217 号档。v6 = v5 的全部修复 + 派生方式回退：
#
# 修复清单（v4~v6 演进）：
# ① 双命名等待：init.svc.keystore2(MI9/A13+标准) || init.svc.keystore(MI9 MIUI-A11)
#    ——v3.1.1-ckb1 原版只查 keystore（A13 无此属性死等 120s → 定制 APatch deferred
#    模块阶段 6s 看门狗超时 → AP_MODULE_STAGE_FAILED → 批量禁用全部模块，217 §7.1）
# ② 零阻塞：全部等待+守护循环在后台子 shell（模块阶段秒回，不占看门狗预算）
# ③ 派生方式 = 普通子 shell 后台化（v3.1.1-718493d 时代原版形态）——
#    ⚠ 勿用 unshare -m 包裹：MI9 开机 apd 脚本域拒绝 mount namespace 操作，
#    unshare 包裹在 MI9 boot 整个失败（217 §7.1c MI9 实录；A13 su 域可用属特例）
# ④ 循环单实例守卫（pid 文件）防多次开机/手动跑叠加
# ⑤ 自愈层：熔断指纹看门器（≥3 模块同秒 disable = apd 熔断签名 → 自动摘除+审计；
#    单模块/非同秒 = 用户主动行为不干预）
# ⑥ daemon 前等 boot_completed（v3.1.1 竞态修复语义保留：注入过早与 system_server
#    的 keystore2 同步调用互锁 → watchdog 杀 system_server → MIUI 开机循环）
#
# 单实例锁（Android 无 flock）：锁文件 + pid 存活校验。post-fs-data 每次开机清旧锁。
LP=$(cat "$MODDIR/.daemon.lock" 2>/dev/null)
if [ -n "$LP" ] && grep -q TrickyStoreOSS "/proc/$LP/cmdline" 2>/dev/null; then
    exit 0
fi
echo $$ > "$MODDIR/.daemon.lock"

(
  # —— 自愈层：熔断指纹看门器（与守护循环同生命周期）——
  (
    while true; do
      sleep 5
      FILES=$(ls /data/adb/modules/*/disable 2>/dev/null)
      C=$(echo "$FILES" | grep -c disable)
      [ "$C" -ge 3 ] || continue
      TSU=$(stat -c %Y $FILES 2>/dev/null | sort -u | wc -l)
      if [ "$TSU" = "1" ]; then
        echo "[$(date)] CIRCUIT_BREAKER batch-disable signature -> removing: $FILES" >> /data/local/tmp/disable_audit.log
        rm -f $FILES
      fi
    done
  ) &

  # —— keystore 双命名等待（MI9=keystore / A13+=keystore2）——
  i=0
  while [ $i -lt 300 ]; do
    [ "$(getprop init.svc.keystore2 2>/dev/null)" = "running" ] && break
    [ "$(getprop init.svc.keystore 2>/dev/null)" = "running" ] && break
    sleep 1
    i=$((i + 1))
  done

  # —— boot_completed 竞态等待（v3.1.1 竞态修复语义）——
  j=0
  while [ $j -lt 300 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    sleep 1
    j=$((j + 1))
  done

  # —— daemon 守护循环 ——
  while true; do
    ./daemon "$MODDIR"
    sleep 2
  done
) &
