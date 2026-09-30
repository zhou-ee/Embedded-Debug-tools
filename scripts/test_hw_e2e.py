"""embedded-clion-agent 真机端到端测试（硬件已连接时运行）。

覆盖：
  1. 新协议就绪行（proto/token）+ hello 握手
  2. 负向用例：错误 token → 拒绝并退出；无 hello 的请求 → 拒绝
  3. probe-rs 真机连接、真实内存读取、ELF 符号加载与解析
  4. watch/scope 数据流（目标固件运行时有变化）
  5. 协议入口地址/长度校验（越界、超 32 位空间）
  6. 断开后 agent 干净退出（引擎收尾 join 路径）

用法：
  python scripts/test_hw_e2e.py [agent路径] [elf路径] [目标芯片名]
环境变量：AGENT_BIN / TEST_ELF_PATH / HW_TARGET
"""
import json, os, socket, subprocess, sys, time, threading, struct

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)
DEFAULT_AGENT = os.path.normpath(os.path.join(REPO, "agent", "target", "release", "embedded-clion-agent.exe"))
AGENT = os.getenv("AGENT_BIN") or (sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else DEFAULT_AGENT)
ELF = os.getenv("TEST_ELF_PATH") or (sys.argv[2] if len(sys.argv) > 2 else r"E:\Software\Develop\Embeded\Pack\g4_tool_test\build\g4_tool_test.elf")
TARGET = os.getenv("HW_TARGET") or (sys.argv[3] if len(sys.argv) > 3 else "STM32G431CBTx")

fail = 0
def check(name, cond, detail=""):
    global fail
    tag = "PASS" if cond else "FAIL"
    if not cond: fail += 1
    print(f"[{tag}] {name} {detail}")

def spawn_agent():
    proc = subprocess.Popen(
        [AGENT, "--host", "127.0.0.1", "--port", "0"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    ready = proc.stdout.readline().strip()
    print("READY:", ready)
    assert ready.startswith("CLION_AGENT_READY"), ready
    fields = dict(kv.split("=", 1) for kv in ready.split()[1:] if "=" in kv)
    return proc, fields

class Client:
    """模拟插件 AgentClient：hello 握手 + 请求/响应 + 事件收集。"""
    def __init__(self, port, token=None, send_hello=True, hello_token=None):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.events = []
        self.pending = {}
        self.req_id = 0
        self.hello_resp = None
        threading.Thread(target=self._reader, daemon=True).start()
        if send_hello:
            params = {}
            if token is not None:
                params["token"] = hello_token if hello_token is not None else token
            self.hello_resp = self.call("hello", params, timeout=3)

    def _reader(self):
        f = self.sock.makefile("r", encoding="utf-8")
        try:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                m = json.loads(line)
                if "event" in m:
                    self.events.append(m)
                else:
                    self.pending[m["id"]] = m
        except (OSError, ValueError):
            pass

    def call(self, method, params=None, timeout=6):
        self.req_id += 1
        rid = self.req_id
        self.sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
        t0 = time.time()
        while rid not in self.pending:
            if time.time() - t0 > timeout:
                raise TimeoutError(f"{method} no response in {timeout}s")
            time.sleep(0.02)
        resp = self.pending.pop(rid)
        return resp

    def wait_event(self, kind, count, seconds):
        deadline = time.time() + seconds
        n = 0
        while time.time() < deadline:
            time.sleep(0.1)
            n = sum(1 for e in self.events if e["data"].get("kind") == kind)
            if n >= count:
                break
        return n

print("=" * 66)
print("阶段 0：负向协议用例（各自独立 agent 进程）")
print("=" * 66)

# 0a. 错误 token → 握手拒绝（hello 的应答即错误响应）→ 连接被断开 → agent 干净退出
proc, fields = spawn_agent()
check("就绪行含 proto=1", fields.get("proto") == "1", fields)
check("就绪行含 token", bool(fields.get("token")), fields)
bad = Client(int(fields["port"]), token=fields.get("token"), hello_token="deadbeef" * 4)
resp = bad.hello_resp
check("错误 token 被拒（ok=false）", resp is not None and resp.get("ok") is False,
      str((resp or {}).get("error", ""))[:60])
time.sleep(0.5)
check("拒绝后 agent 保持存活（误连不再杀死进程）", proc.poll() is None,
      f"exit={proc.poll()}")
# 随后真实客户端（正确 token）连上：backlog 排队的连接应被 accept 并正常握手
good = Client(int(fields["port"]), token=fields.get("token"))
check("真实客户端随后连接成功", good.hello_resp is not None and good.hello_resp.get("ok") is True,
      str((good.hello_resp or {}).get("result", ""))[:60])
good.sock.close()
t0 = time.time()
while proc.poll() is None and time.time() - t0 < 5:
    time.sleep(0.1)
check("会话结束 agent 干净退出", proc.poll() == 0, f"exit={proc.poll()}")
if proc.poll() is None:
    proc.kill()

# 0b. 不发 hello 直接请求 → 拒绝
proc, fields = spawn_agent()
nohello = Client(int(fields["port"]), send_hello=False)
resp = nohello.call("status", timeout=4)
check("无 hello 被拒（ok=false）", resp.get("ok") is False, resp.get("error", "")[:60])
time.sleep(0.5)
check("拒绝后 agent 保持存活（误连不再杀死进程）", proc.poll() is None,
      f"exit={proc.poll()}")
good = Client(int(fields["port"]), token=fields.get("token"))
check("真实客户端随后连接成功", good.hello_resp is not None and good.hello_resp.get("ok") is True,
      str((good.hello_resp or {}).get("result", ""))[:60])
good.sock.close()
t0 = time.time()
while proc.poll() is None and time.time() - t0 < 5:
    time.sleep(0.1)
check("会话结束 agent 干净退出", proc.poll() == 0, f"exit={proc.poll()}")
if proc.poll() is None:
    proc.kill()

print()
print("=" * 66)
print("阶段 1：真机正向全链路")
print("=" * 66)

proc, fields = spawn_agent()
cli = Client(int(fields["port"]), token=fields.get("token"))
check("hello 响应回带 proto", cli.hello_resp is not None and cli.hello_resp["result"].get("proto") == 1,
      cli.hello_resp and cli.hello_resp.get("result"))

r = cli.call("ping")
check("ping", r["ok"] and r["result"]["name"] == "embedded-clion-agent", r["result"])

r = cli.call("list_probes")
probes = r["result"] if isinstance(r["result"], list) else r["result"].get("probes", [])
check("list_probes 非空", len(probes) > 0, probes)
print("   探针:", probes)

r = cli.call("list_targets", {"filter": "G431"})
names = [t["name"] if isinstance(t, dict) else str(t) for t in (r["result"] if isinstance(r["result"], list) else r["result"].get("targets", []))]
print("   G431 目标名:", names[:5])
target = TARGET if TARGET else (names[0] if names else "STM32G431CBTx")

# 4. 协议校验：超 32 位空间地址必须被入口拒绝（而不是打到后端）
resp = cli.call("read_mem", {"addr": 0xFFFFFFFFFFFFFFF0, "size": 16})
check("u64 溢出地址被拒绝", resp.get("ok") is False and "32 位" in resp.get("error", ""), resp.get("error", "")[:60])
resp = cli.call("set_watch_targets", {"targets": [{"id": "bad", "addr": 0xFFFFFFFFFFFFFFF0, "size": 4, "autoRefresh": True}]})
check("watch 目标溢出被拒绝", resp.get("ok") is False, resp.get("error", "")[:60])
resp = cli.call("read_mem", {"addr": 0x20000000, "size": 10 * 1024 * 1024})
check("read_mem 超限被拒绝", resp.get("ok") is False, resp.get("error", "")[:60])

# 5. 真机连接（probe-rs）
t0 = time.time()
resp = {"ok": False}
try:
    resp = cli.call("connect", {"backend": "probe-rs", "target": target}, timeout=30)
    check(f"probe-rs 连接 {target}", resp.get("ok") is True, f"{time.time()-t0:.1f}s")
except TimeoutError:
    check(f"probe-rs 连接 {target}", False, "30s 超时")

if resp.get("ok"):
    ev = [e for e in cli.events if e.get("event") == "engine"]
    states = [e["data"].get("kind") for e in ev] if ev else []
    print("   引擎事件:", states)

    # 6. 真实内存读取（SRAM 0x20000000，两读验证通道畅通）
    b1 = bytes(cli.call("read_mem", {"addr": 0x20000000, "size": 16}, timeout=10)["result"])
    time.sleep(0.3)
    b2 = bytes(cli.call("read_mem", {"addr": 0x20000000, "size": 16}, timeout=10)["result"])
    check("read_mem 16B 成功", len(b1) == 16 and len(b2) == 16, b1.hex())
    print(f"   SRAM 前 16B: {b1.hex()} / {b2.hex()}（{'有变化，固件运行中' if b1 != b2 else '无变化'}）")

    # 7. 越界读：SRAM 之外 → 应得干净 Transfer 错误而非断线
    resp = cli.call("read_mem", {"addr": 0x7FFFFFFF, "size": 4}, timeout=10)
    check("未映射地址干净报错", resp.get("ok") is False, resp.get("error", "")[:60])
    r2 = cli.call("read_mem", {"addr": 0x20000000, "size": 4}, timeout=10)
    check("越界报错后连接仍可用", r2.get("ok") is True, "")

    # 8. ELF 加载与符号解析
    if os.path.exists(ELF):
        r = cli.call("elf_load", {"path": ELF}, timeout=60)
        res = r["result"]
        check("elf_load", r["ok"] and res["variableCount"] > 0,
              f"vars={res['variableCount']} funcs={res['functionCount']}")
        names = [v["name"] for v in res["variables"]][:8]
        print("   前 8 个全局变量:", names)
        if names:
            r = cli.call("elf_resolve", {"expr": names[0]})
            node = r["result"]
            check("elf_resolve", r["ok"] and node and node.get("address", 0) > 0,
                  f"{node and node.get('name')} @0x{(node or {}).get('address', 0):x}")
            # 用解析出的真实地址做 watch/scope
            addr, size = node["address"], min(node.get("size") or 4, 64)
            enc = (node or {}).get("encoding") or "Unsigned"
            fmt = {"Signed": "i32", "Unsigned": "u32", "Floating": "f32"}.get(enc, "u32")
            call = cli.call
            call("set_watch_targets", {"targets": [{"id": "hw1", "addr": addr, "size": 4, "autoRefresh": True}]})
            n = cli.wait_event("watchData", 2, 6)
            check("真机 watchData 5Hz", n >= 2, f"{n} 条/6s")

            call("set_scope_targets", {"targets": [{"addr": addr, "size": 4}, {"addr": addr, "size": size}]})
            call("set_scope_freq", {"freq": 100})
            deadline = time.time() + 4
            samples = 0
            while time.time() < deadline:
                time.sleep(0.1)
                for e in cli.events:
                    if e["data"].get("kind") == "scopeData":
                        samples += len(e["data"].get("samples", []))
            check("真机 scopeData ~100Hz", samples >= 100, f"{samples} 帧/4s")
            call("set_scope_targets", {"targets": []})
    else:
        print(f"[SKIP] ELF 不存在: {ELF}")

    # 9. attach-only 预检错误信息（无 OpenOCD 在跑时应给明确引导）
    resp = cli.call("connect", {"backend": "openocd", "attachOnly": True, "tclPort": 6666}, timeout=15)
    print(f"   [INFO] attach-only 预检: ok={resp.get('ok')} err={str(resp.get('error', ''))[:80]}")

# 10. 干净退出：断开 → 引擎收尾（join）→ 进程退出
cli.sock.shutdown(socket.SHUT_RDWR)
cli.sock.close()
t0 = time.time()
while proc.poll() is None and time.time() - t0 < 8:
    time.sleep(0.1)
check("断开后 agent 8s 内退出", proc.poll() is not None, f"exit={proc.poll()}")
try:
    err_out = proc.stderr.read() if proc.poll() is not None else ""
    if "收尾等待超时" in err_out:
        check("引擎收尾无超时告警", False, "检测到『引擎收尾等待超时』")
    else:
        check("引擎收尾无超时告警", True, "")
except Exception:
    pass

print()
print("ALL PASS" if fail == 0 else f"{fail} FAILED")
sys.exit(1 if fail else 0)
