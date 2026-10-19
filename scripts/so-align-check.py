#!/usr/bin/env python3
# =============================================================================
# so-align-check.py — #546 APK 内 .so 的 16KB 页对齐检测（ELF p_align 解析）
#
# 背景：MIUI「应用兼容性」告警指出 lib*.so 未 16KB 对齐——面向未来 16KB 页设备
# （Android 15+ 强制方向）。判定标准：ELF program header 的 PT_LOAD p_align
# ≥ 16384 (0x4000) 为合规；1024/4096 为旧对齐。
#
# 用法: python3 scripts/so-align-check.py <apk> [<apk> ...]
# 输出：逐库 p_align 表 + 总判定；exit 0=全合规 / 1=存在不合规
# =============================================================================
import sys, struct, zipfile


def elf_pt_load_aligns(data: bytes):
    """返回 ELF 所有 PT_LOAD 的 p_align 列表（解析失败返回 None）。"""
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        # ELF64: e_phoff@0x20(8) e_phentsize@0x36(2) e_phnum@0x38(2)
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        aligns = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            if off + 56 > len(data):
                return None
            p_type = struct.unpack_from("<I", data, off)[0]
            p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
            if p_type == 1:  # PT_LOAD
                aligns.append(p_align)
        return aligns or None
    # ELF32: e_phoff@0x1C(4) e_phentsize@0x2A(2) e_phnum@0x2C(2)
    e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x2A)[0]
    e_phnum = struct.unpack_from("<H", data, 0x2C)[0]
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 32 > len(data):
            return None
        p_type = struct.unpack_from("<I", data, off)[0]
        p_align = struct.unpack_from("<I", data, off + 28)[0]
        if p_type == 1:
            aligns.append(p_align)
    return aligns or None


def check_apk(path: str) -> bool:
    print(f"== {path}")
    bad, total, seen = [], 0, {}
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            total += 1
            data = z.read(name)
            aligns = elf_pt_load_aligns(data)
            abi = name.split("/")[1] if name.count("/") >= 2 else "?"
            lib = name.split("/")[-1]
            if aligns is None:
                bad.append(f"{abi}/{lib}")
                seen.setdefault(lib, []).append((abi, -1, []))
                continue
            seen.setdefault(lib, []).append((abi, min(aligns), sorted(set(aligns))))
            if min(aligns) < 16384:
                bad.append(f"{abi}/{lib}")
    for lib, entries in sorted(seen.items()):
        abis = ",".join(a for a, _, _ in entries)
        worst = min(w for _, w, _ in entries)
        aligns_h = "/".join(hex(a) for a in next(iter(entries))[2]) or "?"
        if worst < 0:
            print(f"  {lib:44s} [{abis}] 解析失败（非 ELF？）")
            continue
        verdict = "OK " if worst >= 16384 else ("4K " if worst >= 4096 else "!! ")
        print(f"  {lib:44s} [{abis}] p_align(min)={worst:<8d} {verdict}({aligns_h})")
    if total == 0:
        print("  (无 lib/ 条目)")
        return True
    print(f"  小结: {total} 条({len(seen)} 库), 不合规: {bad if bad else '无'}")
    return not bad


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    results = [check_apk(p) for p in sys.argv[1:]]  # 不短路：全部 APK 都要出报告
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
