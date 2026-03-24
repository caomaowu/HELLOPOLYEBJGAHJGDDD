import { useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  List,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message
} from 'antd'
import { useMediaQuery } from 'react-responsive'
import type { ColumnsType } from 'antd/es/table'
import { useTranslation } from 'react-i18next'
import type {
  LeaderTraderAnalysisActivity,
  LeaderTraderAnalysisPosition,
  LeaderTraderAnalysisResponse
} from '../types'
import { apiService } from '../services/api'
import { formatUSDC } from '../utils'
import { buildLeaderAnalysisCopySuggestion } from './leaderAnalysisCopySuggestions'
import { evaluateLeaderAnalysisStability } from './leaderAnalysisStability'

const { Text, Paragraph } = Typography

interface LeaderTraderAnalysisModalProps {
  open: boolean
  onClose: () => void
  onLeaderAdded?: () => Promise<void> | void
}

const isTraderAddress = (value: string) => /^0x[a-fA-F0-9]{40}$/.test(value.trim())
const STABILITY_WINDOWS = [7, 14, 30]

const tagLabelMap: Record<string, string> = {
  existingLeader: '已存在 Leader',
  positiveRoi: '正收益',
  negativeRoi: '负收益',
  diversified: '分散度较好',
  concentrated: '持仓集中',
  manageableExposure: '仓位可控',
  heavyExposure: '仓位较重',
  activeTrader: '交易活跃',
  limitedHistory: '样本偏少',
  lowRisk: '低风险'
}

const formatTime = (timestamp?: number | null, locale = 'zh-CN') => {
  if (!timestamp) return '-'
  return new Date(timestamp).toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const signedColor = (value: string) => {
  const numeric = Number(value || 0)
  if (numeric > 0) return '#52c41a'
  if (numeric < 0) return '#ff4d4f'
  return undefined
}

const scoreColor = (score: number) => {
  if (score >= 75) return '#52c41a'
  if (score >= 55) return '#faad14'
  return '#ff4d4f'
}

const copyLevelColor = (level: string) => {
  if (level === '可逐步放大') return 'success'
  if (level === '观察性跟单') return 'processing'
  if (level === '谨慎小仓') return 'warning'
  return 'error'
}

const trendColor = (label: string) => {
  if (label === '稳定走强' || label === '相对稳定') return 'success'
  if (label === '收益上升但波动扩大' || label === '收益回落但风险收敛') return 'warning'
  if (label === '样本不足') return 'default'
  return 'error'
}

const parseMetric = (value?: string | number | null) => {
  if (value === null || value === undefined || value === '') return 0
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

const buildDecisionInsight = (result: LeaderTraderAnalysisResponse) => {
  const evaluation = result.evaluation
  const totalPnl = parseMetric(evaluation.estimatedTotalPnl)
  const roi = parseMetric(evaluation.estimatedRoiRate)
  const drawdown = parseMetric(evaluation.estimatedDrawdownRate)
  const concentration = parseMetric(evaluation.marketConcentrationRate)
  const riskScore = evaluation.riskScore
  const recommendationScore = evaluation.recommendationScore
  const tradeCount = evaluation.recentTradeCount
  const activeDays = evaluation.activeDays
  const positionCount = evaluation.currentPositionCount

  const decision = (() => {
    if (result.existingLeaderId) {
      return {
        status: 'processing' as const,
        title: '已在 Leader 列表',
        summary: '这个 Trader 已经进入 Leader 管理，可直接复用现有配置继续观察。',
        action: '优先检查现有跟单参数，而不是重复新增。'
      }
    }
    if (evaluation.lowRisk && recommendationScore >= 70 && totalPnl >= 0 && tradeCount >= 10) {
      return {
        status: 'success' as const,
        title: '建议加入观察并小仓跟踪',
        summary: '从当前样本看，收益、风险和持仓结构相对均衡，适合作为候选 Leader 继续跟踪。',
        action: '建议先小仓位试跟，再根据后续稳定性决定是否放大。'
      }
    }
    if (recommendationScore >= 55 && riskScore <= 55 && tradeCount >= 5) {
      return {
        status: 'warning' as const,
        title: '可以加入观察名单',
        summary: '当前数据有一定参考价值，但稳定性和风险承受仍需要继续观察。',
        action: '建议先观察几天，确认收益延续性后再决定是否跟单。'
      }
    }
    return {
      status: 'error' as const,
      title: '暂不建议直接加入',
      summary: '当前样本下的风险或稳定性还不够理想，不适合直接作为跟单对象。',
      action: '建议继续观察，或只做手动跟踪，不要立刻实盘复制。'
    }
  })()

  const style = (() => {
    if (tradeCount < 5 || activeDays <= 2) {
      return {
        title: '样本偏少型',
        summary: '近期交易样本偏少，暂时更适合做观察对象，不适合下结论太重。'
      }
    }
    if (concentration >= 60 || positionCount >= 12) {
      return {
        title: '集中进攻型',
        summary: '仓位更集中，单一市场或少数仓位对整体表现影响会比较大。'
      }
    }
    if (evaluation.lowRisk && concentration <= 35 && positionCount <= 8) {
      return {
        title: '分散稳健型',
        summary: '持仓分散度较好，开放仓位数量可控，更适合作为稳健型跟单候选。'
      }
    }
    if (tradeCount >= 20 && activeDays >= 7) {
      return {
        title: '高活跃交易型',
        summary: '近期出手频率较高，适合持续跟踪，但也要注意信号切换会更快。'
      }
    }
    return {
      title: '均衡交易型',
      summary: '活跃度、分散度和仓位结构相对中性，需要结合后续走势继续评估。'
    }
  })()

  const riskItems = [
    totalPnl < 0 ? `估算总盈亏为 ${formatUSDC(String(totalPnl))}，近期整体表现偏弱。` : '',
    riskScore >= 60 ? `风险分达到 ${riskScore}，已经偏高。` : '',
    drawdown >= 15 ? `估算开放回撤约 ${drawdown}% ，回撤压力不低。` : '',
    concentration >= 50 ? `市场集中度约 ${concentration}% ，单一市场波动影响较大。` : '',
    positionCount >= 12 ? `当前持仓数为 ${positionCount}，仓位管理复杂度较高。` : '',
    tradeCount < 8 ? `最近仅成交 ${tradeCount} 笔，样本还偏少。` : '',
    totalPnl >= 0 && riskScore < 60 && drawdown < 15 && concentration < 50 && tradeCount >= 8
      ? '当前没有特别突出的硬伤，但仍建议继续观察收益稳定性。'
      : ''
  ].filter(Boolean)

  return {
    decision,
    style,
    riskItems,
    metrics: {
      totalPnl,
      roi
    }
  }
}

const LeaderTraderAnalysisModal: React.FC<LeaderTraderAnalysisModalProps> = ({
  open,
  onClose,
  onLeaderAdded
}) => {
  const { t, i18n } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [addressInput, setAddressInput] = useState('')
  const [days, setDays] = useState(14)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<LeaderTraderAnalysisResponse | null>(null)
  const [windowResults, setWindowResults] = useState<Partial<Record<number, LeaderTraderAnalysisResponse>>>({})
  const decisionInsight = result ? buildDecisionInsight(result) : null
  const copySuggestion = result ? buildLeaderAnalysisCopySuggestion(result) : null
  const stabilityInsight = result ? evaluateLeaderAnalysisStability(windowResults, STABILITY_WINDOWS) : null

  const handleAnalyze = async () => {
    const address = addressInput.trim()
    if (!isTraderAddress(address)) {
      message.warning(t('leaderTraderAnalysis.invalidAddress'))
      return
    }

    setLoading(true)
    setResult(null)
    setWindowResults({})
    try {
      const requestedWindows = Array.from(new Set([days, ...STABILITY_WINDOWS]))
      const responses = await Promise.allSettled(
        requestedWindows.map(windowDays =>
          apiService.leaders.discoveryAnalyzeTrader({
            address,
            days: windowDays
          })
        )
      )

      const nextWindowResults: Partial<Record<number, LeaderTraderAnalysisResponse>> = {}
      let selectedResult: LeaderTraderAnalysisResponse | null = null
      let selectedErrorMessage = ''

      responses.forEach((response, index) => {
        const windowDays = requestedWindows[index]
        if (response.status === 'fulfilled') {
          const payload = response.value.data
          if (payload.code === 0 && payload.data) {
            nextWindowResults[windowDays] = payload.data
            if (windowDays === days) {
              selectedResult = payload.data
            }
          } else if (windowDays === days) {
            selectedErrorMessage = payload.msg || t('leaderTraderAnalysis.analyzeFailed')
          }
        } else if (windowDays === days) {
          selectedErrorMessage = response.reason?.message || t('leaderTraderAnalysis.analyzeFailed')
        }
      })

      if (selectedResult) {
        setResult(selectedResult)
        setWindowResults(nextWindowResults)
        if (Object.keys(nextWindowResults).length < requestedWindows.length) {
          message.warning(t('leaderTraderAnalysis.partialWindowsLoaded'))
        }
      } else {
        message.error(selectedErrorMessage || t('leaderTraderAnalysis.analyzeFailed'))
      }
    } catch (error: any) {
      if (error?.code === 'ECONNABORTED' || String(error?.message || '').includes('timeout')) {
        message.error(t('leaderTraderAnalysis.analyzeTimeout'))
      } else {
        message.error(error.message || t('leaderTraderAnalysis.analyzeFailed'))
      }
    } finally {
      setLoading(false)
    }
  }

  const handleAddLeader = async () => {
    if (!result) return
    try {
      const response = await apiService.leaders.add({
        leaderAddress: result.address,
        leaderName: result.displayName || undefined
      })
      if (response.data.code === 0) {
        message.success(t('leaderTraderAnalysis.addSuccess'))
        await onLeaderAdded?.()
        setResult(prev => prev ? {
          ...prev,
          existingLeaderId: response.data.data?.id ?? prev.existingLeaderId,
          existingLeaderName: response.data.data?.leaderName ?? prev.displayName ?? prev.existingLeaderName,
          evaluation: {
            ...prev.evaluation,
            existingLeaderId: response.data.data?.id ?? prev.evaluation.existingLeaderId,
            existingLeaderName: response.data.data?.leaderName ?? prev.displayName ?? prev.evaluation.existingLeaderName
          }
        } : prev)
      } else {
        message.error(response.data.msg || t('leaderTraderAnalysis.addFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderTraderAnalysis.addFailed'))
    }
  }

  const positionColumns: ColumnsType<LeaderTraderAnalysisPosition> = [
    {
      title: t('leaderTraderAnalysis.market'),
      key: 'market',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.title || record.marketId}</Text>
          <Text type="secondary">{record.outcome || record.marketId}</Text>
        </Space>
      )
    },
    {
      title: t('leaderTraderAnalysis.positionValue'),
      dataIndex: 'currentValue',
      key: 'currentValue',
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('leaderTraderAnalysis.realizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      render: (value: string) => <Text style={{ color: signedColor(value) }}>{formatUSDC(value)}</Text>
    },
    {
      title: t('leaderTraderAnalysis.unrealizedPnl'),
      dataIndex: 'unrealizedPnl',
      key: 'unrealizedPnl',
      render: (value: string) => <Text style={{ color: signedColor(value) }}>{formatUSDC(value)}</Text>
    },
    {
      title: t('leaderTraderAnalysis.totalPnl'),
      dataIndex: 'totalPnl',
      key: 'totalPnl',
      render: (value: string) => <Text style={{ color: signedColor(value) }}>{formatUSDC(value)}</Text>
    },
    {
      title: t('leaderTraderAnalysis.percentPnl'),
      dataIndex: 'percentPnl',
      key: 'percentPnl',
      render: (value: string) => <Text style={{ color: signedColor(value) }}>{value}%</Text>
    }
  ]

  const activityColumns: ColumnsType<LeaderTraderAnalysisActivity> = [
    {
      title: t('leaderTraderAnalysis.activityTime'),
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (value: number) => formatTime(value, i18n.language || 'zh-CN')
    },
    {
      title: t('leaderTraderAnalysis.market'),
      key: 'title',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Text>{record.title || record.marketId}</Text>
          <Text type="secondary">{record.outcome || '-'}</Text>
        </Space>
      )
    },
    {
      title: t('leaderTraderAnalysis.side'),
      dataIndex: 'side',
      key: 'side',
      render: (value?: string | null) => {
        if (!value) return <Text type="secondary">-</Text>
        const label = value === 'BUY' ? '买入' : value === 'SELL' ? '卖出' : value
        return <Tag color={value === 'BUY' ? 'green' : 'volcano'}>{label}</Tag>
      }
    },
    {
      title: t('leaderTraderAnalysis.volume'),
      dataIndex: 'usdcSize',
      key: 'usdcSize',
      render: (value: string) => formatUSDC(value)
    }
  ]

  return (
    <Modal
      title={t('leaderTraderAnalysis.title')}
      open={open}
      onCancel={onClose}
      footer={[
        <Button key="close" onClick={onClose}>
          {t('common.close')}
        </Button>,
        <Button
          key="add"
          type="primary"
          onClick={handleAddLeader}
          disabled={!result || Boolean(result.existingLeaderId)}
        >
          {result?.existingLeaderId ? t('leaderTraderAnalysis.alreadyLeader') : t('leaderTraderAnalysis.addAsLeader')}
        </Button>
      ]}
      width={isMobile ? '96%' : 1160}
      style={{ top: 20 }}
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert message={t('leaderTraderAnalysis.notice')} type="info" showIcon />

        <Space wrap style={{ width: '100%' }}>
          <Input
            value={addressInput}
            onChange={event => setAddressInput(event.target.value)}
            onPressEnter={() => void handleAnalyze()}
            placeholder={t('leaderTraderAnalysis.addressPlaceholder')}
            style={{ width: isMobile ? '100%' : 420 }}
          />
          <Select
            value={days}
            onChange={setDays}
            options={[
              { label: t('leaderTraderAnalysis.daysOption', { value: 7 }), value: 7 },
              { label: t('leaderTraderAnalysis.daysOption', { value: 14 }), value: 14 },
              { label: t('leaderTraderAnalysis.daysOption', { value: 30 }), value: 30 }
            ]}
            style={{ width: 140 }}
          />
          <Button type="primary" loading={loading} onClick={handleAnalyze}>
            {t('leaderTraderAnalysis.analyzeAction')}
          </Button>
        </Space>

        {!result && !loading ? (
          <Empty description={t('leaderTraderAnalysis.empty')} />
        ) : null}

        {result ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions
              bordered
              column={isMobile ? 1 : 2}
              size="small"
              title={t('leaderTraderAnalysis.summary')}
            >
              <Descriptions.Item label={t('leaderTraderAnalysis.address')}>
                <Text style={{ fontFamily: 'monospace' }}>{result.address}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderTraderAnalysis.displayName')}>
                {result.displayName || <Text type="secondary">-</Text>}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderTraderAnalysis.leaderStatus')}>
                {result.existingLeaderId ? (
                  <Tag color="blue">{result.existingLeaderName || t('leaderTraderAnalysis.alreadyLeader')}</Tag>
                ) : (
                  <Text type="secondary">{t('leaderTraderAnalysis.notLeader')}</Text>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t('leaderTraderAnalysis.lastSeenAt')}>
                {formatTime(result.evaluation.lastSeenAt, i18n.language || 'zh-CN')}
              </Descriptions.Item>
            </Descriptions>

            {decisionInsight ? (
              <Row gutter={[12, 12]}>
                <Col xs={24} md={8}>
                  <Card title={t('leaderTraderAnalysis.decisionTitle')}>
                    <Tag color={decisionInsight.decision.status} style={{ marginBottom: 12 }}>
                      {decisionInsight.decision.title}
                    </Tag>
                    <Paragraph style={{ marginBottom: 8 }}>
                      {decisionInsight.decision.summary}
                    </Paragraph>
                    <Text type="secondary">{decisionInsight.decision.action}</Text>
                  </Card>
                </Col>
                <Col xs={24} md={8}>
                  <Card title={t('leaderTraderAnalysis.styleTitle')}>
                    <Text strong>{decisionInsight.style.title}</Text>
                    <Paragraph style={{ marginTop: 12, marginBottom: 0 }}>
                      {decisionInsight.style.summary}
                    </Paragraph>
                  </Card>
                </Col>
                <Col xs={24} md={8}>
                  <Card title={t('leaderTraderAnalysis.riskFocusTitle')}>
                    <List
                      size="small"
                      dataSource={decisionInsight.riskItems}
                      renderItem={(item) => <List.Item>{item}</List.Item>}
                    />
                  </Card>
                </Col>
              </Row>
            ) : null}

            <Row gutter={[12, 12]}>
              <Col xs={24} md={12}>
                <Card title={t('leaderTraderAnalysis.copySuggestionTitle')}>
                  {copySuggestion ? (
                    <Space direction="vertical" size={12} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={copyLevelColor(copySuggestion.level)}>{copySuggestion.level}</Tag>
                        <Tag color="blue">{t('leaderTraderAnalysis.convictionLabel', { value: copySuggestion.conviction })}</Tag>
                        <Tag color={copySuggestion.smallSizeOnly ? 'warning' : 'success'}>
                          {copySuggestion.smallSizeOnly ? t('leaderTraderAnalysis.smallSizeOnly') : t('leaderTraderAnalysis.canScale')}
                        </Tag>
                      </Space>
                      <Row gutter={[12, 12]}>
                        <Col xs={12} md={8}>
                          <Card size="small">
                            <Statistic
                              title={t('leaderTraderAnalysis.basePositionRatio')}
                              value={Math.round(copySuggestion.sizeMultiplier * 100)}
                              suffix="%"
                            />
                          </Card>
                        </Col>
                        <Col xs={12} md={8}>
                          <Card size="small">
                            <Statistic title={t('leaderTraderAnalysis.positionStrength')} value={copySuggestion.sizeLabel} />
                          </Card>
                        </Col>
                        <Col xs={24} md={8}>
                          <Card size="small">
                            <Statistic title={t('leaderTraderAnalysis.copyMode')} value={copySuggestion.level} />
                          </Card>
                        </Col>
                      </Row>
                      <Paragraph style={{ marginBottom: 0 }}>{copySuggestion.summary}</Paragraph>
                      <div>
                        <Text strong>{t('leaderTraderAnalysis.copyReasonTitle')}</Text>
                        <List
                          size="small"
                          dataSource={copySuggestion.reasons}
                          renderItem={(item) => <List.Item>{item}</List.Item>}
                        />
                      </div>
                      <div>
                        <Text strong>{t('leaderTraderAnalysis.copyFilterTitle')}</Text>
                        <List
                          size="small"
                          dataSource={copySuggestion.filters}
                          renderItem={(item) => <List.Item>{item}</List.Item>}
                        />
                      </div>
                    </Space>
                  ) : null}
                </Card>
              </Col>
              <Col xs={24} md={12}>
                <Card title={t('leaderTraderAnalysis.stabilityTitle')}>
                  {stabilityInsight ? (
                    <Space direction="vertical" size={12} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={trendColor(stabilityInsight.trendLabel)}>{stabilityInsight.trendLabel}</Tag>
                        <Text type="secondary">
                          {t('leaderTraderAnalysis.bestWindowLabel', { value: stabilityInsight.strongestWindow ?? '-' })}
                        </Text>
                        <Text type="secondary">
                          {t('leaderTraderAnalysis.weakestWindowLabel', { value: stabilityInsight.weakestWindow ?? '-' })}
                        </Text>
                      </Space>
                      <Row gutter={[12, 12]}>
                        <Col xs={24} md={8}>
                          <Card size="small">
                            <Statistic
                              title={t('leaderTraderAnalysis.stabilityScore')}
                              value={stabilityInsight.score}
                              suffix="/ 100"
                              valueStyle={{ color: scoreColor(stabilityInsight.score) }}
                            />
                          </Card>
                        </Col>
                        {stabilityInsight.windows.map((window) => (
                          <Col xs={12} md={8} key={window.days}>
                            <Card size="small">
                              <Statistic
                                title={t('leaderTraderAnalysis.windowScoreLabel', { value: window.days })}
                                value={`${window.recommendationScore} / ${window.riskScore}`}
                              />
                              <Text type="secondary">
                                {t('leaderTraderAnalysis.windowRoiLabel', { value: window.roiRate.toFixed(1) })}
                              </Text>
                            </Card>
                          </Col>
                        ))}
                      </Row>
                      <Paragraph style={{ marginBottom: 0 }}>{stabilityInsight.summary}</Paragraph>
                      <div>
                        <Text strong>{t('leaderTraderAnalysis.stabilityHighlights')}</Text>
                        <List
                          size="small"
                          dataSource={stabilityInsight.highlights}
                          renderItem={(item) => <List.Item>{item}</List.Item>}
                        />
                      </div>
                      <div>
                        <Text strong>{t('leaderTraderAnalysis.stabilityRisks')}</Text>
                        <List
                          size="small"
                          dataSource={stabilityInsight.riskTips}
                          locale={{ emptyText: t('leaderTraderAnalysis.noStabilityRisks') }}
                          renderItem={(item) => <List.Item>{item}</List.Item>}
                        />
                      </div>
                    </Space>
                  ) : null}
                </Card>
              </Col>
            </Row>

            <Row gutter={[12, 12]}>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.tradeCount')} value={result.evaluation.recentTradeCount} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.marketCount')} value={result.evaluation.distinctMarkets} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.activeDays')} value={result.evaluation.activeDays} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.positionCount')} value={result.evaluation.currentPositionCount} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.volume')} value={Number(result.evaluation.recentVolume || 0)} formatter={(value) => formatUSDC(String(value || 0))} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic title={t('leaderTraderAnalysis.positionValue')} value={Number(result.evaluation.currentPositionValue || 0)} formatter={(value) => formatUSDC(String(value || 0))} />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic
                    title={t('leaderTraderAnalysis.totalPnl')}
                    value={Number(result.evaluation.estimatedTotalPnl || 0)}
                    valueStyle={{ color: signedColor(result.evaluation.estimatedTotalPnl) }}
                    formatter={(value) => formatUSDC(String(value || 0))}
                  />
                </Card>
              </Col>
              <Col xs={12} md={6}>
                <Card>
                  <Statistic
                    title={t('leaderTraderAnalysis.score')}
                    value={`${result.evaluation.recommendationScore} / ${result.evaluation.riskScore}`}
                  />
                  <Text type="secondary">
                    {result.evaluation.lowRisk ? t('leaderTraderAnalysis.lowRisk') : t('leaderTraderAnalysis.needCaution')}
                  </Text>
                </Card>
              </Col>
            </Row>

            <Row gutter={[12, 12]}>
              <Col xs={24} md={12}>
                <Card title={t('leaderTraderAnalysis.pnlHighlights')}>
                  <List
                    dataSource={result.pnlHighlights}
                    renderItem={(item) => <List.Item>{item}</List.Item>}
                  />
                </Card>
              </Col>
              <Col xs={24} md={12}>
                <Card title={t('leaderTraderAnalysis.behaviorHighlights')}>
                  <List
                    dataSource={result.behaviorHighlights}
                    renderItem={(item) => <List.Item>{item}</List.Item>}
                  />
                </Card>
              </Col>
            </Row>

            <Card title={t('leaderTraderAnalysis.reasons')}>
              <Space wrap>
                {result.evaluation.tags.map(tag => (
                  <Tag key={tag} color="processing">{tagLabelMap[tag] || tag}</Tag>
                ))}
              </Space>
              <List
                style={{ marginTop: 12 }}
                dataSource={result.evaluation.reasons}
                renderItem={(item) => <List.Item>{item}</List.Item>}
              />
              {result.evaluation.sampleMarkets.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <Text strong>{t('leaderTraderAnalysis.sampleMarkets')}</Text>
                  <div style={{ marginTop: 8 }}>
                    <Space wrap>
                      {result.evaluation.sampleMarkets.map(market => (
                        <Tag key={market.marketId}>{market.title || market.marketId} x{market.tradeCount}</Tag>
                      ))}
                    </Space>
                  </div>
                </div>
              )}
              {result.evaluation.manualNote && (
                <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                  {result.evaluation.manualNote}
                </Paragraph>
              )}
            </Card>

            <Card title={t('leaderTraderAnalysis.positions')}>
              <Table
                rowKey={(record) => `${record.marketId}-${record.outcome || 'unknown'}`}
                columns={positionColumns}
                dataSource={result.positions}
                size="small"
                pagination={{ pageSize: 6 }}
                locale={{ emptyText: t('leaderTraderAnalysis.noPositions') }}
                scroll={{ x: 720 }}
              />
            </Card>

            <Card title={t('leaderTraderAnalysis.activities')}>
              <Table
                rowKey={(record) => `${record.timestamp}-${record.marketId}-${record.transactionHash || 'tx'}`}
                columns={activityColumns}
                dataSource={result.recentActivities}
                size="small"
                pagination={{ pageSize: 6 }}
                locale={{ emptyText: t('leaderTraderAnalysis.noActivities') }}
                scroll={{ x: 720 }}
              />
            </Card>
          </Space>
        ) : null}
      </Space>
    </Modal>
  )
}

export default LeaderTraderAnalysisModal
