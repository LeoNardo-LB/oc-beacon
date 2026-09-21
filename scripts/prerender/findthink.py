#!/usr/bin/env python3
# 定位器:uiautomator dump 中首个「思考」行中心y(展开/收起驱动目标)
# 用法: findthink.py <dump.xml>  → 输出整数y(无匹配无输出)
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
ms = list(re.finditer(r'text="[^"]*思考[^"]*"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml))
if ms: print((int(ms[0].group(2)) + int(ms[0].group(4))) // 2)
