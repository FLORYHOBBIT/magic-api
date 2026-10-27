#!/usr/bin/env bash
# ============================================================
#  全量构建脚本 (Linux / macOS)
#  将 JAVA_HOME 指向 JDK17 再调用 Maven Wrapper 构建。
#  本工程含 jakarta / springdoc 模块(source/target=17)，
#  必须用 JDK17 才能全量编译；核心模块向下兼容 source=8。
#
#  用法:
#    ./build.sh              等价于 clean verify
#    ./build.sh clean package
#    ./build.sh -pl magic-api test
#
#  可用环境变量 JDK17_HOME 指定 JDK17 路径。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# 优先使用显式 JDK17_HOME；否则尝试常见位置 / 系统默认
if [ -n "${JDK17_HOME:-}" ]; then
  export JAVA_HOME="$JDK17_HOME"
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "[WARN] 未设置 JDK17_HOME/JAVA_HOME，将使用系统默认 JDK（需为 17+）。"
fi

echo "[INFO] 使用 JAVA_HOME=${JAVA_HOME:-<system-default>}"

if [ "$#" -eq 0 ]; then
  ./mvnw -B --no-transfer-progress clean verify
else
  ./mvnw -B --no-transfer-progress "$@"
fi
