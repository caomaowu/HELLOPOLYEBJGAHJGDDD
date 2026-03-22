#!/bin/bash

# PolyHermes 非 Docker 部署产物打包脚本

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
OUTPUT_DIR="$PROJECT_ROOT/deploy/package"
VERSION="${VERSION:-$(git -C "$PROJECT_ROOT" describe --tags --always 2>/dev/null || echo dev)}"

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

check_requirements() {
    command -v java >/dev/null 2>&1 || fail "未找到 Java，请安装 JDK 17+"
    command -v node >/dev/null 2>&1 || fail "未找到 Node.js，请安装 Node.js 18+"
    ok "运行环境检查通过"
}

build_backend() {
    info "构建后端 JAR"
    (cd "$BACKEND_DIR" && ./gradlew clean bootJar)
    ok "后端构建完成"
}

build_frontend() {
    info "构建前端静态资源"
    if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
        (cd "$FRONTEND_DIR" && npm install)
    fi
    (cd "$FRONTEND_DIR" && npm run build)
    ok "前端构建完成"
}

stage_artifacts() {
    info "整理部署产物到 $OUTPUT_DIR"
    rm -rf "$OUTPUT_DIR"
    mkdir -p "$OUTPUT_DIR/backend" "$OUTPUT_DIR/frontend"

    local jar_path
    jar_path="$(find "$BACKEND_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' | head -n 1)"
    [ -n "$jar_path" ] || fail "未找到后端 JAR 产物"

    cp "$jar_path" "$OUTPUT_DIR/backend/app.jar"
    cp "$PROJECT_ROOT/docs/zh/nginx-nodocker.conf" "$OUTPUT_DIR/nginx.conf"
    cp -R "$FRONTEND_DIR/dist" "$OUTPUT_DIR/frontend/dist"

    cat > "$OUTPUT_DIR/backend/.env.example" <<EOF
DB_URL=jdbc:mysql://127.0.0.1:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SERVER_PORT=8000
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=replace-with-random-secret
ADMIN_RESET_PASSWORD_KEY=replace-with-random-secret
ENCRYPTION_KEY=replace-with-random-secret
LOG_LEVEL_ROOT=WARN
LOG_LEVEL_APP=INFO
EOF

    cat > "$OUTPUT_DIR/backend/start.sh" <<'EOF'
#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
exec java -Xms512m -Xmx1024m -XX:+UseG1GC -jar app.jar
EOF
    chmod +x "$OUTPUT_DIR/backend/start.sh"

    cat > "$OUTPUT_DIR/backend/polyhermes-backend.service.example" <<'EOF'
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

    cat > "$OUTPUT_DIR/README.txt" <<EOF
PolyHermes 非 Docker 部署产物
版本: $VERSION

目录说明:
- backend/app.jar                 后端可执行 JAR
- backend/.env.example           后端环境变量示例
- backend/start.sh               后端启动脚本
- backend/polyhermes-backend.service.example  systemd 示例
- frontend/dist/                 前端静态资源
- nginx.conf                     Nginx 反向代理示例

详细部署说明:
- docs/zh/NON_DOCKER_DEPLOYMENT.md
EOF

    ok "部署产物已生成"
}

main() {
    echo "=========================================="
    echo "  PolyHermes 非 Docker 部署打包"
    echo "=========================================="
    check_requirements
    build_backend
    build_frontend
    stage_artifacts
    info "完成。产物目录: $OUTPUT_DIR"
}

main "$@"
