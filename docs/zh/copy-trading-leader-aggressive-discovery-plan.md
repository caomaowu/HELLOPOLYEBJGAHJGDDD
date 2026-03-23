# Leader 激进发现能力整合方案

## 1. 目标

本方案的目标不是简单“多加一个扫描按钮”，而是在不影响现有交易执行链路的前提下，把 `polymarket-copy-trading-bot-main` 中更偏“大范围发现 trader”的能力，整合进 `PolyHermes` 的 Leader 发现体系。

这次整合要解决两个问题：

- 让 `PolyHermes` 不再只依赖“开放市场订单簿 owner -> 活跃地址校验”这一条主路径
- 把更激进的发现结果，继续接入现有候选池、标签、推荐评估体系，而不是变成一个孤立脚本

## 2. 现状判断

### 2.1 旧项目的有效能力

`polymarket-copy-trading-bot-main` 的 `scanTradersFromMarkets.ts` 已经实装了以下能力：

- 扫开放市场
- 扫订单簿 owner 抓地址
- 批量活跃地址校验
- seed traders 补充

但它的“网络扩散”目前只是半成品：

- 它能从 trader activity 里提取相关 market
- 但没有真正形成 `trader -> related markets -> new traders` 的闭环扩散

所以我们不应该照搬脚本形式，而应该把“真正有价值的发现能力”重新接入 `PolyHermes`。

### 2.2 PolyHermes 当前基础

当前 `PolyHermes` 已经具备很好的发现基础，关键入口都在：

- `backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/leaders/LeaderDiscoveryService.kt`
- `backend/src/main/kotlin/com/wrbug/polymarketbot/dto/LeaderDiscoveryDto.kt`
- `backend/src/main/kotlin/com/wrbug/polymarketbot/controller/copytrading/leaders/LeaderController.kt`

当前已可复用主能力：

- `scanMarkets`
  - 开放市场抓取
  - 订单簿 owner 扫描
  - 活跃地址批量校验
- `scanTraders`
  - seed leader / seed address -> seed markets -> market traders
- `lookupMarketTraders`
  - 对指定 market 直接按成交记录找 trader
- `recommendCandidates`
  - 把候选地址继续做评估和候选池更新

结论：

- `PolyHermes` 的基础设施比旧项目更成熟
- 但“尽量发现更多 trader”的主动发现能力还不够强
- 最缺的是一个真正闭环的“扩散式发现管线”

## 3. 设计原则

### 3.1 不新起孤立脚本

不建议新建一个和主系统平行的扫描脚本。

原因：

- 旧项目脚本式逻辑不利于候选池管理
- 已有后端 API、DTO、缓存、重试、代理隔离、候选池持久化都可复用
- 新脚本会让前端、日志、错误处理、后续运营标注再次分叉

### 3.2 优先扩展现有 `scan-markets`

第一阶段不新增完全独立的发现模块，优先扩展现有：

- `POST /api/copy-trading/leaders/discovery/scan-markets`

原因：

- 现在前端“全市场扫描”已经打通
- 现有扫描语义就是“面向广域发现”
- 只要新增可选参数，就可以兼容现有请求

### 3.3 真正补的是“一跳闭环扩散”

第一阶段最有价值的新增能力不是随机化，而是补一条真正闭环的扩散路径：

1. 先从开放市场订单簿扫出一批活跃地址
2. 补入 seed addresses
3. 从这些 trader 的 activity 中提取相关 marketIds
4. 再对这些 marketIds 调用现有 market trade 发现逻辑，找出新的 trader
5. 合并、去重、校验、过滤、入池

这样做比旧项目当前脚本更完整，也更适合系统化落地。

## 4. 建议的后端接入点

## 4.1 主要修改类

### A. `LeaderDiscoveryService.kt`

这是核心接入点，建议继续作为发现编排入口。

建议新增内容：

- 在 `scanMarkets` 中支持“激进发现模式”
- 新增私有方法：
  - `discoverAggressiveCandidates(...)`
  - `expandMarketsFromSeedTraders(...)`
  - `discoverTradersFromExpandedMarkets(...)`
  - `mergeDiscoveryCandidates(...)`
  - `enrichDiscoveredAddresses(...)`

建议不要把第一阶段拆成太多 service，先保持在 `LeaderDiscoveryService` 内部闭环，避免过早抽象。

### B. `LeaderDiscoveryDto.kt`

这里需要扩展扫描请求和响应，支持表达“激进发现”的控制参数和统计信息。

建议新增到 `LeaderMarketScanRequest` 的字段：

- `mode: String? = "ORDERBOOK"`
- `seedAddresses: List<String>? = null`
- `includeSeedAddresses: Boolean? = true`
- `expansionRounds: Int? = 1`
- `expansionSeedTraderLimit: Int? = 30`
- `expansionMarketLimit: Int? = 60`
- `expansionTradeLimitPerMarket: Int? = 40`

第一阶段建议只支持两种模式：

- `ORDERBOOK`
- `AGGRESSIVE`

建议新增到 `LeaderMarketScanResponse` 的字段：

- `discoveryMode: String`
- `seedAddressCount: Int`
- `expandedMarketCount: Int`
- `expandedTraderCount: Int`
- `sources: List<String>`

### C. `LeaderController.kt`

控制器可以继续复用现有接口：

- `POST /api/copy-trading/leaders/discovery/scan-markets`

第一阶段不建议新增 `/scan-markets-aggressive`。

原因：

- 一个入口更容易复用前端
- 参数兼容即可
- 后面如果模式继续变多，再考虑拆 API

### D. `LeaderDiscoveryServiceTest.kt`

这是第一阶段必须同步补测试的地方。

至少新增以下测试：

- `AGGRESSIVE` 模式会把 seed addresses 纳入发现集合
- `AGGRESSIVE` 模式会从 trader activity 扩展 marketIds，并继续发现新 trader
- 扩展 market 请求部分失败时仍返回部分结果
- 关闭扩展时行为退化为现有 `scanMarkets`

## 4.2 可能需要新增但不是第一阶段必须的类

如果第一阶段完成后逻辑已经明显变长，再考虑二阶段抽出：

- `LeaderAggressiveDiscoveryPipeline`
- `LeaderDiscoverySourceStats`
- `LeaderDiscoveryExpansionResult`

第一阶段不强制新增这些类，避免为抽象而抽象。

## 5. 可以直接复用的已有方法

以下能力已经在 `LeaderDiscoveryService` 里，第一阶段应该尽量复用，而不是重写：

- `fetchOpenMarkets(...)`
  - 继续作为开放市场入口
- `scanOrderbookOwners(...)`
  - 继续作为第一批候选地址来源
- `validateActiveAddressesInBatch(...)`
  - 继续用于批量确认 trader 是否真实活跃
- `fetchUserActivities(...)`
  - 用于从已发现 trader 提取相关市场
- `fetchMarketTrades(...)`
  - 用于从扩展 market 获取 trader
- `discoverTraders(...)`
  - 可直接复用为“扩展市场 -> trader”的二跳发现能力
- `buildMarketBreakdown(...)`
  - 用于响应展示 sample markets
- `fetchUserPositions(...)`
  - 用于持久化到候选池前的补充评估
- `applyScanFilters(...)`
  - 继续承接 favorite / tags / blacklist 过滤
- `executeWithRetry(...)`
  - 继续承接重试逻辑

现有基础意味着：

- 不需要新增外部 API 依赖
- 不需要改动交易执行链路
- 不需要改代理配置模型

## 6. 推荐的第一阶段最小实现切片

## 6.1 范围

第一阶段只做“闭环的一跳扩散”，不做无限多轮扩散，不做复杂打分重构。

最小闭环如下：

1. 扫开放市场
2. 扫订单簿 owner
3. 批量校验活跃地址
4. 合并 seed addresses
5. 从一部分已确认 trader 拉近期 activity
6. 提取相关 marketIds
7. 对这些 marketIds 复用 `discoverTraders(...)` 再找一批 trader
8. 合并、去重、过滤
9. enrich + 可选持久化到候选池

这就是第一阶段的 MVP。

## 6.2 具体行为

当 `mode=ORDERBOOK` 时：

- 保持现有 `scanMarkets` 行为不变

当 `mode=AGGRESSIVE` 时：

- 先执行现有 orderbook 扫描逻辑
- 再补入 `seedAddresses`
- 从已发现地址中选取一批扩展种子
- 对每个扩展种子拉 `activity`
- 提取去重后的 `conditionId/marketId`
- 过滤已扫描市场
- 对新市场复用 `discoverTraders(...)`
- 对扩展得到的新地址再次合并
- 再走现有 enrichment / persistence

## 6.3 为什么这是最小可交付

因为它满足三个关键条件：

- 对用户有直接效果：发现来源立刻变多
- 对现有代码侵入小：大量复用现有方法
- 对系统风险可控：扩展轮数、扩展 trader 数、扩展 market 数都可以限制

## 7. 第一阶段不做什么

以下内容明确不放进 MVP：

- 不做无限多轮 network expansion
- 不照搬旧项目随机抽样策略
- 不把 trader 模拟收益分析塞进 `scan-markets`
- 不新建脚本式入口
- 不改交易执行、跟单实时链路
- 不新增数据库表

说明：

- 候选池持久化仍复用现有逻辑
- 发现增强和推荐评估继续解耦

## 8. 推荐任务拆分

## Phase 1: 后端 MVP

状态：待开始

### Task 1. 扩展 DTO 与接口参数

涉及文件：

- `backend/src/main/kotlin/com/wrbug/polymarketbot/dto/LeaderDiscoveryDto.kt`
- `backend/src/main/kotlin/com/wrbug/polymarketbot/controller/copytrading/leaders/LeaderController.kt`

交付：

- `scan-markets` 支持 `mode` 和扩展参数
- 向后兼容旧请求

验收：

- 旧前端不改也能继续调用
- 默认行为仍是当前 orderbook 模式

### Task 2. 在 `LeaderDiscoveryService` 中落地激进发现管线

涉及文件：

- `backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/leaders/LeaderDiscoveryService.kt`

交付：

- `AGGRESSIVE` 模式主流程
- 一跳扩展市场发现
- 合并与统计输出

验收：

- 新模式能发现 orderbook 未直接暴露、但在扩展市场里活跃的 trader
- 扩展失败不影响主流程返回部分结果

### Task 3. 测试覆盖

涉及文件：

- `backend/src/test/kotlin/com/wrbug/polymarketbot/service/copytrading/leaders/LeaderDiscoveryServiceTest.kt`

交付：

- 新模式核心行为测试
- 异常与部分失败测试

验收：

- 现有测试不回归
- 新测试能稳定证明扩展路径生效

## Phase 2: 前端控制与可观测性

状态：待开始

### Task 4. 前端暴露扫描模式和限流参数

交付：

- 扫描页面支持切换 `ORDERBOOK / AGGRESSIVE`
- 可选展示 seed 地址与扩展参数

### Task 5. 增加结果来源统计展示

交付：

- 展示本次发现来自哪些来源
- 展示扩展 market / trader 数量

## Phase 3: 二阶段增强

状态：待开始

### Task 6. 多轮扩展

交付：

- 在一跳扩展稳定后，再评估是否支持 2 到 3 轮扩展

### Task 7. 发现与推荐解耦优化

交付：

- 如果 `scanMarkets` 逻辑过长，再抽出 pipeline class

## 9. 风险与控制

### 9.1 风险：API 压力升高

原因：

- 激进模式会额外调用 activity 和 market trades

控制：

- 限制 `expansionRounds`
- 限制 `expansionSeedTraderLimit`
- 限制 `expansionMarketLimit`
- 继续复用 isolated API client 和 retry

### 9.2 风险：返回时间变长

原因：

- 新模式比纯 orderbook 模式请求更多

控制：

- 先只做一跳扩展
- 所有扩展参数都有上限
- 前端继续使用更长请求超时

### 9.3 风险：发现结果质量下降

原因：

- 扩展后容易引入噪音 trader

控制：

- 扩展后仍保留活跃校验
- 仍使用现有 blacklist / existing leader 过滤
- 仍支持后续 `recommendCandidates` 做风险筛选

## 10. 完成定义

当以下条件全部成立，第一阶段可视为完成：

- `scan-markets` 新增 `AGGRESSIVE` 模式
- 激进模式真实实现一跳闭环扩展，而不是只拿到 marketIds 不落地
- 默认模式仍完全兼容现有行为
- 新增测试覆盖扩展路径和部分失败场景
- 扫描结果仍能进入候选池并接入现有评估流程

## 11. 建议的实施顺序

建议按这个顺序推进：

1. 先改 DTO，确定请求/响应模型
2. 再改 `LeaderDiscoveryService`，先做一跳扩展闭环
3. 立即补测试，确认默认模式不回归
4. 最后再决定前端是否暴露高级参数

## 12. 当前建议

建议直接进入 Phase 1。

原因：

- 现有后端基础已经够用
- 这次不是从零设计，而是在成熟发现链路上补“闭环扩散”
- 第一阶段做完后，`PolyHermes` 的发现能力就会实质超过旧项目当前脚本版本
