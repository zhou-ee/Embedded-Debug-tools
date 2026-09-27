# Contributing to Embedded Debug Tools

Thank you for your interest in contributing to **Embedded Debug Tools**! This project provides high-speed, non-intrusive real-time memory monitoring, oscilloscope waveforms, and peripheral register live watch for embedded systems directly inside JetBrains CLion.

---

## 1. Project Overview & Architecture

This repository is maintained as a **Monorepo** consisting of two tightly-integrated subprojects:

```
Embedded Debug tools/
├── plugin/               # Frontend JetBrains CLion plugin (Kotlin / IntelliJ Platform SDK)
│   ├── src/main/kotlin/  # ToolWindows, Actions, Tree View, SVD Engine, IPC client
│   └── src/test/kotlin/  # Automated unit and UI tests (110+ tests)
├── agent/                # High-performance Rust backend agent (Sidecar process)
│   └── crates/
│       ├── embedded-clion-agent/ # JSON-RPC TCP daemon
│       ├── monitor/              # High-speed sampling engine (ring buffer, 1kHz scope, watch)
│       ├── elf-info/             # DWARF parser & member chain resolver (gimli/object)
│       ├── debug-core/           # OpenOCD Tcl RPC & probe-rs drivers
│       └── svd-info/             # CMSIS-SVD peripheral model
├── scripts/              # Helper and simulation scripts
├── build.py              # One-click build & test runner
└── package.py            # Release packaging script
```

---

## 2. Development Prerequisites

To develop, build, and test this project, you need:

1. **Rust Toolchain**:
   - Rust 1.75+ (stable)
   - `cargo` available on `PATH`
2. **Java / JVM Environment**:
   - JDK 17 or JDK 21 (or JetBrains Runtime `jbr`)
   - Recommended: set `JAVA_HOME` to your JDK or CLion JBR path (e.g. `C:\Program Files\JetBrains\CLion\jbr`)
3. **Python**:
   - Python 3.8+ (for `build.py` and `package.py`)
4. **JetBrains CLion**:
   - CLion 2024.2+ or 2026.x
5. **Hardware Debugger (Optional, for physical hardware testing)**:
   - CMSIS-DAP / DAP-Link / ST-Link / J-Link
   - OpenOCD 0.12+ (configured with Tcl RPC enabled on port 6666)

---

## 3. Quick Start & Building

### 3.1 One-Click Build with `build.py`

From the repository root:

```bash
# Build both Rust Agent and CLion Plugin
python build.py

# Build only the Rust backend Agent
python build.py --agent

# Build only the Kotlin CLion Plugin
python build.py --plugin

# Run all unit and integration tests across both subprojects
python build.py --test

# Clean build artifacts
python build.py --clean
```

### 3.2 Packaging Standalone Plugin Distribution

```bash
python package.py
```
This automatically compiles the Rust Agent in release mode, embeds the binary into `plugin/bin/`, runs `gradlew buildPlugin`, and outputs standalone distribution zip files into `release/`.

---

## 4. Subproject Workflows

### 4.1 Frontend Plugin (`plugin/`)

- **Run Tests**:
  ```bash
  cd plugin
  ./gradlew test         # On Linux/macOS
  gradlew.bat test       # On Windows
  ```
- **Build Plugin**:
  ```bash
  ./gradlew buildPlugin
  ```
- **Run in Development CLion Instance**:
  ```bash
  ./gradlew runIde
  ```

### 4.2 Backend Rust Agent (`agent/`)

- **Run All Workspace Tests**:
  ```bash
  cd agent
  cargo test --workspace
  ```
- **Build Release Binary**:
  ```bash
  cargo build --release -p embedded-clion-agent
  ```
- **Run Simulation Smoke Test**:
  ```bash
  python crates/embedded-clion-agent/smoke_test.py
  ```

---

## 5. Coding Standards

### 5.1 Kotlin & IntelliJ SDK Guidelines
- **UI Responsiveness & Thread Safety**: Never perform blocking I/O, heavy parsing, or socket communication on the Event Dispatch Thread (EDT). Use `ApplicationManager.getApplication().executeOnPooledThread` or Kotlin coroutines.
- **Tree Rendering**: Use reactive listeners (`nodeChanged` or `nodeStructureChanged`) rather than reloading entire tree models to prevent visual flicker.
- **Defensive Address Boundaries**: Never send uninitialized struct member offsets (`< 0x1000L`) to hardware memory sampling engines.

### 5.2 Rust Guidelines
- **Zero Panic in Hot Loops**: The sampling loop in `monitor` must never panic. Use `Result` and propagate errors gracefully.
- **Torn Read Prevention**: Always respect 32-bit atomic word boundaries when reading Cortex-M target memory.
- **Cross-Platform Compatibility**: Use `#[cfg(windows)]` and `#[cfg(unix)]` appropriately for OS-specific features like high-resolution timers (`timeBeginPeriod`).

---

## 6. Commit Message Guidelines

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

- `feat(scope)`: A new feature (e.g., `feat(watch): add 15Hz sampling rate option`)
- `fix(scope)`: A bug fix (e.g., `fix(dwarf): shift array struct member offsets recursively`)
- `docs(scope)`: Documentation changes
- `refactor(scope)`: Code refactoring without changing functionality
- `test(scope)`: Adding or modifying test cases
- `chore(scope)`: Tooling, dependency, or packaging updates

---

## 7. Submitting a Pull Request

1. Fork the repository and create your feature branch: `git checkout -b feat/my-new-feature`
2. Ensure all tests pass: `python build.py --test`
3. Commit your changes with clear, descriptive commit messages.
4. Push to your fork: `git push origin feat/my-new-feature`
5. Open a Pull Request targeting the `dev` branch (or `main` branch).
6. Provide a concise explanation of the problem solved, design choices, and verification results.
