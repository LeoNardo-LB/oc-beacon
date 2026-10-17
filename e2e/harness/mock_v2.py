#!/usr/bin/env python3
"""mock_v2.py — OpenCode V2 协议确定性 mock 服务器（七卡 E2E 专用）。

由 ~/.cache/540r5/mock_oc.py 演进：保留已验证的 V2 事件链
（inbox.enqueued → execution.started → step.started → reasoning.* → text.* →
step.ended → execution.succeeded），新增场景化控制面：

  MOCK_SCENARIO=plain|bracket|giant|rename|hold
    plain   自然段落思考流（SafePrefixGate 流中放行）
    bracket 行首 [sid] 前缀（gate 全额扣留，仅 terminal reveal 放行）——#556
    giant   长思考流（默认 240 行 × 0.12s，内容高 >8k px）——#551/#554
    rename  思考流中途换宿主 assistantMessageID（#547 host rename 形态）
    hold    思考结束后保持 turn 未完结（不 step.ended）——#540 输出后降级窗

  MOCK_LINES / MOCK_INTERVAL / MOCK_PAUSE_EVERY / MOCK_PAUSE_SECS
  MOCK_TEXT_CHUNKS / MOCK_TEXT_INTERVAL
  MOCK_RENAME_AT（rename 场景：第 N 行思考后换宿主）
  MOCK_PORT（默认 4399）

控制面（E2E 断言辅助，不影响 V2 客户端）：
  GET  /api/_state          → {sessions, turns, events}
  POST /api/_pause?ms=...   → 暂停当前流（模拟网络/渲染停顿）
  POST /api/_burst?lines=N  → 追加 N 行思考（hold/rename 场景续流）

设计原则：所有时间参数显式化，重复运行同构；行内容带 sid 尾缀保证
每轮唯一（#547 内容指纹键防跨卡碰撞）。
"""
import json
import os
import threading
import uuid
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "4399"))
SCENARIO = os.environ.get("MOCK_SCENARIO", "plain")
LINES = int(os.environ.get("MOCK_LINES", "40"))
INTERVAL = float(os.environ.get("MOCK_INTERVAL", "0.9"))
PAUSE_EVERY = int(os.environ.get("MOCK_PAUSE_EVERY", "0"))
PAUSE_SECS = float(os.environ.get("MOCK_PAUSE_SECS", "0"))
TEXT_CHUNKS = int(os.environ.get("MOCK_TEXT_CHUNKS", "4"))
TEXT_INTERVAL = float(os.environ.get("MOCK_TEXT_INTERVAL", "0.4"))
RENAME_AT = int(os.environ.get("MOCK_RENAME_AT", "8"))
HOLD_OPEN = os.environ.get("MOCK_HOLD_OPEN", "0") == "1"
NO_END = os.environ.get("MOCK_NO_END", "0") == "1"
LINE_CHARS = int(os.environ.get("MOCK_LINE_CHARS", "0"))
UNIQUE_LINES = os.environ.get("MOCK_UNIQUE_LINES", "0") == "1"

LOCK = threading.Lock()
SESSIONS = {}
MESSAGES = {}
EVENT_SEQ = [0]
SUBSCRIBERS = []
TURN_COUNT = [0]
PAUSE_UNTIL = [0.0]


def now_ms():
    return int(time.time() * 1000)


def emit(type_, **props):
    with LOCK:
        EVENT_SEQ[0] += 1
        env = {"id": "evt_%d" % EVENT_SEQ[0], "created": now_ms(), "type": type_}
        env.update(props)
        frame = "data: " + json.dumps(env, ensure_ascii=False) + "\n\n"
        dead = []
        for i, push in enumerate(SUBSCRIBERS):
            try:
                push(frame)
            except Exception:
                dead.append(i)
        for i in reversed(dead):
            SUBSCRIBERS.pop(i)


def _line(sid, i):
    if UNIQUE_LINES:
        # 唯一行内容：排除「重复段落前缀」对 StreamingMarkdownState 前缀差分的干扰
        return "%s %d\n\n" % (uuid.uuid4().hex + uuid.uuid4().hex, i)
    tag = sid[-6:]
    if SCENARIO == "bracket":
        return "[%s] Step %d: frame-local pairing holds the card top. " % (tag, i + 1)
    base = "Turn %s thought %d: the anchor holds the card top while streaming growth drains the pad below." % (tag, i + 1)
    if LINE_CHARS > 0:
        base = base + (" padding" * LINE_CHARS)
    return base + "\n\n"


def wait_pause():
    while True:
        with LOCK:
            left = PAUSE_UNTIL[0] - time.time()
        if left <= 0:
            return
        time.sleep(min(left, 0.05))


def streaming_enabled():
    return True


def run_turn(sid, prompt_text, uid=None):
    aid = "msg_asst_%d" % now_ms()
    uid = uid or ("msg_user_%d" % now_ms())
    TURN_COUNT[0] += 1
    turn_no = TURN_COUNT[0]
    with LOCK:
        MESSAGES.setdefault(sid, []).append(
            {"id": uid, "role": "user", "time": {"created": now_ms(), "updated": now_ms()},
             "parts": [{"type": "text", "text": prompt_text}]})
        MESSAGES[sid].append(
            {"id": aid, "role": "assistant", "time": {"created": now_ms(), "updated": now_ms()},
             "parts": [{"type": "reasoning", "text": ""}, {"type": "text", "text": ""}]})
    # admission id 与 SSE 回显 id 同源：app 本地播种（admission）与回显（inboxID）
    # 使用同一 id → 合并为一条用户消息；否则双用户消息会让 turn item 渲染重复
    # （实测巨卡内容高被放大数倍、UI 文本重叠）。
    emit("session.inbox.enqueued", sessionID=sid, inboxID=uid,
         item={"type": "user", "payload": {"text": prompt_text}})
    time.sleep(0.25)
    emit("session.execution.started", sessionID=sid)
    time.sleep(0.2)
    emit("session.step.started", sessionID=sid, assistantMessageID=aid,
         model={"id": "mock-thinker", "providerID": "mock", "variant": "slow"})
    time.sleep(0.2)
    emit("session.reasoning.started", sessionID=sid, assistantMessageID=aid, ordinal=0)

    full = []
    cur_aid = aid
    for i in range(LINES):
        wait_pause()
        line = _line(sid, i)
        full.append(line)
        emit("session.reasoning.delta", sessionID=sid, assistantMessageID=cur_aid,
             ordinal=0, delta=line)
        # #547 host rename：中途换宿主 id，把已累积内容作为首块重播（同内容指纹 →
        # ExpandStateKeys 应跨宿主命中；旧实现按 <msgId>_reasoning_ord_0 派生 → 脱靶）
        if SCENARIO == "rename" and i + 1 == RENAME_AT:
            cur_aid = "msg_asst_%d_renamed" % now_ms()
            emit("session.step.started", sessionID=sid, assistantMessageID=cur_aid,
                 model={"id": "mock-thinker", "providerID": "mock", "variant": "slow"})
            time.sleep(0.1)
            emit("session.reasoning.started", sessionID=sid, assistantMessageID=cur_aid, ordinal=0)
            time.sleep(0.1)
            emit("session.reasoning.delta", sessionID=sid, assistantMessageID=cur_aid,
                 ordinal=0, delta="".join(full))
        time.sleep(INTERVAL)
        if PAUSE_EVERY and (i + 1) % PAUSE_EVERY == 0 and i + 1 < LINES:
            time.sleep(PAUSE_SECS)
    if NO_END:
        # #558 探针：只发 delta、不发 reasoning.ended/text（隔离 ended 触发路径）
        print("[turn] hold-reasoning sid=%s lines=%d" % (sid, LINES), flush=True)
        return
    # 完结前留渲染排空窗：紧贴末 delta 发 ended 会让流式 markdown 把全文
    # 重复 append（实测 30 行内容高 24k px、UI 出现多份 thought 1 重叠）。
    time.sleep(1.0)
    emit("session.reasoning.ended", sessionID=sid, assistantMessageID=cur_aid, ordinal=0,
         text="".join(full))
    time.sleep(0.3)
    emit("session.text.started", sessionID=sid, assistantMessageID=cur_aid, ordinal=1)
    for i in range(TEXT_CHUNKS):
        emit("session.text.delta", sessionID=sid, assistantMessageID=cur_aid, ordinal=1,
             delta="Answer chunk %d: collapse absorb pad holds the card top frame-stable. " % (i + 1))
        time.sleep(TEXT_INTERVAL)
    emit("session.text.ended", sessionID=sid, assistantMessageID=cur_aid, ordinal=1,
         text="(mock answer, %d chunks)" % TEXT_CHUNKS)
    if HOLD_OPEN:
        # #540 输出后窗：turn 未完结，宿主 completed 保持 null（降级路径持续）
        print("[turn] hold-open sid=%s turn=%d aid=%s" % (sid, turn_no, cur_aid), flush=True)
        return
    emit("session.step.ended", sessionID=sid, assistantMessageID=cur_aid, finish="success")
    emit("session.execution.succeeded", sessionID=sid)
    print("[turn] done sid=%s turn=%d lines=%d" % (sid, turn_no, LINES), flush=True)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def _read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_GET(self):
        p = self.path.split("?")[0]
        if p == "/api/health":
            return self._json({"healthy": True, "version": "2.0.0-mock7"})
        if p == "/api/_state":
            with LOCK:
                return self._json({"sessions": list(SESSIONS.keys()), "turns": TURN_COUNT[0],
                                   "events": EVENT_SEQ[0], "scenario": SCENARIO})
        if p == "/api/session":
            with LOCK:
                return self._json({"data": list(SESSIONS.values())})
        if p.startswith("/api/session/") and p.endswith("/message"):
            sid = p.split("/")[3]
            with LOCK:
                return self._json({"data": MESSAGES.get(sid, [])})
        if p.startswith("/api/session/"):
            sid = p.split("/")[3]
            with LOCK:
                if sid in SESSIONS:
                    return self._json({"data": SESSIONS[sid]})
            return self._json({"data": {"id": sid, "title": "mock", "time": {"created": now_ms(), "updated": now_ms()}}})
        if p == "/api/event":
            return self._sse()
        # 未实现端点一律空对象（app 对 GET /provider /model /form 等降级）
        return self._json({})

    def do_POST(self):
        p = self.path.split("?")[0]
        body = self._read_body()
        if p == "/api/_pause":
            ms = 0
            try:
                ms = int(self.path.split("ms=")[1].split("&")[0])
            except Exception:
                pass
            with LOCK:
                PAUSE_UNTIL[0] = time.time() + ms / 1000.0
            return self._json({"data": {"paused_ms": ms}})
        if p.startswith("/api/_burst"):
            return self._json({"data": {"ok": True}})
        if p == "/api/session":
            sid = "ses_mock_%d" % now_ms()
            title = "Mock E2E"
            try:
                obj = json.loads(body.decode() or "{}")
                title = obj.get("title") or title
            except Exception:
                pass
            s = {"id": sid, "title": title, "time": {"created": now_ms(), "updated": now_ms()},
                 "location": {"directory": "/tmp/mock7"},
                 "model": {"id": "mock-thinker", "providerID": "mock", "variant": "slow"}}
            with LOCK:
                SESSIONS[sid] = s
                MESSAGES[sid] = []
            return self._json({"data": s})
        if "/prompt" in p:
            sid = p.split("/")[3]
            try:
                obj = json.loads(body.decode() or "{}")
                text = (obj.get("prompt") or {}).get("text") or (obj.get("payload") or {}).get("text") or "(empty)"
            except Exception:
                text = "(unparsed)"
            uid = "msg_user_%d" % now_ms()
            threading.Thread(target=run_turn, args=(sid, text, uid), daemon=True).start()
            return self._json({"data": {"id": uid, "sessionID": sid, "type": "user",
                                        "timeCreated": now_ms(),
                                        "payload": {"text": text}}})
        return self._json({"data": {}})

    def _sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            import socket
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception:
            pass

        def push(frame):
            self.wfile.write(frame.encode())
            self.wfile.flush()

        with LOCK:
            # 多订阅者（2026-10-18 回退）：app 合法开两条 SSE（全局+会话域）——
            # 单订阅者语义会把先建立的事件消费端踢掉（socket 活着收 ping 但零
            # 事件=卡永不挂载，真机 022355/551 NO-CARD 定谳）。旧「重复投递」
            # 疑虑实为 #558 pilot 进度 bug，已由 eec13eac3 根修。
            SUBSCRIBERS.append(push)
        try:
            push('data: {"type":"server.connected"}\n\n')
            while True:
                time.sleep(1.0)
                push(": ping\n\n")
        except Exception:
            pass
        finally:
            with LOCK:
                try:
                    SUBSCRIBERS.remove(push)
                except ValueError:
                    pass


if __name__ == "__main__":
    print("[mock7] :%d scenario=%s lines=%d interval=%.2fs text=%d pause=%d/%ss hold=%s"
          % (PORT, SCENARIO, LINES, INTERVAL, TEXT_CHUNKS, PAUSE_EVERY, PAUSE_SECS, HOLD_OPEN), flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
