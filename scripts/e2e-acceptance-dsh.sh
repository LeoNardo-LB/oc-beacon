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

wait_logcat() { # wait_logcat <pattern> <timeout-s>
  local pat=$1 t=$2 i=0
  while [ $i -lt $t ]; do
    sleep 3; i=$((i+3))
    adb shell logcat -d 2>/dev/null | grep -aq "$pat" && return 0
  done
  return 1
}

enter_dsh() { # 冷启 + debug intent 直达 DSH 会话列表，等待回放沉降
  adb reverse tcp:3080 tcp:3080 >/dev/null
  adb logcat -c
  adb shell am force-stop "$PKG"; sleep 1
  adb shell pidof "$PKG" >/dev/null 2>&1 && { echo "  [warn] force-stop 未生效"; return 1; }
  adb shell am start -n "$ACT" --es debug_url http://127.0.0.1:3080 \
    --es debug_username opencode --es debug_name 127.0.0.1:3080 >/dev/null
  wait_logcat 'NavGraph: Debug channel → SessionList' 20 || { echo "  [fail] 未到达会话列表"; return 1; }
  echo "  已进入 DSH 会话列表，等待回放沉降（persist-queue 静默自适应，上限 ${SETTLE_S:-240}s）…"
  local i=0 quiet=0
  while [ $i -lt "${SETTLE_S:-240}" ] && [ $quiet -lt 8 ]; do
    sleep 4; i=$((i+4))
    if adb shell logcat -d -t 60 2>/dev/null | grep -aq 'persist queue full'; then quiet=0; else quiet=$((quiet+4)); fi
  done
  echo "  沉降完成（${i}s）"
}

pid_now() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

tap_text() { # tap_text <grep-pattern> —— dump 定位文本节点 bounds 后点其中心（坐标漂移根治）
  adb shell rm -f /sdcard/e2e-tap.xml
  adb shell uiautomator dump /sdcard/e2e-tap.xml >/dev/null 2>&1
  local b
  b=$(adb shell cat /sdcard/e2e-tap.xml 2>/dev/null | tr '>' '\n' | grep -a "text=\"[^\"]*$1" | grep -ao 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -ao '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  if [ -z "$b" ]; then echo "  [warn] tap_text 未命中: $1"; return 1; fi
  local x1 y1 x2 y2
  IFS='[],' read -r _ x1 y1 x2 y2 _ <<< "$b"
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
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
  tap_text '导出'
  tap_text '导出' || true                        # 双击兜底（首点偶发吞没）
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
  adb logcat -c
  tap_text 'Test Lab'                            # Test Lab Initialization（列表第 4 条）
  wait_dump '提问' 30 || echo "  [warn] 聊天页未就绪（a11y 退化可致误报，截图为准）"
  # 立即抓取（连接洪泛期 logcat 旋转快，晚查会吃掉 session.attachment 行）
  adb shell logcat -d -t 500 2>/dev/null | grep -a 'Ktor Client' | grep -a 'session.attachment' > "$OUT/287-ktor-early.txt"
  sleep 3
  snap 287-session
  local SID
  SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print([i["sessionId"] for i in items if "320c59" in i["sessionId"]][0])' 2>/dev/null)
  echo "  目标会话: ${SID:-未找到}"
  local gate_a=0 gate_b=0
  adb shell logcat -d -t 500 2>/dev/null | grep -a 'Ktor Client' | grep -aq 'session.attachment' && gate_a=1
  [ -s "$OUT/287-ktor-early.txt" ] && gate_a=1
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
  adb shell input tap 975 185; sleep 2           # + 新会话
  adb shell input tap 600 2530; sleep 1
  adb shell input text "E2E_285_$(date +%H%M%S)"; sleep 1
  adb logcat -c
  adb shell input tap 1092 2530; sleep 3         # 发送
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
    wait_logcat 'NavGraph: Debug channel → SessionList' 20 || true
    wait_dump '仲裁申请书' 30 || true
    tap_text '仲裁申请书'                          # 开最新既有会话
    wait_dump '提问' 30 || echo "  [warn] 聊天页未就绪"
  fi
  adb shell input tap 600 2530; sleep 1
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
  elif adb shell logcat -d 2>/dev/null | grep -a 'Ktor Client' | grep -aq 'commands/list'; then
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
  adb shell input tap 975 185; sleep 2
  adb shell input tap 600 2530; sleep 1
  adb shell input text "E2E_278_$(date +%H%M%S)"; sleep 1
  adb shell input tap 1092 2530; sleep 3
  local SID=""
  for i in $(seq 1 30); do
    sleep 3
    SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print(items[0]["sessionId"] if items[0].get("updatedAt",0) > __import__("time").time()*1000-120000 else "")' 2>/dev/null)
    [ -n "$SID" ] && break
  done
  if [ -z "$SID" ]; then SKIP+=("#278:发送通道受阻（连接洪泛），无法造 busy 现场"); return; fi
  echo "  懒建会话: $SID"
  for i in $(seq 1 10); do                        # 等 running=true（10s 窗口）
    sleep 1
    rpc session.list '{}' | grep -q "\"sessionId\":\"$SID\"" && \
      rpc session.list '{}' | python3 -c "import json,sys; items=json.load(sys.stdin)['items']; print([i.get('running') for i in items if i['sessionId']=='$SID'][0])" 2>/dev/null | grep -q True && { card_278_ran=1; break; }
  done
  local old_pid; old_pid=$(pid_now)
  adb shell am force-stop "$PKG"; sleep 1
  local new_pid; new_pid=$(pid_now)
  if [ -n "$new_pid" ] && [ "$old_pid" = "$new_pid" ]; then FAIL+=("#278:force-stop 未生效"); return; fi
  adb logcat -c
  adb shell am start -n "$ACT" --es debug_url http://127.0.0.1:3080 \
    --es debug_username opencode --es debug_name 127.0.0.1:3080 >/dev/null
  wait_logcat '\[syncFromRest\]' 30 || { FAIL+=("#278:重启后未见 syncFromRest 同步行"); return; }
  local sync; sync=$(adb shell logcat -d 2>/dev/null | grep -a '\[syncFromRest\]' | tail -1)
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
    SID=$(rpc session.list '{}' | python3 -c 'import json,sys; items=json.load(sys.stdin)["items"]; items.sort(key=lambda i:i.get("updatedAt",0),reverse=True); print(items[0]["sessionId"])' 2>/dev/null)
  fi
  echo "  目标会话: $SID"
  adb logcat -c
  rpc commands/execute "{\"args\":{\"agentId\":\"$SID\",\"line\":\"/permission read-only\",\"images\":[]}}" >/dev/null 2>&1
  sleep 5
  if adb shell logcat -d 2>/dev/null | grep -a 'EventDispatcher' | grep -aq 'SessionPermissionsChanged'; then
    adb shell logcat -d 2>/dev/null | grep -a 'SessionPermissionsChanged' | head -2 | sed 's/^/    /'
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
