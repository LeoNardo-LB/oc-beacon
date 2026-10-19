#!/usr/bin/env python3
# =============================================================================
# probe_486_sse_rebuild.py — #486 三联场景构造器（备货：复发时即战）
#
# 场景（#471④ 理论盲区的三要素，2026-09-30 勘误「0 触发实为 0 试验」）：
#   ① SSE 断连续传（mock /api/_pause 模拟网络停 → retry/重建窗）
#   ② 流式卡滚出视口（弃树重建候选窗）
#   ③ ever=false 帧（重建首帧）——由 DEBUG 探针（MDResize 负 d / RESETKEY /
#      nonPrefix / rawTail，#471 保留裁决）在 logcat 侧收割
#
# 用法: python3 scripts/probe_486_sse_rebuild.py [--serial emulator-5554] \
#            [--apk <apk>] [--pause-ms 3000] [--rounds 5]
# 纪律: 独立 mock 端口 4399（勿与 e2e 战役并发——共享宿主端口会串台）；
#       结果不入 e2e/runs（非正式卡），证据落 /tmp/486/<ts>/
# 判读: probe 命中清单 + dump 视口状态 + 滚回后视口完整性（手判）
# =============================================================================
import argparse, os, sys, time, glob
HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.path.join(os.path.dirname(HERE), "e2e", "harness")
sys.path.insert(0, HARNESS)
from device import Device, ADB  # noqa: E402
from runner import Mock, send_prompt, launch, open_session, composer_node  # noqa: E402
import subprocess  # noqa: E402

OUT = "/tmp/486"


def send_bilingual(dev, text):
    """双语言发送（模拟器 app 可能英文 UI——send_prompt 只匹配中文「发送」）。"""
    import re as _re
    c = composer_node(dev)
    if not c:
        return False
    dev.tap(c["cx"], c["cy"]); time.sleep(1.5)
    dev.input_text(text); time.sleep(0.8)
    ns = dev.nodes()
    send = [n for n in ns if (n["desc"] or n["text"]) in ("发送", "Send", "send")]
    if not send:
        print("[486] no send btn: %r" % [(n["desc"] or n["text"])[:12] for n in ns if n["clickable"]][:6])
        return False
    base = dev.count_log("] POST /prompt")
    dev.tap(send[0]["cx"], send[0]["cy"]); time.sleep(1.5)
    return dev.count_log("] POST /prompt") > base


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--apk", default=None, help="不传则用已装包")
    ap.add_argument("--pause-ms", type=int, default=3000)
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--lines", type=int, default=90)
    ap.add_argument("--interval", type=float, default=0.12)
    args = ap.parse_args()

    ts = time.strftime("%Y%m%d-%H%M%S")
    outdir = os.path.join(OUT, ts)
    os.makedirs(outdir, exist_ok=True)
    print(f"[486] evidence → {outdir}")

    dev = Device(args.serial)
    assert dev.online(), f"device offline: {args.serial}"
    if args.apk:
        dev.adb("install", "-r", args.apk, timeout=120)

    mock = Mock(4399, {"MOCK_LINES": "90", "MOCK_INTERVAL": "0.12"},
                "/tmp/486/mockwork", title="486-rebuild-probe")
    mock.start()
    try:
        mock.create_session("486-rebuild-probe")
        # 借 runner 成熟序列：launch（start_debug+reverse）→ open_session
        dev.shell("am force-stop dev.leonardo.ocbeacon.dev")
        dev.logcat_clear()
        if not launch(dev, 4399, "486-rebuild-probe"):
            print("[486] debug entry failed"); return
        if not open_session(dev, "486-rebuild-probe"):
            print("[486] session open failed"); return
        w, h = dev.screen_size()

        PROBES = ("MDResize", "RESETKEY", "nonPrefix", "rawTail", "452-display", "staged")
        for r in range(1, args.rounds + 1):
            print(f"[486] round {r}/{args.rounds}")
            base = {p: dev.count_log(p) for p in PROBES}
            # 后台线程化流：mock 场景流由 send 后自动推进（mock_v2 的会话流生成）
            ok = send_bilingual(dev, "Tell me a long story about the sea.")
            if not ok:
                print("[486] send fail, retry next round")
                continue
            time.sleep(1.0)
            # ① 断流（pause） ② 同步向上滚大距离（流式卡滚出视口）
            mock.pause(args.pause_ms)
            dev.swipe(w // 2, int(h * 0.3), w // 2, int(h * 0.85), 260)
            time.sleep(args.pause_ms / 1000.0 + 1.5)
            # ③ 恢复后滚回底部，观察重建
            dev.swipe(w // 2, int(h * 0.8), w // 2, int(h * 0.2), 260)
            time.sleep(4)
            hits = {p: dev.count_log(p) - base[p] for p in PROBES}
            hits = {p: c for p, c in hits.items() if c > 0}
            print(f"[486]   probes hit: {hits or '无'}")
            with open(os.path.join(outdir, f"round{r}.txt"), "w") as f:
                f.write(f"hits={hits}\n")
        # 收割全量 logcat 供复盘
        out = subprocess.run([ADB, "-s", args.serial, "logcat", "-d"],
                             capture_output=True, text=True).stdout
        open(os.path.join(outdir, "full.log"), "w").write(out)
        probe_lines = [l for l in out.splitlines()
                       if any(p in l for p in PROBES)]
        open(os.path.join(outdir, "probes.log"), "w").write("\n".join(probe_lines))
        print(f"[486] done. probe lines: {len(probe_lines)} → {outdir}/probes.log")
        print("[486] 判读：probe 非零命中即三联窗触发——比对 #471 spec §8 残余收窄注记")
    finally:
        mock.stop()


if __name__ == "__main__":
    main()
