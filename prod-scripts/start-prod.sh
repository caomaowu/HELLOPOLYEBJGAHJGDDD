#!/bin/bash

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIST_DIR="$PROJECT_ROOT/frontend/dist"
RUN_DIR="$PROJECT_ROOT/.run"
ENV_FILE="$PROJECT_ROOT/.env"
PID_FILE="$RUN_DIR/prod-backend.pid"
LOG_FILE="$RUN_DIR/prod-backend.log"
ERR_FILE="$RUN_DIR/prod-backend.err.log"

info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

ok() {
    echo -e "${GREEN}[OK]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

fail() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

read_env_value() {
    local key="$1"
    local default_value="$2"

    if [[ -f "$ENV_FILE" ]]; then
        local line
        line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
        if [[ -n "$line" ]]; then
            echo "${line#*=}"
            return
        fi
    fi

    echo "$default_value"
}

is_pid_running() {
    local pid="$1"
    kill -0 "$pid" >/dev/null 2>&1
}

find_backend_jar() {
    find "$BACKEND_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1
}

is_port_listening() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn "( sport = :$port )" 2>/dev/null | tail -n +2 | grep -q .
        return
    fi

    if command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]$port$"
        return
    fi

    return 1
}

wait_port() {
    local port="$1"
    local timeout="${2:-60}"
    local deadline=$((SECONDS + timeout))

    while (( SECONDS < deadline )); do
        if is_port_listening "$port"; then
            return 0
        fi
        sleep 1
    done

    return 1
}

[[ -f "$ENV_FILE" ]] || fail "未找到根目录 .env，请先执行 ./prod-scripts/init-prod-env.sh"

mkdir -p "$RUN_DIR"

if [[ -f "$PID_FILE" ]]; then
    existing_pid="$(head -n 1 "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "${existing_pid:-}" ]] && is_pid_running "$existing_pid"; then
        fail "生产后端已在运行，PID=$existing_pid。请先执行 ./prod-scripts/stop-prod.sh"
    fi
    rm -f "$PID_FILE"
fi

JAR_PATH="$(find_backend_jar)"
[[ -n "$JAR_PATH" ]] || fail "未找到后端 JAR，请先执行 ./prod-scripts/build-prod.sh"
[[ -d "$FRONTEND_DIST_DIR" ]] || warn "未找到 frontend/dist，前端静态资源尚未构建"

SERVER_PORT="$(read_env_value "SERVER_PORT" "8000")"
JAVA_OPTS_VALUE="$(read_env_value "JAVA_OPTS" "-Xms512m -Xmx1024m -XX:+UseG1GC")"

if is_port_listening "$SERVER_PORT"; then
    fail "端口 $SERVER_PORT 已被占用，请先释放端口或修改 .env"
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

info "启动生产后端"
nohup bash -lc "cd \"$PROJECT_ROOT\" && exec java $JAVA_OPTS_VALUE -jar \"$JAR_PATH\"" >"$LOG_FILE" 2>"$ERR_FILE" &
BACKEND_PID=$!
echo "$BACKEND_PID" > "$PID_FILE"

if ! wait_port "$SERVER_PORT" 90; then
    rm -f "$PID_FILE"
    if is_pid_running "$BACKEND_PID"; then
        kill "$BACKEND_PID" >/dev/null 2>&1 || true
    fi
    fail "后端启动失败，端口 $SERVER_PORT 未在预期时间内监听。请检查 $LOG_FILE 和 $ERR_FILE"
fi

ok "生产后端已启动，PID=$BACKEND_PID"
echo "后端地址: http://127.0.0.1:$SERVER_PORT"
echo "日志文件: $LOG_FILE"
echo "错误日志: $ERR_FILE"
echo "前端目录: $FRONTEND_DIST_DIR"
echo "说明: 请让 Nginx 或其他静态服务把站点根目录指向 frontend/dist，并将 /api 和 /ws 反代到 127.0.0.1:$SERVER_PORT"
