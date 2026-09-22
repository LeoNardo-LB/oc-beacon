#!/usr/bin/env python3
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
pat = sys.argv[2]
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"', xml):
    if re.search(pat, m.group(1)):
        print(m.group(2)[1:-1].replace('][', ',').strip('[]'))
        break