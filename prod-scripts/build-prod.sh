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
FRONTEND_DIR="$PROJECT_ROOT/frontend"
ENV_FILE="$PROJECT_ROOT/.env"

info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

ok() {
    echo -e "${GREEN}[OK]${NC} $1"
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

command -v java >/dev/null 2>&1 || fail "未找到 Java，请安装 JDK 17+"
command -v node >/dev/null 2>&1 || fail "未找到 Node.js，请安装 Node.js 18+"
command -v npm >/dev/null 2>&1 || fail "未找到 npm"

if [[ -f "$ENV_FILE" ]]; then
    info "检测到根目录 .env，将按生产模式构建"
else
    info "未检测到根目录 .env，将使用默认生产构建参数"
fi

VITE_ENABLE_SYSTEM_UPDATE="$(read_env_value "VITE_ENABLE_SYSTEM_UPDATE" "false")"

info "构建后端 JAR"
(
    cd "$BACKEND_DIR"
    ./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process clean bootJar
    ./gradlew --stop >/dev/null 2>&1 || true
)
ok "后端构建完成"

if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    info "安装前端依赖"
    (cd "$FRONTEND_DIR" && npm install)
fi

info "构建前端静态资源"
(
    cd "$FRONTEND_DIR"
    env \
        VITE_API_URL= \
        VITE_WS_URL= \
        VITE_ENABLE_SYSTEM_UPDATE="$VITE_ENABLE_SYSTEM_UPDATE" \
        npm run build
)
ok "前端构建完成"

echo "构建结果:"
echo "  Backend JAR: $BACKEND_DIR/build/libs"
echo "  Frontend dist: $FRONTEND_DIR/dist"
echo "说明:"
echo "  - 生产前端默认使用相对路径 /api 和 /ws"
echo "  - 请用 Nginx 或其他反向代理指向 frontend/dist"
