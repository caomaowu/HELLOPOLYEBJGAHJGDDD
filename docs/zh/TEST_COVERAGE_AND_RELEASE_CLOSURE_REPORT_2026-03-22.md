# 测试覆盖与发布收口总结报告（2026-03-22）

## 1. 背景与目标
本轮目标聚焦两项：
- 补齐 B/C 线后端自动化测试覆盖不足问题。
- 完成发布前收口，明确当前 `main` 分支可发布边界与风险。

## 2. 本轮完成内容

### 2.1 新增自动化测试（后端）
新增以下测试文件：
- `backend/src/test/kotlin/com/wrbug/polymarketbot/service/backtest/BacktestServiceTest.kt`
- `backend/src/test/kotlin/com/wrbug/polymarketbot/service/copytrading/leaders/LeaderDiscoveryServiceTest.kt`

覆盖能力：
- C 线（Backtest）：
  - compare 仅允许已完成任务参与比较。
  - compare 结果顺序与请求任务顺序一致。
  - compare 输出配置差异与摘要/why-chain。
  - audit-events 的分页与筛选参数归一化（stage/decision/eventType）及摘要统计。
- B 线（Leader Discovery）：
  - market lookup 在 candidate-pool 路径下默认排除黑名单。
  - `favoriteOnly + includeTags + excludeTags` 联合过滤行为。

### 2.2 回归验证
执行结果：
- 后端：`./gradlew.bat --no-daemon test`
  - 结果：`tests=26 failures=0 errors=0`
- 前端：`npm run build`
  - 结果：构建通过（存在 chunk 体积与动态导入告警，但不阻塞构建）。

### 2.3 发布收口处理
已清理本地未跟踪噪音文件：
- `backend/.gradle-home-local`
- `frontend/.eslintrc.cjs`

## 3. 当前分支边界状态
截至本报告，`main` 分支工作区包含：

### 3.1 本轮新增（可明确归属）
- 两个新增后端测试文件（见 2.1）。

### 3.2 已存在的未收敛改动（非本轮新增）
- `README.md`
- `README_EN.md`
- `backend/gradle/wrapper/gradle-wrapper.properties`
- `docs/en/DEVELOPMENT.md`
- `docs/zh/DEVELOPMENT.md`
- `frontend/src/App.tsx`
- `frontend/src/components/AccountImportForm.tsx`
- `frontend/src/i18n/config.ts`
- `frontend/src/pages/SystemSettings.tsx`
- `frontend/src/services/api.ts`
- `verify-implementation.sh`

## 4. 结论
- B/C 线自动化测试空白已完成第一轮补齐，当前后端测试与前端构建均通过。
- 发布收口已完成“噪音清理 + 边界识别”，但 `main` 仍存在历史未收敛改动，发布前应按业务边界进行最终选择与确认。

## 5. 建议的下一步
1. 先按“仅测试补丁”或“合并当前业务改动”二选一确定发布边界。
2. 若走业务合并发布，建议补一轮最小人工冒烟：
   - Leader discovery 筛选链路；
   - Backtest compare/audit 页面链路；
   - Copy-trading 执行事件与聚合快照查询链路。
3. 完成边界确认后再执行提交与发布动作，避免混入无关变更。
