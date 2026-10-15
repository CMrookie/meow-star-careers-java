#!/usr/bin/env bash
# 构建可执行 jar：target/meow-star-careers.jar
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/mvn.sh" -DskipTests package "$@"
