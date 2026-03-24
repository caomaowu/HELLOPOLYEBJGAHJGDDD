#!/bin/bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$PROJECT_ROOT/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
ENV_FILE="$PROJECT_ROOT/.env"
BACKEND_PORT="8000"
FRONTEND_PORT="3000"

info() {
    printf '\033[0;36m[STEP] %s\033[0m\n' "$1"
}

ok() {
    printf '\033[0;32m[OK] %s\033[0m\n' "$1"
}

warn() {
    printf '\033[1;33m[WARN] %s\033[0m\n' "$1"
}

load_ports() {
    if [[ -f "$ENV_FILE" ]]; then
        local backend_line
        local frontend_line
        backend_line="$(grep -E '^SERVER_PORT=' "$ENV_FILE" | tail -n 1 || true)"
        frontend_line="$(grep -E '^FRONTEND_PORT=' "$ENV_FILE" | tail -n 1 || true)"
        if [[ -n "$backend_line" ]]; then
            BACKEND_PORT="${backend_line#*=}"
        fi
        if [[ -n "$frontend_line" ]]; then
            FRONTEND_PORT="${frontend_line#*=}"
        fi
    fi
}

port_pid() {
    local port="$1"

    if command -v lsof >/dev/null 2>&1; then
        lsof -ti tcp:"$port" 2>/dev/null | head -n 1
        return
    fi

    if command -v ss >/dev/null 2>&1; then
        ss -ltnp "( sport = :$port )" 2>/dev/null | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' | head -n 1
        return
    fi

    if command -v netstat >/dev/null 2>&1; then
        netstat -ltnp 2>/dev/null | awk -v port=":$port" '$4 ~ port {split($7, parts, "/"); print parts[1]; exit}'
    fi
}

stop_process() {
    local name="$1"
    local pid_file="$2"
    local port="${3:-}"

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

    if [[ -n "$port" ]]; then
        local listener_pid
        listener_pid="$(port_pid "$port")"
        if [[ -n "$listener_pid" ]] && kill -0 "$listener_pid" >/dev/null 2>&1; then
            kill "$listener_pid" >/dev/null 2>&1 || true
            ok "$name port $port listener (PID: $listener_pid) stopped."
        fi
    fi
}

info "停止开发环境"
load_ports

stop_process "Frontend" "$FRONTEND_PID_FILE" "$FRONTEND_PORT"
stop_process "Backend" "$BACKEND_PID_FILE" "$BACKEND_PORT"

echo ""
ok "所有相关进程已停止。"
echo ""
