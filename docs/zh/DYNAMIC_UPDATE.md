# PolyHermes 升级策略

本仓库已移除基于 Docker 容器的动态更新实现。

当前推荐的升级方式是标准的非 Docker 发布流程：

1. 构建新的后端 JAR
2. 构建新的前端静态资源
3. 备份当前生产产物
4. 替换 `app.jar`
5. 替换前端 `dist`
6. 重启后端进程
7. 重载 Nginx

## 推荐流程

```bash
./deploy.sh
```

然后将以下产物发布到服务器：

- `deploy/package/backend/app.jar`
- `deploy/package/frontend/dist`
- `deploy/package/nginx.conf`

## 升级命令示例

```bash
sudo systemctl stop polyhermes-backend
cp app.jar /opt/polyhermes/backend/app.jar
rsync -av --delete dist/ /opt/polyhermes/frontend/dist/
sudo systemctl start polyhermes-backend
sudo nginx -t && sudo systemctl reload nginx
```

## 回滚建议

- 保留上一个版本的 `app.jar`
- 保留上一个版本的 `dist` 目录快照
- 每次升级前导出数据库备份

## 前端在线更新模块

- 非 Docker 部署默认使用 `VITE_ENABLE_SYSTEM_UPDATE=false`
- 这样系统设置页不会再尝试调用容器专属的更新服务
- 如果未来需要宿主机版在线升级，应实现独立的主机更新服务，而不是恢复容器脚本
