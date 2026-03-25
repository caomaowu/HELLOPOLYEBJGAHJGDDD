# Agent Guide

本文件供 AI 编码助手使用。目标是：在不打扰用户节奏的前提下，快速理解项目、修改代码、验证改动。

## 项目概览

- 项目名：`PolyHermes`
- 这是一个面向实盘使用的 Polymarket 多账户交易与跟单平台
- 技术栈：
  - 后端：`Spring Boot + Kotlin`
  - 前端：`React + TypeScript + Vite`
- 正式开发、验证和发布均以当前主项目为准

## 常用开发命令

### 开发环境

```bash
./dev-scripts/init-dev-env.sh
./dev-scripts/start-dev.sh
./dev-scripts/stop-dev.sh
```

### 后端

```bash
cd backend
./gradlew build
./gradlew bootRun
```

### 前端

```bash
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

### 生产环境

```bash
./prod-scripts/init-prod-env.sh
./prod-scripts/build-prod.sh
./prod-scripts/start-prod.sh
./prod-scripts/stop-prod.sh
```

## AI 修改代码规则

- 先读相关代码，再动手修改；不要脱离现有实现凭空重写
- 改动后只做必要验证；不要因为修了一个 bug 就直接开始生产构建或重建
- 涉及生产端时，`build`、`start`、`restart`、重建产物等操作，必须等用户明确指挥后再执行
- 如果只是排查或修复代码，默认不要主动执行 `./prod-scripts/build-prod.sh`、`./prod-scripts/start-prod.sh`、`./prod-scripts/deploy-prod.sh`
- 如需验证，优先使用局部验证方式：
  - 后端改动优先跑相关构建/测试/启动
  - 前端改动优先跑 `npm run lint` 或 `npm run build`
- 不要修改与当前任务无关的文件
- 不要擅自改动生产配置、密钥、数据库结构或部署脚本；除非用户明确要求
- 输出结论时要说明：
  - 改了什么
  - 是否已验证
  - 哪些操作还没有执行

## 沟通约定

- 默认使用中文
- 如果需要执行可能耗时、影响运行环境或涉及生产流程的操作，先说明再等用户指令
