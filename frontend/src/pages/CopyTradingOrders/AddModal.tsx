import React, { useCallback, useEffect, useState, useRef } from 'react'
import { Modal, Form, Button, Switch, message, Space, Radio, InputNumber, Table, Select, Divider, Input, Tag, InputRef, Card, Row, Col, Statistic, Spin } from 'antd'
import { SaveOutlined, FileTextOutlined, PlusOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import { useAccountStore } from '../../store/accountStore'
import type {
  Leader,
  CopyTradingTemplate,
  CopyTradingCreateRequest,
  FilterMode,
  MarketCategoryOption,
} from '../../types'
import {
  buildRepeatAddReductionPayload,
  formatCopyModeSummary,
  formatMultiplierSummary,
  formatRepeatAddReductionSummary,
  formatUSDC,
  validateAndNormalizeMultiplierTiers,
  validateRepeatAddReductionConfig
} from '../../utils'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import AccountImportForm from '../../components/AccountImportForm'
import LeaderAddForm from '../../components/LeaderAddForm'
import LeaderSelect from '../../components/LeaderSelect'
import MultiplierTierEditor from '../../components/MultiplierTierEditor'

const { Option } = Select

const MARKET_CATEGORY_OPTIONS: Array<{ label: string; value: MarketCategoryOption }> = [
  { label: 'Crypto', value: 'crypto' },
  { label: 'Sports', value: 'sports' },
]

const MARKET_INTERVAL_OPTIONS = [
  { label: '5m', value: 300 },
  { label: '15m', value: 900 },
  { label: '1h', value: 3600 },
  { label: '4h', value: 14400 },
  { label: '1d', value: 86400 },
]

const COIN_SYMBOL_OPTIONS = [
  { label: 'BTC', value: 'BTC' },
  { label: 'ETH', value: 'ETH' },
  { label: 'SOL', value: 'SOL' },
  { label: 'XRP', value: 'XRP' },
  { label: 'DOGE', value: 'DOGE' },
]

interface AddModalProps {
  open: boolean
  onClose: () => void
  onSuccess?: () => void
  preFilledConfig?: {
    leaderId?: number
    copyMode?: 'RATIO' | 'FIXED' | 'ADAPTIVE'
    copyRatio?: number
    fixedAmount?: string
    adaptiveMinRatio?: number
    adaptiveMaxRatio?: number
    adaptiveThreshold?: number
    multiplierMode?: 'NONE' | 'SINGLE' | 'TIERED'
    tradeMultiplier?: number
    tieredMultipliers?: Array<{ min: number; max?: number | null; multiplier: number }>
    maxOrderSize?: number
    minOrderSize?: number
    maxDailyLoss?: number
    maxDailyOrders?: number
    maxDailyVolume?: number
    buyCycleEnabled?: boolean
    buyCycleRunSeconds?: number
    buyCyclePauseSeconds?: number
    repeatAddReductionEnabled?: boolean
    repeatAddReductionStrategy?: 'UNIFORM' | 'PROGRESSIVE'
    repeatAddReductionValueType?: 'PERCENT' | 'FIXED'
    repeatAddReductionPercent?: number
    repeatAddReductionFixedAmount?: number
    repeatAddCooldownEnabled?: boolean
    repeatAddCooldownSeconds?: number
    smallOrderAggregationEnabled?: boolean
    smallOrderAggregationWindowSeconds?: number
    supportSell?: boolean
    keywordFilterMode?: string
    keywords?: string[]
    marketCategoryMode?: FilterMode
    marketCategories?: MarketCategoryOption[]
    marketIntervalMode?: FilterMode
    marketIntervals?: number[]
    marketSeriesMode?: FilterMode
    marketSeries?: string[]
    coinFilterMode?: FilterMode
    coinSymbols?: string[]
    maxPositionValue?: number
    maxPositionCount?: number
    configName?: string
  }
}

const AddModal: React.FC<AddModalProps> = ({
  open,
  onClose,
  onSuccess,
  preFilledConfig
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [templateModalVisible, setTemplateModalVisible] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED' | 'ADAPTIVE'>('RATIO')
  const [multiplierMode, setMultiplierMode] = useState<'NONE' | 'SINGLE' | 'TIERED'>('NONE')
  const [keywords, setKeywords] = useState<string[]>([])
  const keywordInputRef = useRef<InputRef>(null)
  const [maxMarketEndDateValue, setMaxMarketEndDateValue] = useState<number | undefined>()
  const [maxMarketEndDateUnit, setMaxMarketEndDateUnit] = useState<'HOUR' | 'DAY'>('HOUR')
  const [leaderAssetInfo, setLeaderAssetInfo] = useState<{ total: string; available: string; position: string } | null>(null)
  const [loadingAssetInfo, setLoadingAssetInfo] = useState(false)
  
  // 导入账户modal相关状态
  const [accountImportModalVisible, setAccountImportModalVisible] = useState(false)
  const [accountImportForm] = Form.useForm()
  
  // 添加leader modal相关状态
  const [leaderAddModalVisible, setLeaderAddModalVisible] = useState(false)
  const [leaderAddForm] = Form.useForm()
  
  // 生成默认配置名
  const generateDefaultConfigName = useCallback((): string => {
    const now = new Date()
    const dateStr = now.toLocaleDateString('zh-CN', { 
      year: 'numeric', 
      month: '2-digit', 
      day: '2-digit' 
    }).replace(/\//g, '-')
    const timeStr = now.toLocaleTimeString('zh-CN', { 
      hour: '2-digit', 
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
    return `跟单配置-${dateStr}-${timeStr}`
  }, [])
  
  // 获取 Leader 资产信息
  const fetchLeaderAssetInfo = useCallback(async (leaderId: number) => {
    if (!leaderId) return
    
    setLoadingAssetInfo(true)
    setLeaderAssetInfo(null)
    try {
      const response = await apiService.leaders.balance({ leaderId })
      if (response.data.code === 0 && response.data.data) {
        const balance = response.data.data
        setLeaderAssetInfo({
          total: balance.totalBalance || '0',
          available: balance.availableBalance || '0',
          position: balance.positionBalance || '0'
        })
      } else {
        message.error(response.data.msg || t('copyTradingAdd.fetchAssetInfoFailed') || '获取资产信息失败')
      }
    } catch (error: any) {
      console.error('获取 Leader 资产失败:', error)
      message.error(error.message || t('copyTradingAdd.fetchAssetInfoFailed') || '获取资产信息失败')
    } finally {
      setLoadingAssetInfo(false)
    }
  }, [t])

  // 填充预配置数据到表单（复用模板填充逻辑）
  const fillPreFilledConfig = useCallback((config: typeof preFilledConfig) => {
    console.log('[AddModal] fillPreFilledConfig called with config:', config)
    if (!config) {
      console.log('[AddModal] fillPreFilledConfig: config is null/undefined')
      return
    }

    const formValues = {
      configName: config.configName || generateDefaultConfigName(),
      leaderId: config.leaderId,
      copyMode: config.copyMode || 'RATIO',
      copyRatio: config.copyRatio,
      fixedAmount: config.fixedAmount,
      adaptiveMinRatio: config.adaptiveMinRatio,
      adaptiveMaxRatio: config.adaptiveMaxRatio,
      adaptiveThreshold: config.adaptiveThreshold,
      multiplierMode: config.multiplierMode || 'NONE',
      tradeMultiplier: config.tradeMultiplier,
      tieredMultipliers: config.tieredMultipliers,
      maxOrderSize: config.maxOrderSize,
      minOrderSize: config.minOrderSize,
      maxDailyLoss: config.maxDailyLoss,
      maxDailyOrders: config.maxDailyOrders,
      maxDailyVolume: config.maxDailyVolume,
      buyCycleEnabled: config.buyCycleEnabled ?? false,
      buyCycleRunMinutes: config.buyCycleRunSeconds ? config.buyCycleRunSeconds / 60 : 45,
      buyCyclePauseMinutes: config.buyCyclePauseSeconds ? config.buyCyclePauseSeconds / 60 : 30,
      repeatAddReductionEnabled: config.repeatAddReductionEnabled ?? false,
      repeatAddReductionStrategy: config.repeatAddReductionStrategy || 'UNIFORM',
      repeatAddReductionValueType: config.repeatAddReductionValueType || 'PERCENT',
      repeatAddReductionPercent: config.repeatAddReductionPercent,
      repeatAddReductionFixedAmount: config.repeatAddReductionFixedAmount,
      repeatAddCooldownEnabled: config.repeatAddCooldownEnabled ?? false,
      repeatAddCooldownSeconds: config.repeatAddCooldownSeconds ?? 60,
      smallOrderAggregationEnabled: config.smallOrderAggregationEnabled ?? false,
      smallOrderAggregationWindowSeconds: config.smallOrderAggregationWindowSeconds,
      supportSell: config.supportSell,
      keywordFilterMode: config.keywordFilterMode || 'DISABLED',
      marketCategoryMode: config.marketCategoryMode || 'DISABLED',
      marketCategories: config.marketCategories,
      marketIntervalMode: config.marketIntervalMode || 'DISABLED',
      marketIntervals: config.marketIntervals,
      marketSeriesMode: config.marketSeriesMode || 'DISABLED',
      marketSeries: config.marketSeries,
      coinFilterMode: config.coinFilterMode || 'DISABLED',
      coinSymbols: config.coinSymbols,
      maxPositionValue: config.maxPositionValue,
      maxPositionCount: config.maxPositionCount
    }
    console.log('[AddModal] fillPreFilledConfig: setting form values:', formValues)
    
    form.setFieldsValue(formValues)
    setCopyMode(config.copyMode || 'RATIO')
    setMultiplierMode(config.multiplierMode || 'NONE')
    setKeywords(config.keywords || [])
    
    console.log('[AddModal] fillPreFilledConfig: form values set, copyMode:', config.copyMode, 'keywords:', config.keywords)
    
    // 自动获取 Leader 资产信息
    if (config.leaderId) {
      console.log('[AddModal] fillPreFilledConfig: fetching leader asset info for leaderId:', config.leaderId)
      fetchLeaderAssetInfo(config.leaderId)
    }
  }, [fetchLeaderAssetInfo, form, generateDefaultConfigName])
  
  // 处理 Modal 打开/关闭
  const fetchLeaders = useCallback(async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchLeaderFailed') || '获取 Leader 列表失败')
    }
  }, [t])

  const fetchTemplates = useCallback(async () => {
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchTemplateFailed') || '获取模板列表失败')
    }
  }, [t])

  useEffect(() => {
    console.log('[AddModal] useEffect triggered, open:', open, 'preFilledConfig:', preFilledConfig)
    if (open) {
      console.log('[AddModal] Modal opened, fetching accounts, leaders, templates')
      fetchAccounts()
      fetchLeaders()
      fetchTemplates()
      
      // 如果有预填充配置，填充表单（延迟执行确保数据已加载）
      if (preFilledConfig) {
        console.log('[AddModal] preFilledConfig exists, will fill form after 100ms')
        // 使用 setTimeout 确保在下一个事件循环执行，此时 Modal 已完全打开
        setTimeout(() => {
          console.log('[AddModal] setTimeout callback executed, calling fillPreFilledConfig')
          fillPreFilledConfig(preFilledConfig)
        }, 100)
      } else {
        console.log('[AddModal] No preFilledConfig, using default values')
        // 没有预填充配置时，生成默认配置名
      const defaultConfigName = generateDefaultConfigName()
        form.setFieldsValue({
          configName: defaultConfigName,
          copyMode: 'RATIO',
          copyRatio: 100,
          multiplierMode: 'NONE',
          maxOrderSize: 1000,
          minOrderSize: 1,
          maxDailyLoss: 10000,
          maxDailyOrders: 100,
          buyCycleEnabled: false,
          buyCycleRunMinutes: 45,
          buyCyclePauseMinutes: 30,
          repeatAddReductionEnabled: false,
          repeatAddReductionStrategy: 'UNIFORM',
          repeatAddReductionValueType: 'PERCENT',
          repeatAddCooldownEnabled: false,
          repeatAddCooldownSeconds: 60,
          smallOrderAggregationEnabled: false,
          smallOrderAggregationWindowSeconds: 300,
          supportSell: true,
          keywordFilterMode: 'DISABLED',
          marketCategoryMode: 'DISABLED',
          marketIntervalMode: 'DISABLED',
          marketSeriesMode: 'DISABLED',
          coinFilterMode: 'DISABLED'
        })
        setCopyMode('RATIO')
        setMultiplierMode('NONE')
      setKeywords([])
      }
    } else {
      console.log('[AddModal] Modal closed, resetting form')
      // 关闭时重置表单
      form.resetFields()
      setKeywords([])
      setCopyMode('RATIO')
      setMultiplierMode('NONE')
      setLeaderAssetInfo(null)
    }
  }, [fetchAccounts, fetchLeaders, fetchTemplates, fillPreFilledConfig, form, generateDefaultConfigName, open, preFilledConfig])
  
  const handleSelectTemplate = (template: CopyTradingTemplate) => {
    // 填充模板数据到表单（只填充模板中存在的字段）
    form.setFieldsValue({
      copyMode: template.copyMode,
      copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100, // 转换为百分比显示
      fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
      adaptiveMinRatio: template.adaptiveMinRatio ? parseFloat(template.adaptiveMinRatio) * 100 : undefined,
      adaptiveMaxRatio: template.adaptiveMaxRatio ? parseFloat(template.adaptiveMaxRatio) * 100 : undefined,
      adaptiveThreshold: template.adaptiveThreshold ? parseFloat(template.adaptiveThreshold) : undefined,
      multiplierMode: template.multiplierMode || 'NONE',
      tradeMultiplier: template.tradeMultiplier ? parseFloat(template.tradeMultiplier) : undefined,
      tieredMultipliers: template.tieredMultipliers?.map((tier) => ({
        min: parseFloat(tier.min),
        max: tier.max != null ? parseFloat(tier.max) : undefined,
        multiplier: parseFloat(tier.multiplier)
      })),
      maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
      minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
      maxDailyLoss: template.maxDailyLoss ? parseFloat(template.maxDailyLoss) : undefined,
      maxDailyOrders: template.maxDailyOrders,
      maxDailyVolume: template.maxDailyVolume ? parseFloat(template.maxDailyVolume) : undefined,
      buyCycleEnabled: false,
      buyCycleRunMinutes: 45,
      buyCyclePauseMinutes: 30,
      repeatAddReductionEnabled: template.repeatAddReductionEnabled ?? false,
      repeatAddReductionStrategy: template.repeatAddReductionStrategy || 'UNIFORM',
      repeatAddReductionValueType: template.repeatAddReductionValueType || 'PERCENT',
      repeatAddReductionPercent: template.repeatAddReductionPercent ? parseFloat(template.repeatAddReductionPercent) : undefined,
      repeatAddReductionFixedAmount: template.repeatAddReductionFixedAmount ? parseFloat(template.repeatAddReductionFixedAmount) : undefined,
      repeatAddCooldownEnabled: template.repeatAddCooldownEnabled ?? false,
      repeatAddCooldownSeconds: template.repeatAddCooldownSeconds ?? 60,
      smallOrderAggregationEnabled: template.smallOrderAggregationEnabled ?? false,
      smallOrderAggregationWindowSeconds: template.smallOrderAggregationWindowSeconds ?? 300,
      priceTolerance: template.priceTolerance ? parseFloat(template.priceTolerance) : undefined,
      supportSell: template.supportSell,
      minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
      maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
      minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
      maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined,
      maxPositionValue: (template as any).maxPositionValue ? parseFloat((template as any).maxPositionValue) : undefined,
      maxPositionCount: (template as any).maxPositionCount,
      pushFilteredOrders: template.pushFilteredOrders ?? false,
      marketCategoryMode: template.marketCategoryMode || 'DISABLED',
      marketCategories: template.marketCategories,
      marketIntervalMode: template.marketIntervalMode || 'DISABLED',
      marketIntervals: template.marketIntervals,
      marketSeriesMode: template.marketSeriesMode || 'DISABLED',
      marketSeries: template.marketSeries,
      coinFilterMode: template.coinFilterMode || 'DISABLED',
      coinSymbols: template.coinSymbols
    })
    setCopyMode(template.copyMode)
    setMultiplierMode(template.multiplierMode || 'NONE')
    setTemplateModalVisible(false)
    message.success(t('copyTradingAdd.templateFilled') || '模板内容已填充，您可以修改')
  }
  
  const handleCopyModeChange = (mode: 'RATIO' | 'FIXED' | 'ADAPTIVE') => {
    setCopyMode(mode)
  }
  
  // 处理导入账户成功
  const handleAccountImportSuccess = async (accountId: number) => {
    message.success('导入账户成功')
    
    // 刷新账户列表
    await fetchAccounts()
    
    // 自动选择新添加的账户
    form.setFieldsValue({ accountId })
    
    // 关闭modal并重置表单
    setAccountImportModalVisible(false)
    accountImportForm.resetFields()
  }
  
  // 处理添加leader成功
  const handleLeaderAddSuccess = async (leaderId: number) => {
    message.success(t('leaderAdd.addSuccess') || '添加 Leader 成功')
    
    // 刷新leader列表
    await fetchLeaders()
    
    // 自动选择新添加的leader
    form.setFieldsValue({ leaderId })
    
    // 关闭modal并重置表单
    setLeaderAddModalVisible(false)
    leaderAddForm.resetFields()
  }
  
  // 添加关键字
  const handleAddKeyword = (e?: React.KeyboardEvent<HTMLInputElement>) => {
    let inputValue = ''
    
    if (e) {
      // 从键盘事件获取输入值
      const target = e.target as HTMLInputElement
      inputValue = target.value.trim()
    } else if (keywordInputRef.current) {
      // 从输入框 ref 获取值
      inputValue = keywordInputRef.current.input?.value?.trim() || ''
    }
    
    if (!inputValue) {
      return
    }
    
    // 检查是否已存在
    if (keywords.includes(inputValue)) {
      message.warning(t('copyTradingAdd.keywordExists') || '关键字已存在')
      return
    }
    
    // 添加关键字
    const newKeywords = [...keywords, inputValue]
    setKeywords(newKeywords)
    
    // 清空输入框
    if (keywordInputRef.current) {
      keywordInputRef.current.input!.value = ''
    }
  }
  
  // 删除关键字
  const handleRemoveKeyword = (index: number) => {
    const newKeywords = keywords.filter((_, i) => i !== index)
    setKeywords(newKeywords)
  }
  
  const handleSubmit = async (values: any) => {
    // 前端校验
    if (values.copyMode === 'FIXED') {
      if (!values.fixedAmount || Number(values.fixedAmount) < 1) {
        message.error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1')
        return
      }
    }

    if (values.copyMode === 'ADAPTIVE') {
      if (!values.copyRatio || !values.adaptiveMinRatio || !values.adaptiveMaxRatio || !values.adaptiveThreshold) {
        message.error('请完整填写自适应策略参数')
        return
      }
    }
    
    if (values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1')
      return
    }

    if (
      (values.marketCategoryMode === 'WHITELIST' || values.marketCategoryMode === 'BLACKLIST') &&
      (!values.marketCategories || values.marketCategories.length === 0)
    ) {
      message.error('请至少选择一个市场分类')
      return
    }

    if (
      (values.marketIntervalMode === 'WHITELIST' || values.marketIntervalMode === 'BLACKLIST') &&
      (!values.marketIntervals || values.marketIntervals.length === 0)
    ) {
      message.error('请至少选择一个市场周期')
      return
    }

    if (
      (values.marketSeriesMode === 'WHITELIST' || values.marketSeriesMode === 'BLACKLIST') &&
      (!values.marketSeries || values.marketSeries.length === 0)
    ) {
      message.error('请至少输入一个市场系列')
      return
    }

    if (
      (values.coinFilterMode === 'WHITELIST' || values.coinFilterMode === 'BLACKLIST') &&
      (!values.coinSymbols || values.coinSymbols.length === 0)
    ) {
      message.error('请至少选择一个币种')
      return
    }

    const normalizedTierResult = values.multiplierMode === 'TIERED'
      ? validateAndNormalizeMultiplierTiers(values.tieredMultipliers)
      : null
    if (normalizedTierResult && !normalizedTierResult.isValid) {
      message.error(normalizedTierResult.message || '分层 multiplier 配置不合法')
      return
    }

    const repeatAddReductionError = validateRepeatAddReductionConfig(values)
    if (repeatAddReductionError) {
      message.error(repeatAddReductionError)
      return
    }
    if (values.repeatAddCooldownEnabled) {
      if (!values.repeatAddCooldownSeconds || Number(values.repeatAddCooldownSeconds) <= 0) {
        message.error('启用同市场同方向加仓冷却时，冷却秒数必须大于 0')
        return
      }
    }

    if (values.buyCycleEnabled) {
      if (!values.buyCycleRunMinutes || Number(values.buyCycleRunMinutes) <= 0) {
        message.error('启用买单循环时，运行时长必须大于 0 分钟')
        return
      }
      if (!values.buyCyclePauseMinutes || Number(values.buyCyclePauseMinutes) <= 0) {
        message.error('启用买单循环时，暂停时长必须大于 0 分钟')
        return
      }
    }

    const repeatAddReductionPayload: Pick<
      CopyTradingCreateRequest,
      'repeatAddReductionEnabled' |
      'repeatAddReductionStrategy' |
      'repeatAddReductionValueType' |
      'repeatAddReductionPercent' |
      'repeatAddReductionFixedAmount'
    > = buildRepeatAddReductionPayload(values)
    
    // 计算市场截止时间（毫秒）
    let maxMarketEndDate: number | undefined
    if (maxMarketEndDateValue !== undefined && maxMarketEndDateValue > 0) {
      const multiplier = maxMarketEndDateUnit === 'HOUR' 
        ? 60 * 60 * 1000  // 小时转毫秒
        : 24 * 60 * 60 * 1000  // 天转毫秒
      maxMarketEndDate = maxMarketEndDateValue * multiplier
    }
    
    setLoading(true)
    try {
      const request: CopyTradingCreateRequest = {
        accountId: values.accountId,
        leaderId: values.leaderId,
        enabled: false, // 手动新建默认关闭，需手动开启
        copyMode: values.copyMode || 'RATIO',
        copyRatio: (values.copyMode === 'RATIO' || values.copyMode === 'ADAPTIVE') && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        adaptiveMinRatio: values.copyMode === 'ADAPTIVE' ? (values.adaptiveMinRatio / 100).toString() : undefined,
        adaptiveMaxRatio: values.copyMode === 'ADAPTIVE' ? (values.adaptiveMaxRatio / 100).toString() : undefined,
        adaptiveThreshold: values.copyMode === 'ADAPTIVE' ? values.adaptiveThreshold?.toString() : undefined,
        multiplierMode: values.multiplierMode || 'NONE',
        tradeMultiplier: values.multiplierMode === 'SINGLE' ? values.tradeMultiplier?.toString() : undefined,
        tieredMultipliers: values.multiplierMode === 'TIERED'
          ? normalizedTierResult?.tiers
          : undefined,
        maxOrderSize: values.maxOrderSize?.toString(),
        minOrderSize: values.minOrderSize?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        maxDailyVolume: values.maxDailyVolume?.toString(),
        buyCycleEnabled: values.buyCycleEnabled ?? false,
        buyCycleRunSeconds: values.buyCycleEnabled
          ? Math.round(Number(values.buyCycleRunMinutes) * 60)
          : undefined,
        buyCyclePauseSeconds: values.buyCycleEnabled
          ? Math.round(Number(values.buyCyclePauseMinutes) * 60)
          : undefined,
        ...repeatAddReductionPayload,
        repeatAddCooldownEnabled: values.repeatAddCooldownEnabled ?? false,
        repeatAddCooldownSeconds: values.repeatAddCooldownEnabled
          ? values.repeatAddCooldownSeconds
          : undefined,
        smallOrderAggregationEnabled: values.smallOrderAggregationEnabled ?? false,
        smallOrderAggregationWindowSeconds: values.smallOrderAggregationEnabled
          ? values.smallOrderAggregationWindowSeconds
          : undefined,
        priceTolerance: values.priceTolerance?.toString(),
        delaySeconds: values.delaySeconds,
        pollIntervalSeconds: values.pollIntervalSeconds,
        useWebSocket: values.useWebSocket,
        websocketReconnectInterval: values.websocketReconnectInterval,
        websocketMaxRetries: values.websocketMaxRetries,
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        maxPositionValue: values.maxPositionValue?.toString(),
        maxPositionCount: values.maxPositionCount,
        keywordFilterMode: values.keywordFilterMode || 'DISABLED',
        keywords: (values.keywordFilterMode === 'WHITELIST' || values.keywordFilterMode === 'BLACKLIST') 
          ? keywords 
          : undefined,
        marketCategoryMode: values.marketCategoryMode || 'DISABLED',
        marketCategories: (values.marketCategoryMode === 'WHITELIST' || values.marketCategoryMode === 'BLACKLIST')
          ? values.marketCategories
          : undefined,
        marketIntervalMode: values.marketIntervalMode || 'DISABLED',
        marketIntervals: (values.marketIntervalMode === 'WHITELIST' || values.marketIntervalMode === 'BLACKLIST')
          ? values.marketIntervals
          : undefined,
        marketSeriesMode: values.marketSeriesMode || 'DISABLED',
        marketSeries: (values.marketSeriesMode === 'WHITELIST' || values.marketSeriesMode === 'BLACKLIST')
          ? (values.marketSeries as string[] | undefined)?.map((item) => item.trim()).filter(Boolean)
          : undefined,
        coinFilterMode: values.coinFilterMode || 'DISABLED',
        coinSymbols: (values.coinFilterMode === 'WHITELIST' || values.coinFilterMode === 'BLACKLIST')
          ? values.coinSymbols
          : undefined,
        configName: values.configName?.trim(),
        pushFailedOrders: values.pushFailedOrders ?? false,
        pushFilteredOrders: values.pushFilteredOrders ?? false,
        maxMarketEndDate
      }
      
      const response = await apiService.copyTrading.create(request)
      
      if (response.data.code === 0) {
        message.success(t('copyTradingAdd.createSuccess') || '创建跟单配置成功')
        onClose()
        if (onSuccess) {
          onSuccess()
        }
      } else {
        message.error(response.data.msg || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <>
      <Modal
        title={t('copyTradingAdd.title') || '新增跟单配置'}
        open={open}
        onCancel={onClose}
        footer={null}
        width="90%"
        style={{ top: 20 }}
        bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100,
            multiplierMode: 'NONE',
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyLoss: 10000,
            maxDailyOrders: 100,
            buyCycleEnabled: false,
            buyCycleRunMinutes: 45,
            buyCyclePauseMinutes: 30,
            repeatAddCooldownEnabled: false,
            repeatAddCooldownSeconds: 60,
            smallOrderAggregationEnabled: false,
            smallOrderAggregationWindowSeconds: 300,
            priceTolerance: 5,
            delaySeconds: 0,
            pollIntervalSeconds: 5,
            useWebSocket: true,
            websocketReconnectInterval: 5000,
            websocketMaxRetries: 10,
            supportSell: true,
            pushFailedOrders: false,
            pushFilteredOrders: false,
            keywordFilterMode: 'DISABLED',
            marketCategoryMode: 'DISABLED',
            marketIntervalMode: 'DISABLED',
            marketSeriesMode: 'DISABLED',
            coinFilterMode: 'DISABLED'
          }}
        >
          {/* 基础信息 */}
          <Form.Item
            label={t('copyTradingAdd.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingAdd.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingAdd.configNameRequired') || '配置名不能为空' }
            ]}
            tooltip={t('copyTradingAdd.configNameTooltip') || '为跟单配置设置一个名称，便于识别和管理'}
          >
            <Input 
              placeholder={t('copyTradingAdd.configNamePlaceholder') || '例如：跟单配置1'} 
              maxLength={255}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectWallet') || '选择钱包'}
            name="accountId"
            rules={[{ required: true, message: t('copyTradingAdd.walletRequired') || '请选择钱包' }]}
          >
            <Select 
              placeholder={t('copyTradingAdd.selectWalletPlaceholder') || '请选择钱包'}
              notFoundContent={
                accounts.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noAccounts') || '暂无账户'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setAccountImportModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.importAccount') || '导入账户'}
                    </Button>
                  </div>
                ) : null
              }
            >
              {accounts.map(account => (
                <Option key={account.id} value={account.id}>
                  {account.accountName || `账户 ${account.id}`} ({account.walletAddress.slice(0, 6)}...{account.walletAddress.slice(-4)})
                </Option>
              ))}
            </Select>
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectLeader') || '选择 Leader'}
            name="leaderId"
            rules={[{ required: true, message: t('copyTradingAdd.leaderRequired') || '请选择 Leader' }]}
          >
            <LeaderSelect
              leaders={leaders}
              placeholder={t('copyTradingAdd.selectLeaderPlaceholder') || '请选择 Leader'}
              onSelectChange={(value) => value !== undefined && fetchLeaderAssetInfo(value)}
              notFoundContent={
                leaders.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noLeaders') || '暂无 Leader'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setLeaderAddModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.addLeader') || '添加 Leader'}
                    </Button>
                  </div>
                ) : null
              }
            />
          </Form.Item>
          
          {/* Leader 资产信息 */}
          {leaderAssetInfo && (
            <Card
              title={
                <Space>
                  <span>{t('copyTradingAdd.leaderAssetInfo') || 'Leader 资产信息'}</span>
                </Space>
              }
              size="small"
              style={{ marginBottom: '16px', backgroundColor: '#f5f5f5', border: '1px solid #d9d9d9' }}
            >
              {loadingAssetInfo ? (
                <div style={{ textAlign: 'center', padding: '24px' }}>
                  <Spin />
                  <div style={{ marginTop: '8px', color: '#999' }}>
                    {t('copyTradingAdd.loadingAssetInfo') || '加载资产信息中...'}
                  </div>
                </div>
              ) : (
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.totalAsset') || '总资产'}
                      value={parseFloat(leaderAssetInfo.total)}
                      precision={4}
                      valueStyle={{ color: '#52c41a', fontWeight: 'bold', fontSize: '16px' }}
                      suffix="USDC"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.availableBalance') || '可用余额'}
                      value={parseFloat(leaderAssetInfo.available)}
                      precision={4}
                      valueStyle={{ color: '#1890ff', fontSize: '14px' }}
                      suffix="USDC"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('copyTradingAdd.positionAsset') || '仓位资产'}
                      value={parseFloat(leaderAssetInfo.position)}
                      precision={4}
                      valueStyle={{ color: '#722ed1', fontSize: '14px' }}
                      suffix="USDC"
                      formatter={(value) => formatUSDC(value?.toString() || '0')}
                    />
                  </Col>
                </Row>
              )}
            </Card>
          )}
          
          {/* 模板填充按钮 */}
          <Form.Item>
            <Button
              type="dashed"
              icon={<FileTextOutlined />}
              onClick={() => setTemplateModalVisible(true)}
              style={{ width: '100%' }}
            >
              {t('copyTradingAdd.selectTemplateFromModal') || '从模板填充配置'}
            </Button>
          </Form.Item>
          
          {/* 跟单金额模式 */}
          <Form.Item
            label={t('copyTradingAdd.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('copyTradingAdd.copyModeTooltip') || '选择跟单金额的计算方式。支持比例、固定金额和自适应模式。'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingAdd.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingAdd.fixedAmountMode') || '固定金额模式'}</Radio>
              <Radio value="ADAPTIVE">{t('copyTradingAdd.adaptiveMode') || '自适应模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {(copyMode === 'RATIO' || copyMode === 'ADAPTIVE') && (
            <Form.Item
              label={copyMode === 'ADAPTIVE' ? (t('copyTradingAdd.baseCopyRatio') || '基础跟单比例') : (t('copyTradingAdd.copyRatio') || '跟单比例')}
              name="copyRatio"
              tooltip={t('copyTradingAdd.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder={t('copyTradingAdd.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
                parser={(value) => {
                  const cleaned = (value || '').toString().replace(/%/g, '').trim()
                  const parsed = parseFloat(cleaned) || 0
                  if (parsed > 10000) return 10000
                  if (parsed < 0.01) return 0.01
                  return parsed
                }}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  if (num > 10000) return '10000'
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}

          {copyMode === 'ADAPTIVE' && (
            <>
              <Form.Item
                label={t('copyTradingAdd.adaptiveMinRatio') || '自适应最小比例'}
                name="adaptiveMinRatio"
                rules={[{ required: true, message: t('copyTradingAdd.adaptiveMinRatioRequired') || '请输入自适应最小比例' }]}
              >
                <InputNumber
                  min={0.01}
                  max={10000}
                  step={0.01}
                  precision={2}
                  style={{ width: '100%' }}
                  addonAfter="%"
                />
              </Form.Item>
              <Form.Item
                label={t('copyTradingAdd.adaptiveMaxRatio') || '自适应最大比例'}
                name="adaptiveMaxRatio"
                rules={[{ required: true, message: t('copyTradingAdd.adaptiveMaxRatioRequired') || '请输入自适应最大比例' }]}
              >
                <InputNumber
                  min={0.01}
                  max={10000}
                  step={0.01}
                  precision={2}
                  style={{ width: '100%' }}
                  addonAfter="%"
                />
              </Form.Item>
              <Form.Item
                label={t('copyTradingAdd.adaptiveThreshold') || '自适应阈值 (USDC)'}
                name="adaptiveThreshold"
                rules={[{ required: true, message: t('copyTradingAdd.adaptiveThresholdRequired') || '请输入自适应阈值' }]}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.adaptiveThresholdPlaceholder') || '达到该 Leader 订单金额后开始向最小比例收缩'}
                />
              </Form.Item>
            </>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingAdd.fixedAmount') || '固定跟单金额 (USDC)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingAdd.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingAdd.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1'))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                min={1}
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder={t('copyTradingAdd.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}
          
          <Divider>{t('copyTradingAdd.sizingEnhancement') || 'Sizing 增强'}</Divider>

          <Form.Item
            label={t('copyTradingAdd.multiplierMode') || 'Multiplier 模式'}
            name="multiplierMode"
          >
            <Radio.Group onChange={(e) => setMultiplierMode(e.target.value)}>
              <Radio value="NONE">{t('copyTradingAdd.multiplierModeNone') || '无'}</Radio>
              <Radio value="SINGLE">{t('copyTradingAdd.multiplierModeSingle') || '单一倍率'}</Radio>
              <Radio value="TIERED">{t('copyTradingAdd.multiplierModeTiered') || '分层倍率'}</Radio>
            </Radio.Group>
          </Form.Item>

          {multiplierMode === 'SINGLE' && (
            <Form.Item
              label={t('copyTradingAdd.tradeMultiplier') || '倍率'}
              name="tradeMultiplier"
              rules={[{ required: true, message: t('copyTradingAdd.tradeMultiplierRequired') || '请输入倍率' }]}
            >
              <InputNumber
                min={0}
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                addonAfter="x"
              />
            </Form.Item>
          )}

          {multiplierMode === 'TIERED' && (
            <Form.Item
              label={t('copyTradingAdd.tieredMultipliers') || '分层倍率'}
              required
            >
              <MultiplierTierEditor />
            </Form.Item>
          )}

          <Form.Item
            label={t('copyTradingAdd.maxOrderSize') || '单笔订单最大金额 (USDC)'}
            name="maxOrderSize"
            tooltip={t('copyTradingAdd.maxOrderSizeTooltip') || '对所有 sizing 模式统一生效的单笔最大金额'}
          >
            <InputNumber
              min={0.0001}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxOrderSizePlaceholder') || '默认 1000（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.minOrderSize') || '单笔订单最小金额 (USDC)'}
            name="minOrderSize"
            tooltip={t('copyTradingAdd.minOrderSizeTooltip') || '对所有 sizing 模式统一生效的单笔最小金额，必须 >= 1'}
            rules={[
              {
                validator: (_, value) => {
                  if (value === undefined || value === null || value === '') {
                    return Promise.resolve()
                  }
                  if (typeof value === 'number' && value < 1) {
                    return Promise.reject(new Error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1'))
                  }
                  return Promise.resolve()
                }
              }
            ]}
          >
            <InputNumber
              min={1}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.minOrderSizePlaceholder') || '默认 1（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxDailyLoss') || '每日最大亏损限制 (USDC)'}
            name="maxDailyLoss"
            tooltip={t('copyTradingAdd.maxDailyLossTooltip') || '限制每日最大亏损金额，用于风险控制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyLossPlaceholder') || '默认 10000 USDC（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('copyTradingAdd.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.maxDailyVolume') || '每日最大成交额 (USDC)'}
            name="maxDailyVolume"
            tooltip={t('copyTradingAdd.maxDailyVolumeTooltip') || '只统计 BUY 成交额；留空表示不启用'}
          >
            <InputNumber
              min={0.0001}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyVolumePlaceholder') || '例如：5000（可选）'}
            />
          </Form.Item>

          <Divider>同市场再次加仓减金额</Divider>

          <Form.Item
            label="启用同市场再次加仓减金额"
            name="repeatAddReductionEnabled"
            tooltip="只针对同市场同方向的再次买入；首笔不受影响，仓位平掉后重新计数。"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.repeatAddReductionEnabled !== currentValues.repeatAddReductionEnabled ||
            prevValues.repeatAddReductionValueType !== currentValues.repeatAddReductionValueType
          }>
            {({ getFieldValue }) => getFieldValue('repeatAddReductionEnabled') ? (
              <>
                <Form.Item
                  label="缩量策略"
                  name="repeatAddReductionStrategy"
                  rules={[{ required: true, message: '请选择缩量策略' }]}
                >
                  <Radio.Group>
                    <Radio value="UNIFORM">第二笔起统一改小</Radio>
                    <Radio value="PROGRESSIVE">逐次递减</Radio>
                  </Radio.Group>
                </Form.Item>

                <Form.Item
                  label="缩量类型"
                  name="repeatAddReductionValueType"
                  rules={[{ required: true, message: '请选择缩量类型' }]}
                >
                  <Radio.Group>
                    <Radio value="PERCENT">百分比</Radio>
                    <Radio value="FIXED">固定金额</Radio>
                  </Radio.Group>
                </Form.Item>

                {getFieldValue('repeatAddReductionValueType') === 'FIXED' ? (
                  <Form.Item
                    label="固定金额 / 固定减额 (USDC)"
                    name="repeatAddReductionFixedAmount"
                    rules={[
                      { required: true, message: '请输入固定金额' },
                      {
                        validator: (_, value) => {
                          if (value === undefined || value === null || value === '') {
                            return Promise.resolve()
                          }
                          return Number(value) > 0
                            ? Promise.resolve()
                            : Promise.reject(new Error('固定金额必须大于 0'))
                        }
                      }
                    ]}
                  >
                    <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
                  </Form.Item>
                ) : (
                  <Form.Item
                    label="百分比 (%)"
                    name="repeatAddReductionPercent"
                    rules={[
                      { required: true, message: '请输入百分比' },
                      {
                        validator: (_, value) => {
                          if (value === undefined || value === null || value === '') {
                            return Promise.resolve()
                          }
                          return Number(value) > 0 && Number(value) < 100
                            ? Promise.resolve()
                            : Promise.reject(new Error('百分比必须大于 0 且小于 100'))
                        }
                      }
                    ]}
                  >
                    <InputNumber min={0.01} max={99.99} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
                  </Form.Item>
                )}

                <div style={{ marginTop: -8, marginBottom: 16, fontSize: 12, color: '#666' }}>
                  说明：同市场=市场 + 方向；首笔不受影响；平仓后重新计数；最终仍会受最大仓位金额、最大活跃仓位数量、最小下单金额等限制。
                </div>
              </>
            ) : null}
          </Form.Item>

          <Form.Item
            label="启用同市场同方向加仓冷却"
            name="repeatAddCooldownEnabled"
            tooltip="仅 BUY 生效；同市场同方向已有活跃仓位时，距离上次成功买入不足设定秒数将跳过本次加仓。"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.repeatAddCooldownEnabled !== currentValues.repeatAddCooldownEnabled
          }>
            {({ getFieldValue }) => getFieldValue('repeatAddCooldownEnabled') ? (
              <Form.Item
                label="加仓冷却秒数"
                name="repeatAddCooldownSeconds"
                rules={[{ required: true, message: '请输入冷却秒数' }]}
              >
                <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} placeholder="默认 60 秒" />
              </Form.Item>
            ) : null}
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.smallOrderAggregationEnabled') || '启用小额订单聚合'}
            name="smallOrderAggregationEnabled"
            tooltip={t('copyTradingAdd.smallOrderAggregationEnabledTooltip') || '当 sizing 结果低于最小下单金额时，先在短窗口内聚合，再尝试执行'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.smallOrderAggregationEnabled !== currentValues.smallOrderAggregationEnabled
          }>
            {({ getFieldValue }) => getFieldValue('smallOrderAggregationEnabled') ? (
              <Form.Item
                label={t('copyTradingAdd.smallOrderAggregationWindowSeconds') || '聚合窗口 (秒)'}
                name="smallOrderAggregationWindowSeconds"
                rules={[{ required: true, message: t('copyTradingAdd.smallOrderAggregationWindowSecondsRequired') || '请输入聚合窗口' }]}
                tooltip={t('copyTradingAdd.smallOrderAggregationWindowSecondsTooltip') || '窗口到期后会把同配置、同市场、同 outcome 的小额 BUY 一次性释放执行'}
              >
                <InputNumber
                  min={1}
                  max={3600}
                  step={1}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.smallOrderAggregationWindowSecondsPlaceholder') || '默认 300 秒'}
                />
              </Form.Item>
            ) : null}
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('copyTradingAdd.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.priceTolerancePlaceholder') || '默认 5%（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.delaySeconds') || '跟单延迟 (秒)'}
            name="delaySeconds"
            tooltip={t('copyTradingAdd.delaySecondsTooltip') || '跟单延迟时间，0 表示立即跟单'}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.delaySecondsPlaceholder') || '默认 0（立即跟单）'}
            />
          </Form.Item>

          <Divider>买单运行/暂停循环</Divider>

          <Form.Item
            label="启用买单运行/暂停循环"
            name="buyCycleEnabled"
            tooltip="仅影响买单：按“运行时长→暂停时长”循环；暂停窗口内不跟买，卖单不受影响。"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            noStyle
            shouldUpdate={(prevValues, currentValues) =>
              prevValues.buyCycleEnabled !== currentValues.buyCycleEnabled
            }
          >
            {({ getFieldValue }) => getFieldValue('buyCycleEnabled') ? (
              <>
                <Form.Item
                  label="运行时长 (分钟)"
                  name="buyCycleRunMinutes"
                  rules={[{ required: true, message: '请输入运行时长' }]}
                >
                  <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} />
                </Form.Item>

                <Form.Item
                  label="暂停时长 (分钟)"
                  name="buyCyclePauseMinutes"
                  rules={[{ required: true, message: '请输入暂停时长' }]}
                >
                  <InputNumber min={1} step={1} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              </>
            ) : null}
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.minOrderDepth') || '最小订单深度 (USDC)'}
            name="minOrderDepth"
            tooltip={t('copyTradingAdd.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('copyTradingAdd.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('copyTradingAdd.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.minPricePlaceholder') || '最低价（可选）'}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              <span style={{ display: 'inline-block', width: '20px', textAlign: 'center', lineHeight: '32px' }}>-</span>
              <Form.Item name="maxPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.maxPricePlaceholder') || '最高价（可选）'}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            </Input.Group>
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.positionLimitFilter') || '最大仓位限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.maxPositionValue') || '最大仓位金额 (USDC)'}
            name="maxPositionValue"
            tooltip={t('copyTradingAdd.maxPositionValueTooltip') || '限制单个市场同方向的最大持仓成本。如果该方向的当前持仓成本 + 跟单金额超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionValuePlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>

          <Form.Item
            label={t('copyTradingAdd.maxPositionCount') || '最大活跃仓位数量'}
            name="maxPositionCount"
            tooltip={t('copyTradingAdd.maxPositionCountTooltip') || '限制同一跟单配置下可同时持有的活跃仓位数。按市场 + outcome 计数；已有仓位可继续加仓，只有新开仓位时才受此限制。'}
          >
            <InputNumber
              min={1}
              step={1}
              precision={0}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionCountPlaceholder') || '例如：6（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.keywordFilter') || '关键字过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.keywordFilterMode') || '过滤模式'}
            name="keywordFilterMode"
            tooltip={t('copyTradingAdd.keywordFilterModeTooltip') || '选择关键字过滤模式。白名单：只跟单包含关键字的市场；黑名单：不跟单包含关键字的市场；不启用：不进行关键字过滤'}
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) => 
            prevValues.keywordFilterMode !== currentValues.keywordFilterMode
          }>
            {({ getFieldValue }) => {
              const filterMode = getFieldValue('keywordFilterMode')
              if (filterMode !== 'WHITELIST' && filterMode !== 'BLACKLIST') {
                return null
              }
              
              return (
                <>
                  <Form.Item label={t('copyTradingAdd.keywords') || '关键字'}>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        ref={keywordInputRef}
                        placeholder={t('copyTradingAdd.keywordPlaceholder') || '输入关键字，按回车添加'}
                        onPressEnter={(e) => handleAddKeyword(e)}
                      />
                      <Button 
                        type="primary" 
                        onClick={() => handleAddKeyword()}
                      >
                        {t('common.add') || '添加'}
                      </Button>
                    </Space.Compact>
                    
                    {keywords.length > 0 && (
                      <div style={{ marginTop: 8 }}>
                        <Space wrap>
                          {keywords.map((keyword, index) => (
                            <Tag
                              key={index}
                              closable
                              onClose={() => handleRemoveKeyword(index)}
                              color={filterMode === 'WHITELIST' ? 'green' : 'red'}
                            >
                              {keyword}
                            </Tag>
                          ))}
                        </Space>
                      </div>
                    )}
                    
                    <div style={{ marginTop: 8, fontSize: 12, color: '#999' }}>
                      {filterMode === 'WHITELIST' 
                        ? (t('copyTradingAdd.whitelistTooltip') || '💡 白名单模式：只跟单包含上述任意关键字的市场标题')
                        : (t('copyTradingAdd.blacklistTooltip') || '💡 黑名单模式：不跟单包含上述任意关键字的市场标题')
                      }
                    </div>
                  </Form.Item>
                </>
              )
            }}
          </Form.Item>
          
          {/* 市场截止时间限制 */}
          <Divider>{t('copyTradingAdd.marketEndDateFilter') || '市场截止时间限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.maxMarketEndDate') || '最大市场截止时间'}
            tooltip={t('copyTradingAdd.maxMarketEndDateTooltip') || '仅跟单截止时间小于设定时间的订单。例如：24 小时表示只跟单距离结算还剩24小时以内的市场'}
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <InputNumber
                min={0}
                max={9999}
                step={1}
                precision={0}
                value={maxMarketEndDateValue}
                onChange={(value) => {
                  // 允许设置为 null 或 undefined（清空）
                  if (value === null || value === undefined) {
                    setMaxMarketEndDateValue(undefined)
                  } else {
                    const num = Math.floor(value)
                    // 如果值为 0，也设置为 undefined（表示清空）
                    setMaxMarketEndDateValue(num > 0 ? num : undefined)
                  }
                }}
                onBlur={(e) => {
                  // 失去焦点时，如果值为 0 或空，设置为 undefined
                  const input = e.target as HTMLInputElement
                  const value = input.value
                  if (!value || value === '0') {
                    setMaxMarketEndDateValue(undefined)
                  }
                }}
                style={{ width: '60%' }}
                placeholder={t('copyTradingAdd.maxMarketEndDatePlaceholder') || '输入时间值（可选）'}
                parser={(value) => {
                  if (!value) return 0
                  const num = parseInt(value.replace(/\D/g, ''), 10)
                  return isNaN(num) ? 0 : num
                }}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  return Math.floor(value).toString()
                }}
              />
              <Select
                value={maxMarketEndDateUnit}
                onChange={(value) => setMaxMarketEndDateUnit(value)}
                style={{ width: '40%' }}
                placeholder={t('copyTradingAdd.timeUnit') || '单位'}
              >
                <Option value="HOUR">{t('copyTradingAdd.hour') || '小时'}</Option>
                <Option value="DAY">{t('copyTradingAdd.day') || '天'}</Option>
              </Select>
            </Input.Group>
          </Form.Item>
          
          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ fontSize: 12, color: '#999' }}>
              {t('copyTradingAdd.maxMarketEndDateNote') || '💡 说明：不填写表示不启用此限制'}
            </div>
          </Form.Item>

          <Divider>市场过滤</Divider>

          <Form.Item
            label="市场分类过滤"
            name="marketCategoryMode"
            tooltip="按市场分类控制是否跟单，例如只跟 crypto 或 sports"
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.marketCategoryMode !== currentValues.marketCategoryMode
          }>
            {({ getFieldValue }) => {
              const mode = getFieldValue('marketCategoryMode')
              if (mode !== 'WHITELIST' && mode !== 'BLACKLIST') {
                return null
              }

              return (
                <Form.Item
                  label="市场分类"
                  name="marketCategories"
                  rules={[{ required: true, message: '请至少选择一个市场分类' }]}
                >
                  <Select
                    mode="multiple"
                    options={MARKET_CATEGORY_OPTIONS}
                    placeholder="选择需要过滤的市场分类"
                  />
                </Form.Item>
              )
            }}
          </Form.Item>

          <Form.Item
            label="市场周期过滤"
            name="marketIntervalMode"
            tooltip="按市场原始周期过滤，例如只跟 15m，不跟 5m 或 1h"
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.marketIntervalMode !== currentValues.marketIntervalMode
          }>
            {({ getFieldValue }) => {
              const mode = getFieldValue('marketIntervalMode')
              if (mode !== 'WHITELIST' && mode !== 'BLACKLIST') {
                return null
              }

              return (
                <Form.Item
                  label="市场周期"
                  name="marketIntervals"
                  rules={[{ required: true, message: '请至少选择一个市场周期' }]}
                >
                  <Select
                    mode="multiple"
                    options={MARKET_INTERVAL_OPTIONS}
                    placeholder="选择需要过滤的市场周期"
                  />
                </Form.Item>
              )
            }}
          </Form.Item>

          <Form.Item
            label="市场系列过滤"
            name="marketSeriesMode"
            tooltip="按系列 slug 前缀过滤，例如 btc-updown-15m"
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.marketSeriesMode !== currentValues.marketSeriesMode
          }>
            {({ getFieldValue }) => {
              const mode = getFieldValue('marketSeriesMode')
              if (mode !== 'WHITELIST' && mode !== 'BLACKLIST') {
                return null
              }

              return (
                <Form.Item
                  label="市场系列"
                  name="marketSeries"
                  rules={[{ required: true, message: '请至少输入一个市场系列' }]}
                >
                  <Select
                    mode="tags"
                    tokenSeparators={[',', ' ']}
                    placeholder="输入系列前缀，例如 btc-updown-15m"
                  />
                </Form.Item>
              )
            }}
          </Form.Item>

          <Form.Item
            label="币种过滤"
            name="coinFilterMode"
            tooltip="按加密货币币种过滤，例如只跟 ETH，或 ETH+BTC"
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingAdd.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingAdd.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingAdd.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.coinFilterMode !== currentValues.coinFilterMode
          }>
            {({ getFieldValue }) => {
              const mode = getFieldValue('coinFilterMode')
              if (mode !== 'WHITELIST' && mode !== 'BLACKLIST') {
                return null
              }

              return (
                <Form.Item
                  label="币种"
                  name="coinSymbols"
                  rules={[{ required: true, message: '请至少选择一个币种' }]}
                >
                  <Select
                    mode="multiple"
                    options={COIN_SYMBOL_OPTIONS}
                    placeholder="选择需要过滤的币种"
                  />
                </Form.Item>
              )
            }}
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.advancedSettings') || '高级设置'}</Divider>
          
          {/* 跟单卖出 */}
          <Form.Item
            label={t('copyTradingAdd.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('copyTradingAdd.supportSellTooltip') || '是否跟单 Leader 的卖出订单'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送失败订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFailedOrders') || '推送失败订单'}
            name="pushFailedOrders"
            tooltip={t('copyTradingAdd.pushFailedOrdersTooltip') || '开启后，失败的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送已过滤订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFilteredOrders') || '推送已过滤订单'}
            name="pushFilteredOrders"
            tooltip={t('copyTradingAdd.pushFilteredOrdersTooltip') || '开启后，被过滤的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('copyTradingAdd.create') || '创建跟单配置'}
              </Button>
              <Button onClick={onClose}>
                {t('common.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
      
      {/* 模板选择 Modal */}
      <Modal
        title={t('copyTradingAdd.selectTemplate') || '选择模板'}
        open={templateModalVisible}
        onCancel={() => setTemplateModalVisible(false)}
        footer={null}
        width={800}
      >
        <Table
          dataSource={templates}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          onRow={(record) => ({
            onClick: () => handleSelectTemplate(record),
            style: { cursor: 'pointer' }
          })}
          columns={[
            {
              title: t('copyTradingAdd.templateName') || '模板名称',
              dataIndex: 'templateName',
              key: 'templateName'
            },
            {
              title: t('copyTradingAdd.copyMode') || '跟单模式',
              key: 'copyMode',
              render: (_: any, record: CopyTradingTemplate) => (
                <div>
                  <div>{formatCopyModeSummary(record)}</div>
                  {record.multiplierMode && record.multiplierMode !== 'NONE' && (
                    <div style={{ fontSize: 12, color: '#666' }}>
                      {formatMultiplierSummary(record.multiplierMode, record.tradeMultiplier, record.tieredMultipliers)}
                    </div>
                  )}
                  {record.repeatAddReductionEnabled && (
                    <div style={{ fontSize: 12, color: '#666' }}>
                      {formatRepeatAddReductionSummary(record)}
                    </div>
                  )}
                </div>
              )
            },
            {
              title: t('copyTradingAdd.supportSell') || '跟单卖出',
              dataIndex: 'supportSell',
              key: 'supportSell',
              render: (supportSell: boolean) => supportSell ? (t('common.yes') || '是') : (t('common.no') || '否')
            }
          ]}
        />
      </Modal>
      
      {/* 导入账户 Modal */}
      <Modal
        title={t('accountImport.title') || '导入账户'}
        open={accountImportModalVisible}
        onCancel={() => {
          setAccountImportModalVisible(false)
          accountImportForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 640}
        style={{ top: isMobile ? 20 : 50 }}
        bodyStyle={{ padding: isMobile ? '16px 20px' : '24px 28px', maxHeight: 'calc(100vh - 140px)', overflow: 'auto' }}
        destroyOnClose
        maskClosable
        closable
      >
        <AccountImportForm
          form={accountImportForm}
          onSuccess={handleAccountImportSuccess}
          onCancel={() => {
            setAccountImportModalVisible(false)
            accountImportForm.resetFields()
          }}
        />
      </Modal>
      
      {/* 添加 Leader Modal */}
      <Modal
        title={t('leaderAdd.title') || '添加 Leader'}
        open={leaderAddModalVisible}
        onCancel={() => {
          setLeaderAddModalVisible(false)
          leaderAddForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 150px)', overflow: 'auto' }}
        destroyOnClose
        maskClosable
        closable
      >
        <LeaderAddForm
          form={leaderAddForm}
          onSuccess={handleLeaderAddSuccess}
          onCancel={() => {
            setLeaderAddModalVisible(false)
            leaderAddForm.resetFields()
          }}
          showCancelButton={true}
        />
      </Modal>
    </>
  )
}

export default AddModal
