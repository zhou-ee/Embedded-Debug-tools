"""真机证明:同名子树混淆根因。
[2] g_chassis_ctx_ptr.seq(动态地址)应持续递增 —— 实时监视里刷新的就是它;
[3] g_chassis_ptr._ctx.seq = s_instance+56+104(用户误加通道的地址)恒 0 —— 固件从不写;
[4] 同批姊妹通道 data[0].vx 持续变化 —— 采样/解码/渲染管线正常;
[5] 用正确地址建 scope 通道收 scopeData —— 阶梯递增,产品级闭环。
"""
import json, socket, struct, subprocess, sys, threading, time

AGENT = r"E:\Software\Develop\Embeded\Clion_plugin\Embedded Debug tools\agent\target\release\embedded-clion-agent.exe"
ELF = r"E:\Software\Develop\Embeded\Pack\g4_tool_test\cmake-build-debug-stm32\g4_tool_test.elf"

CTX_OFF = 56    # wl_chassis_t::_ctx 偏移(DWARF)
SEQ_OFF = 104   # wl_chassis_ctx_t::seq 偏移(DWARF)
DATA0_OFF = 48  # wl_chassis_ctx_t::data[0] 偏移(seq104 - sensors16 - 2*20)

class Client:
    def __init__(self, port, token):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock.sendall((json.dumps({"id": 0, "method": "hello", "params": {"token": token}}) + "\n").encode())
        self.pending, self.events, self.rid = {}, [], 1
        threading.Thread(target=self._reader, daemon=True).start()
        time.sleep(0.3)

    def _reader(self):
        f = self.sock.makefile("r", encoding="utf-8")
        for line in f:
            line = line.strip()
            if not line:
                continue
            m = json.loads(line)
            if "event" in m:
                self.events.append(m)
            elif "id" in m:
                self.pending[m["id"]] = m

    def call(self, method, params=None, timeout=15):
        rid = self.rid; self.rid += 1
        self.sock.sendall((json.dumps({"id": rid, "method": method, "params": params or {}}) + "\n").encode())
        t0 = time.time()
        while rid not in self.pending:
            if time.time() - t0 > timeout:
                raise TimeoutError(method)
            time.sleep(0.02)
        return self.pending.pop(rid)

proc = subprocess.Popen([AGENT, "--host", "127.0.0.1", "--port", "0"],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
ready = proc.stdout.readline().strip()
print("READY:", ready[:70])
fields = dict(kv.split("=", 1) for kv in ready.split()[1:] if "=" in kv)
cli = Client(int(fields["port"]), fields["token"])

r = cli.call("connect", {"backend": "probe-rs", "target": "STM32G431CBTx"}, timeout=30)
print("connect ok:", r.get("ok"), str(r.get("error", ""))[:80])
if not r.get("ok"):
    sys.exit(1)
# 等待引擎真正就绪(connect 应答成功≠attach 完成),轮询 status 最多 10s
for _ in range(50):
    st = str(cli.call("status").get("result", {}))
    if "disconnected" not in st.lower():
        break
    time.sleep(0.2)
print("engine status:", st[:40])
r = cli.call("elf_load", {"path": ELF}, timeout=60)
print("elf_load ok:", r.get("ok"))
time.sleep(0.8)  # 引擎连接刚建立,首次读前稍作等待

def rd(addr, size=4, signed=False):
    resp = cli.call("read_mem", {"addr": addr, "size": size})
    if not resp.get("ok"):
        time.sleep(1.0)  # 引擎可能在重连,稍候重试一次
        resp = cli.call("read_mem", {"addr": addr, "size": size})
    if not resp.get("ok"):
        raise RuntimeError(f"read_mem 0x{addr:08X} 失败: {str(resp.get('error'))[:100]}")
    b = bytes(resp["result"])
    return int.from_bytes(b, "little", signed=signed)

def rd_f32(addr):
    b = bytes(cli.call("read_mem", {"addr": addr, "size": 4})["result"])
    return struct.unpack("<f", b)[0]

# [1] 两个指针变量与各自指向
for name in ("g_chassis_ctx_ptr", "g_chassis_ptr"):
    node = cli.call("elf_resolve", {"expr": name}, timeout=15).get("result")
    var = node["address"]
    print(f"[1] {name} 变量地址 = 0x{var:08X}, 当前指向 = 0x{rd(var):08X}")

# [2] 刷新中的 seq:g_chassis_ctx_ptr 指向 + SEQ_OFF
ctx_ptr_var = cli.call("elf_resolve", {"expr": "g_chassis_ctx_ptr"}, timeout=15)["result"]["address"]
target = rd(ctx_ptr_var)
seq_addr = target + SEQ_OFF
v1, v2, v3 = rd(seq_addr, signed=True), 0, 0
time.sleep(0.4); v2 = rd(seq_addr, signed=True)
time.sleep(0.4); v3 = rd(seq_addr, signed=True)
ok2 = v3 > v1
print(f"[2] g_chassis_ctx_ptr.seq @0x{seq_addr:08X}: {v1} -> {v2} -> {v3}  "
      f"{'[递增 ✓ 实时监视刷新的就是它]' if ok2 else '[未递增 ✗]'}")

# [3] 用户误加的通道地址:s_instance + CTX_OFF + SEQ_OFF(固件从不写)
s_instance = rd(cli.call("elf_resolve", {"expr": "g_chassis_ptr"}, timeout=15)["result"]["address"])
wrong = s_instance + CTX_OFF + SEQ_OFF
u1 = rd(wrong, signed=True)
time.sleep(0.5)
u2 = rd(wrong, signed=True)
ok3 = (u1 == 0 and u2 == 0)
print(f"[3] g_chassis_ptr._ctx.seq @0x{wrong:08X}: {u1} -> {u2}  "
      f"{'[恒 0 ✓ 用户通道显示的就是真实值]' if ok3 else '[非 0,需复查 ✗]'}")

# [4] 姊妹通道反证管线正常
vx_addr = s_instance + CTX_OFF + DATA0_OFF
x1, x2 = rd_f32(vx_addr), 0.0
time.sleep(0.4)
x2 = rd_f32(vx_addr)
print(f"[4] g_chassis_ptr._ctx.data[0].vx @0x{vx_addr:08X}: {x1:.4f} -> {x2:.4f}  "
      f"{'[变化 ✓ 同批通道管线正常]' if x1 != x2 else '[未变(提示)]'}")

# [5] 产品级闭环:正确地址建 scope 通道,收 scopeData 应阶梯递增
#     schema(AgentModels.kt):每帧 {"t": epoch秒, "values": {"0x%08x": [原始字节]}},小写十六进制键
cli.call("set_scope_targets", {"targets": [{"addr": seq_addr, "size": 4}]})
cli.call("set_scope_freq", {"freq": 100})
t0, samples, key = time.time(), [], f"0x{seq_addr:08x}"
while time.time() - t0 < 2.5:
    time.sleep(0.05)
    for e in cli.events:
        if e["data"].get("kind") == "scopeData":
            for frame in e["data"].get("samples", []):
                raw = frame.get("values", {}).get(key)
                if raw:
                    samples.append(int.from_bytes(bytes(raw), "little", signed=True))
    cli.events.clear()
ok5 = len(samples) >= 50 and samples[-1] > samples[0]
print(f"[5] scopeData @0x{seq_addr:08X}: {len(samples)} 样本, 首值 {samples[0] if samples else '-'} -> 末值 {samples[-1] if samples else '-'}  "
      f"{'[阶梯递增 ✓ 示波通道正常]' if ok5 else '[异常 ✗]'}")
cli.call("set_scope_targets", {"targets": []})

verdict = ok2 and ok3 and ok5
print("PROOF:", "ALL PASS" if verdict else "CHECK ABOVE")
cli.sock.shutdown(socket.SHUT_RDWR); cli.sock.close()
proc.wait(timeout=8)
sys.exit(0 if verdict else 1)
