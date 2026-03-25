#!/bin/bash

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
SERVICE_NAME="polyhermes-backend.service"

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

wait_port() {
    local port="$1"
    local timeout="${2:-90}"
    local deadline=$((SECONDS + timeout))

    while (( SECONDS < deadline )); do
        if ss -ltn "( sport = :$port )" 2>/dev/null | tail -n +2 | grep -q .; then
            return 0
        fi
        sleep 1
    done

    return 1
}

[[ -f "$ENV_FILE" ]] || fail "未找到 $ENV_FILE"
command -v git >/dev/null 2>&1 || fail "未找到 git"
command -v systemctl >/dev/null 2>&1 || fail "未找到 systemctl"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"
command -v pkill >/dev/null 2>&1 || fail "未找到 pkill"

SERVER_PORT="$(read_env_value "SERVER_PORT" "8008")"

info "检查生产环境配置"
"$PROJECT_ROOT/prod-scripts/init-prod-env.sh"

info "拉取最新代码"
(cd "$PROJECT_ROOT" && git pull --ff-only)

info "构建生产产物"
"$PROJECT_ROOT/prod-scripts/build-prod.sh"

info "重启后端服务: $SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

info "等待后端端口就绪: $SERVER_PORT"
if ! wait_port "$SERVER_PORT" 120; then
    systemctl status "$SERVICE_NAME" --no-pager -l || true
    fail "后端端口 $SERVER_PORT 未在预期时间内监听"
fi

info "执行接口健康检查"
HEALTH_RESPONSE="$(curl -sS -m 15 "http://127.0.0.1:${SERVER_PORT}/api/auth/check-first-use" -H 'Content-Type: application/json' -d '{}')" || {
    systemctl status "$SERVICE_NAME" --no-pager -l || true
    fail "健康检查失败"
}

info "清理构建残留进程"
(
    cd "$PROJECT_ROOT/backend"
    ./gradlew --stop >/dev/null 2>&1 || true
)
pkill -f 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' >/dev/null 2>&1 || true

ok "部署完成"
echo "服务状态:"
systemctl --no-pager --no-legend --full status "$SERVICE_NAME" | sed -n '1,5p'
echo "接口返回:"
echo "$HEALTH_RESPONSE"
echo "访问地址:"
echo "  https://poly.caomaowu.lol/"
