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

### 1. 构建产物

```bash
./deploy.sh
```

构建完成后，部署产物位于：

- `deploy/package/backend/app.jar`
- `deploy/package/frontend/dist`
- `deploy/package/nginx.conf`

### 2. 配置后端环境变量

参考：

- `deploy/package/backend/.env.example`

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

### 3. 启动后端

```bash
java -jar app.jar
```

或使用 `systemd`：

```bash
sudo cp polyhermes-backend.service.example /etc/systemd/system/polyhermes-backend.service
sudo systemctl daemon-reload
sudo systemctl enable polyhermes-backend
sudo systemctl start polyhermes-backend
```

### 4. 部署前端

将 `frontend/dist` 发布到你的 Web 根目录，例如：

- `/opt/polyhermes/frontend/dist`

### 5. 配置 Nginx

参考：

- [nginx-nodocker.conf](nginx-nodocker.conf)

## 说明

- 旧版 Docker Compose、镜像构建和容器化动态更新已从仓库移除
- 系统更新页在非 Docker 运行方式下默认关闭
- 如需升级，采用“替换 JAR + 替换前端产物 + 重启进程”的方式
