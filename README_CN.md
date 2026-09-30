<div align="center">

# Embedded Debug Tools for CLion

**专为 JetBrains CLion 打造的嵌入式高速无干扰实时变量监视（Live Variable Watch）、实时示波器（Oscilloscope）与外设寄存器实时监视（Register Live Watch）调试套件。**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CLion](https://img.shields.io/badge/CLion-2026.2%2B-green.svg)](https://www.jetbrains.com/clion/)
[![Rust](https://img.shields.io/badge/Rust-Stable-orange.svg)](https://www.rust-lang.org/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%20(Linux%20%7C%20macOS%EF%BC%9A%E9%9C%80%E8%87%AA%E8%A1%8C%E7%BC%96%E8%AF%91%20Agent)-lightgrey.svg)]()

[**English Documentation**](README.md) | [**版本演进日志 (CHANGELOG)**](CHANGELOG.md) | [**深度架构与交接文档 (HANDOVER)**](HANDOVER.md)

</div>

---

## 🚀 核心特性总览

- **⚡ 高速无干扰实时变量监视（Live Variable Watch）**：
  - 支持在目标 MCU 运行态（无需断点打断）以 **2Hz、5Hz、10Hz、15Hz** 高速采样全局变量与静态变量；
  - 完美支持多层嵌套结构体成员链（如 `g_motor.state.position`）、数组下标（如 `data[0].val`）以及运行态动态指针展开（如 `g_chassis_ptr->speed`）；
  - 数值变动实时高亮展示，支持 HEX/十进制/二进制切换与双击实时修改变量值。
- **📈 实时数字示波器（Oscilloscope）**：
  - 依托底层高速传输通道，采样率最高可达 **1,000Hz (1kHz)**；
  - 支持多通道波形同屏渲染、视窗水平/垂直无级缩放与拖拽、时间/幅值双光标差值测量；
  - 支持一键导出 CSV 离线原始数据集，方便 MATLAB、Python 深入分析。
- **🧠 CLion 原生表达式求值（Evaluation Watch）**：
  - 复杂 C 表达式（强转、函数调用如 `pyro::wl_chassis_t::instance()`）在断点期经平台 API **复用 IDE 自带的 GDB** 进程内求值——不新起 GDB、不占用 3333 端口；
  - 指针结果**自动升级为固定地址实时监视**：求值地址在运行态按常规内存读取链路持续采样，下次断点自动重求值刷新地址。
- **🔍 CMSIS-SVD 外设寄存器实时监视（Register Live Watch）**：
  - 高性能纯 Kotlin SVD 解析引擎，实测解析 STM32G4 完整外设定义（~2MB）耗时 < 25ms；
  - 智能外设树展示，支持每个寄存器单独勾选实时采样复选框（节约 SWD 总线带宽）；
  - 寄存器位段（Bitfield）实时切片展示、枚举描述映射，支持寄存器与位段直接写值；
  - 根据工程目录、CubeMX `.ioc` 文件及 CMake 定义智能自动匹配芯片 SVD 文件，开箱即用。
- **🛡️ 零干扰协同与高可靠内存安全**：
  - **OpenOCD 协同架构（Attach-Only）**：通过 OpenOCD Tcl RPC（默认端口 6666）与 CLion 原生 GDB 调试会话和谐共存，不抢占物理硬件探针所有权；
  - **硬件级字节撕裂（Torn Read）防护**：32 位原子字对齐读取，在采集流中自动重构并发写竞争导致的 IEEE-754 浮点毛刺；
  - **底层绝对地址边界校验**：严格拦截低于 `0x1000L` 的非法相对偏移，彻底杜绝野指针崩溃。

---

## 🏛️ 系统架构设计

本项目采用前后端分离的 **Monorepo** 架构：

```
                      ┌───────────────────────────────────────┐
                      │          JetBrains CLion IDE          │
                      │   (Embedded Debug Plugin - Kotlin)    │
                      └───────────────────────────────────────┘
                             │                     │
              实时变量监视 UI│                     │外设寄存器 & 示波器
              树形结构 / SVD │                     │波形渲染引擎
                             ▼                     ▼
                      ┌───────────────────────────────────────┐
                      │       AgentService (IPC Client)       │
                      └───────────────────────────────────────┘
                                         │
                                         │ 本地 TCP JSON-RPC
                                         │ (127.0.0.1, 随机端口)
                                         ▼
                      ┌───────────────────────────────────────┐
                      │    embedded-clion-agent (Rust Daemon) │
                      └───────────────────────────────────────┘
                             │                     │
               ┌─────────────┴┐                   ┌┴────────────┐
               │                                                │
               ▼                                                ▼
  ┌──────────────────────────┐                    ┌──────────────────────────┐
  │    elf-info & monitor    │                    │        debug-core        │
  │  - DWARF 符号表索引(gimli)│                    │  - OpenOCD Tcl RPC:6666  │
  │  - 1kHz 节拍调度采样引擎  │                    │  - probe-rs 底层探针驱动 │
  │  - 字节撕裂毛刺过滤器    │                    │  - Sim 仿真测试后端      │
  └──────────────────────────┘                    └──────────────────────────┘
               │                                                │
               └───────────────────────┬────────────────────────┘
                                       │
                                       │ SWD / JTAG 硬件物理总线
                                       ▼
                      ┌───────────────────────────────────────┐
                      │           目标单片机 (Target MCU)     │
                      │   STM32 / Cortex-M0/M3/M4/M7/M33...   │
                      └───────────────────────────────────────┘
```

---

## 📦 仓库目录组织

```
Embedded Debug tools/
├── plugin/               # 前端 CLion 插件工程 (基于 Kotlin / IntelliJ SDK, V1.2.25)
│   ├── src/main/kotlin/  # 变量监视、示波器、SVD 寄存器 UI、IPC 交互实现
│   └── src/test/kotlin/  # 前端自动化测试集 (覆盖 110+ 项单测)
├── agent/                # 后端 Rust Agent 工作空间 (v1.2.25)
│   └── crates/
│       ├── embedded-clion-agent/ # Sidecar TCP JSON-RPC 守护进程
│       ├── monitor/              # 高速采样引擎 (环形缓冲、1kHz 示波、节拍调度)
│       ├── elf-info/             # DWARF 符号与嵌套结构体成员链解析
│       ├── debug-core/           # OpenOCD Tcl 协议与 probe-rs 底层驱动
│       └── svd-info/             # CMSIS-SVD 寄存器模型
├── scripts/              # 硬件测试、仿真与频偏验证脚本
├── build.py              # 一键编译与全套测试脚本
├── package.py            # 官方发布与打包脚本 (产出 Standalone 独立安装包)
├── CONTRIBUTING.md       # 开源贡献规范
├── CHANGELOG.md          # 详细版本更新日志
└── HANDOVER.md           # 架构基线与核心缺陷修复交接文档
```

---

## 🛠️ 安装与快速上手

### 方式一：直接安装官方预编译发布包（推荐）
1. 在 GitHub [Releases](../../releases) 页面下载最新发布的 `embedded-debug-plugin-<版本号>-standalone.zip`；
2. 打开 CLion，进入设置 **Settings** (`Ctrl+Alt+S`) -> **Plugins** -> 点击齿轮 ⚙️ -> **Install Plugin from Disk...**；
3. 选择下载好的 `.zip` 文件，安装后重启 CLion；
4. *说明：Standalone 发布包已内置编译好的原生 Rust Agent，使用者无需额外安装任何 Rust 编译环境！当前内置 Agent 二进制仅支持 Windows，Linux/macOS 用户请通过方式二自行编译 Agent。*

### 方式二：从源码编译与打包
#### 依赖准备
- **JDK 17** 或 **JDK 21**（或直接使用 CLion 自带的 `jbr` 目录）；
- **Rust Stable**（最新稳定版工具链，确保 `cargo` 在 PATH 中）；
- **Python 3.8+**。

#### 一键构建命令
```bash
# 克隆仓库
git clone https://github.com/embedded-tools/embedded-debug-tools.git
cd "embedded-debug-tools"

# 运行前后端全套自动化测试
python build.py --test

# 一键编译并打包 Standalone 发布压缩包
python package.py
```
打包成功后，可在 `release/` 目录下找到生成的插件压缩包。

---

## 💡 使用指南

### 1. 实时变量监视（Live Watch）
1. 在 CLion 中如常启动你的 OpenOCD 嵌入式调试会话；
2. 在 C/C++ 代码编辑器中，鼠标选中或右键任意全局/静态变量、结构体或指针：  
   选择 **Embedded Monitor -> 添加到实时变量监视**；
3. 在右侧边栏展开 **EmbeddedLiveWatch** 窗口；
4. 顶部下拉框可随心切换 **2Hz / 5Hz / 10Hz / 15Hz** 刷新率；
5. 在树形视图中自由展开结构体与指针，数值变化时带醒目背景变色提醒。

### 2. 实时示波器（Oscilloscope）
1. 在代码中右键选中任何数值型变量：选择 **Embedded Monitor -> 添加到示波器**；
2. 展开底部边栏的 **EmbeddedScope** 窗口；
3. 选择通道采样频率（最高支持 **1000Hz**），点击 **开始采样**；
4. 鼠标滚轮可无级缩放时间轴与幅值，鼠标拖拽可平移波形，支持双光标差值测量与 CSV 数据导出。

### 3. 外设寄存器实时监视（Embedded Registers）
1. 展开右侧边栏的 **EmbeddedRegisters** 窗口；
2. 插件将自动匹配工程关联的 CMSIS-SVD 芯片外设文件；
3. 勾选感兴趣的寄存器复选框即可启动实时轮询（1Hz~10Hz）；
4. 单击寄存器可查看下方详细位段（Bitfield）切片与枚举说明，双击可直接修改寄存器值。

---

## 🔧 常见问题 (FAQ)

<details>
<summary><b>Q: 插件读取变量会中断单片机的实时运行吗？</b></summary>
<b>绝对不会。</b> 插件不使用任何断点或暂停注入代码，而是利用 ARM Cortex-M 硬件调试访问端口（DAP）在 CPU 全速运行态下后台访问内存总线（AHB/AXI），对电机控制、通信协议等强实时系统完全无干扰。
</details>

<details>
<summary><b>Q: 插件可以与 CLion 原生 GDB 调试会话同时运行吗？</b></summary>
<b>完全支持。</b> 插件默认采用 <i>Attach-Only</i> 模式连接 OpenOCD 的 Tcl RPC 端口（默认 6666），CLion 原生 GDB 负责单步调试，两者完全独立并行。
</details>

<details>
<summary><b>Q: 支持哪些单片机与调试探针？</b></summary>
支持所有 ARM Cortex-M0/M0+/M3/M4/M7/M23/M33 内核的芯片（如 STM32、GD32、NXP 等），调试探针支持 DAP-Link / CMSIS-DAP、ST-Link、J-Link 等。
</details>

---

## 📄 开源许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
