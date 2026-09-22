#!/usr/bin/env python3
# 逐帧全局垂直位移估计:相邻帧中心带互相关,报告最佳对齐 dy 与得分。
# 用法: frame_dy.py <帧目录> [max_shift=400]
import glob, sys
import numpy as np
from PIL import Image
d = sys.argv[1]
mx = int(sys.argv[2]) if len(sys.argv) > 2 else 400
files = sorted(glob.glob(d + '/f*.png'))
if len(files) < 2:
    print('NO FRAMES'); sys.exit(1)
def prof(p):
    im = np.asarray(Image.open(p).convert('L').resize((240, 534)), dtype=np.float32)
    return im[:, 20:220].mean(axis=1)  # 中心列带(避开左右边缘装饰)
prev = prof(files[0])
print('frame | dy | score(t=)', end='')
print()
for i in range(1, len(files)):
    cur = prof(files[i])
    best, bs = 0, -1e9
    n = len(cur)
    for off in range(-mx, mx + 1, 2):
        a0, a1 = max(0, -off), min(n, n - off)
        if a1 - a0 < 200:
            continue
        a = prev[a0:a1]; b = cur[a0 + off:a1 + off]
        va, vb = a - a.mean(), b - b.mean()
        den = (np.sqrt((va ** 2).sum() * (vb ** 2).sum()) + 1e-9)
        s = float((va * vb).sum() / den)
        if s > bs:
            bs, best = s, off
    print(f'{i:4d} | {best:+5d} | {bs:.3f}')
    prev = cur