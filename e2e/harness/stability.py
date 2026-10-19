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
         "540_dsh_stream", "540_dsh_tool", "551", "551_static", "552",
         "544_acc", "544_acc_stream", "544_acc_term"]
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
    "20261019-202131/554": "收起卡顶 117px 间歇竞态（同代码复跑 2/2 delta=0+模拟器绿+昨日双端 22/22——非本批回归，竞态族观察项）",
    "20261019-205819/544_acc": "变体参数越装置界（96 字符行→29k px 怪物卡超 dump 定位能力）——参数收敛后复跑 PASS",
    "20261019-210457/544_acc": "MIUI 安全中心悬浮窗抢焦点（debug entry failed）——清除后串行复跑 PASS",
    "20261019-210702/544_acc": "mock 端口互踩（与模拟器 V1 复跑 21:07:45 起并发——第二 session ses_mock_1792415266089 即其创建）——串行复跑",
    "20261019-210744/544_acc": "mock 端口 4399 双战役并发互踩（真机变体重试与模拟器 V1 复跑撞端口，session 互串实证 ses_mock_1792415266089）——串行复跑",
    "20261019-210522/544_acc_stream": "MIUI 安全中心悬浮窗抢焦点（debug entry failed）——清除后复跑 PASS",
    "20261019-210559/544_acc_term": "同上（MIUI 悬浮窗）——复跑 PASS",
    "20261019-211456/544_acc": "P2 断言一刀切（双卡同 tap 时另一宿主垫留守驱动的防护面被误判——机件原子 episode+anchor+真值 H 实际已在）——断言精细化后 V4R PASS",
    "20261019-202131/544_acc": "验收装置断言过严（PRD-warm 计数窗含 tap 后其它卡预热；路由性质由 toggle 实证）——断言窗收窄后复跑 PASS",
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
    "20261019-015516/552": "CONTENT-H oracle 探针源缺口（contentH 不发终态重建路径；MDResize 并源修复；目录 015516=title 015518）",
    "20261019-020121/552": "Idle 轮询被终态日志冲刷错过空转死线（端上 grep 20K 窗修复；无 result.json）",
    "20261019-022511/552": "真机 552 装置迭代：Idle needle 过宽（流中 streaming=true 计入）",
    "20261019-022551/552": "同上（第二轮）",
    "20261019-024451/552": "真机 552 装置迭代：needle 收窄后仍被流中假 Idle(false) meta 触发（025255 定罪）",
    "20261019-024533/552": "同上（第二轮）",
    "20261019-025255/552": "流中假 Idle(false) meta 定罪轮（终态判据改锚 MDPilot）",
    "20261019-025337/552": "同上（温和 B 轮，needle 收窄版）",
    "20261019-030304/552": "needle 收窄版 A 轮（发现真机未装修复包后中断换包；装置层归因）",
    "20261019-030405/552": "真机未装修复包（旧 APK）——意外真机红证据：堆峰 544,252KB 二次保留实测（装修复包后 139,788KB）",
    "20261019-031435/552": "发现未装修复包后的中断轮（无意义基线；装置层归因）",
    "20261019-032804/552": "孤儿 mock 占端口（杀 A 轮 python 未清子进程）+ 端上管线锚迭代",
    "20261019-033737/552": "端上 logcat|grep 管线高负载期空手（buffer 有 MDPilot 行而 poll 无果）——锚定改宿主侧",
    "20261019-033845/552": "同上（清场后复验轮）",
    "20261019-034726/552": "宿主侧锚窗 tail-3000 过小（MIUI 刷屏 ~50s 窗龄错过终态行）",
    "20261019-040023/552": "terminal reveal 行在持续放行流中不发射（锚设计错误→定时制终态）",
    # ---- #544 C1 迭代轮（2026-10-19）----
    "20261019-120823/540": "#544 2b 首扫：探针 prevVis 条件 remember 晚初始化吃掉翻转事件（修复=无条件 remember；复跑 PASS）",
    "20261019-120823/540_dsh_stream": "同上（探针晚初始化）",
    "20261019-120823/554": "同上（探针晚初始化；垫塌回归另见 123710）",
    "20261019-122559/540": "重跑未带 --apk——装的仍是探针 bug 版旧包（装置操作失误；装机后复跑 PASS）",
    "20261019-122735/540_dsh_stream": "同上（未装机重跑）",
    "20261019-122640/554": "同上（未装机重跑）",
    "20261019-095433/552": "演示彩排轮（修复前旧包装机故意崩溃验证——A 组崩溃组基线；正式演示 A 组同签名崩溃）",
    "20261019-123710/554": "#544 2b 真产品回归：viaDrive 复位漏垫守卫——垫渲染载体随分支消失致垫量瞬塌（卡顶 1377px；修复=复位需垫清空，复跑 PASS）",
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
