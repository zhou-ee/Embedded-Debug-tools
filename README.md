<div align="center">

# Embedded Debug Tools for CLion

**High-speed, non-intrusive real-time memory monitoring, oscilloscope waveforms, and peripheral register live watch for embedded systems in JetBrains CLion.**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![CLion](https://img.shields.io/badge/CLion-2024.2%20%7C%202026.x-green.svg)](https://www.jetbrains.com/clion/)
[![Rust](https://img.shields.io/badge/Rust-1.75%2B-orange.svg)](https://www.rust-lang.org/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-purple.svg)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey.svg)]()
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

[**中文文档 (简体中文)**](README_CN.md) | [**Release Notes**](CHANGELOG.md) | [**Architecture Guide**](HANDOVER.md)

</div>

---

## 🚀 Key Features

- **⚡ High-Speed Live Variable Watch**:
  - Sample global, static, and nested struct variables in real time at **2Hz, 5Hz, 10Hz, or 15Hz** without halting the target MCU.
  - Seamless support for struct member chains (`motor.state.pos`), array indexing (`data[0].val`), and dynamic pointer dereferencing (`g_ptr->field`).
- **📈 Real-Time Software Oscilloscope**:
  - Up to **1,000Hz (1kHz)** high-frequency waveform capture via probe-rs / OpenOCD Tcl RPC.
  - Multiple channels, real-time panning/zooming, dual cursor measurement, and one-click CSV export.
- **🔍 CMSIS-SVD Peripheral Register Live Watch**:
  - Full peripheral tree view powered by a high-performance pure Kotlin SVD parser (<25ms parse time for full STM32 SVDs).
  - Bitfield visualization, automatic chip detection from CubeMX/CMake projects, selective per-register refresh, and inline register writing.
- **🛡️ Zero-Intrusion & Memory Safety**:
  - **Attach-Only OpenOCD mode**: Coexists peacefully with CLion's native GDB debugger without claiming exclusive USB device ownership.
  - 32-bit atomic word aligned memory access with hardware **Torn-Read filter** (eliminates IEEE-754 float glitches during DMA/ISR write contention).
  - Defensive low-address boundary validation (`addr >= 0x1000L`) preventing invalid offset reads.

---

## 🏛️ System Architecture

Embedded Debug Tools adopts a modular **Monorepo architecture** separating the IntelliJ Platform frontend from the high-performance Rust hardware engine:

```
                      +---------------------------------------+
                      |         JetBrains CLion IDE           |
                      |   (Embedded Debug Plugin - Kotlin)    |
                      +---------------------------------------+
                             |                     |
              Live Watch UI  |                     |  Registers & Scope
              Tree View / SVD|                     |  Plotting Engine
                             v                     v
                      +---------------------------------------+
                      |       AgentService (IPC Client)       |
                      +---------------------------------------+
                                         |
                                         | JSON-RPC over TCP
                                         | (localhost:44445)
                                         v
                      +---------------------------------------+
                      |    embedded-clion-agent (Rust Daemon) |
                      +---------------------------------------+
                             |                     |
               +-------------+                     +-------------+
               |                                                 |
               v                                                 v
  +--------------------------+                      +--------------------------+
  |    elf-info & monitor    |                      |        debug-core        |
  |  - DWARF Index (gimli)   |                      |  - OpenOCD Tcl RPC:6666  |
  |  - 1kHz Cadence Engine   |                      |  - probe-rs Native Driver|
  |  - Torn Read Filter      |                      |  - Sim Test Backend      |
  +--------------------------+                      +--------------------------+
               |                                                 |
               +-----------------------+-------------------------+
                                       |
                                       | SWD / JTAG Bus
                                       v
                      +---------------------------------------+
                      |      Target Microcontroller (MCU)     |
                      |   STM32 / Cortex-M0/M3/M4/M7/M33...   |
                      +---------------------------------------+
```

---

## 📦 Directory Structure

```
Embedded Debug tools/
├── plugin/               # CLion plugin frontend (Kotlin, IntelliJ Platform SDK)
│   ├── src/main/kotlin/  # ToolWindows, Actions, Tree View, SVD Engine, IPC client
│   └── src/test/kotlin/  # Automated test suite (110+ tests)
├── agent/                # High-performance Rust backend workspace
│   └── crates/
│       ├── embedded-clion-agent/ # Standalone JSON-RPC daemon binary
│       ├── monitor/              # High-speed sampling engine (ring buffer, 1kHz scope)
│       ├── elf-info/             # DWARF parser & member chain resolver (gimli/object)
│       ├── debug-core/           # OpenOCD Tcl RPC & probe-rs drivers
│       └── svd-info/             # CMSIS-SVD peripheral model
├── scripts/              # Validation, simulation, and hardware test scripts
├── build.py              # One-click build & test runner
├── package.py            # Standalone plugin distribution packager
├── CONTRIBUTING.md       # Contribution guidelines
├── CHANGELOG.md          # Version history & technical fixes
└── HANDOVER.md           # Deep architecture baseline documentation
```

---

## 🛠️ Quick Installation

### Option 1: Install from Prebuilt Standalone Plugin (Recommended)
1. Download `embedded-debug-plugin-V1.2.14-standalone.zip` from [Releases](../../releases).
2. In CLion, navigate to **Settings** (`Ctrl+Alt+S`) -> **Plugins** -> ⚙️ -> **Install Plugin from Disk...**.
3. Select the downloaded `.zip` file and restart CLion.
4. *Note: Standalone package bundles the native `embedded-clion-agent` executable—no external Rust installation needed!*

### Option 2: Build from Source
#### Prerequisites
- **JDK 17** or **JDK 21** (or CLion's bundled JBR: `<CLion_Installation>/jbr`)
- **Rust Stable 1.75+** (`cargo` on PATH)
- **Python 3.8+**

#### One-Click Build & Packaging
```bash
# Clone the repository
git clone https://github.com/embedded-tools/embedded-debug-tools.git
cd "embedded-debug-tools"

# Run full test suite (Rust agent + Kotlin plugin)
python build.py --test

# Build the complete standalone distribution package
python package.py
```
The output zip packages will be generated inside the `release/` folder.

---

## 💡 Usage Guide

### 1. Real-time Variable Watch (Live Watch)
1. Start an OpenOCD debug session in CLion as usual.
2. In the C/C++ editor, right-click on any global or static variable, struct, or pointer and choose:  
   **Embedded Monitor -> Add to Live Variable Watch**.
3. Open the **EmbeddedLiveWatch** tool window on the right tool window bar.
4. Select your preferred refresh rate (**2Hz, 5Hz, 10Hz, or 15Hz**).
5. Expand pointers or structs in real time; values refresh dynamically with color change highlighting.

### 2. High-Frequency Oscilloscope
1. Right-click any numerical variable or struct field and select:  
   **Embedded Monitor -> Add to Scope**.
2. Open the **EmbeddedScope** tool window at the bottom bar.
3. Choose the sampling rate (up to **1,000Hz**) and click **Start Sampling**.
4. Use mouse drag to pan, scroll wheel to zoom time/voltage scales, place dual measurement cursors, or export data to CSV for offline analysis.

### 3. Peripheral Register Live Watch
1. Open the **EmbeddedRegisters** tool window on the right tool window bar.
2. The plugin will automatically match and locate the corresponding CMSIS-SVD file based on your CMake/CubeMX chip definition.
3. Check the checkbox next to any register to start real-time polling (1Hz ~ 10Hz).
4. Click on a register to inspect individual bitfields and descriptions, or double-click value to write new bitfield values to target memory.

---

## 🔧 Configuration & Customization

Open **Settings / Preferences -> Tools -> Embedded Monitor**:

| Setting | Description | Default |
|---|---|---|
| **Backend Mode** | Debug connection backend (`OpenOCD` or `probe-rs`) | `OpenOCD` |
| **OpenOCD Tcl Host/Port** | Tcl RPC interface address and port | `127.0.0.1:6666` |
| **Default Watch Rate** | Default live watch sampling frequency (2, 5, 10, 15Hz) | `5 Hz` |
| **Custom Agent Binary** | Path to custom `embedded-clion-agent` (optional) | *Auto-detected* |
| **Custom SVD Directory** | Custom folder containing CMSIS-SVD chip definitions | *Auto-detected* |

---

## ❓ Frequently Asked Questions (FAQ)

<details>
<summary><b>Q: Does this plugin stop or interfere with target CPU execution?</b></summary>
<b>No.</b> Unlike traditional GDB memory reads that halt target execution or inject code breakpoints, this plugin utilizes hardware Debug Access Port (DAP) memory bus reads via OpenOCD Tcl RPC or probe-rs SWD background transfers while the core is running at full speed.
</details>

<details>
<summary><b>Q: Can I use this alongside my normal CLion GDB debugging?</b></summary>
<b>Yes!</b> By default, the plugin connects to OpenOCD's Tcl RPC port (6666) in <i>Attach-Only</i> mode. CLion's native debugger controls GDB while the plugin streams memory in parallel.
</details>

<details>
<summary><b>Q: Why is my live variable showing pointer members with '...'?</b></summary>
Expand the pointer node in the tree view. The plugin dynamically evaluates the runtime physical address of the pointer and registers second-level targets with the underlying agent to sample pointee fields.
</details>

<details>
<summary><b>Q: Which target architectures are supported?</b></summary>
All ARM Cortex-M processors (Cortex-M0, M0+, M3, M4, M7, M23, M33) supported by CMSIS-DAP, ST-Link, J-Link, and OpenOCD.
</details>

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).

---

<div align="center">
Made with ❤️ for the Embedded Systems and CLion developer community.
</div>
