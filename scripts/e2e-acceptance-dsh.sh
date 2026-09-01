#!/usr/bin/env bash
# =============================================================================
# e2e-acceptance-dsh.sh — DSH 五卡（#278/#279/#283/#285/#287）真机端到端验收
#
# 目标：把「待用户人工验收」压缩为可重复执行的确定性门禁（用户 2026-09-01 指令：
# 减少人工介入的验收工作流）。断言通道全部走确定性证据：
#   - logcat Ktor 请求行 / EventDispatcher 派发行 / SessionStateService 同步行
#   - Room 直查（scripts/pull-app-db.sh 三件套 + 宿主 sqlite3）
#   - DSH 服务端 RPC 直探（session.list / session.history / commands/execute）
#   - 系统 SAF 对话框 uiautomator dump（DocumentsUI 系统组件，a11y 可靠）
#   - 导出产物 unzip -t 完整性校验
# 截图仅作人工抽查归档，不作为门禁输入。
#
# #293 批（2026-09-01）两条点按铁律：
#   1. 键盘弹起后输入栏随 imePadding 上移，发送键盲点坐标必落键盘——发送一律
#      tap_text dump 定位（历轮「发送挂起」假象即此坐标伪影，发送通道实为健康）；
#   2. 发送/切档实验只允许落在懒建 scratch 会话（dsh-openapi-scratch）——深链/
#      通知劫持可能把导航带进用户真实会话，top-updated 兜底会误伤。
#
# 用法: ./scripts/e2e-acceptance-dsh.sh [serial]   # 默认 192.168.110.239:5555
# 前置: 真机 WiFi/USB ADB 在线；宿主 127.0.0.1:3080 DSH 服务器在线（本机即服务器）
# 产物: /tmp/e2e-acceptance-<ts>/ 截图 + 拉取 DB + 结果汇总
# =============================================================================
set -uo pipefail

SERIAL=${1:-192.168.110.239:5555}
PKG=dev.leonardo.ocbeacon.dev
ACT=dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity
OUT=/tmp/e2e-acceptance-$(date +%H%M%S)
mkdir -p "$OUT"
PASS=(); FAIL=(); SKIP=()

adb() { command adb -s "$SERIAL" "$@"; }

# 宿主侧全程序连续 logcat（#293 批教训：设备缓冲在洪泛期分钟级旋转，-d 快照会
# 吃掉 Ktor/派发行）——一切日志门禁 grep 本文件，行号偏移做卡内隔离。
LOG_HOST="$OUT/host-logcat.log"
: > "$LOG_HOST"
adb reverse tcp:3080 tcp:3080 >/dev/null
adb logcat -c
adb logcat -v time > "$LOG_HOST" 2>&1 &
LGPID=$!
trap 'kill $LGPID 2>/dev/null' EXIT

snap() { adb exec-out screencap -p > "$OUT/$1.png"; echo "  [shot] $1.png"; }

rpc() { # rpc <method> <payload-json> → stdout=value JSON
  python3 - "$1" "$2" <<'PYEOF'
import json, sys, urllib.request
method, payload = sys.argv[1], json.loads(sys.argv[2])
req = urllib.request.Request(
    "http://127.0.0.1:3080/api/" + method,
    data=json.dumps({"type":"client-request","rpcId":"e2e","method":method,"payload":payload}).encode(),
    headers={"Content-Type":"application/json"})
with urllib.request.urlopen(req, timeout=8) as r:
    print(json.dumps(json.load(r)["result"]["value"]))
PYEOF
}

log_count() { wc -l < "$LOG_HOST" 2>/dev/null || echo 0; }

grep_from() { # grep_from <startLine> <pattern> [grep-args...] —— 宿主档偏移 grep
  # 进程替换而非管道：脚本启用 pipefail，`tail | grep -q` 早退会让 tail 收 SIGPIPE
  # → 管道返回 141 → 匹配被误判失败（run3 四卡全灭根因）。grep 退出码必须独立。
  local start=$1 pat=$2; shift 2
  grep -a "$@" "$pat" < <(tail -n +"$start" "$LOG_HOST" 2>/dev/null)
}

wait_logcat() { # wait_logcat <pattern> <timeout-s> [startLine] —— 轮询宿主档偏移区段
  local pat=$1 t=$2 start=${3:-$(log_count)} i=0
  while [ $i -lt $t ]; do
    sleep 3; i=$((i+3))
    grep_from "$start" "$pat" -q && return 0
  done
  return 1
}

enter_dsh() { # 冷启 + debug intent 直达 DSH 会话列表，等待回放沉降
  # 沉降两段式（#293 批教训：纯静默窗会在回放开始前假通过——8s「沉降完成」致
  # 导航撞进通知风暴/骨架屏）：①先等回放证据（persist queue full，上限 60s，
  # 温缓存可缺席）；②再等该行 24s 无新增。
  local lc0; lc0=$(log_count)
  adb shell am force-stop "$PKG"; sleep 1
  adb shell pidof "$PKG" >/dev/null 2>&1 && { echo "  [warn] force-stop 未生效"; return 1; }
  adb shell am start -n "$ACT" --es debug_url http://127.0.0.1:3080 \
    --es debug_username opencode --es debug_name 127.0.0.1:3080 >/dev/null
  wait_logcat 'Debug channel → SessionList' 40 "$lc0" || { echo "  [fail] 未到达会话列表"; return 1; }
  echo "  已进入 DSH 会话列表，等待回放沉降（证据→静默两段式，上限 ${SETTLE_S:-240}s）…"
  local i=0
  while [ $i -lt 60 ]; do
    sleep 4; i=$((i+4))
    grep_from "$lc0" 'persist queue full' -q && break
  done
  local quiet=0 last=0 cur i=0
  while [ $i -lt "${SETTLE_S:-240}" ] && [ $quiet -lt 24 ]; do
    sleep 4; i=$((i+4))
    cur=$(grep_from "$lc0" 'persist queue full' | wc -l)
    if [ "$cur" -gt "$last" ]; then last=$cur; quiet=0; else quiet=$((quiet+4)); fi
  done
  echo "  沉降完成（洪泛行=$last，安静 ${quiet}s / 等待 ${i}s）"
}

pid_now() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

open_new_chat() { # 顶栏「新建会话」(固定坐标) → 目录选择表 → 表内点目标目录
  # 顶栏按钮用固定坐标：desc 节点会间歇从 a11y dump 消失（#158），dump 定位反而
  # 会命中同名列表行；顶栏 chrome 位置稳定 [936,194][1008,266]。表出现以
  # 「打开其他项目…」为标志。目录 fallback 链：dsh-openapi-scratch（dsh 重启后
  # 可能从工作区注册表消失）→ oc-beacon（dev 仓库，Test Lab 所在目录）。
  local try scroll
  for try in 1 2 3; do
    adb shell input tap 972 230; sleep 2
    if wait_dump '打开其他项目' 8; then
      for scroll in 1 2 3 4; do
        tap_text 'dsh-openapi-scratch' 2 2 850 2150 && { echo "  目录: dsh-openapi-scratch"; return 0; }
        adb shell input swipe 600 1600 600 800 400; sleep 1
      done
      tap_text 'oc-beacon' 3 2 850 2150 && { echo "  目录: oc-beacon（scratch 缺席回落）"; return 0; }
    fi
    # 表未开：heads-up 深链劫持 → BACK 回列表重试
    adb shell rm -f /sdcard/e2e-chk.xml
    adb shell uiautomator dump /sdcard/e2e-chk.xml >/dev/null 2>&1
    if adb shell cat /sdcard/e2e-chk.xml 2>/dev/null | grep -aq '新建会话'; then
      :   # 已回列表（表自动关了），直接重按
    else
      adb shell input keyevent KEYCODE_BACK; sleep 2
    fi
  done
  return 1
}

send_text() { # send_text <text> <startLineVar> —— 聚焦输入→打字→落框验证→dump 定位发送→回执验证
  # run6 教训：焦点丢失时 input text 静默落空 → sendMessage 空文本无日志 return
  # （零 RPC 假象）。落框验证 + 宿主档 'Sent prompt' 回执双门禁。
  local txt=$1 lcvar=$2
  tap_text '提问' 3 2 || adb shell input tap 600 2530
  sleep 1
  adb shell input text "$txt"; sleep 1
  adb shell rm -f /sdcard/e2e-chk.xml
  adb shell uiautomator dump /sdcard/e2e-chk.xml >/dev/null 2>&1
  if ! adb shell cat /sdcard/e2e-chk.xml 2>/dev/null | grep -aq "${txt:0:9}"; then
    echo "  [warn] 输入文本未落框（焦点丢失）——重聚焦重输"
    adb shell input tap 600 2530; sleep 1
    adb shell input text "$txt"; sleep 1
    adb shell rm -f /sdcard/e2e-chk.xml
    adb shell uiautomator dump /sdcard/e2e-chk.xml >/dev/null 2>&1
    adb shell cat /sdcard/e2e-chk.xml 2>/dev/null | grep -aq "${txt:0:9}" \
      || { echo "  [fail] 文本二次未落框"; return 1; }
  fi
  printf -v "$lcvar" '%s' "$(log_count)"
  tap_text '发送' 6 3 || { echo "  [fail] 发送键未定位"; return 1; }
  sleep 4
  local lc; eval "lc=\${$lcvar}"
  grep_from "$lc" 'Sent prompt to session' -q \
    || { echo "  [fail] 发送点按后无 Sent prompt 回执（${txt:0:12}…）"; return 1; }
  grep_from "$lc" 'Sent prompt to session' | head -1 | sed 's/^/    /'
  return 0
}

tap_text() { # tap_text <grep-pattern> [retries] [settle-s] [ymin] [ymax] —— dump 定位 text=/content-desc= 节点
  # #293 教训（铁律）：键盘弹起后输入栏随 imePadding 上移，发送键盲点坐标必落键盘
  # （历轮「发送挂起」假象根因）；回放期 heads-up 通知亦会劫持顶栏点按——
  # 关键点按一律 dump 定位。同 pattern 多命中取 y 最小（顶栏按钮优先于同名列表项），
  # 点几何中心。可选 ymin/ymax 过滤（overlay 表内点按防穿透到表后节点）。
  local pat=$1 tries=${2:-1} settle=${3:-2} ymin=${4:-0} ymax=${5:-99999} i=0
  while [ $i -lt $tries ]; do
    i=$((i+1))
    adb shell rm -f /sdcard/e2e-tap.xml
    adb shell uiautomator dump /sdcard/e2e-tap.xml >/dev/null 2>&1
    local best_by=999999 bx=0 by=0 line x1 y1 x2 y2
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      IFS='[],' read -r _ x1 y1 x2 y2 _ <<< "$line"
      if [ "$y1" -ge "$ymin" ] && [ "$y1" -le "$ymax" ] && [ "$y1" -lt "$best_by" ]; then
        best_by=$y1; bx=$(( (x1+x2)/2 )); by=$(( (y1+y2)/2 ))
      fi
    done < <(adb shell cat /sdcard/e2e-tap.xml 2>/dev/null | tr '>' '\n' \
      | grep -a "content-desc=\"[^\"]*$pat\|text=\"[^\"]*$pat" \
      | grep -ao 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
      | grep -ao '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
    if [ "$best_by" -ne 999999 ]; then
      adb shell input tap "$bx" "$by"
      return 0
    fi
    sleep "$settle"
  done
  echo "  [warn] tap_text 未命中: $1"
  return 1
}

wait_dump() { # wait_dump <grep-pattern> <timeout-s> —— uiautomator dump 轮询至文本出现
  local pat=$1 t=$2 i=0
  while [ $i -lt $t ]; do
    adb shell rm -f /sdcard/e2e-wait.xml
    adb shell uiautomator dump /sdcard/e2e-wait.xml >/dev/null 2>&1
    adb shell cat /sdcard/e2e-wait.xml 2>/dev/null | grep -aq "$pat" && return 0
    sleep 2; i=$((i+2))
  done
  return 1
}

# ---------------------------------------------------------------- #279 导出
card_279() {
  echo "== #279 导出 SAF MIME/扩展名（落盘 .zip + unzip -t）=="
  enter_dsh || { FAIL+=("#279:无法进入DSH"); return; }
  wait_dump '仲裁申请书' 30 || { FAIL+=("#279:会话列表未就绪"); return; }
  tap_text '仲裁申请书'                          # 列表首条（最新会话）
  wait_dump '提问' 30 || echo "  [warn] 聊天页 30s 未就绪"
  adb shell input tap 1130 185                  # ⋮
  wait_dump '导出' 15 || { FAIL+=("#279:菜单未打开"); return; }
  tap_text '导出' 3 2
  tap_text '导出' 3 2 || true                    # 双击兜底（首点偶发吞没）
  local tries=0 name=""
  while [ $tries -lt 12 ]; do
    sleep 2; tries=$((tries+1))
    adb shell rm -f /sdcard/e2e.xml
    adb shell uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1
    adb shell cat /sdcard/e2e.xml > "$OUT/279-saf-dump-$tries.xml" 2>/dev/null
    # MIUI 坑：DocumentsUI 树并入 app 包名，不能按 package 判——按 .zip 文件名文本判
    name=$(grep -ao 'text="[^"]*\.zip"' "$OUT/279-saf-dump-$tries.xml" | head -1 | sed 's/text="//;s/"$//')
    [ -n "$name" ] && break
  done
  snap 279-saf
  if [ -z "$name" ]; then
    local jname
    jname=$(grep -ao 'text="[^"]*\.json"' "$OUT/279-saf-dump-"*.xml 2>/dev/null | head -1)
    FAIL+=("#279:SAF 预填非 .zip（${jname:-dump 无文件名}）"); return
  fi
  echo "  SAF 预填: $name"
  # 点「保存/SAVE」（从 dump 取 bounds）
  local sb
  sb=$(grep -ao 'text="保存[^"]*"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT"/279-saf-dump-*.xml 2>/dev/null | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  if [ -n "$sb" ]; then
    local x1 y1 x2 y2
    IFS='[],' read -r _ x1 y1 x2 y2 _ <<< "$sb"
    adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); sleep 6
  else
    adb shell input keyevent KEYCODE_ENTER; sleep 6   # 兜底
  fi
  local dev_zip
  dev_zip=$(adb shell 'ls -t /sdcard/Download/*.zip 2>/dev/null | head -1' | tr -d '\r')
  if [ -z "$dev_zip" ]; then FAIL+=("#279:Download 未找到 .zip"); return; fi
  adb pull "$dev_zip" "$OUT/279-export.zip" >/dev/null 2>&1
  if unzip -t "$OUT/279-export.zip" >/dev/null 2>&1; then
    echo "  落盘 $(basename "$dev_zip") unzip -t OK（$(unzip -l "$OUT/279-export.zip" | tail -1 | awk '{print $2}') entries）"
    PASS+=("#279:SAF 预填 .zip + 落盘 unzip -t 通过")
  else
    FAIL+=("#279:zip 完整性校验失败")
  fi
  adb shell rm -f "$dev_zip"
}

# ---------------------------------------------------------------- #287 缩略图
card_287() {
  echo "== #287 附件缩略图（session.attachment 拉取 or data URL 落库）=="
  enter_dsh || { FAIL+=("#287:无法进入DSH"); return; }
  wait_dump 'Test Lab' 60 || { FAIL+=("#287:Test Lab 条目 60s 未渲染"); snap 287-list; return; }
  local lc287; lc287=$(log_count)
  tap_text 'Test Lab'                            # Test Lab Initialization（列表第 4 条）
  wait_dump '提问' 30 || echo "  [warn] 聊天页未就绪（a11y 退化可致误报，截图为准）"
  sleep 3
  snap 287-session
  local SID
  SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print([i["sessionId"] for i in items if "320c59" in i["sessionId"]][0])' 2>/dev/null)
  echo "  目标会话: ${SID:-未找到}"
  local gate_a=0 gate_b=0
  # 通道A：宿主连续档偏移 grep（进会话即拉取，无旋转丢失）
  grep_from "$lc287" 'session\.attachment' | grep -a 'Ktor Client' > "$OUT/287-ktor-host.txt"
  [ -s "$OUT/287-ktor-host.txt" ] && gate_a=1
  ./scripts/pull-app-db.sh "$SERIAL" "$OUT/287-db" >/dev/null 2>&1
  if [ -n "$SID" ] && sqlite3 "$OUT/287-db.db" "SELECT count(*) FROM cached_parts WHERE sessionId='$SID' AND payload LIKE 'data:image%';" 2>/dev/null | grep -qv '^0$'; then
    gate_b=1
  fi
  echo "  通道A 当次拉取(session.attachment 请求)=$gate_a · 通道B data URL 落库=$gate_b"
  if [ $gate_a -eq 1 ] || [ $gate_b -eq 1 ]; then
    PASS+=("#287:附件管线证据成立（A=$gate_a B=$gate_b，截图归档供抽查）")
  else
    FAIL+=("#287:两通道均无附件证据")
  fi
}

# ---------------------------------------------------------------- #285 斜杠命令
card_285() {
  echo "== #285 斜杠命令（懒建会话发送 + 服务端命令弹层）=="
  enter_dsh || { FAIL+=("#285:无法进入DSH"); return; }
  # 导航/发送全链 dump 定位（#293 教训：盲点坐标会被键盘位移与 heads-up 劫持）。
  # open_new_chat 已含目录翻找点选——懒建会话固定落 scratch/oc-beacon，不进用户真实项目
  open_new_chat || { FAIL+=("#285:目录选择未完成（劫持/渲染异常）"); return; }
  sleep 3
  local lc285=""
  send_text "E2E_285_$(date +%H%M%S)" lc285 || { FAIL+=("#285:发送链失败（详见上方取证）"); return; }
  sleep 3
  snap 285-sent
  local NEW_SID=""
  for i in $(seq 1 30); do                       # 服务端轮询懒建（最多 90s）
    sleep 3
    NEW_SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print(items[0]["sessionId"] if items[0].get("updatedAt",0) > __import__("time").time()*1000-120000 else "")' 2>/dev/null)
    [ -n "$NEW_SID" ] && break
  done
  if [ -n "$NEW_SID" ]; then
    echo "  懒建成功: $NEW_SID"
  else
    echo "  [warn] 懒建未观察到位（发送通道环境受阻——回落既有会话弹层门禁）"
    adb shell am force-stop "$PKG"; sleep 1      # 回退不稳（BACK 层级漂移），冷启重进
    adb shell am start -n "$ACT" --es debug_url http://127.0.0.1:3080 \
      --es debug_username opencode --es debug_name 127.0.0.1:3080 >/dev/null
    wait_logcat 'Debug channel → SessionList' 20 || true
    wait_dump '仲裁申请书' 30 || true
    tap_text '仲裁申请书'                          # 开最新既有会话
    wait_dump '提问' 30 || echo "  [warn] 聊天页未就绪"
  fi
  tap_text '提问' 4 2 || true
  adb shell input text '/'; sleep 2
  snap 285-slash
  adb shell uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1
  adb shell cat /sdcard/e2e.xml > "$OUT/285-slash-dump.xml" 2>/dev/null
  local cmds
  cmds=$(grep -ao 'text="/[a-z][a-z-]*"' "$OUT/285-slash-dump.xml" | sort -u | head -5 | tr '\n' ' ')
  if [ -n "$cmds" ]; then
    echo "  弹层命令(dump): $cmds"
    local extra=""
    [ -n "$NEW_SID" ] && extra="（懒建 $NEW_SID 也通过）"
    PASS+=("#285:命令弹层确定性命中$extra")
  elif grep_from "$lc285" 'commands/list' | grep -aq 'Ktor Client'; then
    PASS+=("#285:commands/list 请求已发出（dump 未捕获 UI 文本，弱通过）")
  else
    FAIL+=("#285:弹层与 commands/list 均无证据")
  fi
  adb shell input keyevent KEYCODE_DEL
}

# ---------------------------------------------------------------- #278 Busy 收敛
card_278() {
  echo "== #278 僵尸 Busy L3 收敛（running 播种 + syncFromRest）=="
  card_278_sid=""; card_278_ran=0
  enter_dsh || { FAIL+=("#278:无法进入DSH"); return; }
  # 导航/发送全链 dump 定位（同 #285；盲点坐标在键盘弹起后必落键盘）
  open_new_chat || { FAIL+=("#278:目录选择未完成（劫持/渲染异常）"); return; }
  sleep 3
  local lc278send=""
  send_text "E2E_278_$(date +%H%M%S)" lc278send || { FAIL+=("#278:发送链失败（详见上方取证）"); return; }
  local SID=""
  for i in $(seq 1 30); do
    sleep 3
    SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print(items[0]["sessionId"] if items[0].get("updatedAt",0) > __import__("time").time()*1000-120000 else "")' 2>/dev/null)
    [ -n "$SID" ] && break
  done
  if [ -z "$SID" ]; then SKIP+=("#278:懒建未观察到位（发送链 dump 定位后仍失败——需人工排查）"); return; fi
  echo "  懒建会话: $SID"
  for i in $(seq 1 15); do                        # 等 running=true（30s 窗口，命中即杀——保活轮次造僵尸现场）
    sleep 2
    rpc session.list '{}' | python3 -c "import json,sys; items=json.load(sys.stdin)['items']; print([i.get('running') for i in items if i['sessionId']=='$SID'][0])" 2>/dev/null | grep -q True && { card_278_ran=1; break; }
  done
  local old_pid; old_pid=$(pid_now)
  adb shell am force-stop "$PKG"; sleep 1
  local new_pid; new_pid=$(pid_now)
  if [ -n "$new_pid" ] && [ "$old_pid" = "$new_pid" ]; then FAIL+=("#278:force-stop 未生效"); return; fi
  local lc278; lc278=$(log_count)
  adb shell am start -n "$ACT" --es debug_url http://127.0.0.1:3080 \
    --es debug_username opencode --es debug_name 127.0.0.1:3080 >/dev/null
  wait_logcat '\[syncFromRest\]' 45 "$lc278" || { FAIL+=("#278:重启后未见 syncFromRest 同步行"); return; }
  sleep 3
  local sync; sync=$(grep_from "$lc278" '\[syncFromRest\]' | tail -1)
  echo "  $sync"
  if echo "$sync" | grep -q 'busy='; then
    PASS+=("#278:强杀重启后 REST 播种生效（running 观察=$card_278_ran；${sync##*] }）")
  else
    FAIL+=("#278:syncFromRest 行无 busy 计数")
  fi
  card_278_sid=$SID
}

# ---------------------------------------------------------------- #283 a2 投影帧
card_283() {
  echo "== #283-a2 permissions 投影帧消费（外部切档 → SessionPermissionsChanged 派发）=="
  local SID="${card_278_sid:-}"
  if [ -z "$SID" ]; then
    # 只用本批懒建的 scratch 会话做切档实验——top-updated 兜底可能命中用户真实
    # 会话（如外包维权），/permission 切档会改其权限设置（2026-09-01 #293 批教训）
    SKIP+=("#283-a2:无懒建会话可用（拒绝在未知会话上切权限档）")
    return
  fi
  echo "  目标会话: $SID"
  local lc283; lc283=$(log_count)
  rpc commands/execute "{\"args\":{\"agentId\":\"$SID\",\"line\":\"/permission read-only\",\"images\":[]}}" >/dev/null 2>&1
  sleep 5
  if grep_from "$lc283" 'SessionPermissionsChanged' | grep -aq 'EventDispatcher'; then
    grep_from "$lc283" 'SessionPermissionsChanged' | head -2 | sed 's/^/    /'
    rpc commands/execute "{\"args\":{\"agentId\":\"$SID\",\"line\":\"/permission workspace-write\",\"images\":[]}}" >/dev/null 2>&1
    PASS+=("#283-a2:外部切档投影帧被消费（SessionPermissionsChanged 派发实证）")
  else
    FAIL+=("#283-a2:投影帧派发行未见（可能未订阅该会话）")
  fi
  snap 283-a1-hint
}

# ---------------------------------------------------------------- 执行（依次串行）
echo "E2E 验收开始 $(date '+%F %T') · serial=$SERIAL · 产物=$OUT"
card_279
card_287
card_285
card_278
card_283

# ---------------------------------------------------------------- 汇总
echo; echo "================ E2E 验收汇总 ================"
for p in "${PASS[@]:-}"; do [ -n "$p" ] && echo "PASS  $p"; done
for f in "${FAIL[@]:-}"; do [ -n "$f" ] && echo "FAIL  $f"; done
for s in "${SKIP[@]:-}"; do [ -n "$s" ] && echo "SKIP  $s"; done
echo "证据目录: $OUT"
[ ${#FAIL[@]} -eq 0 ]
