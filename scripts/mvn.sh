#!/usr/bin/env bash
# 仓库内置 Maven 包装脚本（无需系统安装 maven；依赖下载到 .m2repo/）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/usr/local/Cellar/openjdk/27/libexec/openjdk.jdk/Contents/Home}"
MVN="$ROOT/.tools/apache-maven-3.9.9/bin/mvn"
if [ ! -x "$MVN" ]; then
  echo "缺少本地 Maven：$MVN" >&2
  echo "安装：mkdir -p .tools && cd .tools && curl -fsSL -o m.tgz https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz && tar xzf m.tgz && rm m.tgz" >&2
  exit 1
fi
exec "$MVN" -B -Dmaven.repo.local="$ROOT/.m2repo" "$@"
