# PolyHermes

[![GitHub](https://img.shields.io/badge/GitHub-WrBug%2FPolyHermes-blue?logo=github)](https://github.com/WrBug/PolyHermes)
[![Twitter](https://img.shields.io/badge/Twitter-@polyhermes-blue?logo=twitter)](https://x.com/polyhermes)
[![Docker](https://img.shields.io/docker/v/wrbug/polyhermes?label=Docker&logo=docker)](https://hub.docker.com/r/wrbug/polyhermes)

> 中文文档

PolyHermes 是一个面向实盘使用的 Polymarket 平台型交易系统，不只是“跟单脚本”。  
它把多账户管理、Leader 发现、自动跟单、回测审计、加密尾盘策略、仓位处理、系统诊断和运行配置收口到统一的 Web UI 与后端服务中。

当前主项目为 `PolyHermes`，所有正式开发、验证和发布均以它为准。

---

## 目录

- [产品定位](#产品定位)
- [当前能力总览](#当前能力总览)
- [界面预览](#界面预览)
- [技术亮点](#技术亮点)
- [部署方式](#部署方式)
- [环境配置](#环境配置)
- [文档入口](#文档入口)
- [免责声明](#免责声明)

---

## 产品定位

PolyHermes 当前的定位是：

- 面向 Polymarket 的多账户交易与跟单平台
- 强调配置化、可观测、可运营，而不是一次性脚本
- 同时覆盖实盘执行、策略评估、运行诊断和系统维护

适用场景：

- 需要同时管理多个账户、多个 Leader、多个跟单关系
- 需要在 Web UI 中完成配置、监控、诊断与排障
- 需要保留执行事件、过滤原因、回测审计链，便于复盘
- 需要把代理、RPC、通知、Builder API、自动赎回等运行参数纳入统一管理

---

## 当前能力总览

下面列的是当前代码中已经存在、并已接入前后端主链路的能力。

### 1. 账户与安全

- 支持私钥和助记词导入账户
- 导入时自动推导地址并校验格式
- 导入时自动识别代理钱包类型，支持选择 `Safe / Magic` 路径
- 导入后可检查账户初始化状态：
  - 代理钱包是否已部署
  - 交易是否已启用
  - Token 授权是否完成
- 支持多账户统一管理、编辑、删除、余额查看
- 私钥和敏感凭证采用加密存储
- 支持登录鉴权、首次使用重置密码、用户管理

### 2. Leader 管理与 Discovery

- 支持手动添加、编辑、删除 Leader
- 支持查看 Leader 详情、余额、持仓和关联统计
- 支持按 Leader 跳转查看关联的跟单配置和回测任务
- 已接入 Leader Discovery 能力：
  - 扫描近期活跃 Trader
  - 推荐候选 Leader
  - 按市场反查活跃 Trader
- 已具备候选池运营能力：
  - 收藏
  - 黑名单
  - 标签
  - 备注
  - 批量标注
  - 评分历史
  - activity 历史事件沉淀

### 3. 跟单模板与跟单配置

- 支持模板化管理跟单参数
- 支持账户、Leader、模板三者组合为独立跟单关系
- 支持启用、停用、编辑、删除跟单配置
- 支持以下下单模式：
  - `RATIO`
  - `FIXED`
  - `ADAPTIVE`
- 支持倍率模式：
  - `NONE`
  - `SINGLE`
  - `TIERED`
- 支持风险控制与限制项，包括但不限于：
  - 最大单笔金额
  - 最小单笔金额
  - 最大日亏损
  - 最大日交易量
  - 最大订单数
  - 价格容忍度
  - 市场截止时间限制
  - 关键字过滤
  - 是否跟卖
- 支持小额订单聚合：
  - BUY / SELL 双链路
  - 聚合窗口释放
  - 阈值释放
  - `leaderTradeId` 去重
  - 配置停用/删除后的缓冲清理

### 4. 实盘监听、执行与可观测性

- 采用双路监听架构，而不是单一轮询：
  - Polymarket `activity` WebSocket
  - Polygon on-chain WebSocket 日志监听
- 两路信号统一汇总到主执行链路处理
- 已接入执行前诊断：
  - 私钥
  - 地址
  - 代理钱包关系
  - API 凭证
  - allowance
  - 签名类型
- 已接入执行事件体系：
  - 执行阶段
  - 决策结果
  - 过滤/跳过原因
  - monitor 失败原因
  - 结构化 `detailJson`
- 已接入链路延迟埋点：
  - `leaderTradeTimestamp`
  - `sourceReceivedAt`
  - `processTradeStartedAt`
  - `orderCreateRequestedAt`
  - `orderCreateCompletedAt`
- `Activity WS` 已具备静默超时自愈与自动重连能力

### 5. 订单、仓位与执行结果查看

- 跟单订单支持统一查看：
  - 买入订单
  - 卖出订单
  - 匹配关系
  - 已过滤订单
  - 执行事件
- 支持按账户、Leader、状态、时间等维度筛选
- 仓位页面完全依赖 WebSocket 推送：
  - 首次连接接收全量仓位
  - 后续接收增量更新
- 支持仓位卖出：
  - 市价
  - 限价
- 支持批量赎回已结算仓位
- 支持查看可赎回仓位汇总

### 6. 统计与运营分析

- 提供全局统计
- 提供 Leader 统计
- 提供分类统计
- 提供单个跟单关系的详细统计
- 支持查看收益、胜率、订单数、持仓等关键指标

### 7. 回测、Compare 与 Audit

- 支持创建回测任务
- 支持查看回测任务列表、详情、交易记录、资金曲线
- 支持对同一 Leader 进行多任务对比
- 支持回测审计摘要
- 支持审计事件分页查询
- 支持按阶段、事件类型、决策结果查看 why-chain
- 支持从回测配置直接创建跟单配置
- 支持停止、删除、重试、按当前配置重新测试

### 8. 加密尾盘策略

PolyHermes 当前除了 Leader 跟单，还内置一套独立的加密尾盘策略能力：

- 支持 `BTC / ETH / SOL / XRP` 的 Up/Down 周期市场
- 支持 5 分钟与 15 分钟周期
- 支持按时间窗口触发
- 支持价格区间过滤
- 支持投入方式：
  - 比例
  - 固定金额
- 支持价差条件：
  - 无
  - 固定价差
  - 自动价差
- 支持最小/最大价差方向配置
- 支持触发记录查询
- 支持实时监控页
- 自动依赖系统级自动赎回能力与 Builder API
- 运行前会结合健康检查判断币安 API / WebSocket 可用性

### 9. 系统管理与运维能力

系统设置页当前不是单一“参数页”，而是一个运维入口，包含：

- Builder API Key 配置
- 自动赎回开关
- Telegram 消息通知配置
- 代理配置
- 在线更新
- 语言框架入口

已接入的系统能力包括：

#### 代理配置

- 支持通过 Web UI 配置并即时生效
- 支持协议：
  - `HTTP`
  - `HTTPS`
  - `SOCKS5`
- 支持：
  - 主机 / IP
  - 端口
  - 用户名
  - 密码
  - 启用 / 关闭
- 支持代理连通性检查
- 配置变更后自动刷新运行态代理
- 配置变更后自动触发相关 WebSocket 重连

#### 统一启动前健康检查

- 统一检查外部 API、WebSocket 链路、Builder、跟单账户执行前状态
- 支持显示阻断项、告警项、异常账户、修复建议
- 已成为第二阶段的主诊断入口

#### RPC 节点管理

- 支持添加、删除、启用、禁用 RPC 节点
- 支持优先级排序
- 支持健康检查与响应时间记录
- 支持常见提供商快捷配置：
  - Alchemy
  - Infura
  - QuickNode
  - Chainstack
  - GetBlock
  - Custom

#### 通知

- 当前已接入 Telegram 通知配置
- 支持新增、编辑、启停、删除、测试发送

#### 公告与版本更新

- 首页提供公告中心
- 支持读取公告详情与 Markdown 内容展示
- 支持在线检查新版本
- 支持在线更新系统版本
- 前端可显示当前版本和更新状态

---

## 界面预览

### 桌面端

<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_172940_894.png" alt="" width="90%" />
      </td>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173042_509.png" alt="" width="90%" />
      </td>
    </tr>
    <tr>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173105_822.png" alt="" width="90%" />
      </td>
      <td align="center">
        <img src="screenshot/pc/ScreenShot_2025-12-07_173133_527.png" alt="" width="90%" />
      </td>
    </tr>
  </table>
</div>

### 移动端

<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173224_069.png" alt="" width="70%" />
      </td>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173309_995.png" alt="" width="70%" />
      </td>
      <td align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173330_724.png" alt="" width="70%" />
      </td>
    </tr>
    <tr>
      <td colspan="3" align="center">
        <img src="screenshot/mobile/ScreenShot_2025-12-07_173354_840.png" alt="" width="70%" />
      </td>
    </tr>
  </table>
</div>

---

## 技术亮点

### 架构

- 后端：Spring Boot 3 + Kotlin + JPA + Flyway + MySQL
- 前端：React 18 + TypeScript + Vite + Ant Design
- 通信：REST + WebSocket
- HTTP 客户端：Retrofit + OkHttp

### 工程特性

- 实盘执行、回测评估、系统运维在同一产品内闭环
- 双路监听替代单一 polling
- 执行事件与审计链可用于定位“为什么没下单 / 为什么下单”
- 支持多账户、多 Leader、多配置、多策略并存
- 敏感数据加密存储
- 桌面端与移动端统一支持
- 前端当前默认以简体中文为主界面

---

## 部署方式

### 一体化部署（推荐）

推荐使用 Docker 一体化部署，前后端一起运行。

前置要求：

- Docker 20.10+
- Docker Compose 2.0+

### 一键安装

使用 `curl`：

```bash
mkdir -p ~/polyhermes && cd ~/polyhermes && curl -fsSL https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh -o deploy.sh && chmod +x deploy.sh && ./deploy.sh
```

使用 `wget`：

```bash
mkdir -p ~/polyhermes && cd ~/polyhermes && wget -O deploy.sh https://raw.githubusercontent.com/WrBug/PolyHermes/main/deploy-interactive.sh && chmod +x deploy.sh && ./deploy.sh
```

这个脚本会自动：

- 创建工作目录
- 检查 Docker 环境
- 交互式生成配置
- 生成随机密钥
- 拉取或构建镜像并启动服务

### 使用 Docker Hub 镜像

```bash
mkdir polyhermes && cd polyhermes
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/docker-compose.prod.yml
curl -O https://raw.githubusercontent.com/WrBug/PolyHermes/main/docker-compose.prod.env.example
cp docker-compose.prod.env.example .env
docker-compose -f docker-compose.prod.yml up -d
```

### 本地构建部署

```bash
./deploy.sh
```

### 手动部署

```bash
cat > .env <<EOF
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=your_password_here
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=80
JWT_SECRET=your-jwt-secret-key-change-in-production
ADMIN_RESET_PASSWORD_KEY=your-admin-reset-key-change-in-production
EOF

docker-compose build
docker-compose up -d
```

访问方式：

- 统一入口：`http://localhost:80`
- `/api/*` 自动代理到后端
- `/ws` 自动代理到 WebSocket

更多部署细节见：

- [部署文档](docs/zh/DEPLOYMENT.md)

---

## 环境配置

### 必需环境变量

| 变量名 | 说明 | 默认值 |
| --- | --- | --- |
| `DB_USERNAME` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库密码 | - |
| `SERVER_PORT` | 后端端口 | `8000` |
| `JWT_SECRET` | JWT 密钥 | - |
| `ADMIN_RESET_PASSWORD_KEY` | 管理员重置密码密钥 | - |
| `CRYPTO_SECRET_KEY` | 敏感数据加密密钥 | - |

### 运行配置说明

#### 1. Builder API Key

用于系统级 Gasless 操作，例如：

- 创建订单
- 赎回仓位
- 自动赎回
- 加密尾盘策略相关执行

#### 2. 自动赎回

- 可在系统设置中开启
- 加密尾盘策略依赖该能力

#### 3. 代理

当前支持通过 Web UI 配置：

- `HTTP`
- `HTTPS`
- `SOCKS5`

配置后即时生效，不需要重启服务。

#### 4. RPC 节点

建议为生产环境配置稳定的 Polygon RPC，并定期做健康检查。

---

## 文档入口

### 中文文档

- [部署文档](docs/zh/DEPLOYMENT.md)
- [开发文档](docs/zh/DEVELOPMENT.md)
- [动态更新文档](docs/zh/DYNAMIC_UPDATE.md)
- [版本管理文档](docs/zh/VERSION_MANAGEMENT.md)
- [跟单系统需求文档](docs/zh/copy-trading-requirements.md)
- [前端需求文档](docs/zh/copy-trading-frontend-requirements.md)

### 当前状态文档

如果你关心当前迁移与增强状态，可以继续看：

- [CONTEXT.md](../CONTEXT.md)
- [MIGRATION_PLAN.md](../MIGRATION_PLAN.md)

---

## 免责声明

本软件仅供学习、研究和系统开发使用。  
任何实盘交易风险、策略风险、代理与网络风险、第三方服务风险均由使用者自行承担。

---

## 许可证

本项目采用 MIT 许可证，详见 [LICENSE](LICENSE)。

---

## 相关链接

- [GitHub 仓库](https://github.com/WrBug/PolyHermes)
- [Twitter](https://x.com/polyhermes)
- [Telegram 群组](https://t.me/polyhermes)
- [Polymarket 官网](https://polymarket.com)
- [Polymarket API 文档](https://docs.polymarket.com)

---

如果这个项目对你有帮助，欢迎 Star。
