#!/bin/bash

set -euo pipefail

INSTALL_DEPS=false
if [[ "${1:-}" == "--install-deps" ]]; then
    INSTALL_DEPS=true
fi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
RUN_DIR="$PROJECT_ROOT/.run"
BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

info() {
    printf '[INFO] %s\n' "$1"
}

ok() {
    printf '[OK] %s\n' "$1"
}

fail() {
    printf '[ERROR] %s\n' "$1" >&2
    exit 1
}

assert_command() {
    local name="$1"
    local hint="$2"
    command -v "$name" >/dev/null 2>&1 || fail "$name 未安装。$hint"
}

is_running() {
    local pid_file="$1"
    if [[ ! -f "$pid_file" ]]; then
        return 1
    fi

    local pid
    pid="$(head -n 1 "$pid_file" 2>/dev/null || true)"
    [[ -n "$pid" ]] || return 1
    kill -0 "$pid" >/dev/null 2>&1
}

start_background() {
    local name="$1"
    local workdir="$2"
    local command="$3"
    local log_file="$4"
    local pid_file="$5"

    if is_running "$pid_file"; then
        fail "$name 已在运行，PID=$(cat "$pid_file")"
    fi

    (
        cd "$workdir"
        nohup bash -lc "$command" >>"$log_file" 2>&1 &
        echo $! > "$pid_file"
    )

    ok "$name 已启动，PID=$(cat "$pid_file")"
}

assert_command java "请安装 JDK 17+。"
assert_command node "请安装 Node.js 18+。"
assert_command npm "请安装 npm。"

[[ -f "$BACKEND_DIR/gradlew" ]] || fail "未找到 backend/gradlew"
[[ -f "$FRONTEND_DIR/package.json" ]] || fail "未找到 frontend/package.json"

mkdir -p "$RUN_DIR"

if [[ "$INSTALL_DEPS" == "true" || ! -d "$FRONTEND_DIR/node_modules" ]]; then
    info "安装前端依赖"
    (cd "$FRONTEND_DIR" && npm install)
fi

[[ -f "$PROJECT_ROOT/.env" ]] || info "提示：根目录 .env 不存在，建议先执行 ./init-dev-env.sh"
[[ -f "$FRONTEND_DIR/.env" ]] || info "提示：frontend/.env 不存在，建议先执行 ./init-dev-env.sh"

info "启动后端"
start_background "Backend" "$BACKEND_DIR" "./gradlew bootRun" "$BACKEND_LOG" "$BACKEND_PID_FILE"

sleep 2

info "启动前端"
start_background "Frontend" "$FRONTEND_DIR" "npm run dev" "$FRONTEND_LOG" "$FRONTEND_PID_FILE"

printf '\n'
ok "前后端已启动"
printf 'Frontend: http://localhost:3000\n'
printf 'Backend:  http://localhost:8000\n'
printf 'Backend Log:  %s\n' "$BACKEND_LOG"
printf 'Frontend Log: %s\n' "$FRONTEND_LOG"
