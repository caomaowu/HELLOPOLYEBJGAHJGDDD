# Copy Trading Sizing Migration Todo

## Background
- 目标：将参考项目 `copyStrategy.ts` 的 sizing 能力迁移到 `PolyHermes`
- 约束：所有最终改动只能落在 `PolyHermes`
- 范围：实盘、模板、回测、前端配置与展示同步升级

## Decisions Locked
- 首批按全量 sizing 能力实现
- 回测同步升级，避免实盘与回测语义分叉
- 保留旧 `RATIO` / `FIXED` 配置兼容
- 不引入余额 buffer / 可用余额自适应缩减逻辑
- `maxDailyVolume` 只统计 BUY
- `tieredMultipliers` 按 leader 订单金额命中
- `maxPositionValue` 继续按市场 + outcomeIndex 控制

## Todo
- [x] 扩展实体、DTO、前端类型与 API
- [x] 新增 Flyway migration
- [x] 实现统一 sizing 服务
- [x] 接入实盘 BUY sizing 链路
- [x] 接入回测 sizing 链路
- [x] 更新模板/跟单/回测前端表单
- [x] 更新列表与详情展示
- [x] 补充测试与验证记录

## In Progress
- 整理本批迁移交付说明，准备进入人工联调与产品验收

## Done
- [x] 分析 `PolyHermes` 现有跟单与回测 sizing 落点
- [x] 分析参考项目 `copyStrategy.ts`
- [x] 确认首批功能范围、回测同步和开发记录要求
- [x] 为 `copy_trading`、`copy_trading_templates`、`backtest_task` 增加 sizing 新字段
- [x] 扩展后端实体、DTO、模板与回测配置映射
- [x] 落地统一 `CopyTradingSizingService`，覆盖 `RATIO` / `FIXED` / `ADAPTIVE` 与 multiplier 能力
- [x] 将实盘 BUY sizing 接入 `CopyOrderTrackingService`
- [x] 将回测 BUY sizing 接入 `BacktestExecutionService`
- [x] 前端新增 `ADAPTIVE`、`SINGLE`、`TIERED` 配置编辑与策略摘要展示
- [x] 补充 sizing 单元测试并修正拒绝态保留计算结果的输出语义
- [x] 收敛 `maxPositionValue` / `maxDailyVolume` 的重复职责，统一回归 sizing 阶段决策
- [x] 收敛 `tieredMultipliers` 的前后端校验与升序保存行为

## Open Questions
- 暂无阻塞性问题，进入联调/验收阶段

## Verification Notes
- `frontend`: `npm run build` 通过
- `frontend`: 构建存在 Vite 体积 warning 与 `api.ts` mixed import warning，但不阻塞本批 sizing 迁移交付
- `backend`: `./gradlew --no-daemon test --tests "com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingSizingServiceTest"` 通过
- `backend`: `./gradlew --no-daemon test` 通过
- 本地测试使用 `JAVA_HOME=C:\\Users\\gcb\\jdk17`
- 自适应 sizing 的拒绝态现在会保留已计算的 `appliedAdaptiveRatio` / `appliedMultiplier` / 金额结果，便于日志与后续前端展示复用
- `tieredMultipliers` 现在在前端提交前校验“非重叠、无上界仅最后一档”，后端保存时统一按 `min` 升序持久化
