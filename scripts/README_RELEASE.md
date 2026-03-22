# Release 创建脚本使用说明

## 简介

`create-release.sh` 用于创建 Git tag 和 GitHub Release。

它负责：

- 创建本地与远程 tag
- 创建 GitHub Release
- 标记是否为 Pre-release

它不再负责触发 Docker 镜像发布。

## 前置要求

- 已安装 `git`
- 已安装并登录 `gh`

```bash
gh auth status
```

## 基本用法

```bash
./create-release.sh -t v1.0.1 -T "Release v1.0.1" -d "更新内容"
./create-release.sh -t v1.0.1 --prerelease -d "测试版本"
```

## 建议发布流程

1. 确认代码已提交
2. 运行 `./create-release.sh`
3. 运行 `./deploy.sh`
4. 将 `deploy/package/` 中的产物发布到服务器

## 相关文档

- [版本号管理说明](../docs/zh/VERSION_MANAGEMENT.md)
- [升级策略](../docs/zh/DYNAMIC_UPDATE.md)
- [非 Docker 部署方案](../docs/zh/NON_DOCKER_DEPLOYMENT.md)
