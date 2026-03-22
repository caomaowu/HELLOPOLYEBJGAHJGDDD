# PolyHermes 非 Docker 开发与部署方案

本文档给出一套不依赖 Docker 的开发、测试与生产部署方案。

适用目标：

- 本地直接开发前后端
- 服务器直接运行 Java 进程
- 使用宿主机 MySQL
- 使用宿主机 Nginx 托管前端并代理后端 API / WebSocket

不包含内容：

- Docker Compose
- 容器内 MySQL
- 容器内在线更新服务

## 总体架构

```text
浏览器
  ↓
Nginx (80/443)
  ├─ /            → frontend/dist
  ├─ /api/*       → http://127.0.0.1:<SERVER_PORT>
  └─ /ws          → ws://127.0.0.1:<SERVER_PORT>

Spring Boot (127.0.0.1:<SERVER_PORT>)
  ↓
MySQL (127.0.0.1:3306)
```

## 开发方案

### 依赖要求

- JDK 17+
- Node.js 18+
- MySQL 8.0+

### 1. 初始化环境

Windows:

```powershell
Copy-Item ".env.example" ".env"
# 编辑 .env，至少填写 DB_PASSWORD
.\dev-scripts\init-dev-env.ps1
```

Linux/macOS:

```bash
cp ".env.example" ".env"
# 编辑 .env，至少填写 DB_PASSWORD
chmod +x "./dev-scripts/init-dev-env.sh"
./dev-scripts/init-dev-env.sh
```

建议保持以下关键值：

```env
DB_HOST=localhost:3306
DB_NAME=polyhermes
SERVER_PORT=8000
VITE_API_URL=http://localhost:8000
VITE_WS_URL=ws://localhost:8000
VITE_ENABLE_SYSTEM_UPDATE=false
```

说明：

- `SERVER_PORT` 可以改成任意未占用端口，但 `VITE_API_URL`、`VITE_WS_URL` 和反向代理必须同步修改
- `VITE_ENABLE_SYSTEM_UPDATE=false` 用于关闭容器专属的在线更新模块
- 初始化脚本会写入 `frontend/.env`
- 后端通过 Flyway 自动迁移数据库

### 2. 启动后端

Windows:

```powershell
Set-Location "backend"
.\gradlew.bat bootRun
```

Linux/macOS:

```bash
cd "./backend"
./gradlew bootRun
```

默认地址：

- 后端 API: `http://localhost:8000/api`
- WebSocket: `ws://localhost:8000/ws`
- 健康检查: `http://localhost:8000/api/system/health`

### 3. 启动前端

```bash
cd "./frontend"
npm install
npm run dev
```

默认地址：

- 前端开发服务：`http://localhost:3000`

## 生产部署方案

推荐使用：

- 宿主机 MySQL
- 宿主机 `systemd` 管理后端
- 宿主机 Nginx 托管前端静态文件并做反向代理

如果你只是想先在服务器项目目录里直接跑起来，也可以先使用：

```bash
./prod-scripts/init-prod-env.sh
./prod-scripts/build-prod.sh
./prod-scripts/start-prod.sh
```

停止方式：

```bash
./prod-scripts/stop-prod.sh
```

这套脚本适合单机直接运行源码目录，但长期稳定运行仍建议切换到 `systemd + Nginx`。

### 1. 后端构建

Windows:

```powershell
Set-Location "backend"
.\gradlew.bat clean bootJar
```

Linux/macOS:

```bash
cd "./backend"
./gradlew clean bootJar
```

产物目录：

- `backend/build/libs/`

### 2. 前端构建

推荐生产环境继续关闭在线更新模块：

```env
VITE_ENABLE_SYSTEM_UPDATE=false
```

如果 Nginx 与后端在同一域名下统一代理，前端构建时可以不显式指定 `VITE_API_URL` 和 `VITE_WS_URL`，直接走默认相对路径 `/api` 和 `/ws`。

```bash
cd "./frontend"
npm install
npm run build
```

产物目录：

- `frontend/dist/`

### 3. 服务器目录建议

```text
/opt/polyhermes/
  ├─ backend/
  │   ├─ app.jar
  │   └─ .env
  └─ frontend/
      └─ dist/
```

### 4. 后端环境变量示例

服务器上的 `/opt/polyhermes/backend/.env` 建议类似：

```env
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
```

说明：

- 这里必须使用 `127.0.0.1` 或真实数据库地址，不要再使用 `mysql:3306`
- `JWT_SECRET`、`ADMIN_RESET_PASSWORD_KEY`、`ENCRYPTION_KEY` 必须使用真实随机值
- `SERVER_PORT` 改动后，需要同步更新 Nginx 反向代理端口

### 5. systemd 服务示例

将下面内容保存为 `/etc/systemd/system/polyhermes-backend.service`：

```ini
[Unit]
Description=PolyHermes Backend
After=network.target mysql.service

[Service]
Type=simple
User=polyhermes
WorkingDirectory=/opt/polyhermes/PolyHermes
EnvironmentFile=/opt/polyhermes/PolyHermes/.env
ExecStart=/bin/bash -lc 'cd /opt/polyhermes/PolyHermes && JAR_PATH=$(find backend/build/libs -maxdepth 1 -type f -name "*.jar" ! -name "*-plain.jar" | head -n 1) && exec java ${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC} -jar "$JAR_PATH"'
Restart=always
RestartSec=10
SuccessExitStatus=143
TimeoutStopSec=30
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

启用方式：

```bash
sudo systemctl daemon-reload
sudo systemctl enable polyhermes-backend
sudo systemctl start polyhermes-backend
sudo systemctl status polyhermes-backend
```

日志查看：

```bash
journalctl -u polyhermes-backend -f
```

### 6. Nginx 部署

仓库内已提供可直接改造的示例配置：

- [nginx-nodocker.conf](nginx-nodocker.conf)

核心思路：

- `root` 指向 `frontend/dist`
- `/api` 代理到 `127.0.0.1:<SERVER_PORT>`
- `/ws` 代理到 `127.0.0.1:<SERVER_PORT>`
- 其他路径回退到 `index.html`

### 7. 升级流程

非 Docker 方案建议使用“构建产物替换 + 重启进程”的发布方式：

1. 构建新的后端 JAR
2. 构建新的前端 `dist`
3. 替换服务器上的 `app.jar`
4. 替换服务器上的前端静态文件
5. 执行 `sudo systemctl restart polyhermes-backend`
6. 执行 `sudo nginx -t && sudo systemctl reload nginx`

## 取舍说明

非 Docker 方案的优点：

- 依赖更少，便于和现有服务器体系集成
- 调试直接，进程、日志、端口都在宿主机可见
- 不需要维护容器镜像和 Compose

非 Docker 方案的限制：

- 当前仓库里的在线更新服务默认是容器方案的一部分
- 因此前端应配置 `VITE_ENABLE_SYSTEM_UPDATE=false`
- 如果你需要保留“Web UI 一键升级”，需要额外实现主机版更新服务
