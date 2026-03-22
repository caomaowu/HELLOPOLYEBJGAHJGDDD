# PolyHermes 一键部署脚本使用说明

## ✨ 核心特性

- **可在任意目录运行** - 无需下载项目源码
- **仅使用线上镜像** - 从 Docker Hub 拉取官方镜像
- **自动下载配置** - 从 GitHub 下载最新的 `docker-compose.prod.yml`
- **交互式配置** - 友好的问答式配置向导
- **自动生成密钥** - 所有敏感配置回车自动生成安全随机值

## 🚀 快速开始

### 一键安装（推荐）

**使用 curl（推荐）：**
```bash
mkdir -p ~/polyhermes && cd ~/polyhermes && curl -fsSL https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh -o deploy.sh && chmod +x deploy.sh && ./deploy.sh
```

**使用 wget：**
```bash
mkdir -p ~/polyhermes && cd ~/polyhermes && wget -O deploy.sh https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh && chmod +x deploy.sh && ./deploy.sh
```

这个命令会自动：
- 📁 创建专用工作目录 `~/polyhermes`
- 📥 下载部署脚本
- ✅ 自动检查 Docker 环境
- ⚙️ 交互式配置所有参数（支持回车使用默认值）
- 🔐 自动生成安全的随机密钥
- 🚀 自动下载最新镜像并部署

**或者直接通过管道运行（不保存文件）：**
```bash
# curl 方式
mkdir -p ~/polyhermes && cd ~/polyhermes && curl -fsSL https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh | bash

# wget 方式
mkdir -p ~/polyhermes && cd ~/polyhermes && wget -qO- https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh | bash
```

### 方式一：直接下载脚本运行

```bash
# 下载脚本
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh

# 添加执行权限
chmod +x deploy-interactive.sh

# 运行
./deploy-interactive.sh
```

### 方式二：在项目目录中运行

```bash
git clone https://github.com/WrBug/PolyHermes.git
cd PolyHermes
./deploy-interactive.sh
```

## 📝 使用流程

运行脚本后，会引导你完成以下步骤：

```
步骤 1: 环境检查         → 检查 Docker/Docker Compose
步骤 2: 配置收集         → 交互式输入配置（可全部回车使用默认）
步骤 3: 获取部署配置     → 从 GitHub 下载 docker-compose.prod.yml
步骤 4: 生成环境变量文件 → 自动生成 .env
步骤 5: 拉取 Docker 镜像 → 从 Docker Hub 拉取最新镜像
步骤 6: 部署服务         → 启动容器
步骤 7: 健康检查         → 验证服务是否正常运行
```

## ⚡ 最简单的使用方式

**所有配置项直接回车使用默认值**，脚本会自动：
- 使用端口 80（应用）和 3307（MySQL）
- 生成 32 字符的数据库密码
- 生成 128 字符的 JWT 密钥
- 生成 64 字符的管理员重置密钥
- 生成 64 字符的加密密钥
- 配置合理的日志级别

### 交互示例

脚本会逐项提示你输入配置，**直接按回车即可跳过使用默认值**：

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  步骤 2: 配置收集
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

💡 所有配置项均为可选，直接按回车即可使用默认值或自动生成

⚠ 密钥配置：回车将自动生成安全的随机密钥
⚠ 其他配置：回车将使用括号中的默认值

【基础配置】
将配置：服务器端口、MySQL端口、时区
➤ 服务器端口 [默认: 80]: ⏎
➤ MySQL 端口（外部访问） [默认: 3307]: ⏎
➤ 时区 [默认: Asia/Shanghai]: ⏎

【数据库配置】
将配置：数据库用户名、数据库密码
➤ 数据库用户名 [默认: root]: ⏎
➤ 数据库密码 [回车自动生成]: ⏎
[✓] 已自动生成数据库密码（32字符）

【安全配置】
将配置：JWT密钥、管理员密码重置密钥、数据加密密钥
➤ JWT 密钥 [回车自动生成]: ⏎
[✓] 已自动生成 JWT 密钥（128字符）
➤ 管理员密码重置密钥 [回车自动生成]: ⏎
[✓] 已自动生成管理员重置密钥（64字符）
➤ 加密密钥（用于加密 API Key） [回车自动生成]: ⏎
[✓] 已自动生成加密密钥（64字符）

【日志配置】
将配置：Root日志级别、应用日志级别
可选级别: TRACE, DEBUG, INFO, WARN, ERROR, OFF
➤ Root 日志级别（第三方库） [默认: WARN]: ⏎
➤ 应用日志级别 [默认: INFO]: ⏎

【其他配置】
将配置：运行环境、自动更新策略、GitHub仓库
➤ Spring Profile [默认: prod]: ⏎
➤ 允许预发布版本更新 (true/false) [默认: false]: ⏎
➤ GitHub 仓库 [默认: WrBug/PolyHermes]: ⏎
```

## 🔧 脚本生成的文件

脚本运行后会在当前目录生成：

1. **docker-compose.prod.yml** - 从 GitHub 下载的 Docker Compose 配置文件（始终保持最新）
2. **.env** - 根据你的配置自动生成的环境变量文件

这两个文件包含了运行 PolyHermes 所需的全部配置。

## 🌐 部署后管理

### 快速更新（推荐）

如果已有配置文件，再次运行脚本时会自动检测并询问：

```bash
./deploy-interactive.sh
```

```
【检测到现有配置】
发现已存在的 .env 配置文件

是否使用现有配置直接更新镜像？[Y/n]: ⏎
```

- **回车或输入 Y**：使用现有配置，直接拉取最新镜像并更新
- **输入 N**：重新配置（会备份现有配置）

### 手动管理命令

```bash
# 查看服务状态
docker compose -f docker-compose.prod.yml ps

# 查看日志
docker compose -f docker-compose.prod.yml logs -f

# 重启服务
docker compose -f docker-compose.prod.yml restart

# 停止服务
docker compose -f docker-compose.prod.yml down

# 更新到最新版本
docker pull wrbug/polyhermes:latest
docker compose -f docker-compose.prod.yml up -d
```

## 🔐 安全建议

- **保护 .env 文件**：其中包含敏感信息，切勿提交到版本控制
- **定期备份数据库**：数据存储在 Docker volume `mysql-data` 中
- **生产环境配置 HTTPS**：建议使用 Nginx 或 Caddy 作为反向代理

## 📞 获取支持

- [GitHub 仓库](https://github.com/WrBug/PolyHermes)
- [问题反馈](https://github.com/WrBug/PolyHermes/issues)
- [完整部署文档](docs/zh/DEPLOYMENT.md)
