#!/usr/bin/env python3
"""
Embedded Debug Tools 官方发布打包脚本
自动编译 Rust Agent，构建前端 CLion 插件，并输出包含 Standalone（内置 Agent）的完整分发包。
"""
import os
import sys
import shutil
import zipfile
import hashlib
import subprocess

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
PLUGIN_DIR = os.path.join(ROOT_DIR, "plugin")
AGENT_DIR = os.path.join(ROOT_DIR, "agent")
RELEASE_DIR = os.path.join(ROOT_DIR, "release")


def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()


def detect_java_home():
    if os.getenv("JAVA_HOME") and os.path.isdir(os.getenv("JAVA_HOME")):
        return os.getenv("JAVA_HOME")
    candidates = [
        r"C:\Program Files\JetBrains\CLion\jbr",
        r"C:\Program Files\Java\jdk-21",
        r"C:\Program Files\Java\jdk-17",
    ]
    for c in candidates:
        if os.path.isdir(c):
            return c
    return None


def get_plugin_version():
    props = os.path.join(PLUGIN_DIR, "gradle.properties")
    if os.path.exists(props):
        with open(props, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("version=") or line.startswith("pluginVersion="):
                    return line.split("=")[1].strip()
    print("[!] 无法从 plugin/gradle.properties 读取版本号（version=/pluginVersion=）", file=sys.stderr)
    sys.exit(1)


def package():
    os.makedirs(RELEASE_DIR, exist_ok=True)
    version = get_plugin_version()
    print(f"[*] 开始打包 Embedded Debug Tools 发布版本: {version}")

    # 1. 编译最新的 Rust Agent
    print("[*] 正在编译最新 release 版 embedded-clion-agent...")
    res = subprocess.run(
        ["cargo", "build", "--release", "--locked", "-p", "embedded-clion-agent"],
        cwd=AGENT_DIR,
        shell=False,
    )
    if res.returncode != 0:
        print("[!] Cargo 编译 Agent 失败！", file=sys.stderr)
        sys.exit(1)

    agent_exe_name = "embedded-clion-agent.exe" if os.name == "nt" else "embedded-clion-agent"
    agent_target = os.path.join(AGENT_DIR, "target", "release", agent_exe_name)
    agent_plugin_bin = os.path.join(PLUGIN_DIR, "bin", agent_exe_name)

    if not os.path.exists(agent_target):
        print(f"[!] 找不到 Agent 可执行文件: {agent_target}", file=sys.stderr)
        sys.exit(1)

    # 同步复制到 plugin/bin/
    os.makedirs(os.path.dirname(agent_plugin_bin), exist_ok=True)
    shutil.copy2(agent_target, agent_plugin_bin)
    print(f"[+] Agent 同步至: {agent_plugin_bin}")

    # 2. 构建前端 CLion 插件
    print("[*] 正在执行 Gradle 构建插件 (gradlew buildPlugin)...")
    env = os.environ.copy()
    java_home = detect_java_home()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")

    gradlew = os.path.join(PLUGIN_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
    # PKG_GRADLE_OFFLINE=1：本地全量缓存已就绪时跳过一切依赖网络校验，
    # 规避国内网络对 JVM TLS 连接的间歇性 RST 导致配置期无限挂死（CI 不受影响）
    gradle_args = [gradlew, "buildPlugin"]
    if os.getenv("PKG_GRADLE_OFFLINE") == "1":
        gradle_args.append("--offline")
    res = subprocess.run(gradle_args, cwd=PLUGIN_DIR, env=env, shell=False)
    if res.returncode != 0:
        print("[!] Gradle 构建插件失败！", file=sys.stderr)
        sys.exit(1)

    # 3. 寻找 Gradle 生成的插件 zip
    dist_dir = os.path.join(PLUGIN_DIR, "build", "distributions")
    base_zip = None
    if os.path.exists(dist_dir):
        for f in os.listdir(dist_dir):
            if f.endswith(".zip") and version in f:
                base_zip = os.path.join(dist_dir, f)
                break

    if not base_zip or not os.path.exists(base_zip):
        print(f"[!] 找不到基础插件 zip 包 (在 {dist_dir} 中)", file=sys.stderr)
        sys.exit(1)

    print(f"[+] 找到基础插件包: {base_zip}")

    target_base_zip = os.path.join(RELEASE_DIR, f"embedded-debug-plugin-{version}.zip")
    target_standalone_zip = os.path.join(RELEASE_DIR, f"embedded-debug-plugin-{version}-standalone.zip")

    shutil.copy2(base_zip, target_base_zip)

    # 4. 生成包含 Agent 的 Standalone Zip
    print("[*] 正在组装 Standalone 便携发布包（内置 Agent）...")
    with zipfile.ZipFile(base_zip, "r") as bz:
        base_entries = {info.filename: (info, bz.read(info.filename)) for info in bz.infolist()}

    with zipfile.ZipFile(target_standalone_zip, "w", compression=zipfile.ZIP_DEFLATED) as sz:
        # 4.1 根目录
        info_root = zipfile.ZipInfo("embedded-debug-plugin/")
        info_root.external_attr = 0o755 << 16 | 0x10
        sz.writestr(info_root, "")

        # 4.2 bin 目录
        info_bin = zipfile.ZipInfo("embedded-debug-plugin/bin/")
        info_bin.external_attr = 0o755 << 16 | 0x10
        sz.writestr(info_bin, "")

        # 4.3 写入 Agent 可执行文件（赋予 0755 可执行权限）
        with open(agent_target, "rb") as f:
            agent_data = f.read()
        info_exe = zipfile.ZipInfo(f"embedded-debug-plugin/bin/{agent_exe_name}")
        info_exe.external_attr = 0o755 << 16
        sz.writestr(info_exe, agent_data)
        print(f"  [+] 成功注入 Agent: {len(agent_data):,} 字节")

        # 4.4 写入所有原生 Jar/库条目
        for name, (info, data) in sorted(base_entries.items()):
            if name == "embedded-debug-plugin/":
                continue
            clean_name = name.replace("\\", "/")
            info.filename = clean_name
            sz.writestr(info, data)

    print("\n" + "=" * 70)
    print("发布打包产物总览:")
    print("=" * 70)
    for item in [target_base_zip, target_standalone_zip]:
        if os.path.exists(item):
            size = os.path.getsize(item)
            h = sha256_file(item)
            print(f"  {os.path.basename(item):45} {size:>10,} 字节  SHA256: {h[:16]}...")
    print("=" * 70)
    print("[+] 打包圆满完成！产物存放于 release/ 目录。")


if __name__ == "__main__":
    package()
