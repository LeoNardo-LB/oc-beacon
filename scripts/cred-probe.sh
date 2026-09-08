#!/bin/bash
# =============================================================================
# cred-probe.sh — 服务器凭据探查 + 自动输入（backlog #359，走查反馈⑨）
#
# 用法:
#   ./scripts/cred-probe.sh status              # 全目标探查报告（运行态/凭据源/可否注入）
#   ./scripts/cred-probe.sh v1 [serial]         # V1-4198：确保运行→reverse→debug intent 注入
#   ./scripts/cred-probe.sh dsh012 [serial]     # dsh012-a5：确保容器→token 提取→reverse→注入
#   ./scripts/cred-probe.sh opencode <port> <name> [serial]   # 通用：/proc 口令探针→注入
#   ./scripts/cred-probe.sh boundary-248        # 192.168.110.248:248 边界报告（不可探查）
#
# 调研结论（docs/research/2026-09-09-credential-probe-359.md）:
#   - V1-4198：runbook 配方测试服——重启即定义口令（/tmp/v1srv/.pass 落盘 600），
#     运行中亦可从 /proc/<pid>/environ 探回；
#   - dsh012-a5：DSH 0.1.2-alpha.5 探针容器——原 token 不可恢复（仅存进程内存），
#     重建容器后 docker logs 提取新 token（重启轮换语义，与 #325 生产 DSH 同款）；
#   - 192.168.110.248:248：宿主零痕迹（无配置/无容器/无历史）——边界：需用户裁定。
#   - Host-4199：service.json 现成（debug-entry.sh 已覆盖，本脚本 status 一并报告）。
#
# 安全: 口令/token 只经 adb 与本机文件（600）传递；不回显完整值。
# =============================================================================
set -eu

SERIAL=${OCBEACON_SERIAL:-e69a99d8}
PKG=${OCBEACON_PKG:-dev.leonardo.ocbeacon.dev}
ACT="$PKG/dev.leonardo.ocbeacon.MainActivity"

V1_DIR=/tmp/v1srv
V1_PORT=4198
V1_PASS_FILE=$V1_DIR/.pass
V2_CRED_DB=${V2_CRED_DB:-$HOME/.local/share/opencode/opencode.db}
SERVICE_JSON=${OCBEACEN_SERVICE_JSON:-/persistent/home/leo-tkp/.config/opencode/service.json}

DSH012_NAME=dsh012-a5
DSH012_IMG=dsh-keepalive-e2e:0.1.2-alpha.5
DSH012_PORT=3081

# --- 小工具 ---------------------------------------------------------------

listen_pid() { # $1=port → 监听进程 pid（空=无）
    ss -tlnp 2>/dev/null | awk -v p=":$1$" '$4 ~ p {print $NF}' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2 || true
}

proc_password() { # $1=pid → environ 中的 OPENCODE_SERVER_PASSWORD（空=无）
    tr '\0' '\n' < "/proc/$1/environ" 2>/dev/null | grep '^OPENCODE_SERVER_PASSWORD=' | cut -d= -f2- || true
}

wait_logcat() { # $1=grep 模式 $2=超时秒
    for _ in $(seq 1 "$2"); do
        sleep 1
        if adb -s "$SERIAL" logcat -d 2>/dev/null | grep -aq "$1"; then return 0; fi
    done
    return 1
}

inject_opencode() { # $1=port $2=name $3=password [$4=username]
    local port=$1 name=$2 pw=$3 user=${4:-opencode}
    adb -s "$SERIAL" reverse "tcp:$port" "tcp:$port"
    adb -s "$SERIAL" logcat -c
    adb -s "$SERIAL" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 1
    adb -s "$SERIAL" shell am start -n "$ACT" \
        --es debug_url "http://127.0.0.1:$port" \
        --es debug_username "$user" \
        --es debug_password "$pw" \
        --es debug_name "$name" >/dev/null
    echo "[*] 已注入（:$port，name=$name）；等待连接确认…"
    if wait_logcat 'NavGraph: Debug channel → SessionList' 15; then
        echo "OK: $name 已连接，app 停在会话列表"
    else
        echo "FAIL: SessionList 标志未出现（检查 reverse/服务器/口令/是否 dev 包）" >&2
        return 1
    fi
}

# --- status ----------------------------------------------------------------

cmd_status() {
    echo "===== 凭据探查报告（$(date '+%F %T')）====="
    # Host-4199
    if [ -r "$SERVICE_JSON" ] && python3 -c "import json,sys; json.load(open('$SERVICE_JSON'))['password']" >/dev/null 2>&1; then
        echo "Host-4199(V2)   : 运行$( [ -n "$(listen_pid 4199)" ] && echo '中' || echo '未起' ) · 凭据源 service.json ✔ · 注入=debug-entry.sh"
    else
        echo "Host-4199(V2)   : service.json 不可读/缺 password"
    fi
    # V1-4198
    local v1pid; v1pid=$(listen_pid $V1_PORT)
    if [ -n "$v1pid" ]; then
        local pw; pw=$(proc_password "$v1pid")
        echo "V1-4198         : 运行中(pid $v1pid) · $([ -n "$pw" ] && echo '/proc environ 口令 ✔' || echo "/proc 无口令(检查 $V1_PASS_FILE)" ) · 注入=cred-probe.sh v1"
    elif [ -r "$V1_PASS_FILE" ]; then
        echo "V1-4198         : 未运行 · 口令文件在($V1_PASS_FILE) · ./scripts/cred-probe.sh v1 可起服+注入"
    else
        echo "V1-4198         : 未运行 · 无口令文件 · v1 子命令将按 runbook 配方起服并定义新口令"
    fi
    # dsh012-a5
    if docker inspect -f '{{.State.Running}}' "$DSH012_NAME" 2>/dev/null | grep -q true; then
        if docker logs "$DSH012_NAME" 2>&1 | grep -qE 'token=[A-Za-z0-9_-]+'; then
            echo "dsh012-a5(DSH)  : 容器运行中 · docker logs token ✔ · 注入=cred-probe.sh dsh012"
        else
            echo "dsh012-a5(DSH)  : 容器运行中但 logs 无 token（启动行未打/版本无鉴权）"
        fi
    elif docker inspect "$DSH012_NAME" >/dev/null 2>&1; then
        echo "dsh012-a5(DSH)  : 容器已停 · docker start 后注入=cred-probe.sh dsh012"
    elif docker images "$DSH012_IMG" --format x 2>/dev/null | grep -q x; then
        echo "dsh012-a5(DSH)  : 容器不存在(镜像在) · dsh012 子命令将重建容器（token 重生成）"
    else
        echo "dsh012-a5(DSH)  : 镜像缺失——无法重建（边界）"
    fi
    echo "192.168.110.248:248 : 未运行 · 宿主零痕迹 · ❌ 不可探查（见 boundary-248）"
}

# --- v1 --------------------------------------------------------------------

ensure_v1_running() {
    if [ -n "$(listen_pid $V1_PORT)" ]; then echo "[*] V1-4198 已在运行"; return 0; fi
    echo "[*] V1-4198 未运行——按 runbook 配方起服…"
    mkdir -p "$V1_DIR"/data/opencode "$V1_DIR"/config/opencode "$V1_DIR"/home
    # 口令：沿用落盘值，否则新生成并落盘（600）
    local pw
    if [ -r "$V1_PASS_FILE" ]; then
        pw=$(cat "$V1_PASS_FILE")
    else
        pw=$(python3 -c 'import secrets; print(secrets.token_urlsafe(9))')
        umask 177; printf '%s' "$pw" > "$V1_PASS_FILE"; umask 022
        echo "[*] 已生成并落盘新口令（$V1_PASS_FILE，600）"
    fi
    # auth.json（V1 data 目录）：从 V2 凭据库提取 zhipuai-coding-plan key
    if [ ! -f "$V1_DIR"/data/opencode/auth.json ]; then
        if [ -r "$V2_CRED_DB" ]; then
            python3 - "$V2_CRED_DB" "$V1_DIR"/data/opencode/auth.json <<'PY'
import json, sqlite3, sys
db, out = sys.argv[1], sys.argv[2]
row = sqlite3.connect(db).execute(
    "SELECT value FROM credential WHERE integration_id='zhipuai-coding-plan' AND active=1 ORDER BY time_updated DESC LIMIT 1").fetchone()
if row:
    key = json.loads(row[0]).get('key') or json.loads(row[0]).get('apiKey')
    if key:
        import os; open(out, 'w').write(json.dumps({'zhipuai-coding-plan': {'type': 'api', 'key': key}}))
        os.chmod(out, 0o600)
        print('[*] auth.json 已生成（key 从 V2 凭据库提取）')
    else: print('[!] V2 凭据值无 key 字段——V1 将无 LLM（UI 面仍可测）', file=sys.stderr)
else:
    print('[!] V2 凭据库无 zhipuai-coding-plan——V1 将无 LLM（UI 面仍可测）', file=sys.stderr)
PY
        else
            echo "[!] V2 凭据库不可读——V1 将无 LLM（UI 面仍可测）" >&2
        fi
    fi
    # V1 自定义 provider 配置（zhipuai coding plan，OpenAI 兼容）
    cat > "$V1_DIR"/config/opencode/opencode.json <<'JSON'
{
  "model": "zhipuai-coding-plan/glm-5.3",
  "provider": {
    "zhipuai-coding-plan": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Zhipu Coding Plan",
      "options": { "baseURL": "https://open.bigmodel.cn/api/coding/paas/v4" },
      "models": {
        "glm-5.3": { "name": "GLM 5.3" }
      }
    }
  }
}
JSON
    nohup setsid env XDG_DATA_HOME="$V1_DIR"/data XDG_CONFIG_HOME="$V1_DIR"/config HOME="$V1_DIR"/home \
        OPENCODE_SERVER_PASSWORD="$pw" opencode serve --hostname 127.0.0.1 --port $V1_PORT \
        > "$V1_DIR"/serve.log 2>&1 < /dev/null & disown
    for _ in $(seq 1 15); do
        sleep 1
        [ -n "$(listen_pid $V1_PORT)" ] && { echo "[*] V1-4198 已起（pid $(listen_pid $V1_PORT)）"; return 0; }
    done
    echo "FAIL: V1 起服超时——查 $V1_DIR/serve.log" >&2; return 1
}

cmd_v1() {
    [ "${1:-}" ] && SERIAL=$1
    ensure_v1_running
    local pw; pw=$(proc_password "$(listen_pid $V1_PORT)")
    [ -z "$pw" ] && pw=$(cat "$V1_PASS_FILE")
    inject_opencode $V1_PORT V1-4198 "$pw"
}

# --- dsh012 ----------------------------------------------------------------

cmd_dsh012() {
    [ "${1:-}" ] && SERIAL=$1
    if ! docker inspect "$DSH012_NAME" >/dev/null 2>&1; then
        echo "[*] 容器不存在——重建（$DSH012_IMG，0.0.0.0:$DSH012_PORT）…"
        docker run -d --name "$DSH012_NAME" -p ${DSH012_PORT}:${DSH012_PORT} \
            --entrypoint bash "$DSH012_IMG" -c \
            'export DSH_HOME=/e2e/dsh-home; exec dsh web --patch /e2e/e2e-overlay.yml --host 0.0.0.0 --port ${DSH012_PORT}' \
            >/dev/null
    elif ! docker inspect -f '{{.State.Running}}' "$DSH012_NAME" 2>/dev/null | grep -q true; then
        echo "[*] 容器已停——docker start…"
        docker start "$DSH012_NAME" >/dev/null
    fi
    echo "[*] 等待启动行 token（最多 20s）…"
    local token=""
    for _ in $(seq 1 20); do
        sleep 1
        token=$(docker logs "$DSH012_NAME" 2>&1 | grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2 || true)
        [ -n "$token" ] && break
    done
    if [ -z "$token" ]; then
        echo "FAIL: docker logs 无 token=（0.1.1 无鉴权版无需配对；或启动失败——docker logs $DSH012_NAME）" >&2; exit 1
    fi
    echo "[*] token 已提取（${#token} 字符，容器重启后需重跑）"
    adb -s "$SERIAL" reverse tcp:$DSH012_PORT tcp:$DSH012_PORT
    adb -s "$SERIAL" logcat -c
    adb -s "$SERIAL" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 1
    adb -s "$SERIAL" shell am start -n "$ACT" \
        --es debug_url "http://127.0.0.1:$DSH012_PORT" \
        --es debug_server_type dsh \
        --es debug_username dsh \
        --es debug_name dsh012-a5 \
        --es debug_token "$token" >/dev/null
    echo "[*] 已注入（:$DSH012_PORT，server_type=dsh，name=dsh012-a5）；等待交换确认…"
    if wait_logcat 'debug_token exchange .*: ok' 15; then
        echo "OK: token 交换成功，cookie 已持久化（365 天）"
    else
        echo "FAIL: 交换确认未出现（检查 reverse/容器/dev 包）" >&2; exit 1
    fi
}

# --- 通用 opencode（/proc 探针）--------------------------------------------

cmd_opencode() { # $1=port $2=name [$3=serial]
    local port=$1 name=$2
    [ "${3:-}" ] && SERIAL=$3
    local pid; pid=$(listen_pid "$port")
    if [ -z "$pid" ]; then echo "FAIL: :$port 无监听进程（服务须在运行）" >&2; exit 1; fi
    local pw; pw=$(proc_password "$pid")
    if [ -z "$pw" ]; then echo "FAIL: pid $pid environ 无 OPENCODE_SERVER_PASSWORD（服务可能从配置文件读口令——用对应专用通道）" >&2; exit 1; fi
    inject_opencode "$port" "$name" "$pw"
}

# --- 边界 ------------------------------------------------------------------

cmd_boundary() {
    cat <<'EOF'
192.168.110.248:248 —— 不可探查（2026-09-09 全清点）
  已查: ~/.config/opencode/(service.json×2 均 4199) · opencode.jsonc · docker 容器/镜像 ·
        bash·zsh history(无口令记录) · systemd 单元 · 当前无 :248 监听
  裁定路径: ①用户重启该服务并以环境变量携带口令 → ./scripts/cred-probe.sh opencode 248 <name>
            即可接管（/proc 探针）; ②弃用该条目
EOF
}

case "${1:-}" in
    status) shift; cmd_status ;;
    v1) shift; cmd_v1 "$@" ;;
    dsh012) shift; cmd_dsh012 "$@" ;;
    opencode) shift; [ $# -ge 2 ] || { echo '用法: opencode <port> <name> [serial]' >&2; exit 2; }; cmd_opencode "$@" ;;
    boundary-248) shift; cmd_boundary ;;
    *) sed -n '2,20p' "$0"; exit 2 ;;
esac