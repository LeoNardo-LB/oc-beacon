import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
seen = set()
for m in re.finditer(r'<node[^>]*?text="([^"]{1,80})"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"', xml):
    t, b = m.group(1).strip(), m.group(2)
    if t and (t, b) not in seen:
        seen.add((t, b))
        print(b, ' ', t)