#!/bin/bash

set -e

SKIP_DB_CREATE=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-db-create)
            SKIP_DB_CREATE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--skip-db-create]"
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
ENV_EXAMPLE_FILE="$PROJECT_ROOT/.env.example"
BACKEND_APP_PROPS="$PROJECT_ROOT/backend/src/main/resources/application.properties"
FRONTEND_ENV_FILE="$PROJECT_ROOT/frontend/.env"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
WHITE='\033[1;37m'
NC='\033[0m'

print_step() {
    echo -e "\n${CYAN}[STEP] $1${NC}"
}

print_ok() {
    echo -e "${GREEN}[OK] $1${NC}"
}

print_err() {
    echo -e "${RED}[ERROR] $1${NC}"
}

print_info() {
    echo -e "${YELLOW}[INFO] $1${NC}"
}

get_env_value() {
    local key=$1
    local default=$2
    local value="${env_hash[$key]}"
    if [[ -z "$value" ]]; then
        echo "$default"
    else
        echo "$value"
    fi
}

update_app_property() {
    local file=$1
    local key=$2
    local value=$3

    if grep -q "^[[:space:]]*$key[[:space:]]*=" "$file"; then
        sed -i "s|^[[:space:]]*$key[[:space:]]*=.*|$key=$value|" "$file"
    else
        echo "$key=$value" >> "$file"
    fi
}

echo -e "${MAGENTA}========================================${NC}"
echo -e "${MAGENTA}  PolyHermes Dev Environment Init${NC}"
echo -e "${MAGENTA}========================================${NC}"

print_step "1. Checking .env file"
if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -f "$ENV_EXAMPLE_FILE" ]]; then
        cp "$ENV_EXAMPLE_FILE" "$ENV_FILE"
        print_info ".env file created from .env.example"
        print_info "Please edit .env file and fill in your database password"
        echo ""
        echo "File: $ENV_FILE"
        exit 1
    else
        print_err ".env.example not found"
        exit 1
    fi
fi

declare -A env_hash
while IFS='=' read -r key value; do
    key=$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    value=$(echo "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    if [[ -n "$key" ]]; then
        env_hash["$key"]="$value"
    fi
done < "$ENV_FILE"

DB_HOST=$(get_env_value "DB_HOST" "localhost:3306")
DB_USERNAME=$(get_env_value "DB_USERNAME" "root")
DB_PASSWORD=$(get_env_value "DB_PASSWORD" "")
DB_NAME=$(get_env_value "DB_NAME" "polyhermes")
SERVER_PORT=$(get_env_value "SERVER_PORT" "8000")
FRONTEND_PORT=$(get_env_value "FRONTEND_PORT" "3000")
JWT_SECRET=$(get_env_value "JWT_SECRET" "change-me-in-production")
ADMIN_RESET_PASSWORD_KEY=$(get_env_value "ADMIN_RESET_PASSWORD_KEY" "change-me-in-production-use-openssl-rand-hex-32")
VITE_API_URL=$(get_env_value "VITE_API_URL" "http://localhost:8000")
VITE_WS_URL=$(get_env_value "VITE_WS_URL" "ws://localhost:8000")
VITE_ENABLE_SYSTEM_UPDATE=$(get_env_value "VITE_ENABLE_SYSTEM_UPDATE" "false")

if [[ -z "$DB_PASSWORD" ]] || [[ "$DB_PASSWORD" == "your_password_here" ]]; then
    print_err "DB_PASSWORD is not set in .env file"
    echo -e "Please edit ${YELLOW}$ENV_FILE${NC} and set a valid database password"
    exit 1
fi

print_ok ".env file loaded"

print_step "2. Checking prerequisites"

if [[ "$SKIP_DB_CREATE" == "false" ]]; then
    echo -n "  - Checking MySQL..."
    if ! command -v mysql &> /dev/null; then
        echo ""
        print_err "MySQL client not found"
        echo -e "Please install MySQL or add it to PATH"
        exit 1
    fi
    print_ok "MySQL found"
else
    print_info "Skipping MySQL client check"
fi

echo -n "  - Checking JDK..."
if ! command -v java &> /dev/null; then
    echo ""
    print_err "JDK not found"
    echo -e "Please install JDK 17+ or add it to PATH"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1)
if [[ "$JAVA_VERSION" =~ version\ \"(\d+)\.(\d+) ]]; then
    MAJOR="${BASH_REMATCH[1]}"
    MINOR="${BASH_REMATCH[2]}"
    if [[ $MAJOR -lt 17 ]]; then
        echo ""
        print_err "JDK version is $MAJOR.$MINOR, but JDK 17+ is required"
        exit 1
    fi
fi
print_ok "JDK 17+ found"

echo -n "  - Checking Node.js..."
if command -v node &> /dev/null; then
    NODE_VERSION=$(node --version)
    print_ok "Node.js found ($NODE_VERSION)"
else
    print_info "Node.js not found (optional for backend-only development)"
fi

if [[ "$SKIP_DB_CREATE" == "false" ]]; then
    print_step "3. Testing database connection"

    DB_HOSTParts=(${DB_HOST//:/ })
    DB_HOST_NAME="${DB_HOSTParts[0]}"
    DB_HOST_PORT="${DB_HOSTParts[1]:-3306}"

    if ! mysql -h "$DB_HOST_NAME" -P "$DB_HOST_PORT" -u "$DB_USERNAME" -p"$DB_PASSWORD" -e "SELECT 1" &> /dev/null; then
        print_err "Cannot connect to MySQL at $DB_HOST"
        echo -e "Please check your database credentials in .env"
        exit 1
    fi
    print_ok "Database connection OK"

    print_step "4. Creating database"
    if ! mysql -h "$DB_HOST_NAME" -P "$DB_HOST_PORT" -u "$DB_USERNAME" -p"$DB_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" &> /dev/null; then
        print_err "Failed to create database"
        exit 1
    fi
    print_ok "Database '$DB_NAME' ready"
else
    print_info "Skipping database creation"
fi

print_step "5. Updating backend configuration"
if [[ -f "$BACKEND_APP_PROPS" ]]; then
    DB_URL="jdbc:mysql://${DB_HOST}/${DB_NAME}?useSSL=false\&serverTimezone=UTC\&characterEncoding=utf8\&allowPublicKeyRetrieval=true"
    ESCAPED_DB_URL=$(echo "$DB_URL" | sed 's/\\/\\\\/g')
    update_app_property "$BACKEND_APP_PROPS" "spring.datasource.url" "\${DB_URL:$ESCAPED_DB_URL}"
    update_app_property "$BACKEND_APP_PROPS" "spring.datasource.username" "\${DB_USERNAME:$DB_USERNAME}"
    update_app_property "$BACKEND_APP_PROPS" "spring.datasource.password" "\${DB_PASSWORD:$DB_PASSWORD}"
    update_app_property "$BACKEND_APP_PROPS" "server.port" "\${SERVER_PORT:$SERVER_PORT}"
    update_app_property "$BACKEND_APP_PROPS" "jwt.secret" "\${JWT_SECRET:$JWT_SECRET}"
    update_app_property "$BACKEND_APP_PROPS" "admin.reset-password.key" "\${ADMIN_RESET_PASSWORD_KEY:$ADMIN_RESET_PASSWORD_KEY}"
    print_ok "Backend configuration updated"
else
    print_err "application.properties not found at $BACKEND_APP_PROPS"
fi

print_step "6. Creating frontend environment file"
cat > "$FRONTEND_ENV_FILE" << EOF
FRONTEND_PORT=$FRONTEND_PORT
VITE_API_URL=$VITE_API_URL
VITE_WS_URL=$VITE_WS_URL
VITE_ENABLE_SYSTEM_UPDATE=$VITE_ENABLE_SYSTEM_UPDATE
EOF
print_ok "Frontend environment file created"

echo ""
echo -e "${MAGENTA}========================================${NC}"
echo -e "${MAGENTA}  Initialization Complete!${NC}"
echo -e "${MAGENTA}========================================${NC}"
echo ""
echo -e "Next steps:"
echo -e "  ${WHITE}1. Start MySQL (if not running)${NC}"
echo -e "  ${WHITE}2. Backend: cd backend; ./gradlew bootRun${NC}"
echo -e "  ${WHITE}3. Frontend: cd frontend; npm install && npm run dev${NC}"
echo ""
