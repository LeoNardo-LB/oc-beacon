#!/usr/bin/env python3
"""device.py — adb / uiautomator 最小驱动层（七卡 E2E）。

约定：
- 所有命令带 `-s <serial>`；默认模拟器 emulator-5554。
- dump 前删除旧文件，避免 uiautomator 失败时读到陈旧 XML（历史事故）。
- 时间戳统一 device logcat `-v time`（MM-DD HH:MM:SS.mmm），解析在 probes.py。
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PATH_ENV = os.environ.get("PATH", "") + ":/home/leonardo/android-sdk/platform-tools"
ADB = os.environ.get("ADB", "/home/leonardo/android-sdk/platform-tools/adb")


class DeviceError(RuntimeError):
    pass


def list_devices():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=20).stdout
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) == 2 and parts[1].strip() == "device":
            rows.append(parts[0])
    return rows


class Device:
    def __init__(self, serial="emulator-5554"):
        self.serial = serial

    # ---------- 基础 ----------
    def adb(self, *args, timeout=30, check=False):
        cmd = [ADB, "-s", self.serial] + [str(a) for a in args]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           env={**os.environ, "PATH": PATH_ENV})
        if check and r.returncode != 0:
            raise DeviceError(f"adb {' '.join(map(str, args))} -> {r.returncode}: {r.stderr[:300]}")
        return r

    def shell(self, cmd, timeout=30, check=False):
        return self.adb("shell", cmd, timeout=timeout, check=check)

    def online(self):
        return self.serial in list_devices()

    def wait_online(self, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            if self.online():
                return True
            time.sleep(1)
        return False

    # ---------- 包/服务 ----------
    def reverse(self, port):
        return self.adb("reverse", f"tcp:{port}", f"tcp:{port}")

    def install(self, apk):
        self.adb("push", apk, "/data/local/tmp/e2e7.apk", timeout=120, check=True)
        out = self.shell("pm install -r /data/local/tmp/e2e7.apk", timeout=180, check=True)
        if "Success" not in out.stdout:
            raise DeviceError("pm install failed: " + out.stdout[-300:])
        return True

    def package_version(self, pkg):
        out = self.shell(f"dumpsys package {pkg} | grep -E 'versionName|versionCode' | head -2").stdout
        return out.strip()

    def force_stop(self, pkg):
        self.shell(f"am force-stop {pkg}")
        time.sleep(0.8)

    def start_debug(self, pkg, url, name="Mock4399", server_type="opencode", extra=()):
        args = ["am", "start", "-n", f"{pkg}/dev.leonardo.ocbeacon.MainActivity",
                "--es", "debug_url", url,
                "--es", "debug_name", name,
                "--es", "debug_server_type", server_type]
        for e in extra:
            args += list(e)
        self.shell(" ".join(args))
        return self.wait_text(name, timeout=25)

    def current_focus(self):
        out = self.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4").stdout
        return out

    # ---------- 日志 ----------
    def logcat_clear(self):
        self.adb("logcat", "-c")

    def logcat(self, timeout=45):
        return self.adb("logcat", "-d", "-v", "time", timeout=timeout).stdout

    def count_log(self, needle, tail=3000):
        """近 tail 行内计数（比全量 dump 快一个量级；用于命中反馈）。"""
        out = self.adb("logcat", "-d", "-t", str(tail), timeout=20).stdout
        return out.count(needle)

    # ---------- UI ----------
    def dump(self, retries=2):
        for _ in range(retries):
            self.shell("rm -f /sdcard/e2e7.xml")
            r = self.shell("uiautomator dump /sdcard/e2e7.xml", timeout=15)
            if "dumped" not in (r.stdout + r.stderr):
                # 历史事故（#554 卡面）：uiautomator 进程卡死持 UiAutomation 连接，
                # 后续 dump 恒空。杀 shell 域残留进程后重试。
                self.shell("killall uiautomator 2>/dev/null; pkill -f com.android.commands.uiautomator 2>/dev/null")
                time.sleep(0.5)
                continue
            x = self.shell("cat /sdcard/e2e7.xml", timeout=20).stdout
            if x.strip().startswith("<?xml"):
                return x
            time.sleep(0.6)
        return ""

    def nodes(self, xml=None):
        if xml is None:
            xml = self.dump()
        if not xml:
            return []
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        out = []

        def walk(n):
            b = n.attrib.get("bounds", "")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            if m:
                l, t, r, bo = map(int, m.groups())
                out.append({
                    "text": n.attrib.get("text", ""),
                    "desc": n.attrib.get("content-desc", ""),
                    "cls": n.attrib.get("class", ""),
                    "clickable": n.attrib.get("clickable") == "true",
                    "l": l, "t": t, "r": r, "b": bo,
                    "cx": (l + r) // 2, "cy": (t + bo) // 2,
                })
            for c in n:
                walk(c)

        walk(root)
        return out

    def find(self, nodes, text=None, desc=None, contains=False):
        res = []
        for n in nodes:
            if text is not None:
                ok = (text in n["text"]) if contains else (n["text"] == text)
                if not ok:
                    continue
            if desc is not None:
                ok = (desc in n["desc"]) if contains else (n["desc"] == desc)
                if not ok:
                    continue
            res.append(n)
        return res

    def wait_text(self, text, timeout=20, contains=True, interval=0.8):
        end = time.time() + timeout
        while time.time() < end:
            ns = self.nodes()
            if self.find(ns, text=text, contains=contains):
                return True
            time.sleep(interval)
        return False

    def text_bounds(self, text, contains=True):
        ns = self.nodes()
        hit = self.find(ns, text=text, contains=contains)
        return hit

    def tap(self, x, y):
        self.shell(f"input tap {int(x)} {int(y)}")

    def tap_node(self, n):
        self.tap(n["cx"], n["cy"])

    def tap_text(self, text, contains=True, target_y=None):
        hits = self.text_bounds(text, contains)
        if not hits:
            return False
        n = hits[0]
        if target_y is not None:
            self.tap(n["cx"], target_y)
        else:
            self.tap(n["cx"], n["cy"])
        return True

    def swipe(self, x1, y1, x2, y2, ms=400):
        self.shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

    def input_text(self, text):
        # adb input text 不支持空格/特殊字符；本套件 prompt 为纯 ASCII
        self.shell("input text '%s'" % text.replace("'", "\\'"))

    def key(self, code):
        self.shell(f"input keyevent {code}")

    # ---------- 设置（DataStore pb） ----------
    def pull_prefs(self, pkg, local):
        self.shell(f"run-as {pkg} cat files/datastore/opencode_prefs.preferences_pb > /sdcard/e2e7_prefs.pb")
        self.adb("pull", "/sdcard/e2e7_prefs.pb", local, timeout=30)
        return local

    def push_prefs(self, pkg, local):
        self.adb("push", local, "/data/local/tmp/e2e7_prefs.pb", timeout=30)
        self.shell("chmod 644 /data/local/tmp/e2e7_prefs.pb")
        self.shell(f"run-as {pkg} cp /data/local/tmp/e2e7_prefs.pb files/datastore/opencode_prefs.preferences_pb")

    def set_bool_pref(self, pkg, key, value):
        import prefs as prefs_mod
        tmp = "/tmp/e2e7_prefs_%s.pb" % key
        self.pull_prefs(pkg, tmp)
        if not os.path.exists(tmp):
            raise DeviceError("prefs pull failed")
        data = open(tmp, "rb").read()
        open(tmp, "wb").write(prefs_mod.set_bool(data, key, value))
        self.push_prefs(pkg, tmp)
        return True

    def screen_size(self):
        out = self.shell("wm size").stdout
        m = re.search(r"(\d+)x(\d+)", out)
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    def is_streaming(self):
        ns = self.nodes()
        return bool(self.find(ns, desc="停止", contains=True) or self.find(ns, text="停止", contains=True))
