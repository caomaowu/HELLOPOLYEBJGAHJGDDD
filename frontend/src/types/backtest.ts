/**
 * 回测相关类型定义
 */

export interface MultiplierTier {
  min: string
  max?: string | null
  multiplier: string
}

export type RepeatAddReductionStrategy = 'UNIFORM' | 'PROGRESSIVE'
export type RepeatAddReductionValueType = 'PERCENT' | 'FIXED'

/**
 * 回测任务创建请求
 */
export interface BacktestCreateRequest {
  taskName: string
  leaderId: number
  initialBalance: string
  backtestDays: number  // 1-30
  // 跟单配置
  copyMode?: 'RATIO' | 'FIXED' | 'ADAPTIVE'
  copyRatio?: string
  fixedAmount?: string
  adaptiveMinRatio?: string
  adaptiveMaxRatio?: string
  adaptiveThreshold?: string
  multiplierMode?: 'NONE' | 'SINGLE' | 'TIERED'
  tradeMultiplier?: string
  tieredMultipliers?: MultiplierTier[]
  maxOrderSize?: string
  minOrderSize?: string
  maxDailyLoss?: string
  maxDailyOrders?: number
  maxDailyVolume?: string
  repeatAddReductionEnabled?: boolean
  repeatAddReductionStrategy?: RepeatAddReductionStrategy
  repeatAddReductionValueType?: RepeatAddReductionValueType
  repeatAddReductionPercent?: string
  repeatAddReductionFixedAmount?: string
  supportSell?: boolean
  minPrice?: string
  maxPrice?: string
  maxPositionValue?: string
  keywordFilterMode?: 'DISABLED' | 'WHITELIST' | 'BLACKLIST'
  keywords?: string[]
  pageForResume?: number  // 用于恢复中断任务，从指定页码开始获取历史数据（从1开始）
}

/**
 * 回测任务列表请求
 */
export interface BacktestListRequest {
  leaderId?: number
  status?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
  sortBy?: 'profitAmount' | 'profitRate' | 'createdAt'
  sortOrder?: 'asc' | 'desc'
  page: number
  size: number
  pageForResume?: number  // 恢复时从指定页码开始
}

/**
 * 回测任务详情请求
 */
export interface BacktestDetailRequest {
  id: number
}

/**
 * 回测交易记录列表请求
 */
export interface BacktestTradeListRequest {
  taskId: number
  page: number
  size: number
}

/**
 * 回测进度查询请求
 */
export interface BacktestProgressRequest {
  id: number
}

/**
 * 回测任务停止请求
 */
export interface BacktestStopRequest {
  id: number
}

export interface BacktestCompareRequest {
  taskIds: number[]
}

export interface BacktestAuditRequest {
  taskIds: number[]
  targetTaskId?: number
  includeEventTrail?: boolean
  eventPageSize?: number
}

export interface BacktestAuditEventListRequest {
  taskId: number
  page?: number
  size?: number
  stage?: string
  decision?: string
  eventType?: string
}

/**
 * 回测任务删除请求
 */
export interface BacktestDeleteRequest {
  id: number
}

/**
 * 回测任务重试请求
 */
export interface BacktestRetryRequest {
  id: number
}

export interface BacktestRerunRequest {
  id: number
  taskName?: string
}

/**
 * 回测任务列表响应
 */
export interface BacktestListResponse {
  list: BacktestTaskDto[]
  total: number
  page: number
  size: number
}

/**
 * 回测任务详情响应
 */
export interface BacktestDetailResponse {
  task: BacktestTaskDto
  config: BacktestConfigDto
  statistics: BacktestStatisticsDto
}

/**
 * 回测交易记录列表响应
 */
export interface BacktestTradeListResponse {
  list: BacktestTradeDto[]
  total: number
  page: number
  size: number
}

export interface BacktestCompareResponse {
  list: BacktestCompareItemDto[]
  configDifferences: BacktestConfigDifferenceDto[]
  summary: BacktestCompareSummaryDto
}

export interface BacktestAuditResponse {
  compare: BacktestCompareResponse
  generatedAt: number
  summary?: BacktestAuditSummaryDto | null
  recentEvents?: BacktestAuditEventDto[]
  version: string
}

export interface BacktestAuditSummaryDto {
  taskId: number
  totalEvents: number
  passEvents: number
  skipEvents: number
  errorEvents: number
  stopEvents: number
  stageCounts: Record<string, number>
  latestEventAt?: number | null
}

export interface BacktestAuditEventDto {
  id: number
  taskId: number
  eventTime?: number | null
  stage: string
  eventType: string
  decision: string
  leaderTradeId?: string | null
  marketId?: string | null
  marketTitle?: string | null
  side?: string | null
  reasonCode?: string | null
  reasonMessage?: string | null
  detailJson?: string | null
  createdAt: number
}

export interface BacktestAuditEventListResponse {
  list: BacktestAuditEventDto[]
  total: number
  page: number
  size: number
  summary: BacktestAuditSummaryDto
}

/**
 * 回测进度响应
 */
export interface BacktestProgressResponse {
  progress: number  // 0-100
  currentBalance: string
  totalTrades: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
}

/**
 * 回测任务 DTO
 */
export interface BacktestTaskDto {
  id: number
  taskName: string
  leaderId: number
  leaderName: string | null
  leaderAddress: string | null
  initialBalance: string
  finalBalance: string | null
  profitAmount: string | null
  profitRate: string | null  // 百分比
  backtestDays: number
  startTime: number
  endTime: number | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
  progress: number  // 0-100
  totalTrades: number
  createdAt: number
  executionStartedAt: number | null
  executionFinishedAt: number | null
  dataSource: string
  errorMessage: string | null
  updatedAt: number
  lastProcessedTradeTime: number | null
  lastProcessedTradeIndex: number | null
  processedTradeCount: number
}

/**
 * 回测配置 DTO
 */
export interface BacktestConfigDto {
  copyMode: 'RATIO' | 'FIXED' | 'ADAPTIVE'
  copyRatio: string
  fixedAmount: string | null
  adaptiveMinRatio?: string | null
  adaptiveMaxRatio?: string | null
  adaptiveThreshold?: string | null
  multiplierMode?: 'NONE' | 'SINGLE' | 'TIERED'
  tradeMultiplier?: string | null
  tieredMultipliers?: MultiplierTier[] | null
  maxOrderSize: string
  minOrderSize: string
  maxDailyLoss: string
  maxDailyOrders: number
  maxDailyVolume?: string | null
  repeatAddReductionEnabled: boolean
  repeatAddReductionStrategy: RepeatAddReductionStrategy
  repeatAddReductionValueType: RepeatAddReductionValueType
  repeatAddReductionPercent?: string | null
  repeatAddReductionFixedAmount?: string | null
  supportSell: boolean
  minPrice: string | null
  maxPrice: string | null
  maxPositionValue: string | null
  keywordFilterMode: 'DISABLED' | 'WHITELIST' | 'BLACKLIST' | null
  keywords: string[] | null
}

/**
 * 回测统计 DTO
 */
export interface BacktestStatisticsDto {
  totalTrades: number  // 总交易笔数
  buyTrades: number  // 买入笔数
  sellTrades: number  // 卖出笔数
  winTrades: number  // 盈利交易笔数
  lossTrades: number  // 亏损交易笔数
  winRate: string  // 胜率 (百分比)
  maxProfit: string  // 最大单笔盈利
  maxLoss: string  // 最大单笔亏损
  maxDrawdown: string  // 最大回撤
  avgHoldingTime: number | null  // 平均持仓时间 (毫秒)
}

export interface BacktestCompareItemDto {
  task: BacktestTaskDto
  config: BacktestConfigDto
  statistics: BacktestStatisticsDto
  highlights: string[]
}

export interface BacktestConfigDifferenceDto {
  field: string
  label: string
  values: Record<number, string | null>
}

export interface BacktestCompareSummaryDto {
  bestProfitTaskId?: number | null
  bestProfitRateTaskId?: number | null
  bestWinRateTaskId?: number | null
  lowestDrawdownTaskId?: number | null
  notes: string[]
  whyChain?: BacktestCompareWhyChainDto | null
}

export interface BacktestCompareWhyChainDto {
  anchorTaskId?: number | null
  topReasons: BacktestCompareReasonItemDto[]
  perTaskReasons: Record<number, BacktestCompareReasonItemDto[]>
}

export interface BacktestCompareReasonItemDto {
  factor: string
  title: string
  detail: string
  type: 'POSITIVE' | 'NEGATIVE' | 'NEUTRAL'
  score: number
}

/**
 * 回测交易记录 DTO
 */
export interface BacktestTradeDto {
  id: number
  tradeTime: number
  marketId: string
  marketTitle: string | null
  side: 'BUY' | 'SELL' | 'SETTLEMENT'
  outcome: string  // YES/NO 或 outcomeIndex
  outcomeIndex: number | null
  quantity: string
  price: string
  amount: string
  fee: string
  profitLoss: string | null  // 仅卖出和结算时有值
  balanceAfter: string
  leaderTradeId: string | null
}

