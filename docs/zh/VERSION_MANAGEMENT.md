# PolyHermes 版本管理

当前版本管理策略已从“Docker 镜像标签驱动”调整为“代码版本与源码部署驱动”。

## 版本来源

前端版本显示来自以下来源：

1. `window.__VERSION__`
2. `VITE_APP_VERSION`
3. `VITE_APP_GIT_TAG`
4. 默认值 `dev`

相关实现见：

- `frontend/src/utils/version.ts`

## 建议发布流程

1. 在 Git 中创建语义化版本 tag，例如 `v1.2.0`
2. 将代码推送到目标分支或服务器
3. 在服务器项目目录执行 `./prod-scripts/build-prod.sh`
4. 通过 `systemd` 或 `./prod-scripts/start-prod.sh` 启动新版本

## 环境变量

前端构建可选注入：

```env
VITE_APP_VERSION=v1.2.0
VITE_APP_GIT_TAG=v1.2.0
VITE_APP_GITHUB_REPO_URL=https://github.com/your-org/your-repo
```

如果不注入，开发环境默认显示 `dev`。

## 当前仓库中已移除的内容

- 镜像仓库版本标签
- Docker 镜像发布工作流
- Docker 镜像删除工作流

## 建议

- 用 Git tag 作为唯一版本源
- 如需 Release 页面，可手动在 GitHub 上创建说明
- 生产环境以源码目录构建和运行结果作为交付物
