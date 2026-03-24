import { useEffect, useMemo, useState } from 'react'
import type { Key } from 'react'
import {
  Alert,
  Button,
  Collapse,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  Leader,
  LeaderActivityHistoryItem,
  LeaderActivityHistoryResponse,
  LeaderCandidatePoolBatchLabelUpdateRequest,
  LeaderCandidatePoolItem,
  LeaderCandidatePoolListResponse,
  LeaderCandidateRecommendation,
  LeaderCandidateRecommendResponse,
  LeaderCandidateScoreHistoryItem,
  LeaderCandidateScoreHistoryResponse,
  LeaderDiscoveredTrader,
  LeaderMarketScanResponse,
  LeaderMarketTraderLookupItem,
  LeaderTraderScanResponse
} from '../types'
import { apiService } from '../services/api'
import { formatUSDC } from '../utils'
import { useTranslation } from 'react-i18next'

const { Text, Paragraph } = Typography

interface LeaderDiscoveryModalProps {
  open: boolean
  leaders: Leader[]
  onClose: () => void
  onLeaderAdded?: () => Promise<void> | void
}

const parseMultilineValues = (value: string): string[] => {
  return value
    .split(/[\s,]+/)
    .map(item => item.trim())
    .filter(Boolean)
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

const renderMarketSummary = (markets: Array<{ marketId: string; title?: string | null; tradeCount: number }>) => {
  if (!markets.length) return <Text type="secondary">-</Text>
  return (
    <Space wrap size={[4, 4]}>
      {markets.map(market => (
        <Tag key={`${market.marketId}-${market.tradeCount}`}>{market.title || market.marketId} x{market.tradeCount}</Tag>
      ))}
    </Space>
  )
}

const parseOrderbookHint = (
  manualNote?: string | null,
  bidCount?: number,
  askCount?: number
): {
  total: number
  bids: number
  asks: number
  markets: number
  tokens: number
} | null => {
  const matched = (manualNote || '').match(/orderbook-source:\s*total=(\d+),bids=(\d+),asks=(\d+),markets=(\d+),tokens=(\d+)/i)
  if (matched) {
    return {
      total: Number(matched[1] || 0),
      bids: Number(matched[2] || 0),
      asks: Number(matched[3] || 0),
      markets: Number(matched[4] || 0),
      tokens: Number(matched[5] || 0)
    }
  }
  const safeBids = Math.max(0, bidCount || 0)
  const safeAsks = Math.max(0, askCount || 0)
  if (safeBids > 0 || safeAsks > 0) {
    return {
      total: safeBids + safeAsks,
      bids: safeBids,
      asks: safeAsks,
      markets: 0,
      tokens: 0
    }
  }
  return null
}

const formatDuration = (durationMs?: number): string => {
  if (durationMs == null || durationMs < 0) return '-'
  if (durationMs < 1000) return `${durationMs} ms`
  const seconds = durationMs / 1000
  if (seconds < 10) return `${seconds.toFixed(2)} s`
  if (seconds < 100) return `${seconds.toFixed(1)} s`
  return `${Math.round(seconds)} s`
}

const LeaderDiscoveryModal: React.FC<LeaderDiscoveryModalProps> = ({
  open,
  leaders,
  onClose,
  onLeaderAdded
}) => {
  const { t, i18n } = useTranslation()

  const leaderOptions = useMemo(
    () => leaders.map(leader => ({ label: leader.leaderName || leader.leaderAddress, value: leader.id })),
    [leaders]
  )

  const [selectedLeaderIds, setSelectedLeaderIds] = useState<number[]>([])
  const [seedAddressInput, setSeedAddressInput] = useState('')
  const [days, setDays] = useState(7)
  const [maxSeedMarkets, setMaxSeedMarkets] = useState(20)
  const [marketTradeLimit, setMarketTradeLimit] = useState(120)
  const [excludeExistingLeaders, setExcludeExistingLeaders] = useState(true)
  const [excludeBlacklistedDiscovery, setExcludeBlacklistedDiscovery] = useState(true)
  const [discoveryFavoriteOnly, setDiscoveryFavoriteOnly] = useState(false)
  const [discoveryIncludeTagsInput, setDiscoveryIncludeTagsInput] = useState('')
  const [discoveryExcludeTagsInput, setDiscoveryExcludeTagsInput] = useState('')

  const [scanLoading, setScanLoading] = useState(false)
  const [scanResult, setScanResult] = useState<LeaderTraderScanResponse | null>(null)
  const [marketScanLoading, setMarketScanLoading] = useState(false)
  const [marketScanResult, setMarketScanResult] = useState<LeaderMarketScanResponse | null>(null)
  const [scanMarketLimit, setScanMarketLimit] = useState(50)
  const [scanTokenPerMarketLimit, setScanTokenPerMarketLimit] = useState(2)
  const [scanMaxCandidateAddresses, setScanMaxCandidateAddresses] = useState(500)
  const [scanValidationSampleSize, setScanValidationSampleSize] = useState(300)
  const [scanMode, setScanMode] = useState<'ORDERBOOK' | 'AGGRESSIVE'>('ORDERBOOK')
  const [scanIncludeSeedAddresses, setScanIncludeSeedAddresses] = useState(true)
  const [scanExpansionRounds, setScanExpansionRounds] = useState(1)
  const [scanExpansionSeedTraderLimit, setScanExpansionSeedTraderLimit] = useState(30)
  const [scanExpansionMarketLimit, setScanExpansionMarketLimit] = useState(8)
  const [scanExpansionTradeLimitPerMarket, setScanExpansionTradeLimitPerMarket] = useState(60)
  const [scanPersistToPool, setScanPersistToPool] = useState(true)

  const [recommendLoading, setRecommendLoading] = useState(false)
  const [recommendResult, setRecommendResult] = useState<LeaderCandidateRecommendResponse | null>(null)
  const [candidateAddressInput, setCandidateAddressInput] = useState('')
  const [recommendTraderLimit, setRecommendTraderLimit] = useState(20)
  const [minTrades, setMinTrades] = useState(8)
  const [maxOpenPositions, setMaxOpenPositions] = useState(8)
  const [maxMarketConcentrationRate, setMaxMarketConcentrationRate] = useState(0.45)
  const [maxEstimatedDrawdownRate, setMaxEstimatedDrawdownRate] = useState(0.18)
  const [maxRiskScore, setMaxRiskScore] = useState(45)
  const [lowRiskOnly, setLowRiskOnly] = useState(false)

  const [marketLookupInput, setMarketLookupInput] = useState('')
  const [marketLookupLoading, setMarketLookupLoading] = useState(false)
  const [marketLookupResult, setMarketLookupResult] = useState<LeaderMarketTraderLookupItem[]>([])
  const [limitPerMarket, setLimitPerMarket] = useState(20)
  const [minTradesPerTrader, setMinTradesPerTrader] = useState(1)

  const [poolLoading, setPoolLoading] = useState(false)
  const [poolResult, setPoolResult] = useState<LeaderCandidatePoolListResponse | null>(null)
  const [poolPage, setPoolPage] = useState(1)
  const [poolLowRiskOnly, setPoolLowRiskOnly] = useState(false)
  const [poolFavoriteOnly, setPoolFavoriteOnly] = useState(false)
  const [poolIncludeBlacklisted, setPoolIncludeBlacklisted] = useState(false)
  const [labelUpdatingAddress, setLabelUpdatingAddress] = useState<string | null>(null)
  const [selectedPoolAddresses, setSelectedPoolAddresses] = useState<string[]>([])
  const [batchTagModalOpen, setBatchTagModalOpen] = useState(false)
  const [batchTagDraft, setBatchTagDraft] = useState('')

  const [noteModalOpen, setNoteModalOpen] = useState(false)
  const [editingCandidate, setEditingCandidate] = useState<LeaderCandidatePoolItem | null>(null)
  const [noteDraft, setNoteDraft] = useState('')
  const [tagDraft, setTagDraft] = useState('')

  const [historyModalOpen, setHistoryModalOpen] = useState(false)
  const [historyCandidate, setHistoryCandidate] = useState<LeaderCandidatePoolItem | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyResult, setHistoryResult] = useState<LeaderCandidateScoreHistoryResponse | null>(null)
  const [activityHistoryLoading, setActivityHistoryLoading] = useState(false)
  const [activityHistoryResult, setActivityHistoryResult] = useState<LeaderActivityHistoryResponse | null>(null)
  const [activityHistoryAddressInput, setActivityHistoryAddressInput] = useState('')
  const [activityHistoryMarketInput, setActivityHistoryMarketInput] = useState('')
  const [activityHistoryStartTime, setActivityHistoryStartTime] = useState<number | undefined>(undefined)
  const [activityHistoryEndTime, setActivityHistoryEndTime] = useState<number | undefined>(undefined)
  const [activityHistoryIncludeRaw, setActivityHistoryIncludeRaw] = useState(false)
  const [activityHistoryPage, setActivityHistoryPage] = useState(1)

  useEffect(() => {
    if (open && selectedLeaderIds.length === 0 && !seedAddressInput && leaders.length > 0) {
      setSelectedLeaderIds(leaders.slice(0, 5).map(leader => leader.id))
    }
  }, [open, leaders, selectedLeaderIds.length, seedAddressInput])

  useEffect(() => {
    if (open) {
      handleLoadPool(1, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
    }
  }, [open])

  const buildBasePayload = () => ({
    leaderIds: selectedLeaderIds,
    seedAddresses: parseMultilineValues(seedAddressInput),
    days,
    maxSeedMarkets,
    marketTradeLimit,
    excludeExistingLeaders
  })

  const buildDiscoveryLabelFilters = () => ({
    excludeBlacklistedTraders: excludeBlacklistedDiscovery,
    favoriteOnly: discoveryFavoriteOnly,
    includeTags: parseMultilineValues(discoveryIncludeTagsInput),
    excludeTags: parseMultilineValues(discoveryExcludeTagsInput)
  })

  const translateMarketScanMode = (mode?: string | null) =>
    mode ? t(`leaderDiscovery.scanModeOptions.${mode}`, { defaultValue: mode }) : '-'

const translateMarketScanSource = (source?: string | null) =>
  source ? t(`leaderDiscovery.scanSourceOptions.${source}`, { defaultValue: source }) : '-'

const formatCount = (value?: number) => (value != null ? value : '-')

const extractMarketScanSources = (record: LeaderDiscoveredTrader): string[] => {
  const parsed = (record.sourceType || '')
    .split('+')
    .map(item => item.trim().toLowerCase())
    .filter(Boolean)
  if (parsed.length > 0) {
    return Array.from(new Set(parsed))
  }
  if ((record.orderbookBidCount || 0) + (record.orderbookAskCount || 0) > 0 || record.manualNote?.startsWith('orderbook-source:')) {
    return ['orderbook']
  }
  return []
}

const renderManualLabels = (
  record: {
    address: string
      favorite?: boolean
      blacklisted?: boolean
      manualNote?: string | null
      manualTags?: string[]
    }
  ) => (
    <>
      <Space wrap size={[4, 4]}>
        {record.favorite && <Tag color="gold">{t('leaderDiscovery.favorite')}</Tag>}
        {record.blacklisted && <Tag color="red">{t('leaderDiscovery.blacklisted')}</Tag>}
        {(record.manualTags || []).map(tag => <Tag key={`${record.address}-${tag}`}>{tag}</Tag>)}
      </Space>
      {record.manualNote && (
        <Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginBottom: 0, maxWidth: 260 }}>
          {record.manualNote}
        </Paragraph>
      )}
    </>
  )

  const markAddedLeader = (address: string, leaderId: number, leaderName?: string | null) => {
    setScanResult(prev => prev ? {
      ...prev,
      list: prev.list.map(item => item.address === address ? { ...item, existingLeaderId: leaderId, existingLeaderName: leaderName } : item)
    } : prev)
    setMarketScanResult(prev => prev ? {
      ...prev,
      list: prev.list.map(item => item.address === address ? { ...item, existingLeaderId: leaderId, existingLeaderName: leaderName } : item)
    } : prev)
    setRecommendResult(prev => prev ? {
      ...prev,
      list: prev.list.map(item => item.address === address ? { ...item, existingLeaderId: leaderId, existingLeaderName: leaderName } : item)
    } : prev)
    setMarketLookupResult(prev => prev.map(item => ({
      ...item,
      list: item.list.map(trader => trader.address === address ? { ...trader, existingLeaderId: leaderId, existingLeaderName: leaderName } : trader)
    })))
    setPoolResult(prev => prev ? {
      ...prev,
      list: prev.list.map(item => item.address === address ? { ...item, existingLeaderId: leaderId, existingLeaderName: leaderName } : item)
    } : prev)
  }

  const handleAddLeader = async (address: string, displayName?: string | null) => {
    try {
      const response = await apiService.leaders.add({
        leaderAddress: address,
        leaderName: displayName || undefined
      })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('leaderDiscovery.addSuccess'))
        markAddedLeader(address, response.data.data.id, response.data.data.leaderName)
        await onLeaderAdded?.()
        await handleLoadPool(poolPage, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.addFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.addFailed'))
    }
  }

  const handleScan = async () => {
    setScanLoading(true)
    try {
      const response = await apiService.leaders.discoveryScan({
        ...buildBasePayload(),
        ...buildDiscoveryLabelFilters(),
        traderLimit: 30
      })
      if (response.data.code === 0 && response.data.data) {
        setScanResult(response.data.data)
        if (!candidateAddressInput) {
          setCandidateAddressInput(response.data.data.list.map(item => item.address).join('\n'))
        }
      } else {
        message.error(response.data.msg || t('leaderDiscovery.scanFailed'))
      }
    } catch (error: any) {
      if (error?.code === 'ECONNABORTED' || String(error?.message || '').includes('timeout')) {
        message.error(t('leaderDiscovery.traderScanTimeout'))
      } else {
        message.error(error.message || t('leaderDiscovery.scanFailed'))
      }
    } finally {
      setScanLoading(false)
    }
  }

  const handleMarketScan = async () => {
    setMarketScanLoading(true)
    setMarketScanResult(null)
    try {
      const seedAddresses = parseMultilineValues(seedAddressInput)
      const response = await apiService.leaders.discoveryScanMarkets({
        mode: scanMode,
        marketLimit: scanMarketLimit,
        tokenPerMarketLimit: scanTokenPerMarketLimit,
        maxCandidateAddresses: scanMaxCandidateAddresses,
        validationSampleSize: scanValidationSampleSize,
        seedAddresses,
        includeSeedAddresses: scanIncludeSeedAddresses,
        expansionRounds: scanExpansionRounds,
        expansionSeedTraderLimit: scanExpansionSeedTraderLimit,
        expansionMarketLimit: scanExpansionMarketLimit,
        expansionTradeLimitPerMarket: scanExpansionTradeLimitPerMarket,
        days,
        excludeExistingLeaders,
        ...buildDiscoveryLabelFilters(),
        persistToPool: scanPersistToPool
      })
      if (response.data.code === 0 && response.data.data) {
        setMarketScanResult(response.data.data)
        if (!candidateAddressInput) {
          setCandidateAddressInput(response.data.data.list.map(item => item.address).join('\n'))
        }
        if (scanPersistToPool) {
          await handleLoadPool(1, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
        }
        const candidateCount = response.data.data.finalCandidateCount ?? response.data.data.list.length
        if (candidateCount > 0) {
          message.success(
            response.data.data.persistedToPool
              ? t('leaderDiscovery.scanCompletedPersisted', { count: candidateCount })
              : t('leaderDiscovery.scanCompleted', { count: candidateCount })
          )
        } else if ((response.data.data.marketCount ?? 0) === 0) {
          message.warning(t('leaderDiscovery.scanNoMarkets'))
        } else {
          message.info(t('leaderDiscovery.scanNoCandidates'))
        }
      } else {
        setMarketScanResult(null)
        message.error(response.data.msg || t('leaderDiscovery.scanFailed'))
      }
    } catch (error: any) {
      setMarketScanResult(null)
      if (error?.code === 'ECONNABORTED' || String(error?.message || '').includes('timeout')) {
        message.error(t('leaderDiscovery.scanTimeout'))
      } else {
        message.error(error.message || t('leaderDiscovery.scanFailed'))
      }
    } finally {
      setMarketScanLoading(false)
    }
  }

  const handleRecommend = async () => {
    setRecommendLoading(true)
    try {
      const response = await apiService.leaders.discoveryRecommend({
        ...buildBasePayload(),
        ...buildDiscoveryLabelFilters(),
        candidateAddresses: parseMultilineValues(candidateAddressInput),
        traderLimit: recommendTraderLimit,
        minTrades,
        maxOpenPositions,
        maxMarketConcentrationRate,
        maxEstimatedDrawdownRate,
        maxRiskScore,
        lowRiskOnly
      })
      if (response.data.code === 0 && response.data.data) {
        setRecommendResult(response.data.data)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.recommendFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.recommendFailed'))
    } finally {
      setRecommendLoading(false)
    }
  }

  const handleMarketLookup = async () => {
    setMarketLookupLoading(true)
    try {
      const response = await apiService.leaders.discoveryMarketTraders({
        marketIds: parseMultilineValues(marketLookupInput),
        limitPerMarket,
        minTradesPerTrader,
        excludeExistingLeaders,
        ...buildDiscoveryLabelFilters()
      })
      if (response.data.code === 0 && response.data.data) {
        setMarketLookupResult(response.data.data.list)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.marketLookupFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.marketLookupFailed'))
    } finally {
      setMarketLookupLoading(false)
    }
  }

  const handleLoadPool = async (
    page = poolPage,
    lowRisk = poolLowRiskOnly,
    favoriteOnly = poolFavoriteOnly,
    includeBlacklisted = poolIncludeBlacklisted
  ) => {
    setPoolLoading(true)
    try {
      const response = await apiService.leaders.discoveryPool({
        page,
        limit: 10,
        lowRiskOnly: lowRisk,
        favoriteOnly,
        includeBlacklisted
      })
      if (response.data.code === 0 && response.data.data) {
        setPoolResult(response.data.data)
        setPoolPage(page)
        setPoolLowRiskOnly(lowRisk)
        setPoolFavoriteOnly(favoriteOnly)
        setPoolIncludeBlacklisted(includeBlacklisted)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.poolFetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.poolFetchFailed'))
    } finally {
      setPoolLoading(false)
    }
  }

  const handleUpdateLabels = async (
    address: string,
    payload: {
      favorite?: boolean
      blacklisted?: boolean
      manualNote?: string | null
      manualTags?: string[]
    }
  ) => {
    setLabelUpdatingAddress(address)
    try {
      const response = await apiService.leaders.discoveryPoolUpdateLabels({
        address,
        ...payload
      })
      if (response.data.code === 0) {
        message.success(t('leaderDiscovery.labelsUpdated'))
        await handleLoadPool(poolPage, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.labelsUpdateFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.labelsUpdateFailed'))
    } finally {
      setLabelUpdatingAddress(null)
    }
  }

  const handleBatchUpdateLabels = async (payload: Omit<LeaderCandidatePoolBatchLabelUpdateRequest, 'addresses'>) => {
    if (!selectedPoolAddresses.length) {
      message.warning(t('leaderDiscovery.selectCandidatesFirst'))
      return
    }
    setLabelUpdatingAddress('__batch__')
    try {
      const response = await apiService.leaders.discoveryPoolBatchUpdateLabels({
        addresses: selectedPoolAddresses,
        ...payload
      })
      if (response.data.code === 0) {
        message.success(t('leaderDiscovery.batchLabelsUpdated', { count: selectedPoolAddresses.length }))
        setSelectedPoolAddresses([])
        await handleLoadPool(poolPage, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.batchLabelsUpdateFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.batchLabelsUpdateFailed'))
    } finally {
      setLabelUpdatingAddress(null)
    }
  }

  const openNoteEditor = (record: LeaderCandidatePoolItem) => {
    setEditingCandidate(record)
    setNoteDraft(record.manualNote || '')
    setTagDraft((record.manualTags || []).join('\n'))
    setNoteModalOpen(true)
  }

  const handleSaveNote = async () => {
    if (!editingCandidate) return
    await handleUpdateLabels(editingCandidate.address, {
      manualNote: noteDraft,
      manualTags: parseMultilineValues(tagDraft)
    })
    setNoteModalOpen(false)
  }

  const loadHistory = async (address: string, page = 1) => {
    setHistoryLoading(true)
    try {
      const response = await apiService.leaders.discoveryPoolHistory({
        address,
        page,
        limit: 10
      })
      if (response.data.code === 0 && response.data.data) {
        setHistoryResult(response.data.data)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.historyFetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.historyFetchFailed'))
    } finally {
      setHistoryLoading(false)
    }
  }

  const openHistoryModal = async (record: LeaderCandidatePoolItem) => {
    setHistoryCandidate(record)
    setHistoryModalOpen(true)
    await loadHistory(record.address, 1)
  }

  const handleBatchSaveTags = async () => {
    await handleBatchUpdateLabels({
      manualTags: parseMultilineValues(batchTagDraft)
    })
    setBatchTagModalOpen(false)
    setBatchTagDraft('')
  }

  const loadActivityHistoryByAddress = async (page = 1) => {
    const address = activityHistoryAddressInput.trim()
    if (!address) {
      message.warning(t('leaderDiscovery.activityHistoryAddressRequired'))
      return
    }
    setActivityHistoryLoading(true)
    try {
      const response = await apiService.leaders.discoveryHistoryByAddress({
        address,
        page,
        limit: 20,
        startTime: activityHistoryStartTime,
        endTime: activityHistoryEndTime,
        includeRaw: activityHistoryIncludeRaw
      })
      if (response.data.code === 0 && response.data.data) {
        setActivityHistoryResult(response.data.data)
        setActivityHistoryPage(page)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.activityHistoryFetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.activityHistoryFetchFailed'))
    } finally {
      setActivityHistoryLoading(false)
    }
  }

  const loadActivityHistoryByMarket = async (page = 1) => {
    const marketId = activityHistoryMarketInput.trim()
    if (!marketId) {
      message.warning(t('leaderDiscovery.activityHistoryMarketRequired'))
      return
    }
    setActivityHistoryLoading(true)
    try {
      const traderAddress = activityHistoryAddressInput.trim()
      const response = await apiService.leaders.discoveryHistoryByMarket({
        marketId,
        traderAddress: traderAddress || undefined,
        page,
        limit: 20,
        startTime: activityHistoryStartTime,
        endTime: activityHistoryEndTime,
        includeRaw: activityHistoryIncludeRaw
      })
      if (response.data.code === 0 && response.data.data) {
        setActivityHistoryResult(response.data.data)
        setActivityHistoryPage(page)
      } else {
        message.error(response.data.msg || t('leaderDiscovery.activityHistoryFetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('leaderDiscovery.activityHistoryFetchFailed'))
    } finally {
      setActivityHistoryLoading(false)
    }
  }

  const scanColumns = [
    {
      title: t('leaderDiscovery.address'),
      dataIndex: 'address',
      key: 'address',
      render: (_: string, record: LeaderDiscoveredTrader) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.displayName || record.address}</Text>
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.address}</Text>
          {renderManualLabels(record)}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activity'),
      key: 'activity',
      render: (_: unknown, record: LeaderDiscoveredTrader) => (
        <Space direction="vertical" size={0}>
          <Text>{t('leaderDiscovery.tradeCountLabel', { count: record.recentTradeCount })}</Text>
          <Text type="secondary">{t('leaderDiscovery.marketCountLabel', { count: record.distinctMarkets })}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.volume'),
      dataIndex: 'recentVolume',
      key: 'recentVolume',
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('leaderDiscovery.sampleMarkets'),
      key: 'sampleMarkets',
      render: (_: unknown, record: LeaderDiscoveredTrader) => renderMarketSummary(record.sampleMarkets)
    },
    {
      title: t('leaderDiscovery.lastSeenAt'),
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      render: (value?: number | null) => formatTime(value, i18n.language)
    },
    {
      title: t('common.actions'),
      key: 'action',
      render: (_: unknown, record: LeaderDiscoveredTrader) => (
        record.existingLeaderId ? (
          <Tag color="blue">{t('leaderDiscovery.alreadyLeader')}</Tag>
        ) : (
          <Button size="small" onClick={() => handleAddLeader(record.address, record.displayName)}>
            {t('leaderDiscovery.addAsLeader')}
          </Button>
        )
      )
    }
  ]

  const sourceFilterOptions = [
    { text: t('leaderDiscovery.sourceFilterOrderbook'), value: 'orderbook' },
    { text: t('leaderDiscovery.sourceFilterSeed'), value: 'seed' },
    { text: t('leaderDiscovery.sourceFilterMarketExpansion'), value: 'market-expansion' }
  ]

  const marketScanColumns: ColumnsType<LeaderDiscoveredTrader> = [
    {
      title: t('leaderDiscovery.address'),
      dataIndex: 'address',
      key: 'address',
      render: (_: string, record: LeaderDiscoveredTrader) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.displayName || record.address}</Text>
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.address}</Text>
          {renderManualLabels({
            ...record,
            // scan-markets 中 manualNote 可能是 orderbook 原始摘要，不直接展示给用户
            manualNote: record.manualNote?.startsWith('orderbook-source:') ? null : record.manualNote
          })}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activity'),
      key: 'activity',
      render: (_: unknown, record: LeaderDiscoveredTrader) => (
        <Space direction="vertical" size={0}>
          <Text>{t('leaderDiscovery.tradeCountLabel', { count: record.recentTradeCount })}</Text>
          <Text type="secondary">{t('leaderDiscovery.buySellLabel', { buy: record.recentBuyCount, sell: record.recentSellCount })}</Text>
          <Text type="secondary">{t('leaderDiscovery.marketCountLabel', { count: record.distinctMarkets })}</Text>
        </Space>
      )
    },
    {
      title: '来源线索',
      key: 'sourceHints',
      filters: sourceFilterOptions,
      onFilter: (value: boolean | Key, record: LeaderDiscoveredTrader) => extractMarketScanSources(record).includes(String(value)),
      defaultSortOrder: 'descend',
      sorter: (left: LeaderDiscoveredTrader, right: LeaderDiscoveredTrader) => {
        const confidenceDiff = (left.discoveryConfidence || 0) - (right.discoveryConfidence || 0)
        if (confidenceDiff !== 0) return confidenceDiff
        const sourceCountDiff = extractMarketScanSources(left).length - extractMarketScanSources(right).length
        if (sourceCountDiff !== 0) return sourceCountDiff
        return left.recentTradeCount - right.recentTradeCount
      },
      render: (_: unknown, record: LeaderDiscoveredTrader) => {
        const hint = parseOrderbookHint(record.manualNote, record.orderbookBidCount, record.orderbookAskCount)
        const marketCount = record.sourceMarketIds?.length || hint?.markets || 0
        const tokenCount = record.sourceTokenIds?.length || hint?.tokens || 0
        const sources = extractMarketScanSources(record)
        const sourceLabel = sources.length > 0
          ? sources.map(translateMarketScanSource).join(' + ')
          : '-'
        return (
          <Space direction="vertical" size={2}>
            <Space wrap size={[4, 4]}>
              <Tag color="geekblue">{sourceLabel}</Tag>
              {hint && <Tag>{`盘口总单 ${hint.total}`}</Tag>}
              {hint && <Tag>{`买 ${hint.bids} / 卖 ${hint.asks}`}</Tag>}
              {marketCount > 0 && <Tag>{`来源市场 ${marketCount}`}</Tag>}
              {tokenCount > 0 && <Tag>{`来源 token ${tokenCount}`}</Tag>}
            </Space>
            <Text type="secondary">{record.discoveryConfidence != null ? `置信度 ${record.discoveryConfidence}` : '-'}</Text>
          </Space>
        )
      }
    },
    {
      title: t('leaderDiscovery.sampleMarkets'),
      key: 'sampleMarkets',
      render: (_: unknown, record: LeaderDiscoveredTrader) => renderMarketSummary(record.sampleMarkets)
    },
    {
      title: t('leaderDiscovery.volume'),
      dataIndex: 'recentVolume',
      key: 'recentVolume',
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('leaderDiscovery.lastSeenAt'),
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      render: (value?: number | null) => formatTime(value, i18n.language)
    },
    {
      title: t('common.actions'),
      key: 'action',
      render: (_: unknown, record: LeaderDiscoveredTrader) => (
        record.existingLeaderId ? (
          <Tag color="blue">{t('leaderDiscovery.alreadyLeader')}</Tag>
        ) : (
          <Button size="small" onClick={() => handleAddLeader(record.address, record.displayName)}>
            {t('leaderDiscovery.addAsLeader')}
          </Button>
        )
      )
    },
    {
      title: t('leaderDiscovery.sourceFilter'),
      dataIndex: 'sourceType',
      key: 'sourceType',
      filters: sourceFilterOptions,
      onFilter: (value: boolean | Key, record: LeaderDiscoveredTrader) =>
        Boolean(record.sourceType?.split('+').includes(String(value))),
      render: (value?: string | null, record?: LeaderDiscoveredTrader) => (
        <Tag color={record?.sourceType?.includes('market-expansion') ? 'purple' : 'geekblue'}>
          {value || '-'}
        </Tag>
      )
    },
  ]

  const recommendationColumns = [
    {
      title: t('leaderDiscovery.address'),
      dataIndex: 'address',
      key: 'address',
      render: (_: string, record: LeaderCandidateRecommendation) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.displayName || record.address}</Text>
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.address}</Text>
          {renderManualLabels(record)}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.score'),
      key: 'score',
      render: (_: unknown, record: LeaderCandidateRecommendation) => (
        <Space direction="vertical" size={0}>
          <Tag color={record.lowRisk ? 'green' : 'orange'}>{t('leaderDiscovery.recommendScoreLabel', { score: record.recommendationScore })}</Tag>
          <Tag color={record.riskScore <= maxRiskScore ? 'green' : 'red'}>{t('leaderDiscovery.riskScoreLabel', { score: record.riskScore })}</Tag>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.metrics'),
      key: 'metrics',
      render: (_: unknown, record: LeaderCandidateRecommendation) => (
        <Space direction="vertical" size={0}>
          <Text>{t('leaderDiscovery.estimatedRoiLabel', { rate: record.estimatedRoiRate })}</Text>
          <Text type="secondary">{t('leaderDiscovery.drawdownLabel', { rate: record.estimatedDrawdownRate })}</Text>
          <Text type="secondary">{t('leaderDiscovery.concentrationLabel', { rate: record.marketConcentrationRate })}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.reasons'),
      key: 'reasons',
      render: (_: unknown, record: LeaderCandidateRecommendation) => (
        <Space direction="vertical" size={4}>
          <Space wrap>
            {record.tags.map(tag => <Tag key={`${record.address}-${tag}`}>{tag}</Tag>)}
          </Space>
          <Paragraph style={{ marginBottom: 0 }}>{record.reasons.join('；')}</Paragraph>
        </Space>
      )
    },
    {
      title: t('common.actions'),
      key: 'action',
      render: (_: unknown, record: LeaderCandidateRecommendation) => (
        record.existingLeaderId ? (
          <Tag color="blue">{t('leaderDiscovery.alreadyLeader')}</Tag>
        ) : (
          <Button size="small" onClick={() => handleAddLeader(record.address, record.displayName)}>
            {t('leaderDiscovery.addAsLeader')}
          </Button>
        )
      )
    }
  ]

  const poolColumns = [
    {
      title: t('leaderDiscovery.address'),
      dataIndex: 'address',
      key: 'address',
      render: (_: string, record: LeaderCandidatePoolItem) => (
        <Space direction="vertical" size={4}>
          <Space direction="vertical" size={0}>
            <Text strong>{record.displayName || record.address}</Text>
            <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.address}</Text>
          </Space>
          {renderManualLabels(record)}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activity'),
      key: 'activity',
      render: (_: unknown, record: LeaderCandidatePoolItem) => (
        <Space direction="vertical" size={0}>
          <Text>{t('leaderDiscovery.tradeCountLabel', { count: record.recentTradeCount })}</Text>
          <Text type="secondary">{t('leaderDiscovery.buySellLabel', { buy: record.recentBuyCount, sell: record.recentSellCount })}</Text>
          <Text type="secondary">{t('leaderDiscovery.marketCountLabel', { count: record.distinctMarkets })}</Text>
          <Text type="secondary">{formatUSDC(record.recentVolume)}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.lastMarket'),
      key: 'lastMarket',
      render: (_: unknown, record: LeaderCandidatePoolItem) => record.lastMarketTitle || record.lastMarketId || '-'
    },
    {
      title: t('leaderDiscovery.score'),
      key: 'score',
      render: (_: unknown, record: LeaderCandidatePoolItem) => (
        <Space direction="vertical" size={4}>
          {record.recommendationScore !== null && record.recommendationScore !== undefined ? (
            <Tag color={record.lowRisk ? 'green' : 'orange'}>{t('leaderDiscovery.recommendScoreLabel', { score: record.recommendationScore })}</Tag>
          ) : (
            <Text type="secondary">{t('leaderDiscovery.notEvaluated')}</Text>
          )}
          {record.riskScore !== null && record.riskScore !== undefined && (
            <Tag color={record.lowRisk ? 'green' : 'red'}>{t('leaderDiscovery.riskScoreLabel', { score: record.riskScore })}</Tag>
          )}
          {record.estimatedRoiRate && <Text type="secondary">{t('leaderDiscovery.estimatedRoiLabel', { rate: record.estimatedRoiRate })}</Text>}
          {record.estimatedDrawdownRate && <Text type="secondary">{t('leaderDiscovery.drawdownLabel', { rate: record.estimatedDrawdownRate })}</Text>}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.lastSeenAt'),
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      render: (value?: number | null) => formatTime(value, i18n.language)
    },
    {
      title: t('common.actions'),
      key: 'action',
      render: (_: unknown, record: LeaderCandidatePoolItem) => (
        <Space wrap size={[8, 8]}>
          {record.existingLeaderId ? (
            <Tag color="blue">{t('leaderDiscovery.alreadyLeader')}</Tag>
          ) : (
            <Button size="small" onClick={() => handleAddLeader(record.address, record.displayName)}>
              {t('leaderDiscovery.addAsLeader')}
            </Button>
          )}
          <Button
            size="small"
            type={record.favorite ? 'primary' : 'default'}
            loading={labelUpdatingAddress === record.address}
            onClick={() => handleUpdateLabels(record.address, { favorite: !record.favorite })}
          >
            {record.favorite ? t('leaderDiscovery.unfavorite') : t('leaderDiscovery.favorite')}
          </Button>
          <Button
            size="small"
            danger={record.blacklisted}
            loading={labelUpdatingAddress === record.address}
            onClick={() => handleUpdateLabels(record.address, { blacklisted: !record.blacklisted })}
          >
            {record.blacklisted ? t('leaderDiscovery.removeBlacklist') : t('leaderDiscovery.blacklist')}
          </Button>
          <Button size="small" onClick={() => openNoteEditor(record)}>
            {t('leaderDiscovery.note')}
          </Button>
          <Button size="small" onClick={() => openHistoryModal(record)}>
            {t('leaderDiscovery.history')}
          </Button>
        </Space>
      )
    }
  ]

  const marketLookupPanels = marketLookupResult.map(item => ({
    key: item.marketId,
    label: `${item.marketTitle || item.marketId} (${item.traderCount})`,
    children: (
      <Table
        rowKey="address"
        size="small"
        pagination={false}
        dataSource={item.list}
        columns={[
          {
            title: t('leaderDiscovery.address'),
            dataIndex: 'address',
            key: 'address',
            render: (_: string, record) => (
              <Space direction="vertical" size={0}>
                <Text strong>{record.displayName || record.address}</Text>
                <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.address}</Text>
                {renderManualLabels(record)}
              </Space>
            )
          },
          {
            title: t('leaderDiscovery.activity'),
            key: 'activity',
            render: (_: unknown, record) => (
              <Space direction="vertical" size={0}>
                <Text>{t('leaderDiscovery.tradeCountLabel', { count: record.tradeCount })}</Text>
                <Text type="secondary">{t('leaderDiscovery.buySellLabel', { buy: record.buyCount, sell: record.sellCount })}</Text>
              </Space>
            )
          },
          {
            title: t('leaderDiscovery.volume'),
            dataIndex: 'totalVolume',
            key: 'totalVolume',
            render: (value: string) => formatUSDC(value)
          },
          {
            title: t('common.actions'),
            key: 'action',
            render: (_: unknown, record) => (
              record.existingLeaderId ? (
                <Tag color="blue">{t('leaderDiscovery.alreadyLeader')}</Tag>
              ) : (
                <Button size="small" onClick={() => handleAddLeader(record.address, record.displayName)}>
                  {t('leaderDiscovery.addAsLeader')}
                </Button>
              )
            )
          }
        ]}
      />
    )
  }))

  const historyColumns = [
    {
      title: t('leaderDiscovery.historyTime'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (value: number) => formatTime(value, i18n.language)
    },
    {
      title: t('leaderDiscovery.score'),
      key: 'score',
      render: (_: unknown, record: LeaderCandidateScoreHistoryItem) => (
        <Space direction="vertical" size={4}>
          {record.recommendationScore !== null && record.recommendationScore !== undefined && (
            <Tag color={record.lowRisk ? 'green' : 'orange'}>{t('leaderDiscovery.recommendScoreLabel', { score: record.recommendationScore })}</Tag>
          )}
          {record.riskScore !== null && record.riskScore !== undefined && (
            <Tag color={record.lowRisk ? 'green' : 'red'}>{t('leaderDiscovery.riskScoreLabel', { score: record.riskScore })}</Tag>
          )}
          <Text type="secondary">{record.source}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.metrics'),
      key: 'metrics',
      render: (_: unknown, record: LeaderCandidateScoreHistoryItem) => (
        <Space direction="vertical" size={0}>
          {record.estimatedRoiRate && <Text>{t('leaderDiscovery.estimatedRoiLabel', { rate: record.estimatedRoiRate })}</Text>}
          {record.estimatedDrawdownRate && <Text type="secondary">{t('leaderDiscovery.drawdownLabel', { rate: record.estimatedDrawdownRate })}</Text>}
          {record.marketConcentrationRate && <Text type="secondary">{t('leaderDiscovery.concentrationLabel', { rate: record.marketConcentrationRate })}</Text>}
          {record.estimatedTotalPnl && <Text type="secondary">{t('leaderDiscovery.totalPnlLabel', { value: formatUSDC(record.estimatedTotalPnl) })}</Text>}
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.reasons'),
      key: 'reasons',
      render: (_: unknown, record: LeaderCandidateScoreHistoryItem) => (
        <Space direction="vertical" size={4}>
          <Space wrap size={[4, 4]}>
            {record.tags.map(tag => <Tag key={`${record.createdAt}-${tag}`}>{tag}</Tag>)}
          </Space>
          <Paragraph style={{ marginBottom: 0 }}>
            {record.reasons.length ? record.reasons.join('；') : '-'}
          </Paragraph>
        </Space>
      )
    }
  ]

  const activityHistoryColumns = [
    {
      title: t('leaderDiscovery.activityHistoryEventTime'),
      dataIndex: 'eventTimestamp',
      key: 'eventTimestamp',
      render: (value: number) => formatTime(value, i18n.language)
    },
    {
      title: t('leaderDiscovery.activityHistoryTrader'),
      key: 'trader',
      render: (_: unknown, record: LeaderActivityHistoryItem) => (
        <Space direction="vertical" size={0}>
          <Text strong>{record.displayName || record.traderAddress}</Text>
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{record.traderAddress}</Text>
          <Space wrap size={[4, 4]}>
            {record.favorite && <Tag color="gold">{t('leaderDiscovery.favorite')}</Tag>}
            {record.blacklisted && <Tag color="red">{t('leaderDiscovery.blacklisted')}</Tag>}
          </Space>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activityHistoryMarket'),
      key: 'market',
      render: (_: unknown, record: LeaderActivityHistoryItem) => (
        <Space direction="vertical" size={0}>
          <Text>{record.marketTitle || record.marketId}</Text>
          <Text type="secondary">{record.marketSlug || '-'}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activityHistorySide'),
      dataIndex: 'side',
      key: 'side',
      render: (value?: string | null) => value ? (
        <Tag color={value === 'BUY' ? 'green' : value === 'SELL' ? 'orange' : 'default'}>{value}</Tag>
      ) : '-'
    },
    {
      title: t('leaderDiscovery.activityHistoryTrade'),
      key: 'trade',
      render: (_: unknown, record: LeaderActivityHistoryItem) => (
        <Space direction="vertical" size={0}>
          <Text type="secondary">price: {record.price || '-'}</Text>
          <Text type="secondary">size: {record.size || '-'}</Text>
          <Text type="secondary">vol: {record.volume || '-'}</Text>
        </Space>
      )
    },
    {
      title: t('leaderDiscovery.activityHistoryTxHash'),
      dataIndex: 'transactionHash',
      key: 'transactionHash',
      ellipsis: true,
      render: (value?: string | null) => value || '-'
    }
  ]

  const isAggressiveScanMode = scanMode === 'AGGRESSIVE'

  const poolRowSelection = {
    selectedRowKeys: selectedPoolAddresses,
    onChange: (keys: readonly Key[]) => setSelectedPoolAddresses(keys.map(String))
  }

  return (
    <>
      <Modal
        open={open}
        title={t('leaderDiscovery.title')}
        onCancel={onClose}
        width={1200}
        footer={[
          <Button key="close" onClick={onClose}>{t('common.close')}</Button>
        ]}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert message={t('leaderDiscovery.estimatedNotice')} type="info" showIcon />
          <Space wrap style={{ width: '100%' }}>
            <Select
              mode="multiple"
              allowClear
              style={{ minWidth: 320 }}
              placeholder={t('leaderDiscovery.seedLeadersPlaceholder')}
              options={leaderOptions}
              value={selectedLeaderIds}
              onChange={setSelectedLeaderIds}
            />
            <InputNumber min={1} max={30} value={days} onChange={value => setDays(value || 7)} addonBefore={t('leaderDiscovery.days')} />
            <InputNumber min={1} max={50} value={maxSeedMarkets} onChange={value => setMaxSeedMarkets(value || 20)} addonBefore={t('leaderDiscovery.seedMarkets')} />
            <InputNumber min={20} max={200} value={marketTradeLimit} onChange={value => setMarketTradeLimit(value || 120)} addonBefore={t('leaderDiscovery.marketTrades')} />
            <Space>
              <Text>{t('leaderDiscovery.excludeExisting')}</Text>
              <Switch checked={excludeExistingLeaders} onChange={setExcludeExistingLeaders} />
            </Space>
          </Space>
          <Input.TextArea
            value={seedAddressInput}
            onChange={event => setSeedAddressInput(event.target.value)}
            rows={3}
            placeholder={t('leaderDiscovery.seedAddressesPlaceholder')}
          />

          <Tabs
            items={[
              {
                key: 'pool',
                label: t('leaderDiscovery.poolTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Space wrap>
                      <Button onClick={() => handleLoadPool(1, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)} loading={poolLoading}>
                        {t('leaderDiscovery.refreshPool')}
                      </Button>
                      <Space>
                        <Text>{t('leaderDiscovery.lowRiskOnly')}</Text>
                        <Switch checked={poolLowRiskOnly} onChange={checked => handleLoadPool(1, checked, poolFavoriteOnly, poolIncludeBlacklisted)} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.favoriteOnly')}</Text>
                        <Switch checked={poolFavoriteOnly} onChange={checked => handleLoadPool(1, poolLowRiskOnly, checked, poolIncludeBlacklisted)} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.includeBlacklisted')}</Text>
                        <Switch checked={poolIncludeBlacklisted} onChange={checked => handleLoadPool(1, poolLowRiskOnly, poolFavoriteOnly, checked)} />
                      </Space>
                    </Space>
                    <Space wrap>
                      <Text type="secondary">
                        {selectedPoolAddresses.length
                          ? t('leaderDiscovery.selectedCandidates', { count: selectedPoolAddresses.length })
                          : t('leaderDiscovery.batchSelectionHint')}
                      </Text>
                      <Button
                        size="small"
                        disabled={!selectedPoolAddresses.length}
                        loading={labelUpdatingAddress === '__batch__'}
                        onClick={() => handleBatchUpdateLabels({ favorite: true })}
                      >
                        {t('leaderDiscovery.batchFavorite')}
                      </Button>
                      <Button
                        size="small"
                        disabled={!selectedPoolAddresses.length}
                        loading={labelUpdatingAddress === '__batch__'}
                        onClick={() => handleBatchUpdateLabels({ blacklisted: true })}
                      >
                        {t('leaderDiscovery.batchBlacklist')}
                      </Button>
                      <Button
                        size="small"
                        disabled={!selectedPoolAddresses.length}
                        onClick={() => setBatchTagModalOpen(true)}
                      >
                        {t('leaderDiscovery.batchTag')}
                      </Button>
                    </Space>
                    <Table
                      rowKey="address"
                      size="small"
                      loading={poolLoading}
                      dataSource={poolResult?.list || []}
                      columns={poolColumns}
                      rowSelection={poolRowSelection}
                      pagination={{
                        current: poolResult?.page || poolPage,
                        pageSize: poolResult?.limit || 10,
                        total: poolResult?.total || 0,
                        onChange: page => handleLoadPool(page, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)
                      }}
                    />
                  </Space>
                )
              },
              {
                key: 'scan',
                label: t('leaderDiscovery.scanTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Space>
                      <Text>{t('leaderDiscovery.excludeBlacklisted')}</Text>
                      <Switch checked={excludeBlacklistedDiscovery} onChange={setExcludeBlacklistedDiscovery} />
                    </Space>
                    <Space>
                      <Text>{t('leaderDiscovery.favoriteOnly')}</Text>
                      <Switch checked={discoveryFavoriteOnly} onChange={setDiscoveryFavoriteOnly} />
                    </Space>
                    <Input
                      value={discoveryIncludeTagsInput}
                      onChange={event => setDiscoveryIncludeTagsInput(event.target.value)}
                      style={{ width: 220 }}
                      placeholder={t('leaderDiscovery.includeTags')}
                    />
                    <Input
                      value={discoveryExcludeTagsInput}
                      onChange={event => setDiscoveryExcludeTagsInput(event.target.value)}
                      style={{ width: 220 }}
                      placeholder={t('leaderDiscovery.excludeTags')}
                    />
                    <Button type="primary" loading={scanLoading} onClick={handleScan}>
                      {t('leaderDiscovery.scanAction')}
                    </Button>
                    <Table rowKey="address" size="small" loading={scanLoading} dataSource={scanResult?.list || []} columns={scanColumns} pagination={{ pageSize: 8 }} />
                  </Space>
                )
              },
              {
                key: 'scan-markets',
                label: t('leaderDiscovery.scanMarketsTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Space wrap>
                      <Select
                        value={scanMode}
                        onChange={value => setScanMode(value)}
                        options={[
                          { value: 'ORDERBOOK', label: t('leaderDiscovery.scanModeOptions.ORDERBOOK') },
                          { value: 'AGGRESSIVE', label: t('leaderDiscovery.scanModeOptions.AGGRESSIVE') }
                        ]}
                        style={{ width: 200 }}
                      />
                      <InputNumber
                        min={10}
                        max={300}
                        value={scanMarketLimit}
                        onChange={value => setScanMarketLimit(value || 50)}
                        addonBefore={t('leaderDiscovery.scanMarketLimit')}
                      />
                      <InputNumber
                        min={1}
                        max={5}
                        value={scanTokenPerMarketLimit}
                        onChange={value => setScanTokenPerMarketLimit(value || 2)}
                        addonBefore={t('leaderDiscovery.scanTokenPerMarketLimit')}
                      />
                      <InputNumber
                        min={50}
                        max={3000}
                        value={scanMaxCandidateAddresses}
                        onChange={value => setScanMaxCandidateAddresses(value || 500)}
                        addonBefore={t('leaderDiscovery.scanMaxCandidateAddresses')}
                      />
                      <InputNumber
                        min={20}
                        max={2000}
                        value={scanValidationSampleSize}
                        onChange={value => setScanValidationSampleSize(value || 300)}
                        addonBefore={t('leaderDiscovery.scanValidationSampleSize')}
                      />
                    </Space>
                    <Space wrap>
                      <InputNumber
                        min={0}
                        max={5}
                        value={scanExpansionRounds}
                        onChange={value => setScanExpansionRounds(value || 0)}
                        addonBefore={t('leaderDiscovery.scanExpansionRounds')}
                        disabled={!isAggressiveScanMode}
                      />
                      <InputNumber
                        min={1}
                        max={500}
                        value={scanExpansionSeedTraderLimit}
                        onChange={value => setScanExpansionSeedTraderLimit(value || 30)}
                        addonBefore={t('leaderDiscovery.scanExpansionSeedTraderLimit')}
                        disabled={!isAggressiveScanMode}
                      />
                      <InputNumber
                        min={1}
                        max={200}
                        value={scanExpansionMarketLimit}
                        onChange={value => setScanExpansionMarketLimit(value || 8)}
                        addonBefore={t('leaderDiscovery.scanExpansionMarketLimit')}
                        disabled={!isAggressiveScanMode}
                      />
                      <InputNumber
                        min={1}
                        max={500}
                        value={scanExpansionTradeLimitPerMarket}
                        onChange={value => setScanExpansionTradeLimitPerMarket(value || 60)}
                        addonBefore={t('leaderDiscovery.scanExpansionTradeLimitPerMarket')}
                        disabled={!isAggressiveScanMode}
                      />
                    </Space>
                    <Space wrap>
                      <Space>
                        <Text>{t('leaderDiscovery.excludeBlacklisted')}</Text>
                        <Switch checked={excludeBlacklistedDiscovery} onChange={setExcludeBlacklistedDiscovery} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.favoriteOnly')}</Text>
                        <Switch checked={discoveryFavoriteOnly} onChange={setDiscoveryFavoriteOnly} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.scanIncludeSeedAddresses')}</Text>
                        <Switch checked={scanIncludeSeedAddresses} onChange={setScanIncludeSeedAddresses} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.scanPersistToPool')}</Text>
                        <Switch checked={scanPersistToPool} onChange={setScanPersistToPool} />
                      </Space>
                      <Input
                        value={discoveryIncludeTagsInput}
                        onChange={event => setDiscoveryIncludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.includeTags')}
                      />
                      <Input
                        value={discoveryExcludeTagsInput}
                        onChange={event => setDiscoveryExcludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.excludeTags')}
                      />
                    </Space>
                    <Button type="primary" loading={marketScanLoading} onClick={handleMarketScan}>
                      {t('leaderDiscovery.scanMarketsAction')}
                    </Button>
                    {marketScanResult && (
                      <Alert
                        type={marketScanResult.list.length > 0 ? 'info' : 'warning'}
                        showIcon
                        message={t('leaderDiscovery.scanSummaryTitle', {
                          count: marketScanResult.finalCandidateCount ?? marketScanResult.list.length
                        })}
                        description={(
                          <Space wrap size={[8, 8]}>
                            <Tag>{`${t('leaderDiscovery.scanSummaryDiscoveryMode')} ${translateMarketScanMode(marketScanResult.discoveryMode)}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummarySource')} ${translateMarketScanSource(marketScanResult.source)}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummarySources')} ${(marketScanResult.sources || []).length > 0 ? marketScanResult.sources!.map(translateMarketScanSource).join(', ') : '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryOrderbookCount')} ${formatCount(marketScanResult.sourceBreakdown?.orderbook)}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummarySeedCount')} ${formatCount(marketScanResult.sourceBreakdown?.seed)}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryMarketExpansionCount')} ${formatCount(
                              marketScanResult.sourceBreakdown?.['market-expansion']
                            )}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryMarketCount')} ${marketScanResult.marketCount}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryTokenCount')} ${marketScanResult.tokenCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryRawAddressCount')} ${marketScanResult.rawAddressCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryValidatedAddressCount')} ${marketScanResult.validatedAddressCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummarySeedAddressCount')} ${marketScanResult.seedAddressCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryExpandedMarketCount')} ${marketScanResult.expandedMarketCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryExpandedTraderCount')} ${marketScanResult.expandedTraderCount ?? '-'}`}</Tag>
                            <Tag>{`${t('leaderDiscovery.scanSummaryDuration')} ${formatDuration(marketScanResult.durationMs)}`}</Tag>
                            <Tag color={marketScanResult.persistedToPool ? 'green' : 'default'}>
                              {marketScanResult.persistedToPool
                                ? t('leaderDiscovery.scanSummaryPersisted')
                                : t('leaderDiscovery.scanSummaryNotPersisted')}
                            </Tag>
                            {marketScanResult.persistedToPool && (
                              <Button
                                size="small"
                                onClick={() => handleLoadPool(1, poolLowRiskOnly, poolFavoriteOnly, poolIncludeBlacklisted)}
                              >
                                {t('leaderDiscovery.refreshPool')}
                              </Button>
                            )}
                          </Space>
                        )}
                      />
                    )}
                    {marketScanResult && marketScanResult.list.length === 0 && (
                      <Alert
                        type="warning"
                        showIcon
                        message={t('leaderDiscovery.scanEmptyTitle')}
                        description={
                          marketScanResult.marketCount > 0
                            ? t('leaderDiscovery.scanEmptyDescription')
                            : t('leaderDiscovery.scanEmptyNetworkDescription')
                        }
                      />
                    )}
                    <Table
                      rowKey="address"
                      size="small"
                      loading={marketScanLoading}
                      dataSource={marketScanResult?.list || []}
                      columns={marketScanColumns}
                      pagination={{ pageSize: 8 }}
                    />
                  </Space>
                )
              },
              {
                key: 'recommend',
                label: t('leaderDiscovery.recommendTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Input.TextArea
                      value={candidateAddressInput}
                      onChange={event => setCandidateAddressInput(event.target.value)}
                      rows={3}
                      placeholder={t('leaderDiscovery.candidateAddressesPlaceholder')}
                    />
                    <Space wrap>
                      <InputNumber min={1} max={100} value={recommendTraderLimit} onChange={value => setRecommendTraderLimit(value || 20)} addonBefore={t('leaderDiscovery.traderLimit')} />
                      <InputNumber min={1} max={200} value={minTrades} onChange={value => setMinTrades(value || 8)} addonBefore={t('leaderDiscovery.minTrades')} />
                      <InputNumber min={1} max={50} value={maxOpenPositions} onChange={value => setMaxOpenPositions(value || 8)} addonBefore={t('leaderDiscovery.maxOpenPositions')} />
                      <InputNumber min={0.05} max={1} step={0.05} value={maxMarketConcentrationRate} onChange={value => setMaxMarketConcentrationRate(value || 0.45)} addonBefore={t('leaderDiscovery.maxConcentration')} />
                      <InputNumber min={0.01} max={1} step={0.01} value={maxEstimatedDrawdownRate} onChange={value => setMaxEstimatedDrawdownRate(value || 0.18)} addonBefore={t('leaderDiscovery.maxDrawdown')} />
                      <InputNumber min={0} max={100} value={maxRiskScore} onChange={value => setMaxRiskScore(value || 45)} addonBefore={t('leaderDiscovery.maxRiskScore')} />
                      <Space>
                        <Text>{t('leaderDiscovery.lowRiskOnly')}</Text>
                        <Switch checked={lowRiskOnly} onChange={setLowRiskOnly} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.excludeBlacklisted')}</Text>
                        <Switch checked={excludeBlacklistedDiscovery} onChange={setExcludeBlacklistedDiscovery} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.favoriteOnly')}</Text>
                        <Switch checked={discoveryFavoriteOnly} onChange={setDiscoveryFavoriteOnly} />
                      </Space>
                      <Input
                        value={discoveryIncludeTagsInput}
                        onChange={event => setDiscoveryIncludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.includeTags')}
                      />
                      <Input
                        value={discoveryExcludeTagsInput}
                        onChange={event => setDiscoveryExcludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.excludeTags')}
                      />
                    </Space>
                    <Button type="primary" loading={recommendLoading} onClick={handleRecommend}>
                      {t('leaderDiscovery.recommendAction')}
                    </Button>
                    <Table rowKey="address" size="small" loading={recommendLoading} dataSource={recommendResult?.list || []} columns={recommendationColumns} pagination={{ pageSize: 6 }} />
                  </Space>
                )
              },
              {
                key: 'market',
                label: t('leaderDiscovery.marketLookupTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Input.TextArea
                      value={marketLookupInput}
                      onChange={event => setMarketLookupInput(event.target.value)}
                      rows={3}
                      placeholder={t('leaderDiscovery.marketIdsPlaceholder')}
                    />
                    <Space wrap>
                      <InputNumber min={1} max={100} value={limitPerMarket} onChange={value => setLimitPerMarket(value || 20)} addonBefore={t('leaderDiscovery.limitPerMarket')} />
                      <InputNumber min={1} max={50} value={minTradesPerTrader} onChange={value => setMinTradesPerTrader(value || 1)} addonBefore={t('leaderDiscovery.minTradesPerTrader')} />
                      <Space>
                        <Text>{t('leaderDiscovery.excludeBlacklisted')}</Text>
                        <Switch checked={excludeBlacklistedDiscovery} onChange={setExcludeBlacklistedDiscovery} />
                      </Space>
                      <Space>
                        <Text>{t('leaderDiscovery.favoriteOnly')}</Text>
                        <Switch checked={discoveryFavoriteOnly} onChange={setDiscoveryFavoriteOnly} />
                      </Space>
                      <Input
                        value={discoveryIncludeTagsInput}
                        onChange={event => setDiscoveryIncludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.includeTags')}
                      />
                      <Input
                        value={discoveryExcludeTagsInput}
                        onChange={event => setDiscoveryExcludeTagsInput(event.target.value)}
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.excludeTags')}
                      />
                    </Space>
                    <Button type="primary" loading={marketLookupLoading} onClick={handleMarketLookup}>
                      {t('leaderDiscovery.marketLookupAction')}
                    </Button>
                    <Collapse items={marketLookupPanels} />
                  </Space>
                )
              },
              {
                key: 'activity-history',
                label: t('leaderDiscovery.activityHistoryTab'),
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <Space wrap>
                      <Input
                        style={{ width: 320 }}
                        placeholder={t('leaderDiscovery.activityHistoryAddressPlaceholder')}
                        value={activityHistoryAddressInput}
                        onChange={event => setActivityHistoryAddressInput(event.target.value)}
                      />
                      <Input
                        style={{ width: 260 }}
                        placeholder={t('leaderDiscovery.activityHistoryMarketPlaceholder')}
                        value={activityHistoryMarketInput}
                        onChange={event => setActivityHistoryMarketInput(event.target.value)}
                      />
                      <InputNumber
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.activityHistoryStartPlaceholder')}
                        value={activityHistoryStartTime}
                        onChange={value => setActivityHistoryStartTime(value ?? undefined)}
                      />
                      <InputNumber
                        style={{ width: 220 }}
                        placeholder={t('leaderDiscovery.activityHistoryEndPlaceholder')}
                        value={activityHistoryEndTime}
                        onChange={value => setActivityHistoryEndTime(value ?? undefined)}
                      />
                      <Space>
                        <Text>{t('leaderDiscovery.activityHistoryIncludeRaw')}</Text>
                        <Switch checked={activityHistoryIncludeRaw} onChange={setActivityHistoryIncludeRaw} />
                      </Space>
                    </Space>
                    <Space wrap>
                      <Button loading={activityHistoryLoading} onClick={() => loadActivityHistoryByAddress(1)}>
                        {t('leaderDiscovery.activityHistoryQueryByAddress')}
                      </Button>
                      <Button loading={activityHistoryLoading} onClick={() => loadActivityHistoryByMarket(1)}>
                        {t('leaderDiscovery.activityHistoryQueryByMarket')}
                      </Button>
                    </Space>
                    <Table
                      rowKey={(record: LeaderActivityHistoryItem) => record.eventKey}
                      size="small"
                      loading={activityHistoryLoading}
                      dataSource={activityHistoryResult?.list || []}
                      columns={activityHistoryColumns}
                      pagination={{
                        current: activityHistoryResult?.page || activityHistoryPage,
                        pageSize: activityHistoryResult?.limit || 20,
                        total: activityHistoryResult?.total || 0,
                        onChange: page => {
                          if (activityHistoryMarketInput.trim()) {
                            loadActivityHistoryByMarket(page)
                          } else {
                            loadActivityHistoryByAddress(page)
                          }
                        }
                      }}
                    />
                  </Space>
                )
              }
            ]}
          />
        </Space>
      </Modal>

      <Modal
        open={noteModalOpen}
        title={editingCandidate ? `${t('leaderDiscovery.note')} - ${editingCandidate.displayName || editingCandidate.address}` : t('leaderDiscovery.note')}
        onCancel={() => setNoteModalOpen(false)}
        onOk={handleSaveNote}
        confirmLoading={labelUpdatingAddress === editingCandidate?.address}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input.TextArea
            rows={4}
            value={noteDraft}
            onChange={event => setNoteDraft(event.target.value)}
            placeholder={t('leaderDiscovery.notePlaceholder')}
          />
          <Input.TextArea
            rows={3}
            value={tagDraft}
            onChange={event => setTagDraft(event.target.value)}
            placeholder={t('leaderDiscovery.manualTagsPlaceholder')}
          />
        </Space>
      </Modal>

      <Modal
        open={historyModalOpen}
        title={historyCandidate ? `${t('leaderDiscovery.history')} - ${historyCandidate.displayName || historyCandidate.address}` : t('leaderDiscovery.history')}
        onCancel={() => setHistoryModalOpen(false)}
        width={960}
        footer={[
          <Button key="close" onClick={() => setHistoryModalOpen(false)}>{t('common.close')}</Button>
        ]}
      >
        <Table
          rowKey={record => `${record.createdAt}-${record.source}-${record.address}`}
          size="small"
          loading={historyLoading}
          dataSource={historyResult?.list || []}
          columns={historyColumns}
          pagination={{
            current: historyResult?.page || 1,
            pageSize: historyResult?.limit || 10,
            total: historyResult?.total || 0,
            onChange: page => historyCandidate && loadHistory(historyCandidate.address, page)
          }}
        />
      </Modal>

      <Modal
        open={batchTagModalOpen}
        title={t('leaderDiscovery.batchTag')}
        onCancel={() => setBatchTagModalOpen(false)}
        onOk={handleBatchSaveTags}
        confirmLoading={labelUpdatingAddress === '__batch__'}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Text type="secondary">{t('leaderDiscovery.selectedCandidates', { count: selectedPoolAddresses.length })}</Text>
          <Input.TextArea
            rows={4}
            value={batchTagDraft}
            onChange={event => setBatchTagDraft(event.target.value)}
            placeholder={t('leaderDiscovery.manualTagsPlaceholder')}
          />
        </Space>
      </Modal>
    </>
  )
}

export default LeaderDiscoveryModal
