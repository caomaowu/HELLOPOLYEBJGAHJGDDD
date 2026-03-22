#!/bin/bash

# PolyHermes 后端部署辅助脚本（非 Docker）

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

APP_NAME="polyhermes-backend"
DEPLOY_DIR="./deploy"

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

check_java() {
    command -v java >/dev/null 2>&1 || fail "未安装 Java，请先安装 JDK 17+"
    ok "Java 环境检查通过"
}

build_app() {
    info "构建后端 JAR"
    ./gradlew clean bootJar
    ok "后端构建完成"
}

prepare_deploy_dir() {
    info "整理后端部署目录"
    rm -rf "$DEPLOY_DIR"
    mkdir -p "$DEPLOY_DIR"

    local jar_path
    jar_path="$(find build/libs -maxdepth 1 -type f -name '*.jar' | head -n 1)"
    [ -n "$jar_path" ] || fail "未找到后端 JAR 产物"

    cp "$jar_path" "$DEPLOY_DIR/app.jar"

    cat > "$DEPLOY_DIR/start.sh" <<'EOF'
#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
exec java -Xms512m -Xmx1024m -XX:+UseG1GC -jar app.jar
EOF
    chmod +x "$DEPLOY_DIR/start.sh"

    cat > "$DEPLOY_DIR/${APP_NAME}.service.example" <<'EOF'
[Unit]
Description=PolyHermes Backend
After=network.target mysql.service

[Service]
Type=simple
User=polyhermes
WorkingDirectory=/opt/polyhermes/backend
EnvironmentFile=/opt/polyhermes/backend/.env
ExecStart=/usr/bin/java -Xms512m -Xmx1024m -XX:+UseG1GC -jar /opt/polyhermes/backend/app.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

    ok "部署目录已生成: $DEPLOY_DIR"
}

main() {
    echo "=========================================="
    echo "  PolyHermes Backend Deploy Helper"
    echo "=========================================="
    check_java
    build_app
    prepare_deploy_dir
    info "可执行: cd $DEPLOY_DIR && ./start.sh"
}

main "$@"
