# Leader Discovery 集成改造方案

## 1. 目标

本次改造的目标，不是简单把旧脚本搬进 `PolyHermes`，而是把旧项目里更激进的 trader 发现能力，整合到当前已经存在的 `Leader Discovery + Candidate Pool + 推荐评估` 体系中。

最终希望同时得到两种能力：

- 保留 `PolyHermes` 当前较稳定、较产品化的发现与候选池管理能力
- 补上旧项目更偏“大范围捞 trader”的发现能力，提高候选地址覆盖率

本次方案聚焦的问题是：

- `polymarket-copy-trading-bot-main` 的发现逻辑和本项目是否不同
- 如果不同，哪些能力值得整合
- 在 `PolyHermes` 里应该怎么落地，才能减少对现有交易链路的影响

## 2. 结论先行

两个项目的发现逻辑有实质区别。

### 2.1 旧项目更偏“广撒网”

旧项目 `scanTradersFromMarkets.ts` 的核心思路是：

1. 扫开放市场
2. 扫订单簿 owner
3. 批量校验地址是否活跃
4. 补 seed traders
5. 尝试做多轮扩散
6. 最后再进入分析和排名

它的目标很明确：先尽量找到更多 trader，再慢慢筛。

### 2.2 PolyHermes 当前更偏“结构化发现”

当前 `PolyHermes` 已有多条发现入口：

- `scanTraders`
- `scanMarkets`
- `lookupMarketTraders`
- `recommendCandidates`
- `backfillActivityHistory`

但这些入口目前还是偏“分工明确、各自收敛”，并没有形成一条完整的“广覆盖发现管线”。

### 2.3 一个重要修正

旧项目里“网络扩散”这部分不是完整成品。

它已经能从 trader activity 提取相关 market，但主流程并没有继续把这些 market 重新转成新的 trader 地址，因此它更像“半闭环扩散”，不是真正成熟的 `trader -> market -> trader` 递进发现引擎。

所以这次整合不能机械照抄旧项目，而要把它“想做但没做完整”的那一段，在 `PolyHermes` 里补成真正可用的能力。

## 3. 当前差异矩阵

| 能力 | polymarket-copy-trading-bot-main | PolyHermes 当前状态 | 判断 |
| --- | --- | --- | --- |
| 开放市场扫描 | 有 | 有 | 两边都有 |
| 扫订单簿 owner | 有 | 有 | 两边都有 |
| 批量活跃地址校验 | 有 | 有 | 两边都有 |
| seed traders 启动发现 | 有 | 有，但主要用于 `scanTraders` | 本项目已具备基础 |
| 从市场成交记录反查 trader | 弱，主要靠 orderbook | 有独立 `lookupMarketTraders` | 本项目反而更完整 |
| 多轮 trader 扩散 | 有框架，但不完整 | 没有独立闭环 | 两边都不完整 |
| 候选池持久化 | 几乎没有产品化体系 | 有 | 本项目明显更强 |
| 黑名单/收藏/标签/备注 | 脚本态 | 有 | 本项目明显更强 |
| 推荐与风险评估 | 有脚本分析 | 有结构化评估 | 本项目更适合长期维护 |

## 4. 改造目标

建议把目标拆成两个层次。

### 4.1 Phase 1 目标

先把“旧项目真正有效的发现部分”接进来：

- seed bootstrap
- market scan
- orderbook owner extraction
- active address validation
- market-trade based expansion

注意这里的“market-trade based expansion”不是旧项目那种半成品，而是要真正把发现闭环打通。

### 4.2 Phase 2 目标

把发现结果和 `PolyHermes` 的运营体系彻底打通：

- candidate pool
- score history
- 手工标签
- 推荐评估
- UI 参数化

### 4.3 非目标

本次不建议把旧项目整套分析脚本完整迁入。

原因：

- `PolyHermes` 已经有自己的推荐与评估入口
- 旧项目分析逻辑更偏单脚本批处理，不适合直接进主产品链路
- 当前真正缺的是“更强的发现”，不是第二套重复分析器

## 5. 设计原则

### 5.1 不影响交易热路径

Leader discovery 属于离线发现/半离线运营能力，不应影响跟单执行链路。

因此所有改造都应限定在：

- `LeaderController`
- `LeaderDiscoveryService`
- `TraderCandidatePoolService`
- 相关 DTO / 前端 discovery 页面

不进入实时跟单热路径。

### 5.2 复用现有能力，不新造体系

当前项目已经具备以下可复用基础：

- `fetchOpenMarkets`
- `scanOrderbookOwners`
- `validateActiveAddressesInBatch`
- `fetchMarketTrades`
- `fetchUserActivities`
- `lookupMarketTraders`
- `recommendCandidates`
- candidate pool 持久化能力

因此应该基于现有服务扩展，而不是再做一个平行的 `AggressiveDiscoveryService` 大分叉。

### 5.3 把“扩散”做成真的闭环

这次整合最关键的改进点不是“多加几个参数”，而是让扩散从半成品变成完整链路：

1. 先拿到初始 trader 集合
2. 从 trader activity 中提取更多 market
3. 用这些 market 的 trade 或 orderbook 继续发现新 trader
4. 新 trader 再进入下一轮
5. 每轮都做去重、活跃校验、黑名单和已有 leader 过滤

## 6. 推荐落地形态

### 6.1 接口层建议

不建议第一阶段直接新开完全独立页面。

建议在现有 `scan-markets` 基础上扩展请求参数，让当前“全市场扫描”支持两种模式：

- `STANDARD`
- `AGGRESSIVE`

推荐做法：

- 在 `LeaderMarketScanRequest` 新增 `mode`
- 默认值保持 `STANDARD`
- 前端在“全市场扫描”里增加“发现模式”切换

这样可以保证：

- 兼容现有调用
- 不破坏当前用户认知
- 后端实现可以逐步增强，而不是维护两套几乎重复的 API

### 6.2 请求参数建议

建议在 [LeaderDiscoveryDto.kt](/C:/Users/11618/Desktop/poly/PolyHermes/backend/src/main/kotlin/com/wrbug/polymarketbot/dto/LeaderDiscoveryDto.kt) 的 `LeaderMarketScanRequest` 扩展以下字段：

- `mode`
- `seedAddresses`
- `seedLeaderIds`
- `discoveryRounds`
- `expansionTraderSampleSize`
- `expansionMarketLimit`
- `expansionTradeLimitPerMarket`
- `enableOrderbookScan`
- `enableTradeExpansion`
- `enableSeedBootstrap`
- `maxTotalCandidateAddresses`

原则：

- 默认值必须保守，避免第一次上线就把外部 API 压垮
- 参数名要表达“发现范围”和“扩散规模”，而不是技术细节

### 6.3 响应结构建议

当前 `LeaderMarketScanResponse` 已有：

- `marketCount`
- `tokenCount`
- `rawAddressCount`
- `validatedAddressCount`
- `finalCandidateCount`
- `list`

建议补充以下观测字段：

- `seedAddressCount`
- `expansionRoundsCompleted`
- `expandedMarketCount`
- `tradeLookupAddressCount`
- `deduplicatedAddressCount`
- `sourceBreakdown`

其中 `sourceBreakdown` 可表达每类来源贡献了多少地址，例如：

- `orderbook`
- `seed`
- `marketTrades`
- `expansion`

这对后续调参很重要，否则只能看到最终人数，看不到来源质量。

## 7. 后端方案

### 7.1 现有接入点

当前最合适的主接入点仍然是：

- [LeaderController.kt](/C:/Users/11618/Desktop/poly/PolyHermes/backend/src/main/kotlin/com/wrbug/polymarketbot/controller/copytrading/leaders/LeaderController.kt)
- [LeaderDiscoveryService.kt](/C:/Users/11618/Desktop/poly/PolyHermes/backend/src/main/kotlin/com/wrbug/polymarketbot/service/copytrading/leaders/LeaderDiscoveryService.kt)
- [LeaderDiscoveryDto.kt](/C:/Users/11618/Desktop/poly/PolyHermes/backend/src/main/kotlin/com/wrbug/polymarketbot/dto/LeaderDiscoveryDto.kt)

原因很简单：

- API 已存在
- 前端 discovery 页面已接入这套接口
- 候选池、标签、推荐等后续链路都依赖这里

### 7.2 新增核心方法建议

建议在 `LeaderDiscoveryService` 内部新增一个独立主流程方法，例如：

- `scanMarketsAggressively(...)`

再由 `scanMarkets(...)` 根据 `mode` 分发：

- `STANDARD` -> 现有逻辑
- `AGGRESSIVE` -> 新逻辑

这样做比直接把现有 `scanMarkets` 改成一团更稳。

### 7.3 Aggressive 模式的推荐流程

推荐流程如下：

1. 获取开放市场
2. 对市场做订单簿 owner 扫描
3. 对 owner 地址批量活跃校验
4. 合并 seed addresses / seed leaders 解析出的地址
5. 形成初始 trader 集合
6. 从初始 trader 的 activity 中提取更多 marketId
7. 对扩散 market 做 `fetchMarketTrades`
8. 从 trade 中提取 trader 地址
9. 对新增地址再做批量活跃校验
10. 进入下一轮，直到达到轮数或候选上限
11. 统一做已有 leader / blacklist / tag 过滤
12. 生成 `LeaderDiscoveredTraderDto`
13. 可选持久化进 candidate pool

### 7.4 关键复用点

以下现有方法可以直接复用：

- `fetchOpenMarkets`
- `scanOrderbookOwners`
- `validateActiveAddressesInBatch`
- `fetchMarketTrades`
- `fetchUserActivities`
- `applyScanFilters`

这意味着新增代码重点不在底层请求，而在“调度组织”和“来源合并”。

### 7.5 真正需要补的能力

当前真正缺的不是“再调一个 API”，而是以下能力：

#### A. trader -> markets 扩散

需要把 trader 最近 activity 里的 `marketId / slug` 抽出来，形成扩散 market 集合。

#### B. markets -> traders 再发现

需要把扩散 market 通过 `fetchMarketTrades` 或订单簿，再反推出新 trader。

#### C. 多轮调度与去重

需要明确维护几个集合：

- `knownTraderAddresses`
- `validatedTraderAddresses`
- `processedTraderAddresses`
- `knownMarketIds`
- `processedMarketIds`

否则很容易重复请求或扩散失控。

#### D. 来源标记

每个候选 trader 最好记录来源：

- `ORDERBOOK`
- `SEED`
- `MARKET_TRADE`
- `EXPANSION_TRADE`

这样后续推荐和人工筛选更有依据。

## 8. 前端方案

### 8.1 最小改动

第一阶段只建议改 discovery 页，不建议重做页面结构。

建议新增以下表单项：

- 发现模式：`STANDARD / AGGRESSIVE`
- Seed 地址
- 扩散轮数
- 每轮扩散 trader 数量
- 扩散 market 上限
- 是否启用基于成交记录的扩散

### 8.2 结果展示

扫描结果建议新增两类信息：

- 顶部摘要统计
- 列表中展示来源标签

顶部摘要统计建议包含：

- 扫描市场数
- 订单簿发现地址数
- seed 地址数
- 扩散新增地址数
- 最终活跃地址数

列表建议在现有 trader 卡片/表格中补一列：

- `sourceType`

### 8.3 交互原则

如果选择 `AGGRESSIVE`，前端应提示：

- 耗时更长
- 外部 API 压力更大
- 更适合离线发现，不适合频繁点击

## 9. 项目拆分

## Epic A：后端发现管线

### A1. DTO 扩展

- 在 `LeaderMarketScanRequest` 增加模式与扩散参数
- 在 `LeaderMarketScanResponse` 增加来源统计字段
- 如有必要，为 `sourceBreakdown` 增加 DTO

### A2. 服务主流程

- 在 `LeaderDiscoveryService` 中加入 `AGGRESSIVE` 模式分发
- 新增 `scanMarketsAggressively`
- 提取统一的地址合并、去重、校验逻辑

### A3. 扩散实现

- trader activity -> marketIds
- marketIds -> market trades
- market trades -> trader addresses
- 多轮停止条件

### A4. 持久化与过滤

- 与现有 candidate pool 逻辑打通
- 复用 blacklist / favorite / manualTags 过滤能力

## Epic B：前端发现页

### B1. 参数表单

- 增加模式切换
- 增加 seed 与扩散参数输入项

### B2. 结果摘要

- 展示来源统计
- 展示扩散轮次和新增地址数量

### B3. 列表展示

- 增加来源字段
- 兼容已有候选池导入/查看逻辑

## Epic C：验证与观测

### C1. 单元测试

- DTO 默认值测试
- aggressive 模式分支测试
- 多轮去重测试
- 来源统计测试

### C2. 集成测试

- Gamma / CLOB / Data API 模拟响应
- 扩散过程中重复 market / trader 去重
- 外部 API 失败时的容错行为

### C3. 运行观测

- 日志里补充 round、marketCount、newTraderCount
- 响应里保留来源统计
- 避免再次出现“失败但像成功返回空列表”的情况

## 10. 建议实施顺序

### 第一阶段：最小可交付

目标是先做出“真的比现在更能找 trader”的版本。

范围：

- `scan-markets` 增加 `AGGRESSIVE` 模式
- 引入 seed bootstrap
- 引入 trader activity -> market -> trade -> trader 的一轮闭环扩散
- 结果可持久化到 candidate pool
- 前端支持最少必要参数

这一阶段不追求特别复杂的评分增强，只追求发现覆盖率明显提升。

### 第二阶段：把扩散做稳

范围：

- 多轮扩散
- 来源统计
- 更细粒度限流和上限控制
- 前端结果摘要完善

### 第三阶段：发现与推荐联动

范围：

- 基于来源类型调整推荐权重
- 在推荐候选时利用扩散来源和活跃度信息
- 让人工运营更容易筛出高价值 trader

## 11. 风险与控制

### 11.1 外部 API 压力上升

风险：

- `Gamma`
- `CLOB`
- `Data API`

都会因为扩散而增加请求量。

控制方式：

- 每轮 trader 数量上限
- 每轮 market 数量上限
- 批量校验尺寸上限
- 明确总候选上限
- 默认参数保守

### 11.2 扫描耗时变长

风险：

- 前端超时
- 用户误以为卡死

控制方式：

- 后端日志记录阶段进度
- 前端请求超时适当调大
- 返回更丰富的摘要统计

### 11.3 发现质量下降

风险：

- 找到很多噪音地址
- 订单簿挂单地址不一定是真正值得跟的 trader

控制方式：

- 必须保留活跃校验
- 必须保留已有 leader / blacklist 过滤
- 必须接上候选推荐与人工运营流程

### 11.4 无限扩散或重复扫描

风险：

- 同一批地址和市场被反复扫描

控制方式：

- trader / market 双集合去重
- round 计数器
- 上限到达即停止

## 12. 验收标准

当以下条件满足时，可以认为第一阶段完成：

- `scan-markets` 已支持 `AGGRESSIVE` 模式
- 可以输入 seed 地址参与启动发现
- 能完成至少一轮真正的 `trader -> market -> trader` 扩散
- 返回结果中能看出来源统计
- 能把结果进入 candidate pool
- 外部 API 失败时不会再伪装成“成功但空结果”
- 不影响现有 `STANDARD` 模式

## 13. 开发任务清单

- [ ] 扩展 `LeaderMarketScanRequest` 和 `LeaderMarketScanResponse`
- [ ] 在 `LeaderDiscoveryService` 增加 `mode` 分发
- [ ] 实现 `scanMarketsAggressively`
- [ ] 实现 trader activity 提取扩散 market
- [ ] 实现扩散 market 的 trade 反查 trader
- [ ] 增加多轮去重与停止条件
- [ ] 增加来源统计和来源标记
- [ ] 接入 candidate pool 持久化
- [ ] 增加 discovery 页参数表单
- [ ] 增加 discovery 页摘要展示
- [ ] 增加后端测试
- [ ] 增加前端联调验证

## 14. 推荐的第一刀

如果按“最快见效”的原则推进，建议先做这一刀：

1. 后端先加 `AGGRESSIVE` 模式
2. 只做一轮真实扩散，不急着做多轮
3. 先用 `fetchMarketTrades` 完成 `markets -> traders`
4. 前端只先加模式开关和少量参数

这样可以最快验证一个关键问题：

扩展发现来源后，`PolyHermes` 的候选地址覆盖率是否明显优于当前版本。

如果这一步效果明显，再继续做多轮扩散、来源统计和更细的运营能力，会更稳。
