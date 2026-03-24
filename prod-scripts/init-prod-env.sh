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
ENV_EXAMPLE_FILE="$PROJECT_ROOT/.env.example"

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
    local default_value="${2:-}"

    if [[ ! -f "$ENV_FILE" ]]; then
        echo "$default_value"
        return
    fi

    local line
    line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
    if [[ -z "$line" ]]; then
        echo "$default_value"
        return
    fi

    echo "${line#*=}"
}

require_non_empty() {
    local key="$1"
    local value="$2"
    if [[ -z "$value" ]]; then
        fail "$key 未配置，请编辑 $ENV_FILE"
    fi
}

warn_if_insecure() {
    local key="$1"
    local value="$2"

    case "$value" in
        your_password_here|change-me-in-production|change-me-in-production-use-openssl-rand-hex-32|change-me-in-production-use-openssl-rand-hex-64|replace-with-random-secret)
            warn "$key 仍然是默认示例值，生产环境必须替换"
            ;;
    esac
}

info "初始化生产环境配置"

if [[ ! -f "$ENV_FILE" ]]; then
    [[ -f "$ENV_EXAMPLE_FILE" ]] || fail "未找到 $ENV_EXAMPLE_FILE"
    cp "$ENV_EXAMPLE_FILE" "$ENV_FILE"
    warn "已创建 $ENV_FILE，请先修改其中的生产配置后再继续"
    exit 1
fi

SERVER_PORT="$(read_env_value "SERVER_PORT" "8000")"
DB_PASSWORD="$(read_env_value "DB_PASSWORD" "")"
JWT_SECRET="$(read_env_value "JWT_SECRET" "")"
ADMIN_RESET_PASSWORD_KEY="$(read_env_value "ADMIN_RESET_PASSWORD_KEY" "")"
ENCRYPTION_KEY="$(read_env_value "ENCRYPTION_KEY" "")"
SPRING_PROFILES_ACTIVE="$(read_env_value "SPRING_PROFILES_ACTIVE" "prod")"
VITE_ENABLE_SYSTEM_UPDATE="$(read_env_value "VITE_ENABLE_SYSTEM_UPDATE" "false")"

require_non_empty "DB_PASSWORD" "$DB_PASSWORD"
require_non_empty "JWT_SECRET" "$JWT_SECRET"
require_non_empty "ADMIN_RESET_PASSWORD_KEY" "$ADMIN_RESET_PASSWORD_KEY"
require_non_empty "ENCRYPTION_KEY" "$ENCRYPTION_KEY"

warn_if_insecure "DB_PASSWORD" "$DB_PASSWORD"
warn_if_insecure "JWT_SECRET" "$JWT_SECRET"
warn_if_insecure "ADMIN_RESET_PASSWORD_KEY" "$ADMIN_RESET_PASSWORD_KEY"
warn_if_insecure "ENCRYPTION_KEY" "$ENCRYPTION_KEY"

if [[ "$SPRING_PROFILES_ACTIVE" != "prod" ]]; then
    warn "SPRING_PROFILES_ACTIVE 当前为 $SPRING_PROFILES_ACTIVE，服务器建议使用 prod"
fi

if [[ "$VITE_ENABLE_SYSTEM_UPDATE" != "false" ]]; then
    warn "VITE_ENABLE_SYSTEM_UPDATE 当前为 $VITE_ENABLE_SYSTEM_UPDATE，非 Docker 服务器建议设为 false"
fi

mkdir -p "$PROJECT_ROOT/.run"

ok "生产环境配置检查完成"
echo "项目根目录: $PROJECT_ROOT"
echo "后端端口:   $SERVER_PORT"
echo "下一步:"
echo "  1. ./prod-scripts/build-prod.sh"
echo "  2. ./prod-scripts/start-prod.sh"
