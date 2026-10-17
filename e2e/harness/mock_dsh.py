#!/usr/bin/env python3
"""mock_dsh.py — DSH 0.1.1 线面最小 mock（#540 DSH 腿）。

协议面（只实现 E2E 所需子集）：
- 探测：POST /api/session/list → 404（slash 形态缺席）；POST /api/session.list → 200；
  decide() 判 V011（dot 200 + slash 404）。
- RPC：POST /api/{method}，信封 {type:client-request,rpcId,method,payload} →
  {type:server-response,rpcId,result:{ok:true,value:{...}}}。
- 下行：GET /api/events.mux + /api/events.host（WebSocket，只收不发；服务端推
  server-request 帧）。
- 事件：turn/start → user/message → assistant/chunk（reasoning/text）→
  assistant/message（整装）→ tool/call → tool/result → [hold] → turn/end。

场景（MOCK_DSH_SCENARIO）：
  stream   长思考流；发送后约 2s 起可手动展开思考卡（#540 A 腿：降级配对）
  tool     思考/正文整装后工具卡 result 已回填 completed，但 turn 未结束；
           展开工具卡应走机件路径（#540 B 腿：输出后窗收窄）
  toolhold 工具卡 result 不回填（对照：模拟修复前恒 null，展开应走降级）
"""
import base64
import hashlib
import json
import os
import queue
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "4398"))
SCENARIO = os.environ.get("MOCK_DSH_SCENARIO", "stream")
REASON_LINES = int(os.environ.get("MOCK_LINES", "20"))
INTERVAL = float(os.environ.get("MOCK_INTERVAL", "0.8"))
# 场景 staged 性钳制：降级路径 tap 需流式窗 ≥8s（装置发送/定位延迟地板）；
# 更快节奏的压力面由 540-V2 极速变体承担（80×0.15=12s 可 staged）。
_MIN_STREAM_SECS = float(os.environ.get("MOCK_MIN_STREAM_SECS", "0"))
if REASON_LINES and INTERVAL * REASON_LINES < _MIN_STREAM_SECS:
    INTERVAL = _MIN_STREAM_SECS / REASON_LINES
HOLD_SECS = float(os.environ.get("MOCK_HOLD_SECS", "12"))
TOOL_HOLD = SCENARIO == "toolhold"
SID = os.environ.get("MOCK_SID", "sess-e2e-%d" % int(time.time()))
TITLE = os.environ.get("MOCK_TITLE", "DSH E2E")

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
LOCK = threading.Lock()
SUBSCRIBERS = []  # list[queue.Queue]
SEQ = [0]
MESSAGES = []     # history rows
PROMPT_LOCK = threading.Lock()


def now_ms():
    return int(time.time() * 1000)


def _frame(method, payload, rpc_id=None):
    env = {"type": "server-request", "rpcId": rpc_id or ("srv-%d" % now_ms()),
           "method": method, "payload": payload}
    return json.dumps(env, ensure_ascii=False)


def push_env(method, payload):
    text = _frame(method, payload)
    with LOCK:
        dead = []
        for i, q in enumerate(SUBSCRIBERS):
            try:
                q.put_nowait(text)
            except Exception:
                dead.append(i)
        for i in reversed(dead):
            SUBSCRIBERS.pop(i)


def session_event(evt_type, data, seq=None):
    with LOCK:
        SEQ[0] += 1
        seq = SEQ[0] if seq is None else seq
    ev = {"type": evt_type, "seq": seq, "time": now_ms()}
    if data is not None:
        ev["data"] = data
    push_env("session/event", {"type": "session/event", "sessionId": SID, "event": ev})
    return seq


def chunk(turn, step, obj):
    return {"turn": turn, "step": step, "chunk": obj}


def run_turn(prompt):
    turn = 1
    step = 1
    session_event("turn/start", {"turn": turn})
    time.sleep(0.15)
    session_event("user/message", {
        "source": {"kind": "user", "rpcId": "rpc-" + str(now_ms())},
        "content": [{"type": "text", "text": prompt}],
    })
    time.sleep(0.15)
    session_event("assistant/chunk", chunk(turn, step, {"type": "block-start", "index": 0, "blockType": "reasoning"}))
    full = []
    for i in range(REASON_LINES):
        line = "DSH turn thought %d: anchor holds while streaming growth drains the pad.\n\n" % (i + 1)
        full.append(line)
        session_event("assistant/chunk", chunk(turn, step, {"type": "reasoning-delta", "index": 0, "text": line}))
        time.sleep(INTERVAL)
    session_event("assistant/chunk", chunk(turn, step, {"type": "block-end", "index": 0}))
    session_event("assistant/chunk", chunk(turn, step, {"type": "block-start", "index": 1, "blockType": "text"}))
    answer = []
    for i in range(3):
        line = "DSH answer chunk %d. " % (i + 1)
        answer.append(line)
        session_event("assistant/chunk", chunk(turn, step, {"type": "text-delta", "index": 1, "text": line}))
        time.sleep(0.3)
    session_event("assistant/chunk", chunk(turn, step, {"type": "block-end", "index": 1}))
    time.sleep(0.2)
    # 整装结算：拆流式桥 + 权威 completed（turn item 只余工具卡宿主）
    session_event("assistant/message", {
        "turn": turn, "step": step,
        "message": {
            "id": "msg-%d" % now_ms(),
            "source": {"kind": "model", "provider": "mock", "model": "mock-dsh"},
            "content": [
                {"type": "reasoning", "text": "".join(full)},
                {"type": "text", "text": "".join(answer)},
            ],
        },
    })
    time.sleep(0.2)
    # 工具卡：call → result（B 腿回填 completed）；toolhold 不回 result
    session_event("tool/call", {"turn": turn, "step": step, "callId": "call_e2e_1",
                                "name": "bash", "arguments": "{\"command\":\"echo e2e\"}"})
    time.sleep(0.3)
    if not TOOL_HOLD:
        session_event("tool/result", {
            "message": {
                "source": {"callId": "call_e2e_1"},
                "content": [{"type": "tool-result", "toolCallId": "call_e2e_1", "output": "e2e\n"}],
            }
        })
    # 输出后窗：turn 未结束（工具卡展开发生在此时）
    time.sleep(HOLD_SECS)
    session_event("turn/end", {})


def ok(value=None, rpc_id=None):
    if value is None:
        value = {}
    return {"type": "server-response", "rpcId": rpc_id or ("rpc-%d" % now_ms()),
            "result": {"ok": True, "value": value}}


def session_item():
    return {"sessionId": SID, "updatedAt": now_ms(), "running": False, "blank": False,
            "cwd": "/tmp/mockdsh", "origin": "user", "agentPreset": "code",
            "projections": {"asOfSeq": 0, "values": {"title": TITLE}}}


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

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw.decode() or "{}")
        except Exception:
            return {}

    def do_GET(self):
        p = self.path.split("?")[0]
        if p in ("/api/events.mux", "/api/events.host"):
            return self._ws()
        if p == "/api/health":
            return self._json({"healthy": True, "version": "0.1.1-mock"})
        if p == "/api/_state":
            return self._json({"sid": SID, "seq": SEQ[0], "subs": len(SUBSCRIBERS)})
        return self._json({"ok": True})

    def do_POST(self):
        p = self.path.split("?")[0]
        # keep-alive 铁律：body 必须在任何 return 之前消费——未读 body 会被
        # http.server 当作下一请求的 request line（实测真机复用连接时下一请求
        # 501、DSH probe 判 Unreachable→「服务器已断开」横幅→发送无效）。
        body = self._body()
        if p == "/api/session/list":
            # probe slash 形态：缺席
            return self._json({"error": "not found"}, 404)
        if p == "/api/_state":
            return self._json({"sid": SID, "seq": SEQ[0], "subs": len(SUBSCRIBERS)})
        if not p.startswith("/api/"):
            return self._json({"error": "not found"}, 404)
        method = p[len("/api/"):]
        rpc_id = body.get("rpcId")
        payload = body.get("payload") or {}
        if method == "session.list":
            return self._json(ok({"items": [session_item()]}, rpc_id))
        if method == "session.history":
            return self._json(ok({"entries": MESSAGES, "hasMore": False}, rpc_id))
        if method == "session.create":
            return self._json(ok(session_item(), rpc_id))
        if method == "session.prompt":
            text = ""
            for c in payload.get("content") or []:
                if isinstance(c, dict) and c.get("type") == "text":
                    text += c.get("text") or ""
            threading.Thread(target=run_turn, args=(text,), daemon=True).start()
            return self._json(ok({}, rpc_id))
        if method in ("session.selectModel", "session.rename", "settings.describe",
                      "llm.models", "llm.providers", "session.modelCatalog",
                      "agentPreset.list", "question.list", "session.updateQueue"):
            return self._json(ok({}, rpc_id))
        return self._json(ok({}, rpc_id))

    def _ws(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            return self._json({"error": "upgrade required"}, 426)
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        q = queue.Queue()
        with LOCK:
            SUBSCRIBERS.append(q)
        try:
            # 订阅基线：lastSeq=0（新会话无历史）
            self.wfile.write(_ws_frame(_frame("session/subscribed", {
                "type": "session/subscribed", "sessionId": SID, "lastSeq": 0})))
            self.wfile.flush()
            while True:
                try:
                    text = q.get(timeout=1.0)
                except queue.Empty:
                    continue
                self.wfile.write(_ws_frame(text))
                self.wfile.flush()
        except Exception:
            pass
        finally:
            with LOCK:
                try:
                    SUBSCRIBERS.remove(q)
                except ValueError:
                    pass


def _ws_frame(text):
    payload = text.encode()
    n = len(payload)
    hdr = bytearray([0x81])
    if n < 126:
        hdr.append(n)
    elif n < 65536:
        hdr.append(126)
        hdr += struct.pack(">H", n)
    else:
        hdr.append(127)
        hdr += struct.pack(">Q", n)
    return bytes(hdr) + payload


if __name__ == "__main__":
    print("[mockdsh] :%d scenario=%s sid=%s lines=%d interval=%.2f hold=%.0fs toolhold=%s"
          % (PORT, SCENARIO, SID, REASON_LINES, INTERVAL, HOLD_SECS, TOOL_HOLD), flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
