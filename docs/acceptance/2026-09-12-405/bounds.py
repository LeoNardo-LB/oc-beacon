import re,sys,xml.etree.ElementTree as ET
# usage: bounds.py file.xml "text|desc|substr" [attr]
p=sys.argv[1]; needle=sys.argv[2]
attr=sys.argv[3] if len(sys.argv)>3 else None
x=open(p,encoding='utf-8',errors='replace').read()
def scan(nodes):
    for n in nodes:
        t=n.get('text','') or ''
        d=n.get('content-desc','') or ''
        r=n.get('resource-id','') or ''
        hay=t+'\u0001'+d+'\u0001'+r
        if needle in hay and (attr is None or needle in (n.get(attr,'') or '')):
            b=n.get('bounds','')
            m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
            if m:
                x1,y1,x2,y2=map(int,m.groups())
                print(f"text={t!r} desc={d!r} rid={r!r} bounds={b} center=({(x1+x2)//2},{(y1+y2)//2})")
        scan(list(n))
try:
    root=ET.fromstring(x)
    scan([root])
except Exception as e:
    print("ERR",e)
