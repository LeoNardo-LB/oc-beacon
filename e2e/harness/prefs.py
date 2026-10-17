#!/usr/bin/env python3
"""DataStore preferences_pb 读写（仅 bool/string 键，保留未知条目）。

OC Beacon 的 SettingsDataStore 使用标准 DataStore Preferences protobuf：
  message PreferenceMap { map<string, Value> preferences = 1; }
  message Value { oneof value { bool boolean=1; float float=2; int32 integer=3;
                   int64 long=4; string string=5; StringSet string_set=6;
                   double double=7; bytes bytes=8; } }
本模块直接解析 wire format（无需 .proto），用于测试装置确定性设置
expand_reasoning 等开关，避免 UI 导航与 IME 干扰。
"""
import struct, sys

def _rv(b, i):
    v = 0; s = 0
    while True:
        x = b[i]; i += 1
        v |= (x & 0x7f) << s
        if not x & 0x80:
            return v, i
        s += 7

def _wv(v):
    out = bytearray()
    while True:
        x = v & 0x7f
        v >>= 7
        if v:
            out.append(x | 0x80)
        else:
            out.append(x)
            return bytes(out)

def _field(num, wire):
    return _wv((num << 3) | wire)

def parse_map(data):
    """return list of (key, value_bytes or None, raw_entry_bytes)"""
    out = []
    i = 0
    while i < len(data):
        start = i
        k, i = _rv(data, i)
        f, w = k >> 3, k & 7
        if w != 2:
            raise ValueError(f"unexpected top-level wire {w} field {f}")
        n, i = _rv(data, i)
        entry = data[i:i+n]; i += n
        j = 0; key = None; val = None
        while j < len(entry):
            kk, j = _rv(entry, j)
            ff, ww = kk >> 3, kk & 7
            if ww == 2:
                nn, j = _rv(entry, j)
                vv = entry[j:j+nn]; j += nn
                if ff == 1:
                    key = vv.decode('utf-8', 'replace')
                elif ff == 2:
                    val = vv
            elif ww == 0:
                _, j = _rv(entry, j)
            elif ww == 1:
                j += 8
            elif ww == 5:
                j += 4
            else:
                raise ValueError(f"unexpected entry wire {ww}")
        out.append((key, val, data[start:i]))
    return out

def value_kind(val):
    if val is None:
        return None
    i = 0
    k, i = _rv(val, i)
    f, w = k >> 3, k & 7
    return (f, w)

def get_bool(data, key):
    for k, v, _ in parse_map(data):
        if k == key:
            f, w = value_kind(v)
            if w == 0:
                x, _ = _rv(v, 1)
                return bool(x)
    return None

def set_bool(data, key, value):
    entries = parse_map(data)
    target = None
    for idx, (k, v, raw) in enumerate(entries):
        if k == key:
            target = idx
            break
    if target is None:
        entry = _field(1, 2) + _wv(len(key)) + key.encode()
        val_msg = _field(1, 0) + _wv(1 if value else 0)
        entry += _field(2, 2) + _wv(len(val_msg)) + val_msg
        entries.append((key, val_msg, entry))
    else:
        k, v, raw = entries[target]
        val_msg = _field(1, 0) + _wv(1 if value else 0)
        entry = _field(1, 2) + _wv(len(key)) + key.encode()
        entry += _field(2, 2) + _wv(len(val_msg)) + val_msg
        entries[target] = (key, val_msg, entry)
    out = bytearray()
    for _, _, raw in entries:
        out += _field(1, 2) + _wv(len(raw)) + raw
    return bytes(out)

if __name__ == '__main__':
    cmd = sys.argv[1]
    path = sys.argv[2]
    data = open(path, 'rb').read()
    if cmd == 'get':
        print(get_bool(data, sys.argv[3]))
    elif cmd == 'set':
        val = sys.argv[4].lower() in ('1', 'true', 'on', 'yes')
        open(path, 'wb').write(set_bool(data, sys.argv[3], val))
    elif cmd == 'dump':
        for k, v, _ in parse_map(data):
            f, w = value_kind(v)
            if w == 0:
                x, _ = _rv(v, 1)
                print(k, '=', x)
            elif w == 2:
                x, _ = _rv(v, 1)
                print(k, '=', v[1:].decode('utf-8', 'replace')[:60])
            else:
                print(k, '=?', f, w)
