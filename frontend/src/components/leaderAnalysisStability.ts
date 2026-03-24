import type { LeaderTraderAnalysisResponse } from '../types'

export interface LeaderAnalysisWindowSnapshot {
  days: number
  tradeCount: number
  activeDays: number
  recommendationScore: number
  riskScore: number
  totalPnl: number
  roiRate: number
  drawdownRate: number
  concentrationRate: number
}

export interface LeaderAnalysisStabilityResult {
  score: number
  trendLabel: string
  summary: string
  riskTips: string[]
  highlights: string[]
  strongestWindow?: number
  weakestWindow?: number
  windows: LeaderAnalysisWindowSnapshot[]
}

const DEFAULT_WINDOWS = [7, 14, 30]

const toNumber = (value?: string | number | null) => {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

const average = (values: number[]) => {
  if (values.length === 0) return 0
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

const clamp = (value: number, min: number, max: number) => {
  if (value < min) return min
  if (value > max) return max
  return value
}

const normalizeWindows = (
  input: Partial<Record<number, LeaderTraderAnalysisResponse | null | undefined>>
): LeaderAnalysisWindowSnapshot[] => {
  return Object.entries(input)
    .map(([daysKey, response]) => {
      if (!response) return null
      const days = Number(daysKey)
      if (!Number.isFinite(days)) return null
      return {
        days,
        tradeCount: response.evaluation.recentTradeCount,
        activeDays: response.evaluation.activeDays,
        recommendationScore: response.evaluation.recommendationScore,
        riskScore: response.evaluation.riskScore,
        totalPnl: toNumber(response.evaluation.estimatedTotalPnl),
        roiRate: toNumber(response.evaluation.estimatedRoiRate),
        drawdownRate: toNumber(response.evaluation.estimatedDrawdownRate),
        concentrationRate: toNumber(response.evaluation.marketConcentrationRate)
      }
    })
    .filter((item): item is LeaderAnalysisWindowSnapshot => Boolean(item))
    .sort((left, right) => left.days - right.days)
}

const buildTrendLabel = (
  scoreDelta: number,
  roiDelta: number,
  riskDelta: number,
  sufficientSamples: boolean
) => {
  if (!sufficientSamples) return '样本不足'
  if (scoreDelta >= 8 && roiDelta >= 0 && riskDelta <= 5) return '稳定走强'
  if (scoreDelta <= -8 && roiDelta < 0 && riskDelta >= 5) return '持续转弱'
  if (Math.abs(scoreDelta) <= 6 && Math.abs(roiDelta) <= 5 && Math.abs(riskDelta) <= 6) return '相对稳定'
  if (roiDelta > 0 && riskDelta > 8) return '收益上升但波动扩大'
  if (roiDelta < 0 && riskDelta < -5) return '收益回落但风险收敛'
  return '波动较大'
}

export const evaluateLeaderAnalysisStability = (
  responsesByWindow: Partial<Record<number, LeaderTraderAnalysisResponse | null | undefined>>,
  expectedWindows: number[] = DEFAULT_WINDOWS
): LeaderAnalysisStabilityResult => {
  const windows = normalizeWindows(responsesByWindow)
  const availableDays = new Set(windows.map(item => item.days))
  const missingWindows = expectedWindows.filter(days => !availableDays.has(days))
  const scoreValues = windows.map(item => item.recommendationScore)
  const riskValues = windows.map(item => item.riskScore)
  const roiValues = windows.map(item => item.roiRate)
  const pnlValues = windows.map(item => item.totalPnl)
  const concentrationValues = windows.map(item => item.concentrationRate)
  const activeDayValues = windows.map(item => item.activeDays)
  const tradeValues = windows.map(item => item.tradeCount)

  const strongest = windows.reduce<LeaderAnalysisWindowSnapshot | undefined>((best, current) => {
    if (!best) return current
    return current.recommendationScore > best.recommendationScore ? current : best
  }, undefined)

  const weakest = windows.reduce<LeaderAnalysisWindowSnapshot | undefined>((worst, current) => {
    if (!worst) return current
    return current.recommendationScore < worst.recommendationScore ? current : worst
  }, undefined)

  const firstWindow = windows[0]
  const lastWindow = windows[windows.length - 1]
  const scoreDelta = firstWindow && lastWindow ? lastWindow.recommendationScore - firstWindow.recommendationScore : 0
  const roiDelta = firstWindow && lastWindow ? lastWindow.roiRate - firstWindow.roiRate : 0
  const riskDelta = firstWindow && lastWindow ? lastWindow.riskScore - firstWindow.riskScore : 0
  const sufficientSamples = windows.length >= 2

  const avgScore = average(scoreValues)
  const avgRisk = average(riskValues)
  const avgRoi = average(roiValues)
  const avgConcentration = average(concentrationValues)
  const avgTradeCount = average(tradeValues)
  const avgActiveDays = average(activeDayValues)

  const scoreVariance = scoreValues.length > 1 ? Math.max(...scoreValues) - Math.min(...scoreValues) : 0
  const riskVariance = riskValues.length > 1 ? Math.max(...riskValues) - Math.min(...riskValues) : 0
  const roiVariance = roiValues.length > 1 ? Math.max(...roiValues) - Math.min(...roiValues) : 0
  const positiveWindowCount = pnlValues.filter(value => value >= 0).length

  let stabilityScore = 50
  stabilityScore += avgScore * 0.32
  stabilityScore -= avgRisk * 0.22
  stabilityScore += clamp(avgRoi, -30, 30) * 0.35
  stabilityScore -= clamp(scoreVariance, 0, 30) * 0.8
  stabilityScore -= clamp(riskVariance, 0, 30) * 0.45
  stabilityScore -= clamp(roiVariance, 0, 40) * 0.25
  stabilityScore -= avgConcentration > 55 ? 8 : 0
  stabilityScore -= avgTradeCount < 8 ? 10 : 0
  stabilityScore -= avgActiveDays < 3 ? 8 : 0
  stabilityScore += positiveWindowCount === windows.length && windows.length > 0 ? 6 : 0
  stabilityScore -= missingWindows.length * 4

  const finalScore = clamp(Math.round(stabilityScore), 0, 100)
  const trendLabel = buildTrendLabel(scoreDelta, roiDelta, riskDelta, sufficientSamples)

  const summary = (() => {
    if (windows.length === 0) {
      return '暂无足够时间窗数据，暂时无法判断这个 Trader 的历史稳定性。'
    }
    if (finalScore >= 75) {
      return `多个时间窗下的推荐分和收益表现相对稳定，整体更像可持续跟踪型 Trader。`
    }
    if (finalScore >= 55) {
      return `跨时间窗表现中等偏稳，能看到一定延续性，但收益和风险还没有完全稳定下来。`
    }
    return `不同时间窗之间的表现波动偏大，当前更像阶段性表现，稳定性仍需谨慎看待。`
  })()

  const riskTips = [
    missingWindows.length > 0 ? `缺少 ${missingWindows.join('/')} 天窗口数据，稳定性判断会偏保守。` : '',
    avgTradeCount < 8 ? `平均成交笔数只有 ${avgTradeCount.toFixed(1)} 笔，样本仍偏少。` : '',
    avgActiveDays < 3 ? `平均活跃天数只有 ${avgActiveDays.toFixed(1)} 天，连续性不足。` : '',
    scoreVariance >= 18 ? `不同时间窗推荐分差值达到 ${scoreVariance.toFixed(0)} 分，表现不够稳定。` : '',
    riskVariance >= 15 ? `不同时间窗风险分波动达到 ${riskVariance.toFixed(0)} 分，风险画像变化较大。` : '',
    roiVariance >= 20 ? `不同时间窗 ROI 波动约 ${roiVariance.toFixed(1)}%，收益延续性一般。` : '',
    avgConcentration >= 55 ? `平均市场集中度约 ${avgConcentration.toFixed(1)}%，容易受单一市场影响。` : '',
    positiveWindowCount <= Math.floor(windows.length / 2) && windows.length > 0 ? '多数时间窗没有保持正收益，稳定盈利能力偏弱。' : ''
  ].filter(Boolean)

  const highlights = [
    strongest ? `${strongest.days} 天窗口表现最好，推荐分 ${strongest.recommendationScore}，ROI ${strongest.roiRate}%。` : '',
    weakest ? `${weakest.days} 天窗口相对最弱，推荐分 ${weakest.recommendationScore}，风险分 ${weakest.riskScore}。` : '',
    sufficientSamples ? `从 ${firstWindow.days} 天到 ${lastWindow.days} 天，推荐分变化 ${scoreDelta >= 0 ? '+' : ''}${scoreDelta}，风险分变化 ${riskDelta >= 0 ? '+' : ''}${riskDelta}。` : '',
    avgRoi !== 0 ? `多时间窗平均 ROI 约 ${avgRoi.toFixed(1)}%，平均风险分 ${avgRisk.toFixed(1)}。` : `多时间窗平均风险分 ${avgRisk.toFixed(1)}，收益仍需继续观察。`
  ].filter(Boolean)

  return {
    score: finalScore,
    trendLabel,
    summary,
    riskTips,
    highlights,
    strongestWindow: strongest?.days,
    weakestWindow: weakest?.days,
    windows
  }
}

