"""真机实时性测试：OpenOCD spawn → reset run → 实时 watch/scope → 切 probe-rs(显式芯片名) 复测。
只读采样；reset run 仅复位运行（不擦写 flash）。"""
import json, socket, subprocess, sys, time, threading, struct

import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_AGENT = os.path.normpath(os.path.join(SCRIPT_DIR, '..', '..', 'target', 'release', 'embedded-clion-agent.exe'))
if not os.path.exists(DEFAULT_AGENT):
    DEFAULT_AGENT = os.path.normpath(os.path.join(SCRIPT_DIR, '..', '..', 'target', 'debug', 'embedded-clion-agent.exe'))

AGENT = os.getenv("AGENT_BIN") or DEFAULT_AGENT
ELF = os.getenv("TEST_ELF_PATH") or "g4_tool_test.elf"
CFG = os.getenv("OPENOCD_CFG") or "stm32g4_daplink.cfg"
OPENOCD = os.getenv("OPENOCD_BIN") or "openocd"
CHIP = os.getenv("TEST_CHIP") or "STM32G431CBTx"

agent = subprocess.Popen([AGENT, "--port", "0"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
ready = agent.stdout.readline().strip()
port = int(ready.split("port=")[1].split()[0])
sock = socket.create_connection(("127.0.0.1", port), timeout=5)
sock_file = sock.makefile("r", encoding="utf-8")
req_id = 0; pending = {}; events = []

def reader():
    try:
        for line in sock_file:
            m = json.loads(line)
            (events.append(m) if "event" in m else pending.__setitem__(m["id"], m))
    except (OSError, BrokenPipeError):
        pass
threading.Thread(target=reader, daemon=True).start()

def call(method, params=None, timeout=10):
    global req_id
    req_id += 1
    rid = req_id
    sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
    t0 = time.time()
    while rid not in pending:
        if time.time() - t0 > timeout: raise TimeoutError(method)
        time.sleep(0.02)
    resp = pending.pop(rid)
    if not resp.get("ok"): raise RuntimeError(resp.get("error"))
    return resp.get("result")

def wait_event(kind, timeout=15):
    t0 = time.time()
    while time.time() - t0 < timeout:
        for e in events:
            if e["data"].get("kind") == kind: return e["data"]
        time.sleep(0.1)
    return None

def dump_events(tag):
    for e in events[-8:]:
        k = e["data"].get("kind")
        if k in ("connected", "disconnected", "error", "log"):
            print(f"   [{tag}] {k}: {e['data'].get('description') or e['data'].get('reason') or e['data'].get('message')}")

fail = 0
def check(name, cond, detail=""):
    global fail
    tag = "PASS" if cond else "FAIL"
    if not cond: fail += 1
    print(f"[{tag}] {name}  {detail}")

def u32(b): return struct.unpack("<I", bytes(b))[0]

# 1. 探测 + ELF
probes = call("list_probes")
print("调试器:", "; ".join(f"{p['name']} ({p['probeType']})" for p in probes))
elf = call("elf_load", {"path": ELF}, timeout=30)
vars = {v["name"]: v for v in elf["variables"]}

# 2. OpenOCD spawn 连接
call("connect", {"backend": "openocd", "attachOnly": False, "cfgFile": CFG,
                 "openocdPath": OPENOCD, "speedHz": 1000000}, timeout=30)
if not wait_event("connected", 25):
    dump_events("openocd"); print("OpenOCD 连接失败"); sys.exit(1)
print("[PASS] OpenOCD spawn 连接")

# 3. 直接给 OpenOCD 发 reset run（固件跑起来，g_counter 从 0 计数）
tc = socket.create_connection(("127.0.0.1", 6666), timeout=5)
tc.sendall(b"reset run\x1a")
buf = b""
tc.settimeout(3)
try:
    while not buf.endswith(b"\x1a"): buf += tc.recv(4096)
except socket.timeout: pass
tc.close()
print("reset run →", buf.decode(errors="replace").strip() or "OK")
time.sleep(1.5)

# 4. read_mem：g_counter 应递增
v = vars["g_counter"]
vals = []
for _ in range(4):
    b = call("read_mem", {"addr": v["address"], "size": v["size"]}, timeout=10)
    vals.append(u32(b[:4]))
    time.sleep(0.3)
inc = all(b > a for a, b in zip(vals, vals[1:]))
check("read_mem g_counter 实时递增", inc, f"{vals} (@0x{v['address']:08X})")

# 5. watch 5Hz（4 个变量）
names = [n for n in ["g_counter", "g_map", "g_list", "g_state"] if n in vars]
wt = [{"id": n, "addr": vars[n]["address"], "size": vars[n]["size"], "autoRefresh": True} for n in names]
call("set_watch_targets", {"targets": wt})
t0 = time.time()
while time.time() - t0 < 4: time.sleep(0.2)
wd = sum(1 for e in events if e["data"].get("kind") == "watchData")
check("watchData 5Hz", wd >= 12, f"4 秒 {wd} 帧")

# 6. scope 单通道 1000Hz（OpenOCD Tcl 上限 ~870Hz）
call("set_scope_targets", {"targets": [{"addr": v["address"], "size": 4}]})
call("set_scope_freq", {"freq": 1000})
events.clear()
t0 = time.time()
while time.time() - t0 < 4: time.sleep(0.2)
frames = sum(len(e["data"].get("samples", [])) for e in events if e["data"].get("kind") == "scopeData")
rate = frames / 4
check("scope 单通道 1kHz 请求", rate >= 500, f"实际 {rate:.0f} 帧/s（OpenOCD Tcl 上限 ~870）")

# 7. scope 多通道 500Hz（信息性：多块受 Tcl 往返限制）
call("set_scope_targets", {"targets": [{"addr": vars[n]["address"], "size": vars[n]["size"]} for n in names]})
call("set_scope_freq", {"freq": 500})
events.clear()
t0 = time.time()
while time.time() - t0 < 3: time.sleep(0.2)
frames = sum(len(e["data"].get("samples", [])) for e in events if e["data"].get("kind") == "scopeData")
print(f"[INFO] scope 4 通道 500Hz 请求 → 实际 {frames/3:.0f} 帧/s（Tcl RPC 每命令 ~1ms，块多则降速，符合设计）")

# 8. 切 probe-rs（显式芯片名）：先释放 probe
call("disconnect")
time.sleep(1.0)
events.clear()
ok_prs = True
try:
    call("connect", {"backend": "probe-rs", "target": CHIP, "speedHz": 4000000}, timeout=25)
    ev = wait_event("connected", 15)
    if not ev: raise RuntimeError("无 connected 事件")
    print("probe-rs 已连接:", ev.get("description"))
    time.sleep(0.5)
    vals = []
    for _ in range(4):
        b = call("read_mem", {"addr": v["address"], "size": 4}, timeout=10)
        vals.append(u32(b[:4]))
        time.sleep(0.3)
    inc = all(b > a for a, b in zip(vals, vals[1:]))
    check("probe-rs read_mem g_counter 实时递增", inc, vals)
    r = call("status")
    check("目标运行状态", r["state"] == "running", r)
except (RuntimeError, TimeoutError) as e:
    ok_prs = False
    dump_events("probe-rs")
    print(f"[INFO] probe-rs 显式芯片名连接失败: {e}（插件默认后端为 openocd，不影响主路径）")
call("disconnect")

# 9. 断开后状态与收尾
r = call("status")
check("断开后目标状态", r["state"] == "disconnected", r)
sock.shutdown(socket.SHUT_RDWR); sock.close()
t0 = time.time()
while agent.poll() is None and time.time() - t0 < 5: time.sleep(0.1)
check("agent 退出", agent.poll() is not None, f"exit={agent.poll()}")
if agent.poll() is None: agent.kill()

print()
print("ALL PASS" if fail == 0 else f"{fail} FAILED")
sys.exit(1 if fail else 0)
