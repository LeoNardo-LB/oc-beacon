#!/usr/bin/env python3
"""frame-jump-analyze.py — 录屏抽帧突跳检测（fling 跳变定罪工具）
用法: ffmpeg -y -i rec.mp4 -r 24 /tmp/frames/p%03d.png && python3 scripts/frame-jump-analyze.py /tmp/frames
原理: 中列带 1D 互相关估计帧间垂直平移；|shift|>480px 或相关性差 = 突跳帧。
"""

from PIL import Image
import os
import sys
d = sys.argv[1] if len(sys.argv) > 1 else '/tmp/jk-f'
files = sorted(os.listdir(d))
prev_rows = None
prev_img = None
print('帧 | 垂直平移估计(px) | 突跳')
for idx, f in enumerate(files):
    img = Image.open(os.path.join(d, f)).convert('L')
    w, h = img.size
    # 用中间列带做 1D 相关（快速平移估计）
    band = 40
    x0 = w // 2 - band // 2
    cur = []
    for y in range(0, h, 2):
        s = 0
        for x in range(x0, x0 + band, 4):
            s += img.getpixel((x, y))
        cur.append(s)
    if prev_rows is not None:
        # 互相关找最佳平移（-600..600）
        best_shift, best_score = 0, -1e18
        n = len(cur)
        for sh in range(-600, 600, 2):
            s = 0; cnt = 0
            for i in range(200, n - 200, 4):
                j = i + sh // 2
                if 0 <= j < n:
                    s -= abs(cur[i] - prev_rows[j]); cnt += 1
            if cnt > 50:
                score = s / cnt
                if score > best_score:
                    best_score, best_shift = score, sh
        jump = abs(best_shift) > 480 or best_score < -28
        if jump or idx % 8 == 0:
            print(f + ' | ' + str(best_shift) + (' | <<< 突跳' if jump else ''))
    prev_rows = cur
