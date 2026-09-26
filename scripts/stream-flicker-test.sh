#!/bin/bash
# =============================================================================
# stream-flicker-test.sh — SSE 流式闪烁取证协议（固化：2026-09-26 用户裁决）
#
# 固化的操作序列（验收 #437/#440 标准手势）：
#   1) 经 DSH RPC 向目标会话发送测试消息（默认「输出2000字的文章，不要向我提问，我用于测试」）
#   2) 等待 10s（流式进行中，贴底）
#   3) 快拖无 fling 上滑 1/5 屏（手指下拉 = 阅读旧内容方向，reverseLayout 语义）
#
# 用法:
#   ./scripts/stream-flicker-test.sh <sessionId> [serial]
#     前置：应用已打开目标会话的聊天屏（导航/开会话不固化——那部分易变）；
#     DSH 服务器 3080 由本脚本自动 adb reverse 维护。
#   SFT_DRY=1 ./scripts/stream-flicker-test.sh <sessionId>
#     干跑：reverse/cookie 校验/wm size 换算并打印计划，不发送不滑动。
#
# 环境变量:
#   SFT_PROMPT    提示词（默认固定测试提示词）
#   SFT_DELAY_MS  发送后等待毫秒（默认 10000）
#   SFT_FRAC      上滑距离 = 屏高/此值（默认 5，即 1/5 屏）
#   SFT_SWIPE_MS  拖拽时长毫秒（默认 400——快拖但低于 fling 触发速度）
#   SFT_COOKIE    cookie 缓存（默认 /tmp/oc-dsh-cookie.txt；401 时自动重交换）
#   ANDROID_ADB_SERVER_PORT 透传（本机默认 5038）
# =============================================================================
set -eu

SID=${1:?用法: $0 <sessionId> [serial]}
SERIAL=${2:-${SFT_SERIAL:-192.168.110.239:41925}}
ADB_BIN=${ADB_BIN:-adb}
export ANDROID_ADB_SERVER_PORT=${ANDROID_ADB_SERVER_PORT:-5038}
PORT=3080
PROMPT=${SFT_PROMPT:-输出2000字的文章，不要向我提问，我用于测试}
DELAY_MS=${SFT_DELAY_MS:-10000}
FRAC=${SFT_FRAC:-5}
SWIPE_MS=${SFT_SWIPE_MS:-400}
COOKIE=${SFT_COOKIE:-/tmp/oc-dsh-cookie.txt}
TOKEN_FILE=${SFT_TOKEN_FILE:-$HOME/.dsh/token}

ad() { "$ADB_BIN" -s "$SERIAL" "$@"; }

# --- 1) adb reverse 维护（幂等） ---
ad reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

# --- 2) cookie：缓存优先，session/list 验活，401 即重交换 ---
rpc() { # rpc <method> <argsKey> <argsJson> -> 响应体
  curl -s -m 8 -X POST -b "$COOKIE" -H 'Content-Type: application/json' \
    -d "{\"type\":\"client-request\",\"rpcId\":\"sft-$RANDOM$RANDOM\",\"method\":\"$1\",\"payload\":{\"args\":{\"$2\":$3}}}" \
    "http://127.0.0.1:$PORT/api/$1"
}
ensure_cookie() {
  [ -s "$COOKIE" ] || echo > "$COOKIE"
  if ! rpc session/list '_request' '{}' | grep -q '"ok":true'; then
    [ -r "$TOKEN_FILE" ] || { echo "ERROR: 无 cookie 且 token 不可读: $TOKEN_FILE（先跑 scripts/dsh-pair.sh）" >&2; exit 1; }
    T=$(cat "$TOKEN_FILE")
    curl -s -m 6 -c "$COOKIE" -o /dev/null "http://127.0.0.1:$PORT/?token=$T"
    rpc session/list '_request' '{}' | grep -q '"ok":true' \
      || { echo "ERROR: token 交换后仍 401（服务重启轮换？重跑 scripts/dsh-pair.sh）" >&2; exit 1; }
  fi
}
ensure_cookie

# --- 3) 屏高换算：优先 Override size（wm size 最后一行），兜底 Physical ---
SIZE=$(ad shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DIST=$(( H / FRAC ))
Y0=$(( H * 60 / 100 ))
Y1=$(( Y0 + DIST )); [ $Y1 -gt $(( H - 60 )) ] && Y1=$(( H - 60 ))
XC=$(( W / 2 ))

echo "[plan] screen=${W}x${H} dist=$DIST (1/$FRAC) swipe=($XC,$Y0)->($XC,$Y1) ${SWIPE_MS}ms delay=${DELAY_MS}ms"
if [ "${SFT_DRY:-0}" = "1" ]; then echo "[dry] cookie OK，计划如上，未发送未滑动"; exit 0; fi

# --- 4) 发送测试消息（受理即回，流式经应用自身 SSE/WS 渲染） ---
T0=$(date +%s.%3N)
RESP=$(rpc session/prompt 'request' \
  "{\"requestId\":\"req-sft-$(date +%s)\",\"sessionId\":\"$SID\",\"content\":[{\"type\":\"text\",\"text\":\"$PROMPT\"}],\"mode\":\"queue\"}")
echo "$RESP" | grep -q '"ok":true' || { echo "ERROR: session/prompt 被拒: $RESP" >&2; exit 1; }
echo "[send] ok ${T0}"

# --- 5) 等待 → 快拖上滑 ---
sleep_ms() {
  local ms=$1; sleep $(( ms / 1000 )); local r=$(( ms % 1000 )); [ $r -gt 0 ] && sleep "0.$(printf '%03d' $r)" || true
}
sleep_ms "$DELAY_MS"
TS=$(date +%s.%3N)
ad shell input swipe "$XC" "$Y0" "$XC" "$Y1" "$SWIPE_MS"
echo "[swipe] done ${TS} (T+$(echo "$TS $T0" | awk '{printf "%.2f", $1-$2}')s)"
echo "[done] 取证窗口从滑动静止后起算；配 logcat: VPT ScrollDiag SGR-435 VTRACE ChunkDiag MDPilot"
