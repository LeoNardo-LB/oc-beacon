#!/usr/bin/env python3
# demo-538.py — #538 观感项真机演示（用户已下达「演示」口令 2026-10-10）
#   ① metadata/mark 角色视觉：注解(@Serializable/@Composable)映射 tertiary 的观感
#   ② 400-2000c 代码块冷看首帧残余 pop（同步 CAP=400 之上的异步窗口）
# 复用 driver-b2 已验证手法：entry/send_prompt/dump/type_ascii；产物落 evidence/demo538/
import importlib.util, os, subprocess, sys, time, glob

DVR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "driver-b2.py")
spec = importlib.util.spec_from_file_location("d_b2", DVR)
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

# ---- 环境切换（2026-10-10）：v1-e2e zhipuai key 鉴权失败（4199/4299 同 config 同死 key）----
# → V2 服务化常驻 4096（用户真实环境，provider zhipuai-coding-plan / build=glm-5.3）
import json as _json
d.PORT = 4096
d.DEBUG_USERNAME = "opencode"
d.DEBUG_PASSWORD = _json.load(open(os.path.expanduser("~/.config/opencode/service.json")))["password"]

OUT = os.path.join(d.BASE, "evidence", "demo538")
os.makedirs(OUT, exist_ok=True)

PROMPT = ("Output exactly two kotlin code blocks and nothing else. "
          "Mark both blocks with the kotlin language tag on the opening fence line "
          "so they get syntax highlighting. "
          "Block 1 about 8 lines with one small function. "
          "Block 2 about 90 lines with at least four Serializable data classes, "
          "several Composable annotated functions, private helpers, and a main function. "
          "No prose.")

def log(m): print(time.strftime("%H:%M:%S"), m, flush=True)

def shot(name):
    d.sh("shell", "screencap", "-p", "/sdcard/" + name)
    d.sh("pull", "/sdcard/" + name, os.path.join(OUT, name))
    d.sh("shell", "rm", "-f", "/sdcard/" + name)
    log("shot " + name)

def swipe_up(dy=1100):
    d.sh("shell", "input", "swipe", "600", "1900", "600", str(1900 - dy), "350")
    time.sleep(1.2)

def codehl_lines():
    r = d.sh("logcat", "-d", timeout=30)
    return [l for l in (r.stdout or "").splitlines() if "CodeHL" in l]

# ---------- 1. 标准入口（reverse 4299 + 冷启 debug intent → 会话列表）----------
if not d.entry():
    log("entry FAIL"); sys.exit(1)
log("entry OK → 会话列表")

# ---------- 2. 新建会话 ----------
root = d.dump_xml()
newb = d.by_desc(root, "新建会话")
if not newb:
    log("无「新建会话」入口"); sys.exit(1)
d.tap_node(newb[0]); time.sleep(3)
# v1 新会话可能先出目录选择器（标题「新会话」+ 目录行 + 「打开其他项目…」）
root = d.dump_xml()
if d.by_text(root, "打开其他项目") or (d.by_text(root, "新会话") and not d.by_desc(root, "发送")):
    dir_rows = d.by_text(root, "root") or [n for n in d.nodes(root, lambda n: (n.get("text") or "").startswith("/")) if n.get("bounds", "0,0") != "0,0"]
    if dir_rows:
        d.tap_node(dir_rows[0]); time.sleep(3); log("目录选择器 → root")
    else:
        log("目录选择器无目录行"); sys.exit(1)
log("新会话已开")

# ---------- 2.5 切活模型（app 新会话默认 zen 免费模型：exo-free 已 410 deprecated / flash 同源）----------
root = d.dump_xml()
chip = [n for n in d.nodes(root, lambda n: any(k in (n.get("text") or "") for k in ("Free", "Flash", "Pickle")) and len((n.get("text") or "")) < 24)]
if chip:
    d.tap_node(chip[0]); time.sleep(2.2)

    def find_glm(r):
        glm = [n for n in d.nodes(r, lambda n: "GLM" in (n.get("text") or "").upper()
                                  and "FLASH" not in (n.get("text") or "").upper())]
        return glm[0] if glm else None

    def find_bp(r):
        bp = d.by_text(r, "Big Pickle")
        return bp[0] if bp else None

    # 先滚屏找 zhipuai 组的 GLM-5.3（大模型守格式；key 已由 title agent 实证活着）
    pick = None; scrolls = 0
    for i in range(6):
        root = d.dump_xml()
        g = find_glm(root)
        if g is not None:
            pick = g; break
        if i >= 3:
            bp = find_bp(root)
            if bp is not None:
                pick = bp; break
        d.sh("shell", "input", "swipe", "600", "2100", "600", "1100", "350"); time.sleep(1.2)
        scrolls += 1
    if pick is not None:
        d.tap_node(pick); time.sleep(1.8)
        log(f"模型已切 → {pick.get('text')}（滚动 {scrolls} 次）")
    else:
        log("模型表无 GLM/Big Pickle——BACK 弃选沿用默认")
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
else:
    log("未见模型 chip（可能已是目标模型）")

# ---------- 3. 发送演示 prompt（keyevent 逐字）----------
if not d.send_prompt(PROMPT):
    log("send_prompt FAIL"); sys.exit(1)
log("prompt 已发送，等待轮次完成…")

# ---------- 4. 等完成（停止键消失 + 双确认）----------
t0 = time.time()
idle = 0
while time.time() - t0 < 300:
    if d.busy():
        idle = 0
    else:
        idle += 1
        if idle >= 2:
            break
    time.sleep(5)
log(f"轮次完成，耗时 {time.time()-t0:.0f}s")

# ---------- 5. 渲染证据：全屏 + 逐屏滚动特写 ----------
time.sleep(2.5)
shot("d538_01_initial.png")
for i in range(6):
    swipe_up()
    shot(f"d538_02_scroll{i+1}.png")

# ---------- 6. 冷进程重看：录屏捕首帧 pop ----------
d.sh("shell", "am", "force-stop", d.PKG)
time.sleep(1.5)
if not d.entry():
    log("二次 entry FAIL"); sys.exit(1)
log("冷启回列表（进程新，缓存空）")
rows = d.by_desc(d.dump_xml(), "切换会话")
log(f"会话行数={len(rows)}")
rec = subprocess.Popen([d.ADB, "-s", d.SERIAL, "shell",
                        "screenrecord", "--time-limit", "14", "/sdcard/d538_pop.mp4"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1.0)
d.tap_node(rows[0])  # 最新行 = 演示会话
time.sleep(12)       # 录完
rec.wait()
d.sh("pull", "/sdcard/d538_pop.mp4", os.path.join(OUT, "d538_pop.mp4"), timeout=40)
d.sh("shell", "rm", "-f", "/sdcard/d538_pop.mp4")
log("录屏已拉取 d538_pop.mp4")

# ---------- 7. CodeHL 仪器证据：冷看异步作业 + 重看缓存命中 ----------
cold = codehl_lines()
log(f"冷看 CodeHL {len(cold)} 行：")
for l in cold: log("  " + l)
open(os.path.join(OUT, "codehl_cold.txt"), "w").write("\n".join(cold))

# 重看对比：滚出视口再滚回（同进程缓存应命中 → 无新增 CodeHL）
for _ in range(3): swipe_up(1400)
for _ in range(4):
    d.sh("shell", "input", "swipe", "600", "700", "600", "2000", "350"); time.sleep(1.0)
time.sleep(2)
after = codehl_lines()
log(f"重看后 CodeHL 共 {len(after)} 行（冷看 {len(cold)} 行）")
open(os.path.join(OUT, "codehl_revisit.txt"), "w").write("\n".join(after))
shot("d538_03_after_revisit.png")

log("DEMO DONE → " + OUT)
