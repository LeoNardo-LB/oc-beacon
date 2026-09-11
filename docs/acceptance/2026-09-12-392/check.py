import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='replace').read()
title='快速定位' in re.findall(r'text="([^"]*)"', x)
scrim='关闭工作表' in re.findall(r'content-desc="([^"]*)"', x)
q=len(re.findall(r'text="Q\d+"', x))
fab='快速定位' in re.findall(r'content-desc="([^"]*)"', x)
print(f"title={title} scrim={scrim} FAB_desc={fab} Qcount={q} -> {'SHEET_VISIBLE' if scrim else 'SHEET_CLOSED'}")
