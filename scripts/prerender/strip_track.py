#!/usr/bin/env python3
# 红环4:条带绝对Y轨迹(vs 第0帧)+瞬态签名(漂出-回弹=RED)
# 用法: strip_track.py <帧目录> <折叠行y> [参照条带y]
# 注意: d>=11 的弱匹配=按压高亮伪迹,不可作为漂移证据(十一轮实证)
import sys, glob
from PIL import Image
d = sys.argv[1]
y_fold = int(sys.argv[2])
y_ref = int(sys.argv[3]) if len(sys.argv) > 3 else 0
files = sorted(glob.glob(d + '/f*.png'))
if not files: print('NO FRAMES'); sys.exit(1)

def match(base, cur, y, search=220):
    bb = base.crop((60, y-24, 460, y+24)).tobytes()
    best, bo, bd = 1e9, 0, 1e9
    for off in range(-search, search+1, 2):
        yy = y+off
        if yy-24 < 0 or yy+24 > cur.height: continue
        cb = cur.crop((60, yy-24, 460, yy+24)).tobytes()
        dd = sum(abs(x-z) for x,z in zip(bb, cb))/len(bb)
        if dd < best: best, bo, bd = dd, off, dd
    return bo, bd

base = Image.open(files[0]).convert('L')
bands = [('fold', y_fold)] + ([('ref', y_ref)] if y_ref else [])
print('frames=' + str(len(files)) + ' baseline=' + files[0])
traj = {}
unmatched = {}
for n, _ in bands:
    traj[n] = []
    unmatched[n] = 0
for i, f in enumerate(files):
    cur = Image.open(f).convert('L')
    cols = []
    for n, y in bands:
        off, dd = match(base, cur, y)
        traj[n].append(off)
        if dd > 25:
            unmatched[n] += 1
            cols.append(n + ':UNMATCHED(d=' + str(int(dd)) + ')')
        else:
            cols.append(n + ':' + str(off) + '(d=' + str(int(dd)) + ')')
    print(str(i).rjust(4) + ' | ' + ' | '.join(cols))
print('--- signature ---')
for n, _ in bands:
    t = traj[n]
    peak = 0; peak_i = -1
    for i, v in enumerate(t):
        if abs(v) > abs(peak): peak, peak_i = v, i
    end_zone = t[max(peak_i+1, len(t)-5):] if peak_i >= 0 else []
    settled = all(abs(v) < 8 for v in end_zone) if end_zone else True
    red = abs(peak) >= 8 and settled and len(t) > peak_i + 2
    print(n + ': peak=' + str(peak) + 'px @frame' + str(peak_i) + ' end=' + str(end_zone[:6]) + ' unmatched=' + str(unmatched[n]) + ' -> ' + ('RED' if red else 'ok'))
