#!/usr/bin/env python3
"""
Sim 后端多档位刷新率测试脚本 (2Hz, 5Hz, 10Hz, 15Hz)
"""
import os
import sys
import json
import time
import socket
import threading
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.normpath(os.path.join(SCRIPT_DIR, ".."))
DEFAULT_AGENT = os.path.join(ROOT_DIR, "agent", "target", "release", "embedded-clion-agent.exe")
if not os.path.exists(DEFAULT_AGENT):
    DEFAULT_AGENT = os.path.join(ROOT_DIR, "agent", "target", "debug", "embedded-clion-agent.exe")

AGENT = os.getenv("AGENT_BIN") or (sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else DEFAULT_AGENT)

if not os.path.exists(AGENT):
    print(f"[!] 找不到 Agent 可执行文件: {AGENT}")
    print("    请先在 agent/ 目录下执行 cargo build --release")
    sys.exit(1)

print(f"[*] 启动 Agent: {AGENT}")
agent = subprocess.Popen([AGENT, "--port", "0"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
ready = agent.stdout.readline().strip()
print("Agent ready:", ready)
port = int(ready.split("port=")[1].split()[0])
sock = socket.create_connection(("127.0.0.1", port), timeout=5)
sock_file = sock.makefile("r", encoding="utf-8")
req_id = 0
pending = {}
events = []

def reader():
    try:
        for line in sock_file:
            m = json.loads(line)
            if "event" in m:
                events.append(m)
            else:
                pending[m["id"]] = m
    except Exception:
        pass

threading.Thread(target=reader, daemon=True).start()

def call(method, params=None, timeout=10):
    global req_id
    req_id += 1
    rid = req_id
    sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
    t0 = time.time()
    while rid not in pending:
        if time.time() - t0 > timeout:
            raise TimeoutError(method)
        time.sleep(0.01)
    resp = pending.pop(rid)
    if not resp.get("ok"):
        raise RuntimeError(resp.get("error"))
    return resp.get("result")

# 1. 连接 sim 后端
res = call("connect", {"backend": "sim"})
print("Connect result:", res)
time.sleep(0.5)

# 2. 设置监视目标
call("set_watch_targets", {"targets": [{"id": "tick", "addr": 0x20000000, "size": 4, "autoRefresh": True}]})

# 3. 遍历测试 2Hz, 5Hz, 10Hz, 15Hz
for freq in [2.0, 5.0, 10.0, 15.0]:
    r = call("set_watch_freq", {"freq": freq})
    print(f"set_watch_freq({freq}) response:", r)
    events.clear()
    t0 = time.time()
    while time.time() - t0 < 3.0:
        time.sleep(0.05)
    wd = sum(1 for e in events if e.get("data", {}).get("kind") == "watchData")
    rate = wd / 3.0
    print(f"Configured freq: {freq:>4.1f} Hz -> Measured: {rate:>5.2f} Hz ({wd} frames in 3s)")

call("disconnect")
agent.terminate()
print("[+] 频率采样测试完成。")
