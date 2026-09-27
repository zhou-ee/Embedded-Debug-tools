#!/usr/bin/env python3
"""
Embedded Debug Tools 一键编译与测试脚本
支持构建前端 CLion 插件、后端 Rust Agent，以及运行全套自动化测试。

用法：
    python build.py             # 构建全部（Agent + Plugin）
    python build.py --agent     # 仅构建后端 Rust Agent
    python build.py --plugin    # 仅构建前端 CLion 插件
    python build.py --test      # 运行前后端全套自动化测试
    python build.py --clean     # 清理构建产物
"""
import os
import sys
import shutil
import argparse
import subprocess

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
PLUGIN_DIR = os.path.join(ROOT_DIR, "plugin")
AGENT_DIR = os.path.join(ROOT_DIR, "agent")


def detect_java_home():
    """动态探测 Java 运行环境（优先复用环境变量或 CLion JBR）"""
    if os.getenv("JAVA_HOME") and os.path.isdir(os.getenv("JAVA_HOME")):
        return os.getenv("JAVA_HOME")

    candidates = [
        r"E:\Software\JetBrains IDE\CLion\jbr",
        r"C:\Program Files\JetBrains\CLion\jbr",
        r"C:\Program Files\Java\jdk-21",
        r"C:\Program Files\Java\jdk-17",
    ]
    for c in candidates:
        if os.path.isdir(c):
            return c
    return None


def ensure_env():
    """准备环境变量"""
    env = os.environ.copy()
    java_home = detect_java_home()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    return env


def build_agent(release=True):
    """编译 Rust Agent"""
    print("=" * 60)
    print(f"[*] 正在构建后端 Rust Agent (release={release})...")
    print("=" * 60)
    cmd = ["cargo", "build"]
    if release:
        cmd.append("--release")
    res = subprocess.run(cmd, cwd=AGENT_DIR, shell=False)
    if res.returncode != 0:
        print("[!] Rust Agent 构建失败！", file=sys.stderr)
        return False
    print("[+] Rust Agent 构建成功。")
    return True


def build_plugin():
    """编译 CLion 插件"""
    print("=" * 60)
    print("[*] 正在构建前端 CLion 插件 (gradlew buildPlugin)...")
    print("=" * 60)
    env = ensure_env()
    gradlew = os.path.join(PLUGIN_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
    if not os.path.exists(gradlew):
        print(f"[!] 找不到 Gradle Wrapper: {gradlew}", file=sys.stderr)
        return False

    res = subprocess.run([gradlew, "buildPlugin"], cwd=PLUGIN_DIR, env=env, shell=False)
    if res.returncode != 0:
        print("[!] CLion 插件构建失败！", file=sys.stderr)
        return False
    print("[+] CLion 插件构建成功。")
    return True


def test_agent():
    """运行 Rust Agent 工作区测试"""
    print("=" * 60)
    print("[*] 正在运行 Rust Agent 测试 (cargo test --workspace)...")
    print("=" * 60)
    res = subprocess.run(["cargo", "test", "--workspace"], cwd=AGENT_DIR, shell=False)
    if res.returncode != 0:
        print("[!] Rust Agent 测试失败！", file=sys.stderr)
        return False
    print("[+] Rust Agent 测试全部通过。")
    return True


def test_plugin():
    """运行 CLion 插件单元测试"""
    print("=" * 60)
    print("[*] 正在运行 CLion 插件测试 (gradlew test)...")
    print("=" * 60)
    env = ensure_env()
    gradlew = os.path.join(PLUGIN_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
    res = subprocess.run([gradlew, "test"], cwd=PLUGIN_DIR, env=env, shell=False)
    if res.returncode != 0:
        print("[!] CLion 插件测试失败！", file=sys.stderr)
        return False
    print("[+] CLion 插件测试全部通过。")
    return True


def clean():
    """清理构建产物"""
    print("[*] 正在清理构建缓存与临时文件...")
    # Clean Cargo
    subprocess.run(["cargo", "clean"], cwd=AGENT_DIR, shell=False)
    # Clean Gradle
    env = ensure_env()
    gradlew = os.path.join(PLUGIN_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
    if os.path.exists(gradlew):
        subprocess.run([gradlew, "clean"], cwd=PLUGIN_DIR, env=env, shell=False)
    # Remove release
    release_dir = os.path.join(ROOT_DIR, "release")
    if os.path.exists(release_dir):
        shutil.rmtree(release_dir)
    print("[+] 清理完成。")


def main():
    parser = argparse.ArgumentParser(description="Embedded Debug Tools 构建与测试工具")
    parser.add_argument("--agent", action="store_true", help="仅构建后端 Agent")
    parser.add_argument("--plugin", action="store_true", help="仅构建前端 Plugin")
    parser.add_argument("--test", action="store_true", help="运行前后端全部单元测试")
    parser.add_argument("--clean", action="store_true", help="清理所有构建产物")
    args = parser.parse_args()

    if args.clean:
        clean()
        return

    if args.test:
        agent_ok = test_agent()
        plugin_ok = test_plugin()
        if agent_ok and plugin_ok:
            print("\n[+] 全套测试通过 (100% PASS)！")
            sys.exit(0)
        else:
            print("\n[!] 存在失败的测试用例！", file=sys.stderr)
            sys.exit(1)

    if args.agent:
        sys.exit(0 if build_agent() else 1)
    if args.plugin:
        sys.exit(0 if build_plugin() else 1)

    # 默认：全部构建
    if not build_agent():
        sys.exit(1)
    if not build_plugin():
        sys.exit(1)
    print("\n[+] 全部组件构建成功！")


if __name__ == "__main__":
    main()
