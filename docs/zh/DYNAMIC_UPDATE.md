# PolyHermes 升级策略

本仓库已移除基于 Docker 容器的动态更新实现。

当前推荐的升级方式是标准的非 Docker 发布流程：

1. 在服务器项目根目录重新构建
2. 备份当前生产产物
3. 停止后端进程
4. 启动新的后端进程
5. 如有需要，重载 Nginx

## 推荐流程

```bash
./prod-scripts/build-prod.sh
./prod-scripts/stop-prod.sh
./prod-scripts/start-prod.sh
```

## 升级命令示例

```bash
./prod-scripts/build-prod.sh
./prod-scripts/stop-prod.sh
./prod-scripts/start-prod.sh
sudo nginx -t && sudo systemctl reload nginx
```

## 回滚建议

- 保留上一个稳定提交或分支
- 保留上一个版本的 `frontend/dist` 目录快照
- 每次升级前导出数据库备份

## 前端在线更新模块

- 非 Docker 部署默认使用 `VITE_ENABLE_SYSTEM_UPDATE=false`
- 这样系统设置页不会再尝试调用容器专属的更新服务
- 如果未来需要宿主机版在线升级，应实现独立的主机更新服务，而不是恢复容器脚本
