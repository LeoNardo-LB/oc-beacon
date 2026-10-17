#!/usr/bin/env python3
"""probes.py — 七卡 E2E 日志探针解析与判据（单文件可复用）。

输入：`adb logcat -d -v time` 文本（可含多轮）。
输出：结构化事件 + 每卡 verdict（PASS/FAIL + 证据行）。

判据来源：
- #540 五腿/三轮绿签名（journal 2026-10-10-540 §9/§19/§23）：degrade toggle
  reason=in-streaming-turn；窗内增长全 set 配对、零 nodis；无一次性回锚。
- #547 红线（547e2e e547.sh）：同键 expanded true→false 无 toggle = SILENT-COLLAPSE；
  key 跨宿主不变（内容指纹键）。
- #549 fuzz 三脸（journal §38 + 卡面）：重向 slam / 贴底不累积 / 尾段往返。
- #551 高度参照系（铁律 §2.4bis）：tgt 事件时刻锚定（不超当前 contentH 的棘轮）；
  离底让位 ≤ 一屏。
- #554 V3 顶缘不变式：收起前后卡片顶缘 Δ ≤ 10px；垫通道 ABSORB 行。
- #556 gate 终态揭示：bracket 流中 release=0/held 增，完结 terminal reveal 全量。
- #557 驱动活读：win-close 后 contentH 增长且 reg=true、reveal 高度跟随。
"""
import re

# 两种 adb logcat 头格式：
#   time  : MM-DD HH:MM:SS.mmm PID TID L Tag: msg
#   brief : MM-DD HH:MM:SS.mmm L/Tag(PID): msg   （本机 emulator 实测）
TS_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)\s+\d+\s+\d+\s+\w\s+([^:]+):\s?(.*)$")
TS_RE_BRIEF = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+)\s+[VDIWEF]/([^(]+)\(\s*\d+\):\s?(.*)$")


class Line:
    __slots__ = ("raw", "ts", "tag", "msg")

    def __init__(self, raw, ts, tag, msg):
        self.raw, self.ts, self.tag, self.msg = raw, ts, tag, msg


def parse(text):
    out = []
    for raw in text.splitlines():
        m = TS_RE.match(raw) or TS_RE_BRIEF.match(raw)
        if not m:
            continue
        mo, d, h, mi, s, ms, tag, msg = m.groups()
        ts = int(h) * 3600 + int(mi) * 60 + int(s) + int(ms.ljust(3, "0")[:3]) / 1000.0
        out.append(Line(raw, ts, tag.strip(), msg))
    return out


def _ints(msg, *names):
    d = {}
    for n in names:
        m = re.search(n + r"=(-?\d+)", msg)
        if m:
            d[n] = int(m.group(1))
    return d


class Ev:
    """统一事件抽取。"""

    def __init__(self, lines):
        self.lines = lines
        self.windows = []       # {open_ts, close_ts, host, tgt, span, itemH, boxY, stickTop}
        self.toggles = []       # {ts, visible, reason, host}
        self.releases = []      # {ts, kind, capd, drv/dpos, led, set:(fii,fiso)|None, h, raw}
        self.flushes = []       # {ts, fii, fiso, ip}
        self.drops = []         # {ts, fii, fiso}
        self.absorbs = []       # {ts, step, scroll, drain, pad, fii, fiso}
        self.content = []       # {ts, a, b, tgt, reg}
        self.winclose = []      # {ts, vis, remove, h, tgt, contentH, anim}
        self.gates = []         # {ts, release, held, releasedTotal, snapshotTotal}
        self.terminal = []      # {ts, text, total}
        self.rb = []            # {ts, kind, id, key, expanded, mapHit}
        self.rbtog = []         # {ts, id, val}
        self.mdsize = []        # {ts, h, d}
        self.resizes = []       # {ts, key, a, b, d}
        self.fatal = []
        self._scan()

    def _scan(self):
        for ln in self.lines:
            m = ln.msg
            tag = ln.tag
            if "pairing-window open" in m:
                d = _ints(m, "windows", "stickTop", "span", "itemH", "boxY", "tgt")
                hm = re.search(r"host=(\S+)", m)
                self.windows.append({"ts": ln.ts, "host": hm.group(1) if hm else "?",
                                     "tgt": d.get("tgt", -1), "span": d.get("span", -1),
                                     "itemH": d.get("itemH", -1), "boxY": d.get("boxY", -1),
                                     "stickTop": d.get("stickTop", -1),
                                     "windows": d.get("windows", -1)})
            elif "pairing-window close" in m:
                d = _ints(m, "windows")
                for w in reversed(self.windows):
                    if "close_ts" not in w:
                        w["close_ts"] = ln.ts
                        w["close_windows"] = d.get("windows", -1)
                        break
            elif "degrade toggle" in m:
                vm = re.search(r"visible=(\w+)", m)
                rm = re.search(r"reason=(\S+)", m)
                hm = re.search(r"host=(\S+)", m)
                self.toggles.append({"ts": ln.ts,
                                     "visible": vm.group(1) if vm else "?",
                                     "reason": rm.group(1) if rm else "?",
                                     "host": hm.group(1) if hm else "?"})
            elif "release-ref" in m:
                self._release(ln, "neg")
            elif tag == "SGR-435" and m.startswith("release "):
                self._release(ln, "pos")
            elif m.startswith("flush "):
                d = _ints(m, "fii", "fiso")
                self.flushes.append({"ts": ln.ts, "fii": d.get("fii", -1),
                                     "fiso": d.get("fiso", -1),
                                     "ip": "ip=true" in m})
            elif m.startswith("drop("):
                d = _ints(m, "fii", "fiso")
                self.drops.append({"ts": ln.ts, "fii": d.get("fii", -1), "fiso": d.get("fiso", -1)})
            elif tag == "ABSORB" and m.startswith("decompose"):
                self.absorbs.append({"ts": ln.ts, **_ints(m, "stepΣ", "scrollΣ", "drain", "padΣ", "fii", "fiso")})
            elif "contentH" in m and "->" in m:
                mm = re.search(r"contentH (-?\d+) -> (-?\d+)", m)
                if mm:
                    d = _ints(m, "hTarget")
                    self.content.append({"ts": ln.ts, "a": int(mm.group(1)), "b": int(mm.group(2)),
                                         "tgt": d.get("hTarget", -1), "reg": "reg=true" in m})
            elif "win-close" in m:
                vm = re.search(r"vis=(\w+)", m)
                rm = re.search(r"remove=(\w+)", m)
                d = _ints(m, "h", "tgt", "contentH")
                self.winclose.append({"ts": ln.ts,
                                      "vis": vm.group(1) if vm else "?",
                                      "remove": rm.group(1) if rm else "?",
                                      "anim": "anim=true" in m, **d})
            elif "MDPilot" in tag or "MDPilot" in m:
                if "gate release=" in m:
                    d = _ints(m, "release", "held", "releasedTotal", "snapshotTotal")
                    self.gates.append({"ts": ln.ts, **d})
                elif "terminal reveal" in m:
                    tm = re.search(r"\+(\d+)ch total=(\d+)", m)
                    if tm:
                        self.terminal.append({"ts": ln.ts, "add": int(tm.group(1)), "total": int(tm.group(2))})
            elif "RB id=" in m:
                mm = re.search(r"RB id=(\S+) key=(\S+) expanded=(\w+) mapHit=(\w+)", m)
                if mm:
                    self.rb.append({"ts": ln.ts, "id": mm.group(1), "key": mm.group(2),
                                    "expanded": mm.group(3), "mapHit": mm.group(4)})
            elif "toggle id=" in m:
                mm = re.search(r"toggle id=(\S+) -> (\w+)", m)
                if mm:
                    self.rbtog.append({"ts": ln.ts, "id": mm.group(1), "val": mm.group(2)})
            elif tag == "MDResize":
                d = _ints(m, "h", "d")
                self.mdsize.append({"ts": ln.ts, **d})
            elif tag == "ScrollDiag" and m.startswith("RESIZE"):
                mm = re.search(r"RESIZE t=\d+ key=(\S+) h (\d+)->(\d+) \(d=(-?\d+)\)", m)
                if mm:
                    self.resizes.append({"ts": ln.ts, "key": mm.group(1),
                                         "a": int(mm.group(2)), "b": int(mm.group(3)),
                                         "d": int(mm.group(4))})
            if "FATAL EXCEPTION" in ln.raw and "uiautomator" not in ln.raw:
                self.fatal.append(ln)

    def _release(self, ln, kind):
        m = ln.msg
        d = _ints(m, "capd", "drv", "dpos", "led", "h")
        sm = re.search(r"set\(fii=(\d+),fiso=(\d+)\)", m)
        hm = re.search(r"h->(-?\d+)", m)
        if hm:
            d["h"] = int(hm.group(1))
        self.releases.append({
            "ts": ln.ts, "kind": kind, "capd": d.get("capd", 0),
            "drv": d.get("drv", d.get("dpos", 0)), "led": d.get("led", 0),
            "set": (int(sm.group(1)), int(sm.group(2))) if sm else None,
            "nodis": "nodis" in m, "noop": "noop" in m,
            "anchor_not_owner": "anchor-not-owner" in m,
            "h": d.get("h", -1), "raw": ln.raw,
        })

    # ---------- 通用 ----------
    def window_for(self, ts):
        for w in self.windows:
            end = w.get("close_ts", 1e18)
            if w["ts"] - 0.05 <= ts <= end + 0.05:
                return w
        return None

    def in_windows(self, items, extra=0.0):
        out = []
        for it in items:
            w = self.window_for(it["ts"])
            if w:
                out.append((w, it))
        return out

    def window_negative(self):
        return [w for w in self.windows if w.get("close_windows", 0) < 0 or w.get("windows", 0) < 0]

    def max_fiso_excursion(self, w):
        """窗内 set 目标 fiso 的极差（视口位移量）。"""
        sets = [r for r in self.releases if w["ts"] <= r["ts"] <= w.get("close_ts", 1e18) and r["set"]]
        if not sets:
            return 0, None
        first = sets[0]["set"]
        vals = [s["set"][1] for s in sets if s["set"][0] == first[0]]
        if not vals:
            return 0, None
        return max(vals) - min(vals), sets

    # ---------- 卡片判据 ----------
    def card540(self):
        """#540：降级路径展开是否恒配对、无一次性回锚。"""
        ev = []
        ok = True
        deg = [t for t in self.toggles if "in-streaming-turn" in t["reason"]]
        if not deg:
            ok = False
            ev.append("FAIL: 未出现 degrade toggle reason=in-streaming-turn（未走到降级路径）")
        if not self.windows:
            ok = False
            ev.append("FAIL: 无 pairing-window")
        if self.window_negative():
            ok = False
            ev.append("FAIL: windows 负数")
        for w in self.windows:
            end = w.get("close_ts")
            if end is None:
                ok = False
                ev.append("FAIL: pairing-window 未关闭")
                continue
            rel = [r for r in self.releases if w["ts"] <= r["ts"] <= end]
            growth = [r for r in rel if r["drv"] > 0 or r["capd"] > 0]
            nod = [r for r in growth if r["nodis"]]
            sets = [r for r in growth if r["set"]]
            ev.append("window %.3f-%.3f tgt=%d span=%d itemH=%d release=%d growth=%d set=%d nodis=%d"
                      % (w["ts"], end, w["tgt"], w["span"], w["itemH"], len(rel), len(growth), len(sets), len(nod)))
            # 初始 fiso=0 且尚未增长的无害 nodis 排除；有正增长却 nodis = 上推未配对
            bad_nod = [r for r in nod if r["drv"] > 0 and r["h"] > w.get("itemH", 0)]
            if bad_nod:
                ok = False
                ev.append("FAIL: 窗内增长 nodis x%d（上推未配对）: %s" % (len(bad_nod), bad_nod[0]["raw"][-120:]))
            if len(growth) >= 3 and not sets:
                ok = False
                ev.append("FAIL: 窗内增长无任何 set 配对")
        # 说明：旧 #526「窗后一次性回锚」在当前实现已无独立日志签名；窗内增长
        # 若未配对会先在 bad_nod 判据命中，窗后继续的正向 set 属流式稳态增长，
        # 不能按方向判回锚（2026-10-17 冒烟实证：窗净 -52 后 +200 合法配对）。
        return ok, ev

    def card547(self):
        """#547：展开态不得无 toggle 静默塌缩；宿主换名后内容指纹键保持一致。"""
        ok = True
        ev = []
        state = {}
        last_by_id = {}
        collapse = []
        for r in sorted(self.rb, key=lambda x: x["ts"]):
            st = state.setdefault(r["key"], {"expanded": None, "mapHit": None})
            # 同键 toggle 记录（toggle 事件无 key，用 id→key 映射；此处宽松：任一 toggle 前后 2s 内允许）
            if st["expanded"] == "true" and r["expanded"] == "false":
                togs = [t for t in self.rbtog if abs(t["ts"] - r["ts"]) < 2.0]
                if not togs:
                    collapse.append(r)
            if st["mapHit"] == "true" and r["mapHit"] == "false":
                ok = False
                ev.append("FAIL: mapHit true->false key=%s @%.3f" % (r["key"], r["ts"]))
            st["expanded"], st["mapHit"] = r["expanded"], r["mapHit"]
            prev = last_by_id.get(r["id"])
            if prev and prev["expanded"] == "true" and r["expanded"] == "false":
                if not [t for t in self.rbtog if abs(t["ts"] - r["ts"]) < 2.0]:
                    collapse.append(r)
            last_by_id[r["id"]] = r
        if collapse:
            ok = False
            ev.append("FAIL: SILENT-COLLAPSE x%d @%.3f key=%s" % (len(collapse), collapse[0]["ts"], collapse[0]["key"]))
        # 宿主换名：同 key 多 id
        bykey = {}
        for r in self.rb:
            bykey.setdefault(r["key"], set()).add(r["id"])
        swapped = {k: v for k, v in bykey.items() if len(v) > 1}
        for k, ids in swapped.items():
            ev.append("HOST-SWAP-SURVIVED key=%s ids=%d" % (k, len(ids)))
        ev.append("events=%d keys=%d ids=%d swapped_keys=%d" % (len(self.rb), len(bykey),
                                                                 len({r['id'] for r in self.rb}), len(swapped)))
        return ok, ev

    def card549(self):
        """#549：三脸扫描（重向 slam / 贴底不累积 / 尾段往返）。"""
        ok = True
        ev = []
        toggles = [t["ts"] for t in self.toggles] + [t["ts"] for t in self.rbtog]
        gests = [f["ts"] for f in self.flushes if f["ip"]]
        sets = [r for r in self.releases if r["set"]]
        # 脸①：同 fii 正 set >300 后 250ms 内反向 >300
        slam = 0
        for a, b in zip(sets, sets[1:]):
            if a["set"][0] != b["set"][0]:
                continue
            d1 = a["set"][1] - (sets[sets.index(a) - 1]["set"][1] if sets.index(a) > 0 else a["set"][1])
            d2 = b["set"][1] - a["set"][1]
            if b["ts"] - a["ts"] < 0.25 and d1 > 300 and d2 < -300:
                if not any(a["ts"] <= t <= b["ts"] for t in toggles + gests):
                    slam += 1
        if slam:
            ok = False
            ev.append("FAIL: 脸①重向 slam x%d" % slam)
        # 脸②：窗内连续 ≥3 帧正步进却 nodis（贴底不累积）
        evap = 0
        for w in self.windows:
            end = w.get("close_ts", w["ts"] + 1)
            seq = [r for r in self.releases if w["ts"] <= r["ts"] <= end]
            run = 0
            for r in seq:
                if r["drv"] > 0 and r["nodis"]:
                    run += 1
                    if run >= 3:
                        evap += 1
                        break
                elif r["set"]:
                    run = 0
        if evap:
            ok = False
            ev.append("FAIL: 脸②贴底不累积 x%d" % evap)
        # 脸③：窗内同 fii 相邻 set 100ms 内 >200 双向往返（无手势）
        tail = 0
        for a, b in zip(sets, sets[1:]):
            if a["set"][0] != b["set"][0]:
                continue
            d = b["set"][1] - a["set"][1]
            if b["ts"] - a["ts"] < 0.12 and abs(d) > 200:
                # 需要前后形成往返
                idx = sets.index(b)
                if idx + 1 < len(sets):
                    c = sets[idx + 1]
                    d2 = c["set"][1] - b["set"][1]
                    if c["ts"] - b["ts"] < 0.12 and d * d2 < 0 and abs(d2) > 100:
                        if not any(a["ts"] <= t <= c["ts"] for t in toggles + gests):
                            tail += 1
        if tail:
            ok = False
            ev.append("FAIL: 脸③尾段往返 x%d" % tail)
        # 存量红线
        if self.window_negative():
            ok = False
            ev.append("FAIL: windows 负数")
        if any("release-dis" in ln.raw for ln in self.lines):
            ok = False
            ev.append("FAIL: release-dis")
        if self.fatal:
            ok = False
            ev.append("FAIL: FATAL")
        # FLASH：窗后 1.5s 内 MDResize >150 紧接 <-50
        for w in self.windows:
            md = [x for x in self.mdsize if w["ts"] <= x["ts"] <= w["ts"] + 1.5 and "d" in x]
            if md and md[0].get("d", 0) > 150 and len(md) > 1 and md[1].get("d", 0) < -50:
                ok = False
                ev.append("FAIL: FLASH @%.3f" % w["ts"])
        ev.append("sets=%d toggles=%d windows=%d slam=%d evap=%d tail=%d" %
                  (len(sets), len(toggles), len(self.windows), slam, evap, tail))
        return ok, ev

    def card551(self, viewport=2400):
        """#551：第二次展开 tgt 事件锚定 + 离底让位 ≤ 一屏。"""
        ok = True
        ev = []
        if len(self.windows) < 2:
            ok = False
            ev.append("FAIL: 展开窗不足 2（实际 %d）——未覆盖二次展开" % len(self.windows))
            return ok, ev
        w = self.windows[-1]
        # tgt 合法性对照**上一事件时刻的已结算 contentH**（collapsed 状态下 contentH
        # 冻结在末次展开高，正是终高种子的语义）；窗内 contentH 会从 0/小值重新长，
        # 不能拿窗内峰值当基准（否则合法种子必假阳性）。
        before = [c["b"] for c in self.content if c["ts"] < w["ts"]]
        settle = before[-1] if before else w.get("itemH", 0)
        ev.append("second-window tgt=%d span=%d itemH=%d prev_settle=%d" %
                  (w["tgt"], w["span"], w["itemH"], settle))
        if w["tgt"] > 0 and settle > 0 and w["tgt"] > settle * 1.25 + 200:
            ok = False
            ev.append("FAIL: tgt 超上一结算高（max 棘轮）tgt=%d prev_settle=%d" % (w["tgt"], settle))
        exc, sets = self.max_fiso_excursion(w)
        ev.append("window fiso excursion=%d (viewport=%d)" % (exc, viewport))
        if exc > viewport * 1.15:
            ok = False
            ev.append("FAIL: 离底让位超一屏 excursion=%d" % exc)
        return ok, ev

    def card554(self, top_before=None, top_after=None):
        """#554：收起前后卡顶 Δ≤10px；垫通道观测。"""
        ok = True
        ev = []
        if top_before is not None and top_after is not None:
            delta = top_after - top_before
            ev.append("card top before=%d after=%d delta=%d" % (top_before, top_after, delta))
            if abs(delta) > 10:
                ok = False
                ev.append("FAIL: 收起卡顶位移 %dpx > 10" % delta)
        else:
            ev.append("WARN: 缺少 UI 顶缘量测（仅日志判据）")
        neg = [r for r in self.releases if r["kind"] == "neg"]
        ev.append("negative-release=%d absorb=%d" % (len(neg), len(self.absorbs)))
        if not neg:
            ok = False
            ev.append("FAIL: 未捕获收起负向配对（场景未成型）")
        return ok, ev

    def card556(self):
        """#556：流式思考文本可见（plain 放行 / bracket 终态揭示）。"""
        ok = True
        ev = []
        held = [g for g in self.gates if g.get("release", 0) == 0]
        released = [g for g in self.gates if g.get("release", 0) > 0]
        term = self.terminal
        if held:
            ev.append("held-frames=%d last_held=%d" % (len(held), held[-1].get("held", 0)))
        if released:
            ev.append("released-frames=%d last_releasedTotal=%d" % (len(released), released[-1].get("releasedTotal", 0)))
        if term:
            ev.append("terminal-reveal=%d total=%d" % (len(term), term[-1]["total"]))
        # 至少要有一条通路：流中放行 或 终态揭示
        if not released and not term:
            ok = False
            ev.append("FAIL: 思考文本既未流中放行也无终态揭示（永久扣留）")
        return ok, ev

    def card557(self):
        """#557：win-close 后内容继续增长且驱动仍注册、reveal 跟随。"""
        ok = True
        ev = []
        closes = [c for c in self.winclose]
        if not closes:
            ok = False
            ev.append("FAIL: 无 win-close（未覆盖挂载窗关闭）")
            return ok, ev
        c = closes[0]
        after = [x for x in self.content if x["ts"] > c["ts"] and x["b"] > x["a"]]
        ev.append("win-close vis=%s remove=%s h=%s contentH=%s; growth-after=%d"
                  % (c.get("vis"), c.get("remove"), c.get("h"), c.get("contentH"), len(after)))
        if len(after) < 3:
            ok = False
            ev.append("FAIL: 窗关后内容增长 <3 帧（场景未成型）")
        frozen = [x for x in after if x["reg"] is False]
        if frozen:
            ok = False
            ev.append("FAIL: 增长帧 reg=false x%d（驱动未注册）" % len(frozen))
        # reveal 高度（drive h）应随 contentH 增长：窗内 release h 递增
        rel = [r for r in self.releases if r["ts"] > c["ts"] and r["h"] > 0]
        hs = [r["h"] for r in rel]
        if len(hs) >= 3 and max(hs) - min(hs) < 50:
            ok = False
            ev.append("FAIL: 窗关后 release h 冻结 (min=%d max=%d)" % (min(hs), max(hs)))
        else:
            ev.append("post-close release h range=%s..%s n=%d" % (min(hs) if hs else "-", max(hs) if hs else "-", len(hs)))
        return ok, ev


def make(text):
    return Ev(parse(text))
