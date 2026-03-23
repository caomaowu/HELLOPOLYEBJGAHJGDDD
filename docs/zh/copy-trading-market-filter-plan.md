# 跟单市场过滤改造规划

## 1. 目标

本次改造的目标不是单纯“多加几个筛选项”，而是在**尽量不牺牲跟单延迟**的前提下，让系统支持以下能力：

- 按市场分类过滤跟单，例如只跟 `crypto`、`sports` 或未来新增分类
- 按市场周期过滤跟单，例如只跟 `15m`，不跟 `5m`、`1h`、日级、长期市场
- 按市场系列过滤跟单，例如只跟 `btc-updown-15m`、`eth-updown-15m`
- 保持现有过滤能力兼容：
  - 关键字过滤
  - 市场截止时间过滤
  - 价格区间、价差、深度过滤
- 保持现有可观测性，继续追踪过滤命中率和延迟变化

本规划优先面向实盘链路，不先做“看起来完整但热路径很重”的方案。

## 2. 当前基础

当前仓库已经具备以下可复用基础：

- 跟单配置实体已支持过滤字段：
  - `keywordFilterMode`
  - `keywords`
  - `maxMarketEndDate`
- 执行链在下单前集中执行过滤逻辑
- 仅当需要时才获取市场信息或订单簿，已有一定延迟优化
- `Market` 表已缓存以下可用元数据：
  - `marketId`
  - `title`
  - `slug`
  - `eventSlug`
  - `category`
  - `endDate`
- Activity WS 消息已带：
  - `conditionId`
  - `asset/tokenId`
  - `slug`
  - `eventSlug`

这意味着本次改造不需要推翻现有架构，应采取“扩展过滤模型 + 压缩热路径远程依赖”的方式。

## 3. 核心判断

### 3.1 分类过滤可行性

分类过滤可直接建立在 `Market.category` 上，技术风险低。

但不能只依赖 `Leader.category`，因为同一个 Leader 可能会交易多个分类市场。Leader 级标签更适合运营分组，不适合作为单笔交易过滤依据。

### 3.2 周期过滤可行性

“只跟 15m”这类需求不能只用 `maxMarketEndDate` 实现。

原因：

- `maxMarketEndDate=15m` 只能表达“剩余时间不超过 15 分钟”
- 无法区分：
  - 一个 5 分钟市场
  - 一个还剩 15 分钟的 1 小时市场

因此必须新增“市场周期”概念，至少支持：

- `intervalSeconds`
- `marketType`
- `marketSeries`

### 3.3 延迟可控性

如果过滤判断建立在以下信息上，延迟可控：

- Activity WS 直接携带的 `slug/eventSlug`
- 本地缓存/数据库中的 `Market` 元数据
- 简单字符串匹配和数值比较

如果每次收到 Leader 交易后再实时请求 Gamma 才决定是否过滤，延迟会显著波动，尤其在：

- 冷启动
- 首次遇到新市场
- Gamma 响应变慢

因此必须把“远程补数”变成兜底，而不是主路径。

## 4. 设计原则

### 4.1 主路径原则

跟单热路径优先级：

1. 直接使用 WS 消息自带字段
2. 使用本地内存缓存
3. 使用数据库缓存
4. 最后才远程请求 Gamma 补全

### 4.2 配置表达原则

过滤配置必须区分三类能力：

- 粗粒度：
  - 分类过滤
  - 最大剩余时间过滤
- 中粒度：
  - 周期过滤，如 `300/900/3600/14400/86400`
- 细粒度：
  - 指定 slug 前缀白名单，如 `btc-updown-15m`

### 4.3 兼容原则

不破坏现有字段语义：

- `maxMarketEndDate` 继续保留，仍用于“过滤长周期”
- `keywordFilterMode` 继续保留
- 新字段默认关闭，确保旧配置升级后行为不变

### 4.4 可观测原则

新增过滤后，必须能回答：

- 这笔单为什么没跟
- 是命中了哪个过滤条件
- 过滤判断是否额外引入了热路径耗时

## 5. 推荐方案

### 5.1 数据模型扩展

建议在 `copy_trading` 增加以下字段：

- `market_category_mode`
  - `DISABLED`
  - `WHITELIST`
  - `BLACKLIST`
- `market_categories`
  - JSON 数组，例如 `["crypto"]`
- `market_interval_mode`
  - `DISABLED`
  - `WHITELIST`
  - `BLACKLIST`
- `market_intervals`
  - JSON 数组，例如 `[900]`
- `market_series_mode`
  - `DISABLED`
  - `WHITELIST`
  - `BLACKLIST`
- `market_series`
  - JSON 数组，例如 `["btc-updown-15m", "eth-updown-15m"]`

建议在 `markets` 增加以下冗余字段：

- `series_slug_prefix`
  - 例如 `btc-updown-15m`
- `interval_seconds`
  - 例如 `300`、`900`
- `market_source_type`
  - 例如 `CRYPTO_UPDOWN`, `GENERIC_BINARY`, `UNKNOWN`

这些字段可以由 `slug/eventSlug` 或 Gamma 返回结果推导并落库，减少后续重复解析。

### 5.2 热路径元数据策略

建议新增一个统一的“市场过滤元数据对象”，由执行链在进入过滤前构建：

- `marketId`
- `title`
- `category`
- `slug`
- `eventSlug`
- `seriesSlugPrefix`
- `intervalSeconds`
- `endDate`

构建顺序：

1. 从 WS payload 直接取 `slug/eventSlug`
2. 从 `Market` 缓存中补 `category/endDate`
3. 若缺失，再异步/兜底拉 Gamma

重点：

- Activity WS 路径应把 `slug/eventSlug` 继续传下去，不要在解析 `TradeResponse` 时丢掉
- On-chain 路径允许更多依赖 `MarketService`，因为它本来就不是最轻路径

### 5.3 周期识别规则

优先使用可确定规则识别周期：

- 若 slug 命中已知模式：
  - `*-5m-*` -> `300`
  - `*-15m-*` -> `900`
  - 后续可扩展 `1h/4h/1d`
- 若是本项目已验证的 crypto up/down 系列：
  - 直接从 slug 前缀和时间戳推导
- 若无法从 slug 识别：
  - 回退为 `UNKNOWN`
  - 不命中 interval 白名单时按“安全优先”拒绝或放行，需在配置中明确

推荐默认策略：

- 当启用了 interval/category/series 过滤，但市场元数据无法识别时：
  - 默认拒绝
  - 并记录清晰过滤原因

这是更适合实盘风险控制的做法。

## 6. 实施阶段

### Phase 1：最低风险版本

目标：尽快上线“分类过滤 + 长周期过滤增强”，几乎不改监听主结构。

范围：

- 新增市场分类过滤字段
- 沿用 `maxMarketEndDate` 处理长周期过滤
- 在 `CopyTradingFilterService` 中增加：
  - category 过滤
- 在前端配置页增加：
  - 分类白名单/黑名单
  - 对 `maxMarketEndDate` 的文案改为“最大剩余时间”
- 在过滤记录中增加新 filter type

收益：

- 能先解决“只跟 crypto / sports”
- 能先解决“不要十几天的市场”
- 改动较小

### Phase 2：精确周期过滤

目标：支持“只跟 15m，不跟 5m/1h/日级”。

范围：

- `markets` 表新增 `series_slug_prefix`、`interval_seconds`
- `copy_trading` 新增 interval 过滤字段
- Activity WS 路径保留 `slug/eventSlug`
- 增加 slug 解析器：
  - 从 WS 或 market 数据中提取 `seriesSlugPrefix`
  - 识别 `intervalSeconds`
- 在过滤器中新增：
  - interval 过滤
  - series 过滤

收益：

- 能准确表达 15m 需求
- 不再错误依赖剩余时间近似周期

### Phase 3：低延迟强化

目标：把主路径远程依赖继续压低。

范围：

- 在监听阶段提前做市场元数据预热
- 为近期活跃 marketId 建立短期内存缓存
- 对 Activity WS 首次出现的新 market 做异步补全，不阻塞后续相同市场
- 增加过滤耗时埋点：
  - `marketMetaResolveMs`
  - `filterEvaluateMs`

收益：

- 降低冷启动抖动
- 让后续优化有量化依据

## 7. API 与前端改造建议

### 7.1 后端 DTO

在创建/更新跟单配置 DTO 中新增：

- `marketCategoryMode`
- `marketCategories`
- `marketIntervalMode`
- `marketIntervals`
- `marketSeriesMode`
- `marketSeries`

要求：

- 默认值全部为 `DISABLED` / `null`
- 与现有 `keywordFilterMode` 保持一致设计风格

### 7.2 前端交互

先不要一次把所有高级选项塞满表单，推荐分层展开：

1. 基础过滤
   - 分类
   - 最大剩余时间
2. 高级过滤
   - 周期
   - 系列
   - 关键字

建议前端文案明确区分：

- “最大剩余时间”
- “市场周期”
- “市场系列”

避免用户把三者混为一谈。

## 8. 测试规划

### 8.1 单元测试

覆盖：

- category 白名单/黑名单
- interval 白名单/黑名单
- series 白名单/黑名单
- 元数据缺失时的行为
- `maxMarketEndDate` 与 interval 并存时的优先级

### 8.2 集成测试

覆盖：

- Activity WS 收到 crypto 15m 交易时正确命中过滤
- sports 市场不命中 crypto 白名单
- 首次遇到新 market 时从缓存/数据库/远程补全的行为

### 8.3 回归测试

重点回归：

- 未开启新过滤的旧配置行为不变
- 原有关键字过滤不受影响
- 原有买卖、聚合、审计链不受影响

### 8.4 性能观察

上线前后对比：

- `sourceToProcessMs`
- `processToOrderRequestMs`
- 过滤命中率
- 首次新市场命中时的额外耗时

## 9. 风险清单

### 9.1 误判风险

风险：

- 部分市场 slug 不符合预期规则
- Gamma category 不稳定或缺失

应对：

- 对无法识别的市场记录详细原因
- 先对白名单模式采用安全优先
- 逐步积累异常 market 样本

### 9.2 兼容性风险

风险：

- DTO、数据库、前端表单一起改，容易漏字段

应对：

- 按 Phase 分批落地
- 每个阶段先通后优

### 9.3 延迟风险

风险：

- 主路径新增远程查询或复杂解析

应对：

- 规则判断必须优先本地完成
- 远程补全只能做兜底

## 10. 推荐开发顺序

建议严格按以下顺序推进：

1. 先补规划文档与字段设计
2. 再做数据库迁移和实体/DTO 扩展
3. 再做过滤器扩展
4. 再做 Activity WS 链路元数据透传
5. 再做前端配置
6. 最后做埋点、优化和文档回填

不要一开始就同时改：

- 数据库
- 监听链
- 过滤器
- 前端 UI
- 审计展示

这样很容易把上下文撑爆，也很难定位回归。

## 11. 上下文锚点

为了避免后续对话“失忆”或需求漂移，后续每次继续开发前，优先回看这几个文件：

- 本文档：
  - `docs/zh/copy-trading-market-filter-plan.md`
- 当前需求总文档：
  - `docs/zh/copy-trading-requirements.md`
- 当前执行过滤入口：
  - `backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/configs/CopyTradingFilterService.kt`
- 当前主执行链：
  - `backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/statistics/CopyOrderTrackingService.kt`
- 当前市场缓存服务：
  - `backend/src/main/kotlin/com/wrbug/polymarketbot/service/common/MarketService.kt`
- Activity WS 交易解析：
  - `backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/monitor/PolymarketActivityWsService.kt`

后续每轮开发建议遵循以下工作法：

1. 先确认本轮只做一个 Phase 或一个子任务
2. 开始编码前回看本文档的“目标、风险、顺序”
3. 完成后把变更回填到本文档或相关设计文档
4. 不在同一轮里同时推进多个高耦合模块

## 12. 当前落地状态（2026-03-23）

截至当前代码状态，本规划的 Phase 1 与 Phase 2 主体已经落地，且前后端主链路、可观测性链路都已打通：

- 数据库与后端模型已扩展：
  - `copy_trading` / `copy_trading_templates` 已支持 category / interval / series 三组过滤字段
  - `markets` 已支持 `series_slug_prefix`、`interval_seconds`、`market_source_type`
- 过滤器已接入：
  - 实盘执行链
  - 回测执行链
  - 小额订单聚合链路
- 热路径已优化：
  - Activity WS 解析结果已透传 `slug` / `eventSlug`
  - 主路径优先使用 WS + 本地推导 + 本地缓存
  - 仅在元数据不足时才回退查询 `MarketService`
- 前端已接入：
  - 跟单配置新增 / 编辑页
  - 模板新增 / 编辑 / 复制页
  - 类型定义与提交 payload
  - 跟单配置列表 / 模板列表过滤摘要展示
  - 执行事件页耗时标签展示
  - 执行事件页“按耗时指标 + 最小耗时阈值”筛选
  - 执行事件页“Top 20 慢单 / 慢单阈值 / 元数据慢 / 过滤慢”快捷入口
  - 跟单列表页慢单健康摘要展示
  - 跟单统计页执行延迟摘要卡片展示
- 可观测性已增强：
  - 热路径已记录 `marketMetaResolveMs`
  - 热路径已记录 `filterEvaluateMs`
  - 执行事件详情已记录 `marketMetaSource`
  - 可以直接筛选慢单，定位是元数据解析慢、过滤慢，还是整体下单慢
  - `statistics.detail` 已支持最近 100 条执行事件的慢单摘要聚合
- 已验证：
  - 后端过滤器与聚合相关单测通过
  - 前端 `npm run build` 已通过
  - 执行事件耗时筛选后端编译 / 测试通过
  - 执行事件耗时筛选服务单测已补齐，覆盖过滤 / 排序 / 分页
  - 执行事件接口参数校验已补齐，controller 单测已覆盖非法 latency 组合与时间范围
  - 市场过滤元数据解析测试已补齐，见 `CopyOrderTrackingMarketMetadataTest`，覆盖 `payload` 直出路径与 `market cache` 回退路径
  - `CopyTradingStatisticsServiceTest` 已补齐慢单摘要聚合覆盖，验证平均耗时、慢单数、超慢单数、元数据耗时峰值、过滤耗时峰值
  - 后端相关回归已通过：
    - `CopyTradingStatisticsServiceTest`
    - `CopyOrderTrackingServiceTest`
    - `CopyTradingExecutionEventServiceTest`
    - `CopyTradingControllerTest`

当前结论：

- “只跟 crypto / sports” 已具备实现基础
- “只跟 15m” 已不再依赖 `maxMarketEndDate` 的近似语义，而是可以依赖 `marketInterval` / `marketSeries`
- 在现有设计下，过滤复制不会必然显著增加交易延迟，前提是继续坚持“本地元数据优先，远程补数兜底”的原则

## 13. 下一轮建议动作

建议下一轮不要再回到字段设计阶段，而是进入“收口与增强”阶段，优先顺序如下：

- Task 1：补真正的端到端或半集成测试，尤其是 WS 首次遇到新市场时的过滤与埋点链路
- Task 2：补一条更贴近真实调用链的 service 级测试，覆盖 `processTrade/processBuyTrade -> resolveMarketFilterInput -> checkFilters -> detailJson latency` 的串联
- Task 3：视需要再扩展更多分类或更多 interval 识别规则
- Task 4：如果后续开放外部调用，再补更细的执行事件接口参数约束与文档说明
- Task 5：视需要增加按 `marketMetaSource` 或阶段的慢单聚合视图，方便判断瓶颈位置

继续推进时，仍建议每轮只做一类子任务，不要同时混改：

- 执行链
- 市场缓存
- 前端 UI
- 审计展示
- 埋点与性能优化

这样最不容易失控。
