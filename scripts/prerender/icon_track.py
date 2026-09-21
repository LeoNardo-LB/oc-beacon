#!/usr/bin/env python3
# 红环1:头部 ∞ 图标条位置不变量(展开/收起全程逐帧 dy≈0)
# 用法: icon_track.py <帧目录> <图标条y>
# 判定: 置信帧(d<12)中 |dy|>=8 的帧数=0 → GREEN;否则 RED-DRIFT
# 稳定特征说明: ∞ 图标不受摘要淡入影响,是十一轮收口验证过的最稳锚点
import sys, glob
from PIL import Image
d = sys.argv[1]
yh = int(sys.argv[2])
files = sorted(glob.glob(d + '/f*.png'))
if not files: print('NO FRAMES'); sys.exit(1)
base = Image.open(files[0]).convert('L')
X1, X2, R = 88, 150, 16
bb = base.crop((X1, yh-R, X2, yh+R)).tobytes()
rows = []
for i, f in enumerate(files):
    im = Image.open(f).convert('L')
    best, bo, bd = 1e9, 0, 1e9
    for off in range(-300, 301, 2):
        yy = yh + off
        if yy-R < 0 or yy+R > im.height: continue
        cb = im.crop((X1, yy-R, X2, yy+R)).tobytes()
        dd = sum(abs(x-z) for x,z in zip(bb, cb))/len(bb)
        if dd < best: best, bo, bd = dd, off, dd
    rows.append((i, bo, round(bd,1)))
conf = [t for t in rows if t[2] < 12]
print('icon traj (frame,dy,d):', conf[:50])
if conf:
    peak = max(conf, key=lambda t: abs(t[1]))
    moved = [t for t in conf if abs(t[1]) >= 8]
    print('PEAK:', peak, 'END:', conf[-1], 'MOVED:', len(moved))
    print('VERDICT:', 'RED-DRIFT' if moved else 'GREEN')
