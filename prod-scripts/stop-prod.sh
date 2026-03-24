#!/bin/bash

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$PROJECT_ROOT/.run/prod-backend.pid"

info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

ok() {
    echo -e "${GREEN}[OK]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

is_pid_running() {
    local pid="$1"
    kill -0 "$pid" >/dev/null 2>&1
}

if [[ ! -f "$PID_FILE" ]]; then
    warn "未找到生产后端 PID 文件，无需停止"
    exit 0
fi

PID="$(head -n 1 "$PID_FILE" 2>/dev/null || true)"
rm -f "$PID_FILE"

if [[ -z "${PID:-}" ]]; then
    warn "PID 文件为空，已清理"
    exit 0
fi

if ! is_pid_running "$PID"; then
    warn "生产后端进程不在运行，PID=$PID"
    exit 0
fi

info "停止生产后端，PID=$PID"
kill "$PID" >/dev/null 2>&1 || true

for _ in {1..30}; do
    if ! is_pid_running "$PID"; then
        ok "生产后端已停止"
        exit 0
    fi
    sleep 1
done

warn "进程未在 30 秒内退出，执行强制停止"
kill -9 "$PID" >/dev/null 2>&1 || true
ok "生产后端已强制停止"
