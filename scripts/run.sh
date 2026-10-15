#!/usr/bin/env bash
# 启动服务（默认 127.0.0.1:8080，配置来自环境变量 / .env）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/usr/local/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home}"
[ -f target/meow-star-careers.jar ] || "$ROOT/scripts/build.sh"
exec "$JAVA_HOME/bin/java" -jar target/meow-star-careers.jar "$@"
