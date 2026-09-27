"""embedded-clion-agent 冒烟测试：Sim 后端全链路（watch/scope/elf/read_mem）。"""
import json, socket, subprocess, sys, time, threading, math

import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_AGENT = os.path.normpath(os.path.join(SCRIPT_DIR, '..', '..', 'target', 'release', 'embedded-clion-agent.exe'))
if not os.path.exists(DEFAULT_AGENT):
    DEFAULT_AGENT = os.path.normpath(os.path.join(SCRIPT_DIR, '..', '..', 'target', 'debug', 'embedded-clion-agent.exe'))

AGENT = os.getenv("AGENT_BIN") or (sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else DEFAULT_AGENT)
ELF = os.getenv("TEST_ELF_PATH") or (sys.argv[2] if len(sys.argv) > 2 else r"E:\Software\Develop\Embeded\Pack\g4_tool_test\cmake-build-debug-stm32\g4_tool_test.elf")

agent = subprocess.Popen([AGENT, "--port", "0"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
ready = agent.stdout.readline().strip()
print("READY:", ready)
assert ready.startswith("CLION_AGENT_READY"), ready
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
    except (OSError, BrokenPipeError):
        pass

threading.Thread(target=reader, daemon=True).start()

def call(method, params=None, timeout=5):
    global req_id
    req_id += 1
    rid = req_id
    sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
    t0 = time.time()
    while rid not in pending:
        if time.time() - t0 > timeout:
            raise TimeoutError(f"{method} no response in {timeout}s")
        time.sleep(0.02)
    resp = pending.pop(rid)
    if not resp.get("ok"):
        raise RuntimeError(f"{method} error: {resp.get('error')}")
    return resp.get("result")

fail = 0
def check(name, cond, detail=""):
    global fail
    tag = "PASS" if cond else "FAIL"
    if not cond: fail += 1
    print(f"[{tag}] {name} {detail}")

# 1. ping
r = call("ping")
check("ping", r["name"] == "embedded-clion-agent", r)

# 2. connect sim
call("connect", {"backend": "sim"})
time.sleep(0.5)

# 3. read_mem f32 sin @0x20000004 (两读应有变化)
b1 = call("read_mem", {"addr": 0x20000004, "size": 4}, timeout=10)
time.sleep(0.3)
b2 = call("read_mem", {"addr": 0x20000004, "size": 4}, timeout=10)
import struct as _s
v1 = _s.unpack("<f", bytes(b1))[0]
v2 = _s.unpack("<f", bytes(b2))[0]
check("read_mem 变化", bytes(b1) != bytes(b2), f"{v1:.2f} -> {v2:.2f}")
check("sin 幅值", abs(v2) <= 100.01, f"|{v2:.2f}|<=100")

# 4. elf_load 真实工程 ELF
if os.path.exists(ELF):
    r = call("elf_load", {"path": ELF}, timeout=30)
    check("elf_load", r["variableCount"] > 0, f"vars={r['variableCount']} funcs={r['functionCount']}")
    names = [v["name"] for v in r["variables"]]
    print("   前5个全局变量:", names[:5])

    # 5. elf_resolve（顶层符号名）
    r = call("elf_resolve", {"expr": names[0]} if names else {"expr": "__aeabi_dcmpun"})
    node = r if r else None
    if names:
        check("elf_resolve 顶层", node is not None and node["address"] > 0, f"{node and node['name']} @0x{node and node['address']:x}")
else:
    print(f"[SKIP] ELF 文件未找到 ({ELF})，跳过符号表加载验证")

# 6. set_watch_targets @0x20000004 (f32) → watchData 事件流
call("set_watch_targets", {"targets": [{"id": "w1", "addr": 0x20000004, "size": 4, "autoRefresh": True}]})
deadline = time.time() + 3
wd = 0
while time.time() < deadline:
    time.sleep(0.1)
    wd = sum(1 for e in events if e["data"].get("kind") == "watchData")
    if wd >= 2: break
check("watchData 5Hz", wd >= 2, f"{wd} 条/3s")

# 7. set_scope_targets + freq → scopeData 批量事件
call("set_scope_targets", {"targets": [{"addr": 0x20000004, "size": 4}, {"addr": 0x20000000, "size": 4}]})
call("set_scope_freq", {"freq": 100})
deadline = time.time() + 3
samples = 0
while time.time() < deadline:
    time.sleep(0.1)
    for e in events:
        if e["data"].get("kind") == "scopeData":
            samples += len(e["data"].get("samples", []))
check("scopeData ~100Hz", samples >= 100, f"{samples} 帧/3s")

# 8. status
r = call("status")
check("status running", r["state"] == "running", r)

# 9. check_bandwidth
r = call("check_bandwidth", {"targets": [[0x20000000, 8]], "freq": 100})
check("check_bandwidth", "fits" in r, r)

# 10. 断开 → agent 应退出（注意：makefile 持有引用时 close() 不发 FIN，必须 shutdown）
sock.shutdown(socket.SHUT_RDWR)
sock.close()
t0 = time.time()
while agent.poll() is None and time.time() - t0 < 5:
    time.sleep(0.1)
check("断开后 agent 退出", agent.poll() is not None, f"exit={agent.poll()}")

print()
print("ALL PASS" if fail == 0 else f"{fail} FAILED")
sys.exit(1 if fail else 0)
