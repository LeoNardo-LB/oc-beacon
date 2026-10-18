#!/usr/bin/env python3
"""stability.py — 七卡+#558 E2E 累计稳定性跟踪器。

汇总 e2e/runs/ 下全部 result.json，按场景统计：
- 累计 PASS / 总轮（按构建分档：修复前 <20261017-2359 / 修复后 ≥2359）
- 双端分布、最近 N 轮走势
- 失败轮的已知装置归因标注（expl mapping）

产物：e2e/runs/STABILITY.md（每轮战役后重跑刷新）。
"""
import json
import glob
import os
import sys
from datetime import datetime

RUNS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "runs")
ORDER = ["557", "556_plain", "556_bracket", "540", "547", "554", "549",
         "540_dsh_stream", "540_dsh_tool", "551", "551_static", "552"]
FIX_BUILD_VC = 1792252676  # #558 修复构建

# 已定罪装置失败的运行目录 → 归因（journal 落账）
EXPLAINED = {
    "20261017-224854": "旧构建（修复前）",
    "20261018-004051/540_dsh_stream": "发送 tap 抖动（标记修复前）",
    "20261018-010655": "发送标记单协议 bug",
    "20261018-010511": "mock keep-alive 读体 bug",
    "20261018-011315": "mock keep-alive 读体 bug",
    "20261018-010547": "mock keep-alive 读体 bug",
    "20261018-011352": "mock keep-alive 读体 bug",
    "20261018-004952/540_dsh_stream": "mock keep-alive 读体 bug",
    "20261018-004952/540_dsh_tool": "mock keep-alive 读体 bug",
    "20261018-004952/549": "真机坐标 + oracle 脸②未校准",
    "20261018-010104/549": "oracle 脸②过紧（金标重扫 PASS）",
    "20261018-004952/551": "固定坐标（dump 定位修复前）",
    "20261018-010230": "固定坐标（dump 定位修复前）",
    "20261018-004952/551_static": "固定坐标（dump 定位修复前）",
    "20261018-013056/547": "UTF-8 严格解码（errors=replace 修复前）",
    "20261018-014203/549": "oracle 脸②读位判据（校准前）",
    "20261018-014203/551": "NO-CARD 成型抖动（mock 单订阅者根因）",
    "20261018-022355/540_dsh_stream": "oracle nodis 尾判据（终版前）",
    "20261018-022355/551": "mock 单订阅者踢事件端（回退前）",
    "20261018-015241": "浸泡脚本 bash 浮点格式",
    "20261018-015535": "浸泡脚本 bash 浮点格式",
    "20261018-015830": "浸泡脚本 bash 浮点格式",
    "20261018-020128": "runner 废代码 UnboundLocal",
    "20261018-020149": "runner 废代码 UnboundLocal",
    "20261018-020211": "runner 废代码 UnboundLocal",
    "20261018-020233": "runner 废代码 UnboundLocal",
    "20261018-015753": "wait_stream_end 旧 Idle 误匹配（基线修复前）",
    "20261018-013056/540_dsh_stream": "oracle 衰减尾判据（校准前）",
    "20261018-013056/549": "真机 3s 短流成型（参数修复前）",
    "20261018-020401/549": "真机成型门（充分交互判据前）",
    "20261018-021426": "真机成型门（充分交互判据前）",
    "20261018-025859/549": "oracle 脸②量级回退（三档判据前；离线重扫 PASS）",
    # 战役5 浸泡轮3 dsh_stream 快速变体（36×0.11=4s < 装置延迟地板）+ 修复过程轮
    "20261018-040052/540_dsh_stream": "4s 流短于装置延迟地板（机件误走；heal 自愈补丁前）",
    "20261018-062514": "装置修复过程轮（do_GET 恢复前）",
    "20261018-063001": "装置修复过程轮（composer 判别前）",
    "20261018-063309": "装置修复过程轮（空格 %s 前）",
    "20261018-063743": "装置修复过程轮（composer IME 阈值前）",
    "20261018-064428": "装置修复过程轮（IME 阈值 0.72h 前）",
    "20261018-064649": "heal 未等 turn 落幕（wait_stream_end 前）",
    "20261018-065402": "4s 流 staged 钳制前（两轮均机件误走）",
    "20261018-065543": "mock 钳制块变量序错误（NameError）",
    "20261018-064126": "装置修复过程轮（空格 %s 修复验证轮，IME 阈值 0.72h 仍在）",
    "20261018-072008/549": "真机手势弹回会话列表（重开恢复前）",
    "20261018-072346": "同上（诊断轮）",
    "20261018-072523": "同上（恢复验证轮）",
    "20261018-072701": "真机高负载 logcat 超时（count_log 加固前）",
    "20261018-124400/549": "oracle 脸②第2臂单调迁移误判（方向反转校准前；四样本复核 PASS）",
    "20261019-012848/552": "scenario_552 初版 dev.shell() 直取 CompletedProcess（harness bug，.stdout 修复）",
    "20261019-013210/552": "RED 基线（未修构建故意复现 OOM——产品缺陷定罪；修复后同内容 GREEN 全 PASS）",
    "20261019-015518/552": "CONTENT-H oracle 探针源缺口（contentH 不发终态重建路径；MDResize 并源修复）",
    "20261019-020121/552": "Idle 轮询被终态日志冲刷错过空转死线（端上 grep 20K 窗修复；无 result.json）",
}


def main():
    rows = {}
    for p in glob.glob(os.path.join(RUNS, "2026*", "*", "result.json")):
        try:
            d = json.load(open(p))
        except Exception:
            continue
        parts = p.split(os.sep)
        stamp, card = parts[-3], parts[-2]
        dev = "REAL" if "192.168" in d.get("device", "") else "EMU"
        key = f"{stamp}/{card}"
        explained = None
        for k, v in EXPLAINED.items():
            if key.startswith(k) or stamp == k:
                explained = v
                break
        rows.setdefault(card, []).append({
            "stamp": stamp, "dev": dev, "pass": bool(d.get("pass")),
            "explained": explained,
        })

    lines = ["# 七卡+#558 E2E 累计稳定性跟踪", "",
             f"_生成：{datetime.now().strftime('%F %T')}（stability.py 汇总 e2e/runs/）_", "",
             "| 场景 | 修复后 PASS/总 | 已归因装置失败 | 模拟器 P/T | 真机 P/T |",
             "|---|---|---|---|---|"]
    tp = tn = te = 0
    for c in ORDER:
        rs = [r for r in rows.get(c, []) if r["stamp"] >= "20261017-2359"]
        p = sum(1 for r in rs if r["pass"])
        ex = sum(1 for r in rs if not r["pass"] and r["explained"])
        unex = sum(1 for r in rs if not r["pass"] and not r["explained"])
        emu = [r for r in rs if r["dev"] == "EMU"]
        real = [r for r in rs if r["dev"] == "REAL"]
        tp += p; tn += len(rs); te += unex
        lines.append(f"| {c} | {p}/{len(rs)} | {ex}{f' **未归因 {unex}**' if unex else ''} | "
                     f"{sum(1 for r in emu if r['pass'])}/{len(emu)} | "
                     f"{sum(1 for r in real if r['pass'])}/{len(real)} |")
    lines += ["", f"**合计**：修复后 {tp}/{tn} PASS，未归因失败 {te}。", "",
              "> 已归因失败=装置 bug 定罪且修复后同场景复跑 PASS（归因表见 stability.py）。"]
    out = os.path.join(RUNS, "STABILITY.md")
    open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\nwritten: {out}")
    return 0 if te == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
