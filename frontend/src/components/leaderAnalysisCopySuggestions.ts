import type { LeaderTraderAnalysisResponse } from '../types'

export interface LeaderAnalysisCopySuggestion {
  level: '不建议跟单' | '谨慎小仓' | '观察性跟单' | '可逐步放大'
  conviction: '低' | '中' | '中高'
  sizeMultiplier: number
  sizeLabel: string
  smallSizeOnly: boolean
  filters: string[]
  reasons: string[]
  summary: string
}

const parseMetric = (value?: string | number | null) => {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

export const buildLeaderAnalysisCopySuggestion = (
  result: LeaderTraderAnalysisResponse
): LeaderAnalysisCopySuggestion => {
  const evaluation = result.evaluation
  const totalPnl = parseMetric(evaluation.estimatedTotalPnl)
  const roi = parseMetric(evaluation.estimatedRoiRate)
  const drawdown = parseMetric(evaluation.estimatedDrawdownRate)
  const concentration = parseMetric(evaluation.marketConcentrationRate)
  const positionValue = parseMetric(evaluation.currentPositionValue)
  const recentVolume = parseMetric(evaluation.recentVolume)
  const tradeCount = evaluation.recentTradeCount
  const activeDays = evaluation.activeDays
  const riskScore = evaluation.riskScore
  const recommendationScore = evaluation.recommendationScore
  const positionCount = evaluation.currentPositionCount

  let level: LeaderAnalysisCopySuggestion['level'] = '不建议跟单'
  let conviction: LeaderAnalysisCopySuggestion['conviction'] = '低'
  let sizeMultiplier = 0.15

  if (evaluation.lowRisk && recommendationScore >= 75 && totalPnl >= 0 && tradeCount >= 12) {
    level = '可逐步放大'
    conviction = '中高'
    sizeMultiplier = 0.6
  } else if (recommendationScore >= 60 && riskScore <= 55 && tradeCount >= 8) {
    level = '观察性跟单'
    conviction = '中'
    sizeMultiplier = 0.35
  } else if (recommendationScore >= 45 && tradeCount >= 4) {
    level = '谨慎小仓'
    conviction = '低'
    sizeMultiplier = 0.2
  }

  const pressurePenalty =
    (drawdown >= 18 ? 0.12 : 0) +
    (concentration >= 55 ? 0.1 : 0) +
    (positionCount >= 12 ? 0.08 : 0) +
    (totalPnl < 0 ? 0.12 : 0) +
    (tradeCount < 8 ? 0.08 : 0) +
    (activeDays < 3 ? 0.05 : 0)

  const supportBoost =
    (evaluation.lowRisk ? 0.08 : 0) +
    (roi >= 8 ? 0.08 : 0) +
    (recommendationScore >= 70 ? 0.06 : 0) +
    (concentration <= 35 ? 0.05 : 0) +
    (tradeCount >= 20 ? 0.05 : 0)

  sizeMultiplier = clamp(Number((sizeMultiplier + supportBoost - pressurePenalty).toFixed(2)), 0.1, 0.8)

  const smallSizeOnly = sizeMultiplier <= 0.25 || level === '不建议跟单'

  const sizeLabel = (() => {
    if (sizeMultiplier <= 0.2) return '试探级'
    if (sizeMultiplier <= 0.35) return '轻仓级'
    if (sizeMultiplier <= 0.55) return '中低仓级'
    return '可逐步放大'
  })()

  const filters = [
    concentration >= 50 ? '建议过滤单市场过度集中的信号，只跟更分散的仓位。' : '',
    drawdown >= 15 ? '建议设置更严的止损或回撤容忍阈值。' : '',
    positionCount >= 12 ? '建议限制最大同时跟单市场数，避免过度分散执行。' : '',
    tradeCount < 8 ? '建议提高最小成交样本要求，样本不足时先不自动放大。' : '',
    recentVolume < 500 ? '建议只做观察性跟单，等待更多真实成交额验证。' : '',
    positionValue > 0 && concentration < 50 ? '可优先跟随其新增仓位，弱化已深度持有的老仓位。' : '',
    totalPnl < 0 ? '建议仅观察，不建议立即复制当前节奏。' : ''
  ].filter(Boolean)

  const reasons = [
    `推荐分 ${recommendationScore}，风险分 ${riskScore}。`,
    `估算总盈亏 ${totalPnl >= 0 ? '+' : ''}${totalPnl.toFixed(2)} USDC，ROI ${roi.toFixed(2)}%。`,
    `估算开放回撤 ${drawdown.toFixed(2)}%，市场集中度 ${concentration.toFixed(2)}%。`,
    `最近成交 ${tradeCount} 笔，活跃 ${activeDays} 天，当前持仓 ${positionCount} 个。`
  ]

  const summary = (() => {
    if (level === '可逐步放大') {
      return `建议以基准仓位的 ${(sizeMultiplier * 100).toFixed(0)}% 左右开始，先小步放大，重点观察回撤是否继续可控。`
    }
    if (level === '观察性跟单') {
      return `建议以基准仓位的 ${(sizeMultiplier * 100).toFixed(0)}% 左右跟踪，优先把它当观察性候选，而不是主跟单对象。`
    }
    if (level === '谨慎小仓') {
      return `建议只用基准仓位的 ${(sizeMultiplier * 100).toFixed(0)}% 左右试单，确认稳定性前不要放大。`
    }
    return '当前更适合手动观察，不建议直接自动跟单。'
  })()

  return {
    level,
    conviction,
    sizeMultiplier,
    sizeLabel,
    smallSizeOnly,
    filters,
    reasons,
    summary
  }
}
