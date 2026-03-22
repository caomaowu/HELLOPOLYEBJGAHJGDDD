# PolyHermes 部署文档

当前仓库已移除 Docker 方案，标准部署方式为宿主机直接部署：

- MySQL 运行在宿主机或独立数据库服务器
- 后端使用 Java 17 直接运行
- 前端构建为静态文件
- Nginx 负责静态资源托管和 `/api`、`/ws` 反向代理

## 推荐阅读顺序

1. [非 Docker 开发与部署方案](NON_DOCKER_DEPLOYMENT.md)
2. [开发文档](DEVELOPMENT.md)

## 快速部署摘要

### 0. 服务器直接在项目目录运行

推荐方式是在服务器项目根目录直接执行：

```bash
./prod-scripts/init-prod-env.sh
./prod-scripts/build-prod.sh
./prod-scripts/start-prod.sh
```

停止方式：

```bash
./prod-scripts/stop-prod.sh
```

说明：

- 这种方式会直接使用项目目录下的 `backend/build/libs/*.jar` 和 `frontend/dist`
- `start-prod.sh` 只负责启动后端进程
- 前端静态资源仍建议由 Nginx 指向 `frontend/dist`
- `/api` 和 `/ws` 仍需由 Nginx 反代到后端端口

### 1. 配置后端环境变量

关键项：

```env
DB_URL=jdbc:mysql://127.0.0.1:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SERVER_PORT=8000
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=replace-with-random-secret
ADMIN_RESET_PASSWORD_KEY=replace-with-random-secret
ENCRYPTION_KEY=replace-with-random-secret
```

注意：

- `SERVER_PORT` 可以按服务器实际情况调整
- 一旦修改 `SERVER_PORT`，必须同步更新 Nginx 代理目标端口
- 根目录 `.env` 是生产脚本和后端运行时的统一配置来源

### 2. 启动后端

```bash
./prod-scripts/start-prod.sh
```

或使用 `systemd`：

```bash
sudo nano /etc/systemd/system/polyhermes-backend.service
```

示例内容：

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

[Install]
WantedBy=multi-user.target
```

然后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable polyhermes-backend
sudo systemctl start polyhermes-backend
```

### 3. 部署前端

将 `frontend/dist` 发布到你的 Web 根目录，例如：

- `/opt/polyhermes/frontend/dist`

### 4. 配置 Nginx

参考：

- [nginx-nodocker.conf](nginx-nodocker.conf)

## 说明

- 旧版 Docker Compose、镜像构建和容器化动态更新已从仓库移除
- 系统更新页在非 Docker 运行方式下默认关闭
- 如需升级，采用“替换 JAR + 替换前端产物 + 重启进程”的方式
