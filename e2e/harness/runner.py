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
    ns = dev.nodes()
    for n in ns:
        if "EditText" in n["cls"]:
            return n
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
        return False
    dev.tap(c["cx"], c["cy"])
    time.sleep(0.8)
    dev.input_text(text)
    time.sleep(0.8)
    dev.key(4)  # 收 IME
    time.sleep(0.8)
    ns = dev.nodes()
    send = [n for n in ns if n["desc"] == "发送" or n["text"] == "发送"]
    if not send:
        # IME 未收：按当前 dump 的发送键位置重试
        send = [n for n in dev.nodes() if n["desc"] == "发送"]
    if not send:
        return False
    dev.tap(send[0]["cx"], send[0]["cy"])
    # 不用 UI dump 等流出（流式组合期 uiautomator 会等 idle 而卡死）；固定 2s 后
    # 由调用方用日志判据确认。
    time.sleep(2.0)
    return True


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


def wait_stream_end(dev, timeout=120):
    """日志判据：ChatStateAggregator 的 [meta] status=Idle / SseIdle。"""
    end = time.time() + timeout
    while time.time() < end:
        log = dev.logcat()
        if "status=Idle" in log or "SseIdle" in log:
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
    ctx.dev.tap(500, 1972)  # 折叠卡头（确定性几何）
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
    y = find_toggle_y(ctx, ys=(800, 850, 750, 900, 700, 950, 650, 1000, 600, 1050, 550, 1100, 500))
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
    ok, evs = ev.card551(viewport=2400)
    last = ev.windows[-1] if ev.windows else None
    if last is None or last.get("tgt") == 0:
        # 末窗是收起，再展开一次
        ctx.dev.tap(500, y)
        time.sleep(2.0)
        ev = probes.make(ctx.dev.logcat())
        ok, evs = ev.card551(viewport=2400)
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


def scenario_549(ctx):
    """#549：随机扰动（toggle burst/滚动）——三脸扫描。

    expand_reasoning=OFF + 短流多轮：折叠卡头在输入框上方可确定性命中；
    短流避免 mock 展开态全文重复渲染导致内容高无界增长（装置侧已知缺陷，
    见 e2e/README）。命中反馈用 degrade toggle 日志计数，不依赖 dump。
    """
    _launch_and_open(ctx, False)
    taps = 0
    for rnd in range(3):
        if not send_prompt(ctx.dev, "e2e549 fuzz %d" % rnd):
            return {"pass": False, "checks": [("SEND", "FAIL")]}
        time.sleep(1.8)
        # 首展：折叠卡头（确定性几何）
        ctx.dev.tap(500, 1972)
        taps += 1
        time.sleep(0.6)
        # 命中反馈扫描（卡头扩展后上移，覆盖视口中部）
        base = _count(ctx.dev, "degrade toggle")
        found = None
        for y in (800, 900, 700, 1000, 600, 1100, 500, 1200, 400, 1300, 300, 1400, 1800):
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
    if deg < 2:
        ok = False
        evs.append("INVALID: degrade toggle 仅 %d 次（随机动作未命中卡头）" % deg)
    return {"pass": bool(ok), "checks": evs, "metrics": {"taps": taps, "degrade_toggles": deg}}


scenario_549.env = {"MOCK_SCENARIO": "plain", "MOCK_LINES": "12", "MOCK_INTERVAL": "0.25",
                    "MOCK_TEXT_CHUNKS": "2", "MOCK_TEXT_INTERVAL": "0.2"}



def scenario_540_dsh_stream(ctx):
    """#540 DSH 腿 A：DSH 流式思考卡展开——降级路径恒配对。"""
    _launch_and_open(ctx, False, server_type="dsh")
    if not send_prompt(ctx.dev, "e2e540 dsh"):
        return {"pass": False, "checks": [("SEND", "FAIL")]}
    time.sleep(2.0)
    h = wait_header(ctx.dev, 20)
    if not h:
        return {"pass": False, "checks": [("NO-CARD", "FAIL")]}
    ctx.dump("dump-before.xml")
    ctx.dev.tap(500, h["cy"])
    time.sleep(2.5)
    ctx.dump("dump-after.xml")
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card540()
    return {"pass": bool(ok), "checks": evs}


scenario_540_dsh_stream.env = {"MOCK_DSH_SCENARIO": "stream", "MOCK_LINES": "24",
                               "MOCK_INTERVAL": "0.8", "MOCK_HOLD_SECS": "20"}
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
    # 首次展开：7k 内容一次性组合（此后无新 delta）
    ctx.dev.tap(500, 1972)
    time.sleep(2.5)
    ev0 = probes.make(ctx.dev.logcat())
    ch0 = [c["b"] for c in ev0.content]
    # 回滚到卡头并收起
    for _ in range(20):
        ctx.dev.swipe(540, 800, 540, 1500, 500)
        time.sleep(0.35)
    y = find_toggle_y(ctx, ys=(800, 850, 750, 900, 700, 950, 650, 1000, 600, 1050, 550, 1100, 500))
    if y is None:
        ctx.dump("dump-header-lost.xml")
        return {"pass": False, "checks": [("HEADER-LOST", "FAIL"), "contentH=%s" % (ch0[-1] if ch0 else None)]}
    ctx.dev.tap(500, y)  # 收起
    time.sleep(1.5)
    # 回到底部（折叠卡头在输入框上方），第二次展开 → 离底让位预算
    for _ in range(10):
        ctx.dev.swipe(540, 1800, 540, 400, 300)
        time.sleep(0.25)
    ctx.dev.tap(500, 1972)
    time.sleep(2.5)
    ev = probes.make(ctx.dev.logcat())
    ok, evs = ev.card551(viewport=2400)
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

ALL_CARDS = ["557", "556_plain", "556_bracket", "540", "554", "551", "547", "549", "540_dsh_stream", "540_dsh_tool"]


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("list")
    r = sub.add_parser("run")
    r.add_argument("--card", required=True)
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
