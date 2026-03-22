#!/bin/bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$PROJECT_ROOT/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

info() {
    printf '\033[0;36m[STEP] %s\033[0m\n' "$1"
}

ok() {
    printf '\033[0;32m[OK] %s\033[0m\n' "$1"
}

warn() {
    printf '\033[1;33m[WARN] %s\033[0m\n' "$1"
}

stop_process() {
    local name="$1"
    local pid_file="$2"

    if [[ ! -f "$pid_file" ]]; then
        warn "$name PID file not found, skipping."
        return
    fi

    local pid
    pid="$(head -n 1 "$pid_file" 2>/dev/null || true)"
    
    if [[ -z "$pid" ]]; then
        warn "$name PID is empty, skipping."
        rm -f "$pid_file"
        return
    fi

    if kill -0 "$pid" >/dev/null 2>&1; then
        kill "$pid" >/dev/null 2>&1
        ok "$name process (PID: $pid) stopped."
    else
        warn "$name process (PID: $pid) is not running."
    fi

    rm -f "$pid_file"
}

info "停止开发环境"

stop_process "Frontend" "$FRONTEND_PID_FILE"
stop_process "Backend" "$BACKEND_PID_FILE"

echo ""
ok "所有相关进程已停止。"
echo ""
