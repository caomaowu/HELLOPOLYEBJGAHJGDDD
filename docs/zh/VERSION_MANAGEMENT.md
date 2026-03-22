# PolyHermes 版本管理

当前版本管理策略已从“Docker 镜像标签驱动”调整为“代码版本与发布产物驱动”。

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
2. 使用 `create-release.sh` 创建 GitHub Release
3. 执行 `./deploy.sh` 构建部署产物
4. 将产物发布到目标服务器

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
- 用 GitHub Release 记录发布说明
- 用构建产物目录而不是镜像仓库作为交付物
