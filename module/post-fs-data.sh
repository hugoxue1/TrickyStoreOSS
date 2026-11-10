MODDIR=${0%/*}

# TS daemon 启动（v6）——unshare -m 隔离派生（ckbsmart 模式，MI9 开机存活实测）
# + keystore 双命名等待（MI9=keystore/A13=keystore2）+ boot 竞态等待 + 单实例锁
# + 自愈看门器（≥3 模块同秒 disable=apd 熔断签名→自动摘除+审计）
# 详见 217 号档 §7.1c

# 单实例锁
LP=$(cat "$MODDIR/.daemon.lock" 2>/dev/null)
if [ -n "$LP" ] && [ -d "/proc/$LP" ]; then
    exit 0
fi
echo $$ > "$MODDIR/.daemon.lock"

(
  # —— 自愈层：熔断指纹看门器 ——
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

  # —— keystore 双命名等待 + boot 竞态等待 ——
  i=0
  while [ $i -lt 300 ]; do
    [ "$(getprop init.svc.keystore2 2>/dev/null)" = "running" ] && break
    [ "$(getprop init.svc.keystore 2>/dev/null)" = "running" ] && break
    sleep 1
    i=$((i + 1))
  done

  j=0
  while [ $j -lt 300 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    sleep 1
    j=$((j + 1))
  done

  # —— daemon 守护循环（unshare -m 隔离派生，MI9 开机存活）——
  unshare -m sh -c '
    cd "'"$MODDIR"'" 2>/dev/null || exit 1
    while true; do
      ./daemon "'"$MODDIR"'"
      sleep 2
    done
  ' </dev/null >/dev/null 2>&1 &
) &
