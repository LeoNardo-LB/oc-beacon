#!/usr/bin/env python3
"""runner.py — 七卡 E2E 编排器（mock V2 + 真机/模拟器 + 探针判据）。

用法：
  python3 e2e/harness/runner.py list
  python3 e2e/harness/runner.py run --card 557 --device emulator-5554
  python3 e2e/harness/runner.py run-all --device emulator-5554
  python3 e2e/harness/runner.py run --card 556 --apk path/to/app.apk   # 含装包

产物：e2e/runs/<YYYYmmdd-HHMMSS>/<card>/{logcat.txt,result.json,dump-*.xml,...}
      e2e/runs/<...>/report.md|json

纪律：
- 每次 run 独立 mock 进程 + 唯一会话标题（避免跨轮内容指纹碰撞）；
- 出流前清 logcat；判据只读本轮日志；
- 截图/dump 失败不掩盖判据（日志优先，UI 次之）。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import device as devmod  # noqa: E402
import probes  # noqa: E402
import prefs as prefsmod  # noqa: E402

PKG = "dev.leonardo.ocbeacon.dev"
EMU = "emulator-5554"


def http_json(url, method="GET", body=None, timeout=10):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def http_ok(url, timeout=3):
    try:
        http_json(url, timeout=timeout)
        return True
    except Exception:
        return False


class Mock:
    def __init__(self, port, env, workdir, title="", dsh=False):
        self.port = port
        self.env = dict(env)
        self.workdir = workdir
        self.proc = None
        self.dsh = dsh
        self.log = os.path.join(workdir, "mock.log")
        if dsh:
            self.env.setdefault("MOCK_TITLE", title)

    def start(self):
        # 清孤儿 mock（上一轮 runner 被 kill 时 finally 未执行会占住端口）
        for pat in ("harness/mock_v2.py", "harness/mock_dsh.py"):
            subprocess.run(["pkill", "-f", pat], capture_output=True)
        time.sleep(0.6)
        e = {**os.environ, **self.env, "MOCK_PORT": str(self.port)}
        script = "mock_dsh.py" if self.dsh else "mock_v2.py"
        self.proc = subprocess.Popen([sys.executable, os.path.join(HERE, script)],
                                     env=e, stdout=open(self.log, "w"), stderr=subprocess.STDOUT)
        for _ in range(50):
            if http_ok(f"http://127.0.0.1:{self.port}/api/health"):
                return
            time.sleep(0.1)
        raise RuntimeError("mock failed to start: " + open(self.log).read()[-500:])

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except Exception:
                self.proc.kill()
            self.proc = None

    def create_session(self, title):
        if self.dsh:
            return self.state().get("sid")
        return http_json(f"http://127.0.0.1:{self.port}/api/session", "POST", {"title": title})["data"]["id"]

    def state(self):
        return http_json(f"http://127.0.0.1:{self.port}/api/_state")

    def pause(self, ms):
        if self.dsh:
            return {"dsh": True}
        return http_json(f"http://127.0.0.1:{self.port}/api/_pause?ms={int(ms)}", "POST", {})


class Ctx:
    def __init__(self, dev, mock, outdir, card, title):
        self.dev = dev
        self.mock = mock
        self.outdir = outdir
        self.card = card
        self.title = title
        self.t0 = time.time()

    def save(self, name, text):
        p = os.path.join(self.outdir, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return p

    def dump(self, name):
        x = self.dev.dump()
        if x:
            self.save(name, x)
        return x


# ---------------- 通用 UI 流程 ----------------

def composer_node(dev):
    # 聊天输入框=底部 EditText（cy>0.72h）；会话列表顶部的搜索框也是 EditText，
    # 不加位置判据会在错误屏幕上误判（20261018-063743 定罪：SEND FAIL 空诊断）。
    h = dev.screen_size()[1]
    ns = dev.nodes()
    ets = [n for n in ns if "EditText" in n["cls"]]
    # 0.45h：无 IME 时 composer ~0.93h；IME 打开时被顶到 ~0.55h——0.72h 阈值
    # 会把带键盘形态误杀（20261018-064428 定罪：attempt-1 空诊断 SEND FAIL）。
    bottom = [n for n in ets if n["cy"] > h * 0.45]
    if bottom:
        return bottom[0]
    for n in ns:
        if n["text"].startswith("提问") or n["text"].startswith("Ask"):
            return n
    return None


def open_session(dev, title, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        ns = dev.nodes()
        hits = [n for n in ns if n["text"] == title]
        if hits:
            n = hits[0]
            dev.tap(300, n["cy"])
            for _ in range(30):
                time.sleep(0.5)
                if composer_node(dev):
                    time.sleep(1.0)
                    return True
        time.sleep(0.8)
    return False


def send_prompt(dev, text, timeout=30):
    c = composer_node(dev)
    if not c:
        SEND_DIAG[0] = "no-composer;focus-screen-unknown"
        return False
    # 真机 MIUI：IME 冷启可达 ~4s——未就绪时 input text 直接丢失（实测 DSH 轮
    # composer 空、发送键无效）。输入后回读校验，未落字则重聚焦重输（3 次）。
    for attempt in range(3):
        dev.tap(c["cx"], c["cy"])
        time.sleep(1.2 + 0.8 * attempt)
        dev.input_text(text)
        time.sleep(0.7)
        ns = dev.nodes()
        if any(text[:6] in (n["text"] or "") for n in ns if "EditText" in n["cls"]):
            break
        c = composer_node(dev) or c
    dev.key(4)  # 收 IME
    time.sleep(0.8)
    ns = dev.nodes()
    send = [n for n in ns if n["desc"] == "发送" or n["text"] == "发送"]
    if not send:
        send = [n for n in dev.nodes() if n["desc"] == "发送"]
    if not send:
        # 诊断：屏幕上全部可点节点摘要（找发送键失败的取证）
        SEND_DIAG[0] = "no-send-btn;clickables=%r;edit=%r" % (
            [(n["desc"] or n["text"])[:10] for n in ns if n["clickable"]][:8],
            [n["text"][:14] for n in ns if "EditText" in n["cls"]][:2])
        return False
    # 双协议标记：V2 = "] POST /prompt"（[prompt] POST /api/.../prompt）；
    # DSH = "api/session.prompt"（Ktor REQUEST，点分隔——"/prompt" 匹配不上，
    # 20261018 双端 DSH 假 SEND FAIL 定谳）。
    def _mark(dev):
        return dev.count_log("] POST /prompt") + dev.count_log("api/session.prompt")
    base = _mark(dev)
    for _ in range(3):
        dev.tap(send[0]["cx"], send[0]["cy"])
        time.sleep(1.2)
        if _mark(dev) > base:
            _LAST_IDLE_BASE[0] = dev.count_log("status=Idle") + dev.count_log("SseIdle")
            time.sleep(1.0)
            return True
        ns = dev.nodes()
        s2 = [n for n in ns if n["desc"] == "发送" or n["text"] == "发送"]
        if s2:
            send = s2
    _ok = _mark(dev) > base
    SEND_DIAG[0] = "mark=%s base_hit;btn=%r;edit=%r" % (
        SEND_DIAG[0],
        (send[0]["cx"], send[0]["cy"]) if send else None,
        [n["text"][:16] for n in dev.nodes() if "EditText" in n["cls"]][:1])
    return _ok


def wait_stream(dev, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if dev.is_streaming():
            return True
        ns = dev.nodes()
        if any(n["text"].startswith("思考") for n in ns):
            return True
        time.sleep(0.5)
    return False


def wait_header(dev, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        ns = dev.nodes()
        hs = [n for n in ns if n["text"].startswith("思考")]
        if hs:
            return hs[0]
        time.sleep(0.6)
    return None


def header_nodes(dev):
    ns = dev.nodes()
    return [n for n in ns if n["text"].startswith("思考")]


def tap_header(dev, x=500):
    h = wait_header(dev, 15)
    if not h:
        return False
    dev.tap(x, h["cy"])
    return True


_LAST_IDLE_BASE = [0]
SEND_DIAG = [""]


def wait_stream_end(dev, timeout=120, base=None):
    """日志判据：等待**新增**的 Idle/SseIdle（发送前的旧 Idle 行会立刻误匹配——
    浸泡轮 16s 流在 ~9s 采样=流中假终态，20261018-015753 定谳）。base 缺省取
    send_prompt 成功时刻的基线。"""
    if base is None:
        base = _LAST_IDLE_BASE[0]
    end = time.time() + timeout
    while time.time() < end:
        if dev.count_log("status=Idle") + dev.count_log("SseIdle") > base:
            return True
        time.sleep(2.0)
    return False


def set_expand_reasoning(dev, val):
    dev.force_stop(PKG)
    dev.set_bool_pref(PKG, "expand_reasoning", val)
    return True


def setup(card, args):
    outdir = os.path.join(args.out, card)
    os.makedirs(outdir, exist_ok=True)
    port = args.port
    dev = devmod.Device(args.device)
    if not dev.wait_online(20):
        raise RuntimeError("device offline: " + args.device)
    if args.apk:
        dev.install(args.apk)
    dev.reverse(port)
    return dev, outdir, port


def launch(dev, port, name, server_type="opencode"):
    dev.logcat_clear()
    ok = dev.start_debug(PKG, f"http://127.0.0.1:{port}", name=name, server_type=server_type)
    if not ok:
        # 已有连接时 debug 入口也可能停在会话列表；再等一次
        time.sleep(3)
    return ok


def run_card(card, args):
    fn = globals().get("scenario_" + card)
    if not fn:
        raise SystemExit("unknown card " + card)
    dev, outdir, port = setup(card, args)
    stamp = time.strftime("%H%M%S")
    title = f"E2E-{card}-{stamp}"
    scenario_env = fn.__dict__.get("env", {})
    # 稳定性验证用：--env KEY=VAL 覆盖场景默认参数（每轮细微差异：行数/节奏/停顿）
    for kv in getattr(args, "extra_env", None) or []:
        if "=" in kv:
            k, v = kv.split("=", 1)
            scenario_env[k] = v
    is_dsh = fn.__dict__.get("dsh", False)
    mock = Mock(port, scenario_env, outdir, title=title, dsh=is_dsh)
    result = {"card": card, "device": args.device, "title": title, "start": time.strftime("%F %T"),
              "pass": False, "checks": [], "notes": []}
    ctx = None
    try:
        mock.start()
        sid = mock.create_session(title)
        result["session"] = sid
        ctx = Ctx(dev, mock, outdir, card, title)
        ctx.mock_env = dict(scenario_env)
        verdict = fn(ctx)
        result.update(verdict)
    except Exception as e:
        result["error"] = repr(e)
        result["checks"].append(("EXCEPTION", str(e)))
    finally:
        try:
            log = dev.logcat()
            open(os.path.join(outdir, "logcat.txt"), "w", encoding="utf-8").write(log)
        except Exception:
            log = ""
        try:
            result["mock_state"] = mock.state()
        except Exception:
            pass
        mock.stop()
    result["end"] = time.strftime("%F %T")
    with open(os.path.join(outdir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    return result


# ---------------- 场景实现 ----------------

def _launch_and_open(ctx, expand, server_type="opencode"):
    dev = ctx.dev
    set_expand_reasoning(dev, expand)
    if not launch(dev, ctx.mock.port, ctx.title, server_type=server_type):
        raise RuntimeError("debug entry failed")
    if not open_session(dev, ctx.title):
        raise RuntimeError("session open failed")
    return True


def _wait_content(ctx, min_h, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        ev = probes.make(ctx.dev.logcat())
        hs = [c["b"] for c in ev.content]
        if hs and max(hs) >= min_h:
            return max(hs)
        time.sleep(1.0)
    return 0


def scenario_557(ctx):
    """#557：慢速思考流 + 停顿，挂载窗关闭后内容继续增长必须物化为 reveal 高度。"""
    _launch_and_open(ctx, True)
    if not send_prompt(ctx.dev, "e2e557 slow"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(22)
    h = _wait_content(ctx, 300, timeout=10)
    ui = ctx.dump("dump-mid.xml")
    txt = any("thought" in n["text"] for n in ctx.dev.nodes(ui))
    time.sleep(20)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card557()
    ok = bool(ok and h >= 300 and txt)
    evs.append("UI content visible=%s contentH_peak=%d" % (txt, h))
    evs.append("terminal_reveal=%d" % len(ev.terminal))
    return {"pass": ok, "checks": evs}


scenario_557.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "24", "MOCK_INTERVAL": "1.2",
                    "MOCK_PAUSE_EVERY": "8", "MOCK_PAUSE_SECS": "6",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.3"}


def _run_556(ctx, style):
    _launch_and_open(ctx, True)
    if not send_prompt(ctx.dev, "e2e556 " + style):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(8 if style == "plain" else 5)
    mid = probes.make(ctx.dev.logcat())
    ui_mid = ctx.dump("dump-mid.xml")
    mid_marker = "thought" if style == "plain" else "Step"
    mid_text = any(mid_marker in n["text"] for n in ctx.dev.nodes(ui_mid))
    wait_stream_end(ctx.dev, 60)
    time.sleep(2)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card556()
    ui_end = ctx.dump("dump-end.xml")
    end_text = any("Step" in n["text"] for n in ctx.dev.nodes(ui_end))
    if style == "plain":
        # 流中必须放行且可见（#556 产品缺陷面）
        ok = ok and any(g.get("release", 0) > 0 for g in mid.gates) and mid_text
        evs.append("plain mid gate released=%s ui_visible=%s" %
                   (any(g.get("release", 0) > 0 for g in mid.gates), mid_text))
    else:
        held = [g for g in mid.gates if g.get("release", 0) == 0]
        ok = ok and bool(held) and bool(ev.terminal) and end_text
        evs.append("bracket mid held=%s terminal=%d end_visible=%s" %
                   (len(held), len(ev.terminal), end_text))
    return {"pass": bool(ok), "checks": evs}


def scenario_556_plain(ctx):
    return _run_556(ctx, "plain")


def scenario_556_bracket(ctx):
    return _run_556(ctx, "bracket")


scenario_556_plain.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "10", "MOCK_INTERVAL": "0.6",
                          "MOCK_TEXT_CHUNKS": "1", "MOCK_TEXT_INTERVAL": "0.3"}
scenario_556_bracket.env = {"MOCK_SCENARIO": "bracket", "MOCK_LINES": "10", "MOCK_INTERVAL": "0.6",
                            "MOCK_TEXT_CHUNKS": "1", "MOCK_TEXT_INTERVAL": "0.3"}


def scenario_540(ctx):
    """#540：流式 turn 内展开折叠卡——degrade 路径必须恒配对（无上推/回锚）。"""
    _launch_and_open(ctx, False)
    if not send_prompt(ctx.dev, "e2e540 degrade"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(3)
    h = wait_header(ctx.dev, 15)
    if not h:
        return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
    ctx.save("dump-before.xml", ctx.dev.dump())
    ctx.dev.tap(500, h["cy"])
    time.sleep(2.5)
    ctx.dump("dump-after.xml")
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card540()
    return {"pass": bool(ok), "checks": evs}


scenario_540.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "40", "MOCK_INTERVAL": "0.9",
                    "MOCK_PAUSE_EVERY": "15", "MOCK_PAUSE_SECS": "4",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.4"}


def scenario_554(ctx):
    """#554：流式大卡展开后上滑阅读，再收起——卡顶必须不动（absorb pad 场景）。"""
    _launch_and_open(ctx, False)
    if not send_prompt(ctx.dev, "e2e554 absorb"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(2.5)
    h = wait_header(ctx.dev, 15)
    if not h:
        return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
    ctx.dev.tap(500, h["cy"])
    time.sleep(4)  # 卡片长高
    # 冻结流（否则每次 dump 期间流式增长会把视口自愈拉回底部，阅读位不驻留）
    ctx.mock.pause(40000)
    time.sleep(0.5)
    # 阅读位上移：手指下滑=看更早内容（内容下移）——小步直到用户气泡与卡头同屏
    prompt = "e2e554"
    positioned = False
    for _ in range(14):
        ns = ctx.dev.nodes()
        hdr = [n for n in ns if n["text"].startswith("思考")]
        user = [n for n in ns if prompt in n["text"] and n["t"] > 0]
        if hdr and user and 100 <= hdr[0]["t"] <= 1900 and user[0]["t"] < hdr[0]["t"] - 20:
            positioned = True
            break
        ctx.dev.swipe(540, 800, 540, 1200, 500)
        time.sleep(0.4)
    if not positioned:
        ctx.dump("dump-noposition.xml")
        return {"pass": False, "checks": [("POSITION", "FAIL: 无法同时定位用户气泡与卡头")]}
    ns = ctx.dev.nodes()
    hdr = [n for n in ns if n["text"].startswith("思考")][0]
    top_before = hdr["t"]
    ctx.dump("dump-before-collapse.xml")
    # 收起 tap 可能落在预览文本/行内子节点上：以 degrade toggle 计数确认命中，重试 3 次
    base = _count(ctx.dev, "degrade toggle")
    for attempt in range(3):
        ctx.dev.tap(hdr["cx"], hdr["cy"])
        time.sleep(0.8)
        if _count(ctx.dev, "degrade toggle") > base:
            break
        ctx.dev.tap(500, hdr["cy"] + (20 if attempt == 0 else -20))
        time.sleep(0.8)
        if _count(ctx.dev, "degrade toggle") > base:
            break
    time.sleep(1.2)
    ns2 = ctx.dev.nodes()
    hdr2 = [n for n in ns2 if n["text"].startswith("思考")]
    ctx.dump("dump-after-collapse.xml")
    top_after = hdr2[0]["t"] if hdr2 else None
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card554(top_before, top_after)
    return {"pass": bool(ok), "checks": evs,
            "metrics": {"top_before": top_before, "top_after": top_after}}


scenario_554.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "60", "MOCK_INTERVAL": "0.8",
                    "MOCK_PAUSE_EVERY": "20", "MOCK_PAUSE_SECS": "5",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.4"}


def _count(dev, needle):
    return dev.count_log(needle)


def find_toggle_y(ctx, ys=(1900, 1750, 1600, 1450, 1300, 1150, 1000, 850, 700, 550, 400, 250)):
    """日志驱动定位卡头：逐 y 试探 tap，degrade toggle 计数增加即命中。

    流式/巨卡组合期 uiautomator 会等 idle 卡死（历史事故），故大卡场景一律
    不用 dump，以生产探针计数作为命中反馈。
    """
    base = _count(ctx.dev, "degrade toggle")
    for y in ys:
        ctx.dev.tap(500, y)
        time.sleep(0.9)
        if _count(ctx.dev, "degrade toggle") > base:
            return y
    return None


def scenario_551(ctx):
    """#551：巨卡二次展开——tgt 事件锚定 + 离底让位 ≤ 一屏。

    装置：固定坐标首展 → 冻结流 → 下滑回滚 → 日志计数逐 y 试探收起 →
    再展开（末窗=二次展开）。仅在收缩后读一次 dump 换位（流已冻结，稳定）。

    流源用 MOCK_NO_END（只发 delta、不发 reasoning.ended）绕开 #558 的全文重复
    append；turn 保持未完结 → 降级路径成立，卡高可达 3 屏。
    """
    _launch_and_open(ctx, False)
    if not send_prompt(ctx.dev, "e2e551 giant"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(2.5)
    # 首展：dump 定位折叠卡头（折叠态组合轻、dump 安全；真机固定坐标实测
    # 因时序/IME 残留偏移而 miss——展开后的重组合期才需避开 dump）
    h1 = wait_header(ctx.dev, 15)
    if not h1:
        return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
    ctx.dev.tap(h1["cx"], h1["cy"])
    time.sleep(6)           # 内容长到数屏（delta-only ~7k px，3 屏；在流完结前冻结）
    ctx.mock.pause(120000)
    time.sleep(0.4)
    # 回滚：手指下滑看更早内容（内容下移）。本机 700px/500ms 实测每划约 450px。
    def _back(n):
        for _ in range(n):
            ctx.dev.swipe(540, 800, 540, 1500, 500)
            time.sleep(0.45)
    # delta-only 6s 内容实测 ~7.2k px；18 划（~8k）足以顶到列表界，到界后卡头
    # 稳定在视口中部（历史手动轮 813px），再按日志计数扫 y 命中。
    _back(18)
    y = find_toggle_y(ctx, ys=_ys(ctx, (800, 850, 750, 900, 700, 950, 650, 1000, 600, 1050, 550, 1100, 500)))
    if y is None:
        ctx.dump("dump-header-lost.xml")
        return {"pass": False, "checks": [("HEADER-LOST", "FAIL")]}
    # 收起 → 再展开：卡头顶锚，位置基本不变；收集 3 个窗，末窗为二次展开
    ctx.dev.tap(500, y)
    time.sleep(1.4)
    hdr2 = [n for n in ctx.dev.nodes() if n["text"].startswith("思考")]
    y2 = hdr2[0]["cy"] if hdr2 else y
    ctx.dev.tap(500, y2)
    time.sleep(2.2)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card551(viewport=ctx.dev.screen_size()[1])
    last = ev.windows[-1] if ev.windows else None
    if last is None or last.get("tgt") == 0:
        # 末窗是收起，再展开一次
        ctx.dev.tap(500, y)
        time.sleep(2.0)
        ev = probes.make(ctx.dev.logcat())
        ok, evs = ev.card551(viewport=ctx.dev.screen_size()[1])
        last = ev.windows[-1] if ev.windows else None
    evs.append("toggle-y=%d windows=%d last_tgt=%s" %
               (y, len(ev.windows), last.get("tgt") if last else None))
    if len(ev.windows) < 2:
        ok = False
        evs.append("INVALID: 展开窗不足 2")
    return {"pass": bool(ok), "checks": evs}


scenario_551.env = {"MOCK_SCENARIO": "giant", "MOCK_LINES": "40", "MOCK_INTERVAL": "0.3",
                    "MOCK_NO_END": "1", "MOCK_TEXT_CHUNKS": "0"}


def scenario_547(ctx):
    """#547：宿主换名（新 assistantMessageID）后展开态内容指纹键必须命中。"""
    _launch_and_open(ctx, True)
    if not send_prompt(ctx.dev, "e2e547 rename"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(8)
    mid = probes.make(ctx.dev.logcat())
    ui = ctx.dump("dump-mid.xml")
    txt = any("thought" in n["text"] for n in ctx.dev.nodes(ui))
    time.sleep(6)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card547()
    ok = bool(ok and txt)
    evs.append("UI reasoning visible=%s mid_rb=%d" % (txt, len(mid.rb)))
    return {"pass": ok, "checks": evs}


scenario_547.env = {"MOCK_SCENARIO": "rename", "MOCK_LINES": "24", "MOCK_INTERVAL": "0.7",
                    "MOCK_RENAME_AT": "8", "MOCK_TEXT_CHUNKS": "1", "MOCK_TEXT_INTERVAL": "0.3"}



def _hy(ctx, emulator_y=1972, emulator_h=2400):
    """固定 y 按屏幕高度换算（模拟器 1080x2400 标定 → 真机 1200x2670）。"""
    h = ctx.dev.screen_size()[1]
    return int(emulator_y * h / emulator_h)


def _ys(ctx, ys):
    h = ctx.dev.screen_size()[1]
    k = h / 2400.0
    return [int(y * k) for y in ys]


def scenario_549(ctx):
    """#549：随机扰动（toggle burst/滚动）——三脸扫描。

    expand_reasoning=OFF + 短流多轮：折叠卡头在输入框上方可确定性命中；
    短流避免 mock 展开态全文重复渲染导致内容高无界增长（装置侧已知缺陷，
    见 e2e/README）。命中反馈用 degrade toggle 日志计数，不依赖 dump。
    """
    _launch_and_open(ctx, False)
    taps = 0
    for rnd in range(3):
        # 轮首恢复：真机随机手势可被 MIUI 手势区弹回会话列表（072523 存证
        # dump=列表页）——无 composer 时收浮层→仍无则按标题重开会话→再验。
        if not composer_node(ctx.dev):
            ctx.dev.key(4)
            time.sleep(0.8)
            if not composer_node(ctx.dev):
                open_session(ctx.dev, ctx.title, timeout=10)
                time.sleep(0.5)
                if not composer_node(ctx.dev):
                    ctx.save("dump-lost-screen-r%d.xml" % rnd, ctx.dev.dump())
                    return {"pass": False, "checks": [
                        ("INVALID", "round%d 屏幕离开聊天域且重开失败" % rnd),
                        SEND_DIAG[0]]}
        if not send_prompt(ctx.dev, "e2e549 fuzz %d" % rnd):
            return {"pass": False, "checks": [("SEND", "FAIL"), SEND_DIAG[0]]}
        time.sleep(1.8)
        # 首展：dump 定位折叠卡头（设备无关；折叠态 dump 安全）
        h1 = wait_header(ctx.dev, 15)
        if h1:
            ctx.dev.tap(h1["cx"], h1["cy"])
        else:
            ctx.dev.tap(500, _hy(ctx))  # 回退：确定性几何
        taps += 1
        time.sleep(0.6)
        # 命中反馈扫描（卡头扩展后上移，覆盖视口中部）
        base = _count(ctx.dev, "degrade toggle")
        found = None
        for y in _ys(ctx, (800, 900, 700, 1000, 600, 1100, 500, 1200, 400, 1300, 300, 1400, 1800)):
            ctx.dev.tap(500, y)
            taps += 1
            time.sleep(0.25)
            if _count(ctx.dev, "degrade toggle") > base:
                found = y
                break
        if found is not None:
            # 连点 2 次制造重向/动画边界
            for _ in range(2):
                ctx.dev.tap(500, found)
                taps += 1
                time.sleep(0.18)
        # 随机滚动（贴底拉扯）
        if rnd % 2 == 0:
            ctx.dev.swipe(540, 900, 540, 1400, 300)
        else:
            ctx.dev.swipe(540, 1400, 540, 800, 300)
        time.sleep(1.2)
        wait_stream_end(ctx.dev, 30)
        time.sleep(1.0)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card549()
    deg = len([t for t in ev.toggles if "in-streaming-turn" in t["reason"]])
    sets_n = len([r for r in ev.releases if r["set"]])
    # 成型门=交互充分性（防空转 PASS）：degrade≥2 为理想；真机 y 扫描格与展开后
    # 卡头位置错拍时（020401/021426 两轮一致：1 degrade 但 215 sets/3 窗）以
    # 「窗≥2 ∧ sets≥100」判充分交互——3 窗本身即 ≥3 次 toggle 级事件。
    if deg < 2 and not (len(ev.windows) >= 2 and sets_n >= 100):
        ok = False
        evs.append("INVALID: degrade toggle 仅 %d 次（随机动作未命中卡头）" % deg)
    return {"pass": bool(ok), "checks": evs, "metrics": {"taps": taps, "degrade_toggles": deg}}


# 真机 dump ~2-4s/次：3s 短流在 y 扫描开始前已完结（toggles 全落 mech 路径）；
# 40×0.3s=12s 窗保证随机动作落在流式中。
scenario_549.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "40", "MOCK_INTERVAL": "0.3",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.2"}



def scenario_540_dsh_stream(ctx):
    """#540 DSH 腿 A：DSH 流式思考卡展开——降级路径恒配对。

    快节奏变体（如 36×0.11s=4s 窗）+ 发送侧输入校验重试可达 ~12s 时，tap 会
    落在整装消息之后（completed 已回填 → 机件路径展开=产品正确行为，RB toggle/
    mapHit 正常）——此时重发一轮再 tap（成型自愈，2 次上限）。
    """
    _launch_and_open(ctx, False, server_type="dsh")
    for attempt in range(2):
        if not send_prompt(ctx.dev, "e2e540 dsh r%d" % attempt):
            return {"pass": False, "checks": [("SEND", "FAIL"), SEND_DIAG[0]]}
        time.sleep(1.2)
        h = wait_header(ctx.dev, 15)
        if not h:
            if attempt == 0:
                continue
            return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
        base = _count(ctx.dev, "degrade toggle")
        ctx.dev.tap(500, h["cy"])
        time.sleep(2.0)
        if _count(ctx.dev, "degrade toggle") > base:
            break
        # 机件误走（整装已到）→ 等本轮完全落幕（含 hold 窗，否则发送键=停止）
        # 再重发一轮；记录取证（20261018-064649 冷启重放定罪）
        if attempt == 0:
            ctx.save("dump-mech-miss.xml", ctx.dev.dump())
            wait_stream_end(ctx.dev, 60)
            time.sleep(1.0)
            continue
    ctx.dump("dump-after.xml")
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card540()
    return {"pass": bool(ok), "checks": evs}


# MOCK_MIN_STREAM_SECS=9：降级 tap 需流式窗超过装置延迟地板（发送校验+定位
# ~8s）；极速节奏压力由 540-V2 0.15s 变体承担。
scenario_540_dsh_stream.env = {"MOCK_DSH_SCENARIO": "stream", "MOCK_LINES": "24",
                               "MOCK_INTERVAL": "0.8", "MOCK_HOLD_SECS": "20",
                               "MOCK_MIN_STREAM_SECS": "9"}
scenario_540_dsh_stream.dsh = True


def scenario_540_dsh_tool(ctx):
    """#540 DSH 腿 B：输出后工具卡（tool/result 已回填 completed）展开应走机件路径。"""
    _launch_and_open(ctx, False, server_type="dsh")
    if not send_prompt(ctx.dev, "e2e540 tool"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    # 等工具卡出现（assistant/message 整装 + tool/call/result 之后）
    tool = None
    end = time.time() + 40
    while time.time() < end:
        ns = ctx.dev.nodes()
        cand = [n for n in ns if "bash" in n["text"].lower() or "echo e2e" in n["text"]]
        if cand:
            tool = cand[0]
            break
        time.sleep(1.0)
    if not tool:
        ctx.dump("dump-no-tool.xml")
        return {"pass": False, "checks": [("NO-TOOL-CARD", "FAIL")]}
    ctx.dump("dump-before.xml")
    ctx.dev.tap(500, tool["cy"])
    time.sleep(2.0)
    ctx.dump("dump-after.xml")
    ev = probes.make(ctx.dev.logcat())
    log = ctx.dev.logcat()
    deg = [t for t in ev.toggles if "in-streaming-turn" in t["reason"]]
    # 非空洞判据：必须真的发生了展开（RB toggle + mech episode），再断言不走降级
    expanded = ("toggle id=call_e2e_1 -> true" in log) and ("episode-start" in log)
    # B 腿：tool/result 回填后不应再走降级（无 degrade toggle）；且无 windows 负数/回锚
    ok = expanded and not deg and not ev.window_negative()
    evs = ["tool-card expand degrade=%d (B 腿期望 0)" % len(deg),
           "pairing-windows=%d toggles=%d mech-expanded=%s" % (len(ev.windows), len(ev.toggles), expanded)]
    return {"pass": bool(ok), "checks": evs}


scenario_540_dsh_tool.env = {"MOCK_DSH_SCENARIO": "tool", "MOCK_LINES": "8",
                             "MOCK_INTERVAL": "0.5", "MOCK_HOLD_SECS": "25"}
scenario_540_dsh_tool.dsh = True


def scenario_558probe(ctx):
    """#558 探针：只发 delta 的展开态流，量 contentH 与 UI 文本副本数。"""
    _launch_and_open(ctx, False)
    if not send_prompt(ctx.dev, "probe558"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(2.5)
    ctx.dev.tap(500, 1972)
    time.sleep(6)
    ctx.mock.pause(90000)
    time.sleep(0.5)
    ev = probes.make(ctx.dev.logcat())
    ch = [c["b"] for c in ev.content]
    ns = ctx.dev.nodes()
    thought_nodes = [n["text"] for n in ns if "thought" in n["text"]]
    ones = [t for t in thought_nodes if "thought 1:" in t]
    ctx.save("dump-probe.xml", ctx.dev.dump())
    return {"pass": True,
            "checks": ["contentH_max=%s last=%s" % (max(ch) if ch else None, ch[-1] if ch else None),
                       "ui_thought_nodes=%d thought1_copies=%d" % (len(thought_nodes), len(ones))]}


scenario_558probe.env = {"MOCK_SCENARIO": "giant", "MOCK_LINES": "40", "MOCK_INTERVAL": "0.3",
                         "MOCK_NO_END": "1", "MOCK_TEXT_CHUNKS": "0", "MOCK_UNIQUE_LINES": "1"}


def scenario_551_static(ctx):
    """#551 静态巨卡变体：流全部到达后才首次展开（避免 #558 的持续 append 循环），
    再从底部的折叠态做第二次展开，迫使离底让位预算通道。
    """
    _launch_and_open(ctx, False)
    if not send_prompt(ctx.dev, "e2e551 static"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(13)              # 40×0.3s 全部到达；卡片仍折叠（contentH 未测量）
    ctx.mock.pause(180000)
    time.sleep(0.5)
    # 首次展开：dump 定位折叠卡头（折叠态组合轻；见 551 注释）
    h1 = wait_header(ctx.dev, 15)
    if not h1:
        return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
    ctx.dev.tap(h1["cx"], h1["cy"])
    time.sleep(2.5)
    ev0 = probes.make(ctx.dev.logcat())
    ch0 = [c["b"] for c in ev0.content]
    # 回滚到卡头并收起
    for _ in range(20):
        ctx.dev.swipe(540, 800, 540, 1500, 500)
        time.sleep(0.35)
    y = find_toggle_y(ctx, ys=_ys(ctx, (800, 850, 750, 900, 700, 950, 650, 1000, 600, 1050, 550, 1100, 500)))
    if y is None:
        ctx.dump("dump-header-lost.xml")
        return {"pass": False, "checks": [("HEADER-LOST", "FAIL"), "contentH=%s" % (ch0[-1] if ch0 else None)]}
    ctx.dev.tap(500, y)  # 收起
    time.sleep(1.5)
    # 回到底部（折叠卡头在输入框上方），第二次展开 → 离底让位预算
    for _ in range(10):
        ctx.dev.swipe(540, int(ctx.dev.screen_size()[1] * 0.75), 540, int(ctx.dev.screen_size()[1] * 0.17), 300)
        time.sleep(0.25)
    h2 = wait_header(ctx.dev, 10)
    if h2:
        ctx.dev.tap(h2["cx"], h2["cy"])
    else:
        ctx.dev.tap(500, _hy(ctx))
    time.sleep(2.5)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card551(viewport=ctx.dev.screen_size()[1])
    evs.append("static_contentH=%s windows=%d" % (ch0[-1] if ch0 else None, len(ev.windows)))
    if ch0 and ch0[-1] > 10000:
        ok = False
        evs.append("INVALID: 静态展开仍触发 #558 放大 contentH=%d" % ch0[-1])
    if len(ev.windows) < 2:
        ok = False
        evs.append("INVALID: 展开窗不足 2")
    return {"pass": bool(ok), "checks": evs}


scenario_551_static.env = {"MOCK_SCENARIO": "giant", "MOCK_LINES": "40", "MOCK_INTERVAL": "0.3",
                           "MOCK_NO_END": "1", "MOCK_TEXT_CHUNKS": "0"}


def scenario_552(ctx):
    """#552：巨量思考流展开态流式（~283K 字符 / ~2700 逐行毕业段落元素）——峰值堆有界。

    修复前（r1036 真机定罪）：库 MarkdownElementInternal 按元素 remember「毕业时刻
    的全文档字符串快照」→ O(N²) 保留（2480 元素 × ~99K 字符 × 2B ≈ 491MB →
    512MB largeHeap OOM）。判据：无 OOM 且进程存活 AND 峰值 Java Heap ≤
    largeMemoryClass 40% AND 流完结字符数/元素数达标 AND 内容高 ≥100K px。
    """
    dev = ctx.dev
    _launch_and_open(ctx, True)
    # 真机熄屏保活（先例 pin_matrix.sh）：本场景发送后长时零交互——真机 ~35-40s
    # 熄屏即记 Idle(false) 假终态（20261019-025255 定罪：40s/10.4K 时刻早退而
    # 流仍在后台爬升）；svc stayon 全程亮屏，收尾恢复。
    dev.shell("svc power stayon true")
    try:
        return _scenario_552_body(ctx)
    finally:
        dev.shell("svc power stayon false")


def _scenario_552_body(ctx):
    dev = ctx.dev
    if not send_prompt(dev, "e2e552 megastream"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}

    def _sh(cmd):
        return dev.shell(cmd).stdout or ""
    # largeMemoryClass 档位：emulator 576m / 真机 512m（getprop 近似足够判档）
    heap_prop = _sh("getprop dalvik.vm.heapsize").strip().lower()
    large_mb = int(heap_prop[:-1]) if heap_prop.endswith("m") and heap_prop[:-1].isdigit() else 512
    budget_kb = large_mb * 1024 * 2 // 5  # 峰值 Java Heap ≤ 40% large
    peak_kb = 0
    ended = False
    # 定时制终态（无日志锚）：真机终态行不可靠——流中假 Idle(false)（025255）、
    # 端上 logcat|grep 管线高负载期空手（033737）、gate 持续放行时 EOF 无
    # terminal reveal 行（040023）。时长确定性推算：gate 下限 ~0.2s/批（100ch
    # 级行 × BIG_RELEASE 200ch/200ms）+ 流本速 + 终态组合追平余量。
    env = getattr(ctx, "mock_env", {})
    lines = int(env.get("MOCK_LINES", "2700") or 2700)
    interval = float(env.get("MOCK_INTERVAL", "0.01") or 0.01)
    t_deadline = time.time() + 150 + lines * max(interval, 0.21)
    it = 0
    while time.time() < t_deadline:
        it += 1
        alive = _sh("pidof " + PKG).strip()
        if not alive:
            break
        # meminfo 降频（每 3 轮）：负载下单次可达 30-60s
        if it % 3 == 1:
            mi = dev.shell("dumpsys meminfo %s | grep 'Java Heap:'" % PKG, timeout=60).stdout or ""
            for tok in mi.split():
                if tok.isdigit():
                    kb = int(tok)
                    if kb > peak_kb:
                        peak_kb = kb
                    break
        time.sleep(3)
    ended = True  # 定时到点=流完结（真早退由进程死亡分支覆盖；终判据读全量 logcat 自证）
    # 终态组合（terminal 单帧全量揭示）需要数秒完成——contentH 末段仍在上行
    time.sleep(8)
    log = ctx.dev.logcat()
    crashed = ("OutOfMemoryError" in log) or ("FATAL EXCEPTION" in log)
    alive = _sh("pidof " + PKG).strip() != ""
    ev = probes.make(log)
    hs = [c["b"] for c in ev.content]
    # 终态重建路径（reveal 空帧→3s 组合追平）不发 contentH 探针、只发 MDResize
    # card h——两探针取最大（20261019-015518 定罪：contentH 停 31832 而
    # MDResize 已到 313522）。
    mdh = [int(m) for m in re.findall(r"MDResize.*\bh=(\d+)", log)]
    total = max((int(m) for m in re.findall(r"MDPilot.*total=(\d+)", log)), default=0)
    stable = max((int(m) for m in re.findall(r"MDPilot.*stable=(\d+)", log)), default=0)
    ui_alive = False
    if alive:
        try:
            ui_alive = len(dev.nodes()) > 3
        except Exception:
            ui_alive = False
    h_peak = max(hs + mdh) if (hs or mdh) else 0
    checks = [
        ("NO-OOM crash=%s alive=%s" % (crashed, alive),
         "PASS" if (not crashed and alive) else "FAIL"),
        ("HEAP-PEAK %dkb budget=%dkb (large=%dmb)" % (peak_kb, budget_kb, large_mb),
         "PASS" if peak_kb <= budget_kb and peak_kb > 0 else "FAIL"),
        ("STREAM-DONE ended=%s chars=%d elems=%d" % (ended, total, stable),
         "PASS" if (ended and total >= 250000 and stable >= 2300) else "FAIL"),
        ("CONTENT-H peak=%d" % h_peak, "PASS" if h_peak >= 100000 else "FAIL"),
        ("UI-ALIVE nodes=%s" % ui_alive, "PASS" if ui_alive else "FAIL"),
    ]
    ok = all(c[1] == "PASS" for c in checks)
    return {"pass": ok, "checks": checks,
            "metrics": {"peak_heap_kb": peak_kb, "large_mb": large_mb, "chars": total,
                        "stable_elems": stable, "contentH": h_peak}}


scenario_552.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "2700", "MOCK_INTERVAL": "0.01",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.2"}


ALL_CARDS = ["557", "556_plain", "556_bracket", "540", "554", "551", "547", "549", "540_dsh_stream", "540_dsh_tool", "552"]


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("list")
    r = sub.add_parser("run")
    r.add_argument("--card", required=True)
    r.add_argument("--env", dest="extra_env", action="append", default=[],
                   help="覆盖场景 mock 参数（KEY=VAL，可多次）")
    r.add_argument("--device", default=EMU)
    r.add_argument("--apk")
    r.add_argument("--port", type=int, default=4399)
    r.add_argument("--out", default=os.path.join(os.path.dirname(HERE), "runs"))
    ra = sub.add_parser("run-all")
    ra.add_argument("--device", default=EMU)
    ra.add_argument("--apk")
    ra.add_argument("--port", type=int, default=4399)
    ra.add_argument("--out", default=os.path.join(os.path.dirname(HERE), "runs"))
    ra.add_argument("--cards", default=",".join(ALL_CARDS))
    args = ap.parse_args()
    if args.cmd == "list":
        print("\n".join(ALL_CARDS))
        return
    if args.cmd == "run":
        out = args.out
        if not os.path.isabs(out):
            out = os.path.join(os.path.dirname(HERE), out)
        args.out = os.path.join(out, time.strftime("%Y%m%d-%H%M%S"))
        res = run_card(args.card, args)
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return
    if args.cmd == "run-all":
        out = args.out
        if not os.path.isabs(out):
            out = os.path.join(os.path.dirname(HERE), out)
        args.out = os.path.join(out, time.strftime("%Y%m%d-%H%M%S"))
        results = []
        for card in args.cards.split(","):
            print("== running", card, flush=True)
            results.append(run_card(card.strip(), args))
        write_report(results, args.out)
        print(json.dumps([{"card": r["card"], "pass": r.get("pass"), "checks": r.get("checks")}
                          for r in results], ensure_ascii=False, indent=2))
        return
    ap.print_help()


def write_report(results, outdir):
    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, "report.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    lines = ["# 七卡 E2E 运行报告", "", "| 卡 | 判定 | 关键证据 |", "|---|---|---|"]
    for r in results:
        ev = "；".join(str(x) for x in r.get("checks", [])[:3]).replace("|", "\\|")
        lines.append("| #%s | %s | %s |" % (r["card"], "PASS" if r.get("pass") else "FAIL", ev))
    with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
