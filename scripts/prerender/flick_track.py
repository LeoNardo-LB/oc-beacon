#!/usr/bin/env python3
# 红环2辅助:墨水单调性(内容区暗像素占比轨迹,检测内容消失-复现闪烁)
# 用法: flick_track.py <帧目录> <头部y>
# 判定: FLICKER_FRAMES 非空 = 存在涨-塌-复现签名(RED)
import sys, glob
from PIL import Image
d = sys.argv[1]
yh = int(sys.argv[2])
files = sorted(glob.glob(d + '/f*.png'))
if not files: print('NO FRAMES'); sys.exit(1)
inks = []
X1, X2, Y1, Y2 = 60, 1140, yh+20, yh+520
for f in files:
    im = Image.open(f).convert('L')
    hist = im.crop((X1, Y1, X2, Y2)).histogram()
    inks.append(sum(hist[:128]))
peak = max(inks)
print('ink traj (frame:dark%):', ' '.join(str(i)+':'+str(v*100//max(1,peak)) for i, v in enumerate(inks)))
flicker = []
for i in range(len(inks)):
    if inks[i] > peak*0.3:
        later = max(inks[i+1:]) if i+1 < len(inks) else 0
        nxt = inks[i+1] if i+1 < len(inks) else inks[i]
        if nxt < inks[i]*0.5 and later > inks[i]*0.8:
            flicker.append(i)
print('FLICKER_FRAMES:', flicker if flicker else 'none')
