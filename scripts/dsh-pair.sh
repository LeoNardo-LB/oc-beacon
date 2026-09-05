#!/bin/bash
# =============================================================================
# dsh-pair.sh — DSH 首次配对辅助（backlog #325；调研 2026-09-04-dsh-token-autodiscovery）
#
# 用法:
#   ./scripts/dsh-pair.sh [serial]        # USB 模式（默认）：adb reverse + debug_token 注入
#   ./scripts/dsh-pair.sh --lan           # LAN 模式：打印深链 / 服务器 URL / 二维码
#
# 环境变量:
#   DSH_WEB_LOG      dsh web 日志路径（默认 ~/.local/state/dsh-web/web.log）
#   DSH_PAIR_PORT    DSH 端口（默认 3080）
#   DSH_PAIR_LAN_URL LAN 模式显式服务器地址（默认从 web.log 启动行提取 192.168.*）
#
# 四通道裁决（#325，按调研文档 §3/§5 落地）:
#   ① adb 注入   = 实现（app 侧 debug_token extra 通道 #317 现成；本脚本为宿主侧入口）
#   ② QR 扫码    = 不实现（app 无 CameraX/MLKit/zxing，AGENTS 红线禁新增依赖）
#                  → 降级：LAN 模式打二维码（qrencode 可选）/深链，app 侧深链自动填表
#   ③ SSH 通道   = 暂缓（需 sshj ~1.5MB 新依赖 + 凭据 UI + SecretCipher；调研未给出
#                  免依赖路径——Android 无内置 SSH 客户端 API；理由随 #325 汇报登记）
#   ④ sameBackend username 修复 = app 侧实现（DSH↔DSH 忽略 username，单测覆盖）
#
# token 事实（调研 §1）：launch token 只存 dsh web 进程内存，启动行 stdout 是唯一
# 出口——服务重启即轮换，重跑本脚本即可。交换后 cookie 365 天/authority 绑定
#（127.0.0.1 ↔ LAN IP 不可互换）。
# =============================================================================
set -eu

MODE=usb
SERIAL=${DSH_PAIR_SERIAL:-e69a99d8}
if [ "${1:-}" = "--lan" ]; then MODE=lan; shift 2>/dev/null || true; fi
[ $# -ge 1 ] && SERIAL=$1

PKG=${OCBEACON_PKG:-dev.leonardo.ocbeacon.dev}
ACT="$PKG/dev.leonardo.ocbeacon.MainActivity"
LOG="${DSH_WEB_LOG:-$HOME/.local/state/dsh-web/web.log}"
PORT="${DSH_PAIR_PORT:-3080}"

# --- 提取当前 launch token（dsh-url 同款逻辑；重启后轮换，重跑本脚本即可） ---
if [ ! -r "$LOG" ]; then
  echo "ERROR: 日志不可读: $LOG（服务未启动或路径不对，DSH_WEB_LOG 可覆盖）" >&2; exit 1
fi
TOKEN=$(grep -oE 'token=[A-Za-z0-9_-]+' "$LOG" | tail -1 | cut -d= -f2)
if [ -z "$TOKEN" ]; then
  echo "ERROR: $LOG 中未找到 token=（0.1.1 无鉴权版本无需配对；或服务未重启过）" >&2; exit 1
fi

echo "[*] token 已提取（${#TOKEN} 字符 base64url，服务重启后需重跑）"

urlencode() { python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

if [ "$MODE" = "usb" ]; then
  # ------------------------------------------------------------------------
  # 通道①：adb 注入（dev flavor 全构建类型；beta/stable 无调试通道 → 用 --lan）
  # authority 一致性：设备连 127.0.0.1:PORT（adb reverse），交换与连接同 authority。
  # ------------------------------------------------------------------------
  URL="http://127.0.0.1:$PORT"
  adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT"
  adb -s "$SERIAL" logcat -c
  adb -s "$SERIAL" shell am force-stop "$PKG" 2>/dev/null || true
  sleep 1
  adb -s "$SERIAL" shell am start -n "$ACT" \
    --es debug_url "$URL" \
    --es debug_server_type dsh \
    --es debug_username dsh \
    --es debug_name "DSH-Paired" \
    --es debug_token "$TOKEN" >/dev/null
  echo "[*] 已注入（$URL，server_type=dsh）；等待交换确认…"
  for i in $(seq 1 15); do
    sleep 1
    if adb -s "$SERIAL" logcat -d 2>/dev/null | grep -aq 'debug_token exchange .*: ok'; then
      echo "OK: token 交换成功，cookie 已持久化（365 天）；Debug channel → 会话列表"
      adb -s "$SERIAL" logcat -d 2>/dev/null | grep -aE 'Debug channel (activated)|debug_token exchange|NavGraph: Debug channel' | tail -3
      exit 0
    fi
  done
  echo "FAIL: 交换确认未出现（检查：reverse 是否重建 / dsh web 是否在 $PORT / 设备是否 dev 包）" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 通道②（降级）：LAN 深链 / 手动输入 —— 全 flavor 可用
# app 侧解析 ocbeacon://pair?url=…&token=…（DshPairingParser，单测覆盖）；
# 深链只预填添加表单 + 后台交换，保存仍需在 app 内点确认。
# ---------------------------------------------------------------------------
LAN_LINE=$(grep -E 'dsh web: .*token=' "$LOG" | tail -1)
LAN_URL="${DSH_PAIR_LAN_URL:-$(printf '%s' "$LAN_LINE" | grep -oE 'https?://192[.]168[.][^[:space:])>]+' | head -1 | tr -d ')' | cut -d'?' -f1 | sed 's:/$::' || true)}"
if [ -z "$LAN_URL" ]; then
  # trustedHosts 未含 LAN IP 的部署：退回本机地址（同网段设备不可直达时需自配 DSH_PAIR_LAN_URL）
  LAN_URL="http://127.0.0.1:$PORT"
  echo "WARN: web.log 未见 192.168.* 地址，回退 $LAN_URL（可用 DSH_PAIR_LAN_URL 覆盖）" >&2
fi

DEEPLINK="ocbeacon://pair?url=$(urlencode "$LAN_URL")&token=$TOKEN"

echo
echo "===== DSH 首次配对（LAN 降级通道）====="
echo "服务器 URL: $LAN_URL"
echo "深链（点击/扫码打开 app 自动填表）:"
echo "  $DEEPLINK"
echo
echo "adb 直测（E2E 用；#325 E1 教训：URI 里的 & 必须落在设备侧引号内，"
echo "否则被 shell 切分丢失 token 参数——app 侧将记 pair deep-link rejected: MISSING_TOKEN）:"
echo "  adb -s <serial> shell \"am start -a android.intent.action.VIEW -d '$DEEPLINK'\""
echo
if command -v qrencode >/dev/null 2>&1; then
  echo "二维码（任意扫码器/QR app 打开即预填）："
  qrencode -t ansiutf8 "$DEEPLINK"
else
  echo "提示: 安装 qrencode 可打印二维码（apt install qrencode）"
fi
echo "手动路径: app 内添加服务器（类型选 DSH）填 $LAN_URL 保存连接后，"
echo "         点顶部横幅「输入令牌」粘贴上方深链或服务器启动行。"
# 安全备注（调研 §5）：web.log 组可读（664）意味着 token 随之暴露——建议 640/600。
echo "安全: token=RCE 等价物，仅经可信通道传递；建议收紧 $LOG 权限至 600。"
exit 0
