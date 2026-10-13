#!/usr/bin/env python3
# ev539-device.py — #539 真机 E2E 腿（V2 4096 live）：⑤A 截断提示上屏 + compact 全链回归
import importlib.util, os, sys, time, json

DVR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "driver-b2.py")
spec = importlib.util.spec_from_file_location("d_b2", DVR)
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

d.PORT = 4096
d.DEBUG_USERNAME = "opencode"
d.DEBUG_PASSWORD = json.load(open(os.path.expanduser("~/.config/opencode/service.json")))["password"]

OUT = os.path.join(d.BASE, "evidence", "demo538")
os.makedirs(OUT, exist_ok=True)

def log(m): print(time.strftime("%H:%M:%S"), m, flush=True)

def shot(name):
    d.sh("shell", "screencap", "-p", "/sdcard/" + name)
    d.sh("pull", "/sdcard/" + name, os.path.join(OUT, name))
    d.sh("shell", "rm", "-f", "/sdcard/" + name)
    log("shot " + name)

def texts(root):
    out = []
    if root is None: return out
    for n in d.nodes(root, lambda _: True):
        t = (n.get("text") or "").strip()
        if t: out.append(t)
    return out

PROMPT = ("Use the bash tool to run exactly this command. seq 1 12000. "
          "After the tool finishes reply with the single word done.")

# 1. 标准入口（V2）
if not d.entry(): log("entry FAIL"); sys.exit(1)

# 2. 新会话（目录选择器：V2 列 home 目录为「leonardo」，v1 为「root」）
root = d.dump_xml()
newb = d.by_desc(root, "新建会话")
if newb:
    d.tap_node(newb[0]); time.sleep(3)
root = d.dump_xml()
picker_open = bool([n for n in d.nodes(root, lambda n: "打开其他项目" in (n.get("text") or ""))])
if picker_open:
    dir_rows = [n for n in d.nodes(root, lambda n: (n.get("text") or "") in ("leonardo", "root")
                or (n.get("text") or "").startswith("/"))]
    if dir_rows:
        d.tap_node(dir_rows[0]); time.sleep(3); log("目录选择器 → " + (dir_rows[0].get("text") or ""))
    else:
        log("目录选择器无目录行"); sys.exit(1)
if d.screen_state(d.dump_xml()) != "chat":
    log("新会话后不在 chat 屏"); sys.exit(1)

# 3. 模型守卫：chip 非 GLM 系（Free/Flash/Pickle）则切
root = d.dump_xml()
chip = [n for n in d.nodes(root, lambda n: any(k in (n.get("text") or "") for k in ("Free", "Flash", "Pickle")) and len((n.get("text") or "")) < 24)]
if chip:
    d.tap_node(chip[0]); time.sleep(2.2)
    glm = None
    for i in range(6):
        root = d.dump_xml()
        c = [n for n in d.nodes(root, lambda n: "GLM" in (n.get("text") or "").upper() and "FLASH" not in (n.get("text") or "").upper())]
        if c: glm = c[0]; break
        d.sh("shell", "input", "swipe", "600", "2100", "600", "1100", "350"); time.sleep(1.2)
    if glm is not None:
        d.tap_node(glm); time.sleep(1.8); log("模型 → " + (glm.get("text") or ""))
    else:
        d.sh("shell", "input", "keyevent", "KEYCODE_BACK"); log("无 GLM，沿用默认（风险自担）")

# 4. 发 prompt（bash seq 大输出）
if not d.send_prompt(PROMPT): log("send_prompt FAIL"); sys.exit(1)
log("prompt 已发，等轮次…")
t0 = time.time(); idle = 0
while time.time() - t0 < 240:
    if d.busy(): idle = 0
    else:
        idle += 1
        if idle >= 2: break
    time.sleep(5)
log(f"轮次完成 {time.time()-t0:.0f}s")
time.sleep(2)

# 5. 找 bash 卡（text 含 seq 1 12000）→ 点开 → 断言截断提示
hit = None
for attempt in range(6):
    root = d.dump_xml()
    cand = d.nodes(root, lambda n: "seq 1 12000" in (n.get("text") or ""))
    if cand:
        hit = cand[0]; break
    d.sh("shell", "input", "swipe", "600", "1800", "600", "700", "350"); time.sleep(1.2)
if hit is None:
    log("未见 bash 卡（滚动 6 屏）"); shot("e539_nobashcard.png"); sys.exit(1)
d.tap_node(hit); time.sleep(2)
root = d.dump_xml()
ts = texts(root)
hint = [t for t in ts if "输出已被服务器截断" in t]
shot("e539_truncation_hint.png")
log("截断提示 dump 命中: " + json.dumps(hint[:2], ensure_ascii=False))
if hint:
    log("⑤A PASS：提示条上屏 → " + hint[0][:120])
else:
    log("⑤A dump 未见提示（可能 #477 稀疏暴露）——以截图判读")

# 6. compact 全链：更多选项 → 压缩会话
d.sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)  # 收键盘（若在）
root = d.dump_xml()
menu = d.by_desc(root, "更多选项") or d.by_text(root, "更多选项")
if menu:
    d.tap_node(menu[0]); time.sleep(1.8)
    root = d.dump_xml()
    comp = [n for n in d.nodes(root, lambda n: "压缩" in (n.get("text") or "") or "压缩" in (n.get("content-desc") or ""))]
    log("菜单压缩项: " + str([(n.get('text') or n.get('content-desc'))[:12] for n in comp[:3]]))
    if comp:
        d.tap_node(comp[-1] if len(comp) > 1 else comp[0])
        # 轮询 25s：banner（正在压缩上下文）/ snackbar（会话已压缩）/ 失败
        seen = set()
        t0 = time.time()
        while time.time() - t0 < 25:
            ts = texts(d.dump_xml())
            for t in ts:
                for key in ("正在压缩上下文", "会话已压缩", "会话压缩失败", "上下文已压缩"):
                    if key in t and key not in seen:
                        seen.add(key); log("compact UI: " + key)
            if any(k in seen for k in ("会话已压缩", "会话压缩失败", "上下文已压缩")):
                break
            time.sleep(1.5)
        shot("e539_compact_after.png")
        log("compact 断言汇总: " + json.dumps(list(seen), ensure_ascii=False))
    else:
        log("菜单无压缩项")
else:
    log("未找到更多选项")

# 7. logcat 证据
r = d.sh("logcat", "-d", timeout=30)
for line in (r.stdout or "").splitlines():
    if "Compacted session" in line or "compaction" in line.lower():
        log("LC: " + line[-140:])
log("DEVICE E2E DONE")
