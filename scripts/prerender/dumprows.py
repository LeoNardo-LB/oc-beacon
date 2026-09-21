#!/usr/bin/env python3
# dump 解析:列出折叠行(步数行)+前若干内容行(y+bounds,用于选锚点条带)
# 用法: dumprows.py <dump.xml>
import re, sys
xml = open(sys.argv[1], encoding='utf-8').read()
nodes = re.findall(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print("-- fold rows --")
for t, x1, y1, x2, y2 in nodes:
    if re.search(r'\d+\s*(\u6b65|steps?)', t):
        print('FOLD ' + repr(t) + ' y1=' + y1 + ' y2=' + y2 + ' tapx=' + str((int(x1)+int(x2))//2) + ' tapy=' + str((int(y1)+int(y2))//2))
print("-- first text rows (content identity) --")
shown = 0
for t, x1, y1, x2, y2 in nodes:
    tt = t.strip()
    if not tt or len(tt) < 6: continue
    if re.search(r'\d+\s*(\u6b65|steps?)', tt): continue
    print('ROW y1=' + y1 + ' ' + repr(tt[:40]))
    shown += 1
    if shown >= 18: break
