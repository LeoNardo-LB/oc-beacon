#!/usr/bin/env python3
# 定位器:MIUI「USB安装提示」弹框「继续安装」按钮中心坐标
# 用法: find_install_btn.py <dump.xml>  → 输出 "x y"(无弹框无输出)
import re, sys
try:
    xml = open(sys.argv[1], encoding='utf-8').read()
except Exception:
    sys.exit(0)
m = re.search(r'text="继续安装"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    print((int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2)
