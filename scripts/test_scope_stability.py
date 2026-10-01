"""V1.2.35 示波稳定性真机验收（STM32G431CBTx + Flash Pro CMSIS-DAP）。

验证项（用户主场景 1kHz）：
  1. 1kHz 三通道采样率与节拍均匀性（p50/p95/p99/max、>3 周期空档数）
  2. 监视插帧：15Hz watchData 与 1kHz 示波并行到达，示波速率无明显劣化
  3. 3kHz 三通道：记录性指标（不判 FAIL，诚实节拍下 ~1.75kHz 为事务数上限）
  4. scope_perf 计数（burst/frames/degraded）

用法：python scripts/test_scope_stability.py
环境变量：AGENT_BIN / TEST_ELF_PATH / HW_TARGET
"""
import json, os, socket, subprocess, sys, time, threading, statistics

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)
AGENT = os.getenv("AGENT_BIN") or os.path.normpath(
    os.path.join(REPO, "agent", "target", "release", "embedded-clion-agent.exe"))
ELF = os.getenv("TEST_ELF_PATH") or r"E:\Software\Develop\Embeded\Pack\g4_tool_test\cmake-build-debug-stm32\g4_tool_test.elf"
TARGET = os.getenv("HW_TARGET") or "STM32G431CBTx"

fail = 0
def check(name, cond, detail=""):
    global fail
    tag = "PASS" if cond else "FAIL"
    if not cond: fail += 1
    print(f"[{tag}] {name} {detail}")

class Client:
    def __init__(self, port, token):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.events = []
        self.lock = threading.Lock()
        self.pending = {}
        self.req_id = 0
        threading.Thread(target=self._reader, daemon=True).start()
        self.call("hello", {"token": token}, timeout=3)

    def _reader(self):
        f = self.sock.makefile("r", encoding="utf-8")
        try:
            for line in f:
                line = line.strip()
                if not line: continue
                m = json.loads(line)
                with self.lock:
                    if "event" in m: self.events.append(m)
                    else: self.pending[m["id"]] = m
        except (OSError, ValueError): pass

    def call(self, method, params=None, timeout=10):
        self.req_id += 1
        rid = self.req_id
        self.sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
        t0 = time.time()
        while rid not in self.pending:
            if time.time() - t0 > timeout:
                raise TimeoutError(f"{method} no response in {timeout}s")
            time.sleep(0.01)
        with self.lock:
            return self.pending.pop(rid)

    def take_events(self):
        with self.lock:
            ev, self.events = self.events, []
        return ev

def drain_stats(cli, seconds, tag):
    """在 seconds 窗口内统计 scopeData 间隔与 watchData 计数（消费式收取）。"""
    deadline = time.time() + seconds
    samples, watch_n = [], 0
    while time.time() < deadline:
        time.sleep(0.05)
        for e in cli.take_events():
            data = e.get("data", {})
            kind = data.get("kind")
            if kind == "scopeData":
                samples.extend(data.get("samples", []))
            elif kind == "watchData" and "live_val" in data.get("values", {}):
                watch_n += 1
    ts = sorted(s["t"] for s in samples if isinstance(s.get("t"), (int, float)))
    gaps = [b - a for a, b in zip(ts, ts[1:])]
    n = len(ts)
    out = {"frames": n}
    if n >= 2:
        gaps_ms = sorted(g * 1000 for g in gaps)
        hz = (n - 1) / (ts[-1] - ts[0]) if ts[-1] > ts[0] else 0
        req_ms = 1000.0 / REQ_HZ[tag]
        over3 = sum(1 for g in gaps_ms if g > 3 * req_ms)
        out.update({
            "hz": round(hz, 1),
            "p50": round(statistics.median(gaps_ms), 3),
            "p95": round(gaps_ms[int(len(gaps_ms) * 0.95)], 3),
            "p99": round(gaps_ms[min(int(len(gaps_ms) * 0.99), len(gaps_ms) - 1)], 3),
            "max": round(gaps_ms[-1], 3),
            "over3": over3,
        })
    return out, watch_n

REQ_HZ = {"1k": 1000.0, "3k": 3000.0}

proc = subprocess.Popen([AGENT, "--host", "127.0.0.1", "--port", "0"],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
ready = proc.stdout.readline().strip()
assert ready.startswith("CLION_AGENT_READY"), ready
fields = dict(kv.split("=", 1) for kv in ready.split()[1:] if "=" in kv)
cli = Client(int(fields["port"]), fields["token"])

r = cli.call("connect", {"backend": "probe-rs", "target": TARGET}, timeout=30)
check("probe-rs 连接", r.get("ok") is True, r.get("error", "")[:60])
if not r.get("ok"):
    proc.kill(); sys.exit(1)

r = cli.call("elf_load", {"path": ELF}, timeout=60)
check("elf_load", r.get("ok") and r["result"]["variableCount"] > 0,
      f"vars={r.get('result', {}).get('variableCount')}")

# 重新解析三通道地址（不硬编码历史地址）。真三路正弦 sin_1hz/sin_5hz/
# sin_20hz 地址连续（0x94/0x98/0x9c），合并为单个 12B 读块——与界面实际
# 配置一致（2026-10-02 复测指正：旧脚本用 chassis 指针会拆成两个读块，
# 其 3kHz 结论不能代表界面三路能力）
addrs = {}
for expr in ("sin_1hz", "sin_5hz", "sin_20hz"):
    r = cli.call("elf_resolve", {"expr": expr}, timeout=15)
    node = r.get("result") or {}
    if node.get("address"):
        addrs[expr] = (node["address"], min(node.get("size") or 4, 64))
check("符号解析", len(addrs) >= 2, addrs)

scope_targets = [
    {"addr": a, "size": 4}
    for a, _s in (addrs.get(e) for e in ("sin_1hz", "sin_5hz", "sin_20hz"))
    if a
]

perf0 = cli.call("scope_perf")

# ── 场景 1：1kHz 三通道（无 watch 基线）──
cli.call("set_scope_targets", {"targets": scope_targets})
cli.call("set_scope_freq", {"freq": 1000.0})
time.sleep(2.0)  # 预热丢弃窗 + 节流稳定
cli.take_events()
s1, w1 = drain_stats(cli, 12.0, "1k")
check("1kHz 速率 ≥900Hz", s1.get("hz", 0) >= 900, s1)
check("1kHz >3周期空档 ≤12/12s", s1.get("over3", 99) <= 12, f"over3={s1.get('over3')}")
print(f"   1kHz 无 watch 基线: {s1}")

# ── 场景 2：1kHz + 15Hz watch 插帧 ──
cli.call("set_watch_targets", {"targets": [{"id": "live_val", "addr": addrs["sin_1hz"][0], "size": 4, "autoRefresh": True}]})
cli.call("set_watch_freq", {"freq": 15.0})
time.sleep(1.0)
cli.take_events()
s2, w2 = drain_stats(cli, 12.0, "1k")
check("1kHz+插帧 速率 ≥900Hz", s2.get("hz", 0) >= 900, s2)
check("插帧 watchData ≥150/12s", w2 >= 150, f"{w2} 条（15Hz 预期 ~180）")
check("插帧不劣化示波（差 ≤30Hz）", abs(s2.get("hz", 0) - s1.get("hz", 0)) <= 30,
      f"Δ={s2.get('hz', 0) - s1.get('hz', 0):+.1f}Hz")
print(f"   1kHz+15Hz watch: {s2}, watchData={w2}")

# ── 场景 3：3kHz 三通道 + watch 保持（记录性指标）──
# 用户主场景为 1kHz；3kHz 在诚实节拍（超期重锚定，不追赶）下受每帧
# 事务数限制（3 通道 2 块 read_32 ≈ 570µs/帧 → ~1.75kHz），旧版"2800Hz"
# 来自超期后不均匀的追赶连读。此处只记录不判 FAIL。
cli.call("set_scope_freq", {"freq": 3000.0})
time.sleep(1.5)
cli.take_events()
s3, w3 = drain_stats(cli, 12.0, "3k")
print(f"[INFO] 3kHz+15Hz watch（记录性）: {s3}, watchData={w3}")

perf1 = cli.call("scope_perf")
print("   scope_perf:", json.dumps(perf1.get("result", perf1))[:300])

# 收尾：清目标断开
cli.call("set_scope_targets", {"targets": []})
cli.call("set_watch_targets", {"targets": []})
cli.sock.shutdown(socket.SHUT_RDWR); cli.sock.close()
t0 = time.time()
while proc.poll() is None and time.time() - t0 < 8:
    time.sleep(0.1)
check("agent 干净退出", proc.poll() == 0, f"exit={proc.poll()}")

print()
print("ALL PASS" if fail == 0 else f"{fail} FAILED")
sys.exit(1 if fail else 0)
