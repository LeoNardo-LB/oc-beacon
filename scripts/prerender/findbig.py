#!/usr/bin/env python3
# 定位器:首个大组折叠行(「1 步…」)中心y
# 用法: findbig.py <dump.xml>  → 输出整数y(无匹配无输出)
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
ms = list(re.finditer(r'text="1 步[^"]*"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml))
if ms: print((int(ms[0].group(2)) + int(ms[0].group(4))) // 2)
