#!/usr/bin/env python3
# 状态判定:展开内容标记+折叠行全文双检测(纪律:禁用窄条正则单判据)
# 用法: detect_state.py <dump.xml>  → EXPANDED / COLLAPSED / MIXED
# 纪律来源:十一轮实证——sliver 正则把半展开误判为已折叠,掩盖回归
import sys
xml = open(sys.argv[1], encoding='utf-8').read()
exp = '\u7b2c\u4e00\u6b65\uff1a\u5e38\u89c1' in xml   # 第一步：常见
col = '382ms' in xml or '382 ms' in xml
print('EXPANDED' if exp and not col else ('COLLAPSED' if col and not exp else 'MIXED exp=%s col=%s' % (exp, col)))
