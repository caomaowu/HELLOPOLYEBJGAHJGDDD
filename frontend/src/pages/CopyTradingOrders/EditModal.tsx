import React, { useEffect, useRef, useState } from 'react'
import { Modal, Form, Button, message, Radio, InputNumber, Divider, Spin, Select, Input, Space, Switch, Tag, InputRef, Card, Row, Col, Statistic } from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import { apiService } from '../../services/api'
import type {
  CopyTrading,
  CopyTradingUpdateRequest,
  MarketCategoryOption,
} from '../../types'
import { useTranslation } from 'react-i18next'
import { formatUSDC, validateAndNormalizeMultiplierTiers } from '../../utils'
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

interface EditModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
  onSuccess?: () => void
}

const EditModal: React.FC<EditModalProps> = ({
  open,
  onClose,
  copyTradingId,
  onSuccess
}) => {
  const { t } = useTranslation()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)
  const [copyTrading, setCopyTrading] = useState<CopyTrading | null>(null)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED' | 'ADAPTIVE'>('RATIO')
  const [multiplierMode, setMultiplierMode] = useState<'NONE' | 'SINGLE' | 'TIERED'>('NONE')
  const [originalEnabled, setOriginalEnabled] = useState(true)
  const [keywords, setKeywords] = useState<string[]>([])
  const keywordInputRef = useRef<InputRef>(null)
  const [maxMarketEndDateValue, setMaxMarketEndDateValue] = useState<number | undefined>()
  const [maxMarketEndDateUnit, setMaxMarketEndDateUnit] = useState<'HOUR' | 'DAY'>('HOUR')
  const [leaderAssetInfo, setLeaderAssetInfo] = useState<{ total: string; available: string; position: string } | null>(null)
  const [loadingAssetInfo, setLoadingAssetInfo] = useState(false)

  useEffect(() => {
    if (open && copyTradingId) {
      fetchCopyTrading(parseInt(copyTradingId, 10))
    }
  }, [open, copyTradingId])

  const fetchCopyTrading = async (targetId: number) => {
    setFetching(true)
    try {
      const response = await apiService.copyTrading.list({})
      if (response.data.code === 0 && response.data.data) {
        const found = response.data.data.list.find((ct: CopyTrading) => ct.id === targetId)
        if (!found) {
          message.error(t('copyTradingEdit.fetchFailed') || '跟单配置不存在')
          onClose()
          return
        }

        setCopyTrading(found)
        setCopyMode(found.copyMode as 'RATIO' | 'FIXED' | 'ADAPTIVE')
        setMultiplierMode((found.multiplierMode || 'NONE') as 'NONE' | 'SINGLE' | 'TIERED')
        setOriginalEnabled(found.enabled)
        setKeywords(found.keywords || [])

        if (found.maxMarketEndDate) {
          const hours = found.maxMarketEndDate / (60 * 60 * 1000)
          if (hours >= 24 && Number.isInteger(hours / 24)) {
            setMaxMarketEndDateUnit('DAY')
            setMaxMarketEndDateValue(hours / 24)
          } else {
            setMaxMarketEndDateUnit('HOUR')
            setMaxMarketEndDateValue(hours)
          }
        } else {
          setMaxMarketEndDateValue(undefined)
          setMaxMarketEndDateUnit('HOUR')
        }

        form.setFieldsValue({
          accountId: found.accountId,
          leaderId: found.leaderId,
          copyMode: found.copyMode,
          copyRatio: found.copyRatio ? parseFloat(found.copyRatio) * 100 : 100,
          fixedAmount: found.fixedAmount ? parseFloat(found.fixedAmount) : undefined,
          adaptiveMinRatio: found.adaptiveMinRatio ? parseFloat(found.adaptiveMinRatio) * 100 : undefined,
          adaptiveMaxRatio: found.adaptiveMaxRatio ? parseFloat(found.adaptiveMaxRatio) * 100 : undefined,
          adaptiveThreshold: found.adaptiveThreshold ? parseFloat(found.adaptiveThreshold) : undefined,
          multiplierMode: found.multiplierMode || 'NONE',
          tradeMultiplier: found.tradeMultiplier ? parseFloat(found.tradeMultiplier) : undefined,
          tieredMultipliers: found.tieredMultipliers?.map((tier: any) => ({
            min: parseFloat(tier.min),
            max: tier.max != null ? parseFloat(tier.max) : undefined,
            multiplier: parseFloat(tier.multiplier)
          })),
          maxOrderSize: found.maxOrderSize ? parseFloat(found.maxOrderSize) : undefined,
          minOrderSize: found.minOrderSize ? parseFloat(found.minOrderSize) : undefined,
          maxDailyLoss: found.maxDailyLoss ? parseFloat(found.maxDailyLoss) : undefined,
          maxDailyOrders: found.maxDailyOrders,
          maxDailyVolume: found.maxDailyVolume ? parseFloat(found.maxDailyVolume) : undefined,
          smallOrderAggregationEnabled: found.smallOrderAggregationEnabled ?? false,
          smallOrderAggregationWindowSeconds: found.smallOrderAggregationWindowSeconds ?? 300,
          priceTolerance: found.priceTolerance ? parseFloat(found.priceTolerance) : undefined,
          delaySeconds: found.delaySeconds,
          pollIntervalSeconds: found.pollIntervalSeconds,
          useWebSocket: found.useWebSocket,
          websocketReconnectInterval: found.websocketReconnectInterval,
          websocketMaxRetries: found.websocketMaxRetries,
          supportSell: found.supportSell,
          minOrderDepth: found.minOrderDepth ? parseFloat(found.minOrderDepth) : undefined,
          maxSpread: found.maxSpread ? parseFloat(found.maxSpread) : undefined,
          minPrice: found.minPrice ? parseFloat(found.minPrice) : undefined,
          maxPrice: found.maxPrice ? parseFloat(found.maxPrice) : undefined,
          maxPositionValue: found.maxPositionValue ? parseFloat(found.maxPositionValue) : undefined,
          keywordFilterMode: found.keywordFilterMode || 'DISABLED',
          marketCategoryMode: found.marketCategoryMode || 'DISABLED',
          marketCategories: found.marketCategories,
          marketIntervalMode: found.marketIntervalMode || 'DISABLED',
          marketIntervals: found.marketIntervals,
          marketSeriesMode: found.marketSeriesMode || 'DISABLED',
          marketSeries: found.marketSeries,
          configName: found.configName || '',
          pushFailedOrders: found.pushFailedOrders ?? false,
          pushFilteredOrders: found.pushFilteredOrders ?? false
        })

        fetchLeaderAssetInfo(found.leaderId)
      } else {
        message.error(response.data.msg || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
        onClose()
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.fetchFailed') || '获取跟单配置失败')
      onClose()
    } finally {
      setFetching(false)
    }
  }

  const fetchLeaderAssetInfo = async (leaderId: number) => {
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
      }
    } catch (error) {
      console.error('获取 Leader 资产失败:', error)
    } finally {
      setLoadingAssetInfo(false)
    }
  }

  const handleAddKeyword = (e?: React.KeyboardEvent<HTMLInputElement>) => {
    const inputValue = e
      ? (e.target as HTMLInputElement).value.trim()
      : keywordInputRef.current?.input?.value?.trim() || ''

    if (!inputValue) {
      return
    }
    if (keywords.includes(inputValue)) {
      message.warning(t('copyTradingEdit.keywordExists') || t('copyTradingAdd.keywordExists') || '关键字已存在')
      return
    }
    setKeywords([...keywords, inputValue])
    if (keywordInputRef.current?.input) {
      keywordInputRef.current.input.value = ''
    }
  }

  const handleRemoveKeyword = (index: number) => {
    setKeywords(keywords.filter((_, i) => i !== index))
  }

  const handleSubmit = async (values: any) => {
    if (!copyTradingId) {
      message.error('配置 ID 不存在')
      return
    }

    if (values.copyMode === 'FIXED' && (!values.fixedAmount || Number(values.fixedAmount) < 1)) {
      message.error(t('copyTradingEdit.fixedAmountMin') || '固定金额必须 >= 1')
      return
    }

    if (values.copyMode === 'ADAPTIVE') {
      if (!values.copyRatio || !values.adaptiveMinRatio || !values.adaptiveMaxRatio || !values.adaptiveThreshold) {
        message.error(t('copyTradingEdit.adaptiveRequired') || '请完整填写自适应策略参数')
        return
      }
    }

    if (values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingEdit.minOrderSizeMin') || '最小金额必须 >= 1')
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

    const normalizedTierResult = values.multiplierMode === 'TIERED'
      ? validateAndNormalizeMultiplierTiers(values.tieredMultipliers)
      : null
    if (normalizedTierResult && !normalizedTierResult.isValid) {
      message.error(normalizedTierResult.message || '分层 multiplier 配置不合法')
      return
    }

    let maxMarketEndDate: number | undefined
    if (maxMarketEndDateValue !== undefined && maxMarketEndDateValue !== null && maxMarketEndDateValue > 0) {
      const multiplier = maxMarketEndDateUnit === 'HOUR' ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000
      maxMarketEndDate = maxMarketEndDateValue * multiplier
    } else {
      maxMarketEndDate = -1
    }

    setLoading(true)
    try {
      const request: CopyTradingUpdateRequest = {
        copyTradingId: parseInt(copyTradingId, 10),
        enabled: originalEnabled,
        copyMode: values.copyMode,
        copyRatio: (values.copyMode === 'RATIO' || values.copyMode === 'ADAPTIVE') && values.copyRatio
          ? (values.copyRatio / 100).toString()
          : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        adaptiveMinRatio: values.copyMode === 'ADAPTIVE' && values.adaptiveMinRatio != null
          ? (values.adaptiveMinRatio / 100).toString()
          : '',
        adaptiveMaxRatio: values.copyMode === 'ADAPTIVE' && values.adaptiveMaxRatio != null
          ? (values.adaptiveMaxRatio / 100).toString()
          : '',
        adaptiveThreshold: values.copyMode === 'ADAPTIVE' && values.adaptiveThreshold != null
          ? values.adaptiveThreshold.toString()
          : '',
        multiplierMode: values.multiplierMode || 'NONE',
        tradeMultiplier: values.multiplierMode === 'SINGLE' && values.tradeMultiplier != null
          ? values.tradeMultiplier.toString()
          : '',
        tieredMultipliers: values.multiplierMode === 'TIERED'
          ? normalizedTierResult?.tiers ?? []
          : [],
        maxOrderSize: values.maxOrderSize?.toString(),
        minOrderSize: values.minOrderSize?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        maxDailyVolume: values.maxDailyVolume != null ? values.maxDailyVolume.toString() : '',
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
        supportSell: values.supportSell,
        minOrderDepth: values.minOrderDepth != null ? values.minOrderDepth.toString() : '',
        maxSpread: values.maxSpread != null ? values.maxSpread.toString() : '',
        minPrice: values.minPrice != null ? values.minPrice.toString() : '',
        maxPrice: values.maxPrice != null ? values.maxPrice.toString() : '',
        maxPositionValue: values.maxPositionValue != null ? values.maxPositionValue.toString() : '',
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
        configName: values.configName?.trim() || undefined,
        pushFailedOrders: values.pushFailedOrders,
        pushFilteredOrders: values.pushFilteredOrders,
        maxMarketEndDate
      }

      const response = await apiService.copyTrading.update(request)
      if (response.data.code === 0) {
        message.success(t('copyTradingEdit.saveSuccess') || '更新跟单配置成功')
        onClose()
        onSuccess?.()
      } else {
        message.error(response.data.msg || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingEdit.saveFailed') || '更新跟单配置失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      title={t('copyTradingEdit.title') || '编辑跟单配置'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
      destroyOnClose
    >
      {fetching ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <Spin size="large" />
        </div>
      ) : !copyTrading ? (
        <div style={{ textAlign: 'center', padding: '50px' }}>
          <p>{t('copyTradingEdit.fetchFailed') || '跟单配置不存在'}</p>
        </div>
      ) : (
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            keywordFilterMode: 'DISABLED',
            marketCategoryMode: 'DISABLED',
            marketIntervalMode: 'DISABLED',
            marketSeriesMode: 'DISABLED',
            multiplierMode: 'NONE'
          }}
        >
          <Form.Item
            label={t('copyTradingEdit.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingEdit.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingEdit.configNameRequired') || '配置名不能为空' }
            ]}
          >
            <Input maxLength={255} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.selectWallet') || '钱包'} name="accountId">
            <Select disabled>
              <Option value={copyTrading.accountId}>
                {copyTrading.accountName || `账户 ${copyTrading.accountId}`} ({copyTrading.walletAddress.slice(0, 6)}...{copyTrading.walletAddress.slice(-4)})
              </Option>
            </Select>
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.selectLeader') || 'Leader'} name="leaderId">
            <Select disabled>
              <Option value={copyTrading.leaderId}>
                {copyTrading.leaderName || `Leader ${copyTrading.leaderId}`}
              </Option>
            </Select>
          </Form.Item>

          <Card
            title={t('copyTradingAdd.leaderAssetInfo') || 'Leader 资产信息'}
            size="small"
            style={{ marginBottom: 16, backgroundColor: '#f5f5f5', border: '1px solid #d9d9d9' }}
          >
            {loadingAssetInfo ? (
              <div style={{ textAlign: 'center', padding: '24px' }}>
                <Spin />
              </div>
            ) : leaderAssetInfo ? (
              <Row gutter={16}>
                <Col span={8}>
                  <Statistic title={t('copyTradingAdd.totalAsset') || '总资产'} value={parseFloat(leaderAssetInfo.total)} formatter={(value) => formatUSDC(value?.toString() || '0')} suffix="USDC" />
                </Col>
                <Col span={8}>
                  <Statistic title={t('copyTradingAdd.availableBalance') || '可用余额'} value={parseFloat(leaderAssetInfo.available)} formatter={(value) => formatUSDC(value?.toString() || '0')} suffix="USDC" />
                </Col>
                <Col span={8}>
                  <Statistic title={t('copyTradingAdd.positionAsset') || '仓位资产'} value={parseFloat(leaderAssetInfo.position)} formatter={(value) => formatUSDC(value?.toString() || '0')} suffix="USDC" />
                </Col>
              </Row>
            ) : null}
          </Card>

          <Divider>{t('copyTradingEdit.basicConfig') || '基础配置'}</Divider>

          <Form.Item
            label={t('copyTradingEdit.copyMode') || '跟单金额模式'}
            name="copyMode"
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => setCopyMode(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingEdit.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingEdit.fixedAmountMode') || '固定金额模式'}</Radio>
              <Radio value="ADAPTIVE">{t('copyTradingEdit.adaptiveMode') || '自适应模式'}</Radio>
            </Radio.Group>
          </Form.Item>

          {(copyMode === 'RATIO' || copyMode === 'ADAPTIVE') && (
            <Form.Item
              label={copyMode === 'ADAPTIVE' ? (t('copyTradingEdit.baseCopyRatio') || '基础跟单比例') : (t('copyTradingEdit.copyRatio') || '跟单比例')}
              name="copyRatio"
            >
              <InputNumber min={0.01} max={10000} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
            </Form.Item>
          )}

          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingEdit.fixedAmount') || '固定跟单金额 (USDC)'}
              name="fixedAmount"
              rules={[{ required: true, message: t('copyTradingEdit.fixedAmountRequired') || '请输入固定跟单金额' }]}
            >
              <InputNumber min={1} step={0.0001} precision={4} style={{ width: '100%' }} />
            </Form.Item>
          )}

          {copyMode === 'ADAPTIVE' && (
            <>
              <Form.Item
                label={t('copyTradingEdit.adaptiveMinRatio') || '自适应最小比例'}
                name="adaptiveMinRatio"
                rules={[{ required: true, message: t('copyTradingEdit.adaptiveMinRatioRequired') || '请输入自适应最小比例' }]}
              >
                <InputNumber min={0.01} max={10000} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
              <Form.Item
                label={t('copyTradingEdit.adaptiveMaxRatio') || '自适应最大比例'}
                name="adaptiveMaxRatio"
                rules={[{ required: true, message: t('copyTradingEdit.adaptiveMaxRatioRequired') || '请输入自适应最大比例' }]}
              >
                <InputNumber min={0.01} max={10000} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
              <Form.Item
                label={t('copyTradingEdit.adaptiveThreshold') || '自适应阈值 (USDC)'}
                name="adaptiveThreshold"
                rules={[{ required: true, message: t('copyTradingEdit.adaptiveThresholdRequired') || '请输入自适应阈值' }]}
              >
                <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}

          <Divider>{t('copyTradingEdit.sizingEnhancement') || 'Sizing 增强'}</Divider>

          <Form.Item
            label={t('copyTradingEdit.multiplierMode') || 'Multiplier 模式'}
            name="multiplierMode"
          >
            <Radio.Group onChange={(e) => setMultiplierMode(e.target.value)}>
              <Radio value="NONE">{t('copyTradingEdit.multiplierModeNone') || '无'}</Radio>
              <Radio value="SINGLE">{t('copyTradingEdit.multiplierModeSingle') || '单一倍率'}</Radio>
              <Radio value="TIERED">{t('copyTradingEdit.multiplierModeTiered') || '分层倍率'}</Radio>
            </Radio.Group>
          </Form.Item>

          {multiplierMode === 'SINGLE' && (
            <Form.Item
              label={t('copyTradingEdit.tradeMultiplier') || '倍率'}
              name="tradeMultiplier"
              rules={[{ required: true, message: t('copyTradingEdit.tradeMultiplierRequired') || '请输入倍率' }]}
            >
              <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} addonAfter="x" />
            </Form.Item>
          )}

          {multiplierMode === 'TIERED' && (
            <Form.Item
              label={t('copyTradingEdit.tieredMultipliers') || '分层倍率'}
              required
            >
              <MultiplierTierEditor />
            </Form.Item>
          )}

          <Form.Item label={t('copyTradingEdit.maxOrderSize') || '单笔订单最大金额 (USDC)'} name="maxOrderSize">
            <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.minOrderSize') || '单笔订单最小金额 (USDC)'}
            name="minOrderSize"
            rules={[
              {
                validator: (_, value) => {
                  if (value === undefined || value === null || value === '') {
                    return Promise.resolve()
                  }
                  if (typeof value === 'number' && value < 1) {
                    return Promise.reject(new Error(t('copyTradingEdit.minOrderSizeMin') || '最小金额必须 >= 1'))
                  }
                  return Promise.resolve()
                }
              }
            ]}
          >
            <InputNumber min={1} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.maxDailyLoss') || '每日最大亏损限制 (USDC)'} name="maxDailyLoss">
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.maxDailyOrders') || '每日最大跟单订单数'} name="maxDailyOrders">
            <InputNumber min={1} step={1} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.maxDailyVolume') || '每日最大成交额 (USDC)'} name="maxDailyVolume">
            <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item
            label={t('copyTradingEdit.smallOrderAggregationEnabled') || '启用小额订单聚合'}
            name="smallOrderAggregationEnabled"
            tooltip={t('copyTradingEdit.smallOrderAggregationEnabledTooltip') || '当 sizing 结果低于最小下单金额时，先在短窗口内聚合，再尝试执行'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) =>
            prevValues.smallOrderAggregationEnabled !== currentValues.smallOrderAggregationEnabled
          }>
            {({ getFieldValue }) => getFieldValue('smallOrderAggregationEnabled') ? (
              <Form.Item
                label={t('copyTradingEdit.smallOrderAggregationWindowSeconds') || '聚合窗口 (秒)'}
                name="smallOrderAggregationWindowSeconds"
                rules={[{ required: true, message: t('copyTradingEdit.smallOrderAggregationWindowSecondsRequired') || '请输入聚合窗口' }]}
              >
                <InputNumber min={1} max={3600} step={1} style={{ width: '100%' }} />
              </Form.Item>
            ) : null}
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.priceTolerance') || '价格容忍度 (%)'} name="priceTolerance">
            <InputNumber min={0} max={100} step={0.1} precision={2} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.delaySeconds') || '跟单延迟 (秒)'} name="delaySeconds">
            <InputNumber min={0} step={1} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.minOrderDepth') || '最小订单深度 (USDC)'} name="minOrderDepth">
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.maxSpread') || '最大价差（绝对价格）'} name="maxSpread">
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Divider>{t('copyTradingEdit.priceRangeFilter') || '价格区间过滤'}</Divider>

          <Form.Item label={t('copyTradingEdit.priceRange') || '价格区间'}>
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber min={0.01} max={0.99} step={0.0001} precision={4} style={{ width: '50%' }} placeholder={t('copyTradingEdit.minPricePlaceholder') || '最低价（可选）'} />
              </Form.Item>
              <span style={{ display: 'inline-block', width: '20px', textAlign: 'center', lineHeight: '32px' }}>-</span>
              <Form.Item name="maxPrice" noStyle>
                <InputNumber min={0.01} max={0.99} step={0.0001} precision={4} style={{ width: '50%' }} placeholder={t('copyTradingEdit.maxPricePlaceholder') || '最高价（可选）'} />
              </Form.Item>
            </Input.Group>
          </Form.Item>

          <Divider>{t('copyTradingEdit.positionLimitFilter') || '最大仓位限制'}</Divider>

          <Form.Item label={t('copyTradingEdit.maxPositionValue') || '最大仓位金额 (USDC)'} name="maxPositionValue">
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Divider>{t('copyTradingEdit.keywordFilter') || '关键字过滤'}</Divider>

          <Form.Item label={t('copyTradingEdit.keywordFilterMode') || '过滤模式'} name="keywordFilterMode">
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) => prevValues.keywordFilterMode !== currentValues.keywordFilterMode}>
            {({ getFieldValue }) => {
              const filterMode = getFieldValue('keywordFilterMode')
              if (filterMode !== 'WHITELIST' && filterMode !== 'BLACKLIST') {
                return null
              }
              return (
                <Form.Item label={t('copyTradingEdit.keywords') || '关键字'}>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input ref={keywordInputRef} onPressEnter={(e) => handleAddKeyword(e)} />
                    <Button type="primary" onClick={() => handleAddKeyword()}>
                      {t('common.add') || '添加'}
                    </Button>
                  </Space.Compact>
                  {keywords.length > 0 && (
                    <div style={{ marginTop: 8 }}>
                      <Space wrap>
                        {keywords.map((keyword, index) => (
                          <Tag key={index} closable onClose={() => handleRemoveKeyword(index)} color={filterMode === 'WHITELIST' ? 'green' : 'red'}>
                            {keyword}
                          </Tag>
                        ))}
                      </Space>
                    </div>
                  )}
                </Form.Item>
              )
            }}
          </Form.Item>

          <Divider>{t('copyTradingEdit.marketEndDateFilter') || '市场截止时间限制'}</Divider>

          <Form.Item label={t('copyTradingEdit.maxMarketEndDate') || '最大市场截止时间'}>
            <Input.Group compact style={{ display: 'flex' }}>
              <InputNumber
                min={0}
                max={9999}
                step={1}
                precision={0}
                value={maxMarketEndDateValue}
                onChange={(value) => setMaxMarketEndDateValue(value && value > 0 ? Math.floor(value) : undefined)}
                style={{ width: '60%' }}
              />
              <Select value={maxMarketEndDateUnit} onChange={(value) => setMaxMarketEndDateUnit(value)} style={{ width: '40%' }}>
                <Option value="HOUR">{t('copyTradingEdit.hour') || '小时'}</Option>
                <Option value="DAY">{t('copyTradingEdit.day') || '天'}</Option>
              </Select>
            </Input.Group>
          </Form.Item>

          <Divider>市场过滤</Divider>

          <Form.Item
            label="市场分类过滤"
            name="marketCategoryMode"
            tooltip="按市场分类控制是否跟单，例如只跟 crypto 或 sports"
          >
            <Radio.Group>
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) => prevValues.marketCategoryMode !== currentValues.marketCategoryMode}>
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
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) => prevValues.marketIntervalMode !== currentValues.marketIntervalMode}>
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
              <Radio value="DISABLED">{t('copyTradingEdit.disabled') || '不启用'}</Radio>
              <Radio value="WHITELIST">{t('copyTradingEdit.whitelist') || '白名单'}</Radio>
              <Radio value="BLACKLIST">{t('copyTradingEdit.blacklist') || '黑名单'}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prevValues, currentValues) => prevValues.marketSeriesMode !== currentValues.marketSeriesMode}>
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

          <Divider>{t('copyTradingEdit.advancedSettings') || '高级设置'}</Divider>

          <Form.Item label={t('copyTradingEdit.supportSell') || '跟单卖出'} name="supportSell" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.pushFailedOrders') || '推送失败订单'} name="pushFailedOrders" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item label={t('copyTradingEdit.pushFilteredOrders') || '推送已过滤订单'} name="pushFilteredOrders" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={loading}>
                {t('copyTradingEdit.save') || '保存'}
              </Button>
              <Button onClick={onClose}>{t('common.cancel') || '取消'}</Button>
            </Space>
          </Form.Item>
        </Form>
      )}
    </Modal>
  )
}

export default EditModal
