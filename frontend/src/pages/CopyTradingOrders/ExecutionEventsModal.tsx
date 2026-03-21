import { useEffect, useState } from 'react'
import { Card, Divider, Modal, Select, Spin, Table, Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../../services/api'
import type {
  CopyTradingAggregationSnapshot,
  CopyTradingExecutionEvent,
  CopyTradingExecutionEventListResponse
} from '../../types'
import { formatUSDC } from '../../utils'

const { Option } = Select

interface ExecutionEventsModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
}

const ExecutionEventsModal: React.FC<ExecutionEventsModalProps> = ({
  open,
  onClose,
  copyTradingId
}) => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [events, setEvents] = useState<CopyTradingExecutionEvent[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [limit] = useState(20)
  const [stage, setStage] = useState<string | undefined>(undefined)
  const [source, setSource] = useState<string | undefined>(undefined)
  const [snapshotLoading, setSnapshotLoading] = useState(false)
  const [aggregationSnapshot, setAggregationSnapshot] = useState<CopyTradingAggregationSnapshot | null>(null)

  const stageMap: Record<string, { color: string; text: string }> = {
    MONITOR: { color: 'cyan', text: t('copyTradingOrders.executionEventStages.monitor') || '监听/解析' },
    DISCOVERY: { color: 'blue', text: t('copyTradingOrders.executionEventStages.discovery') || '信号发现' },
    FILTER: { color: 'orange', text: t('copyTradingOrders.executionEventStages.filter') || '过滤/跳过' },
    AGGREGATION: { color: 'purple', text: t('copyTradingOrders.executionEventStages.aggregation') || '聚合' },
    PRECHECK: { color: 'gold', text: t('copyTradingOrders.executionEventStages.precheck') || '执行前诊断' },
    EXECUTION: { color: 'green', text: t('copyTradingOrders.executionEventStages.execution') || '执行' }
  }

  const statusMap: Record<string, { color: string; text: string }> = {
    success: { color: 'success', text: t('copyTradingOrders.executionEventStatuses.success') || '成功' },
    info: { color: 'processing', text: t('copyTradingOrders.executionEventStatuses.info') || '进行中' },
    warning: { color: 'warning', text: t('copyTradingOrders.executionEventStatuses.warning') || '跳过' },
    error: { color: 'error', text: t('copyTradingOrders.executionEventStatuses.error') || '失败' },
    skipped: { color: 'default', text: t('copyTradingOrders.executionEventStatuses.skipped') || '跳过' }
  }

  const eventTypeMap: Record<string, { color: string; text: string }> = {
    AGGREGATION_BUFFERED: { color: 'purple', text: t('copyTradingOrders.executionEventTypes.aggregationBuffered') || '已进入聚合缓冲' },
    AGGREGATION_DUPLICATE_IGNORED: { color: 'default', text: t('copyTradingOrders.executionEventTypes.aggregationDuplicateIgnored') || '重复信号已忽略' },
    AGGREGATION_THRESHOLD_REACHED: { color: 'geekblue', text: t('copyTradingOrders.executionEventTypes.aggregationThresholdReached') || '聚合达阈值释放' },
    AGGREGATION_WINDOW_EXPIRED: { color: 'geekblue', text: t('copyTradingOrders.executionEventTypes.aggregationWindowExpired') || '聚合到期释放' },
    AGGREGATION_RELEASED: { color: 'geekblue', text: t('copyTradingOrders.executionEventTypes.aggregationReleased') || '聚合已释放执行' },
    AGGREGATION_TIMEOUT: { color: 'volcano', text: t('copyTradingOrders.executionEventTypes.aggregationTimeout') || '聚合超时' },
    AGGREGATION_TIMEOUT_TOO_SMALL: { color: 'volcano', text: t('copyTradingOrders.executionEventTypes.aggregationTimeoutTooSmall') || '聚合到期仍过小' },
    AGGREGATION_RELEASE_REJECTED: { color: 'orange', text: t('copyTradingOrders.executionEventTypes.aggregationReleaseRejected') || '聚合释放后被拒绝' },
    AGGREGATION_DISCARDED: { color: 'red', text: t('copyTradingOrders.executionEventTypes.aggregationDiscarded') || '聚合已丢弃' },
    ACTIVITY_MESSAGE_PARSE_FAILED: { color: 'red', text: 'Activity 消息解析失败' },
    ACTIVITY_MESSAGE_HANDLE_FAILED: { color: 'red', text: 'Activity 消息处理异常' },
    ACTIVITY_TRADER_ADDRESS_MISSING: { color: 'orange', text: 'Activity 缺少 trader 地址' },
    ACTIVITY_TRADE_PARSE_FAILED: { color: 'orange', text: 'Activity 交易解析失败' },
    ACTIVITY_TRADE_PROCESSING_FAILED: { color: 'red', text: 'Activity 交易处理失败' },
    ONCHAIN_RECEIPT_FETCH_FAILED: { color: 'red', text: '链上 receipt 拉取失败' },
    ONCHAIN_RECEIPT_INVALID: { color: 'red', text: '链上 receipt 无效' },
    ONCHAIN_RECEIPT_LOGS_EMPTY: { color: 'orange', text: '链上 receipt 无日志' },
    ONCHAIN_TRADE_PARSE_FAILED: { color: 'orange', text: '链上交易解析失败' },
    ONCHAIN_TRADE_PROCESSING_FAILED: { color: 'red', text: '链上交易处理失败' },
    EXECUTION_PRECHECK_REJECTED: { color: 'red', text: t('copyTradingOrders.executionEventTypes.executionPrecheckRejected') || '执行前诊断拒绝' },
    ACCOUNT_CREDENTIALS_MISSING: { color: 'red', text: t('copyTradingOrders.executionEventTypes.accountCredentialsMissing') || '账户凭证缺失' },
    ACCOUNT_DISABLED: { color: 'red', text: t('copyTradingOrders.executionEventTypes.accountDisabled') || '账户已禁用' },
    ORDER_CREATED: { color: 'green', text: t('copyTradingOrders.executionEventTypes.orderCreated') || '订单已创建' },
    ORDER_FAILED: { color: 'red', text: t('copyTradingOrders.executionEventTypes.orderFailed') || '订单失败' }
  }

  useEffect(() => {
    if (open && copyTradingId) {
      fetchExecutionEvents()
    }
  }, [open, copyTradingId, page, stage, source])

  useEffect(() => {
    if (open && copyTradingId) {
      fetchAggregationSnapshot()
    } else if (!open) {
      setAggregationSnapshot(null)
    }
  }, [open, copyTradingId])

  const fetchExecutionEvents = async () => {
    if (!copyTradingId) return
    setLoading(true)
    try {
      const response = await apiService.copyTrading.getExecutionEvents({
        copyTradingId: parseInt(copyTradingId, 10),
        stage,
        source,
        page,
        limit
      })
      if (response.data.code === 0 && response.data.data) {
        const data: CopyTradingExecutionEventListResponse = response.data.data
        setEvents(data.list || [])
        setTotal(data.total || 0)
      }
    } catch (error) {
      console.error('获取执行事件失败:', error)
    } finally {
      setLoading(false)
    }
  }

  const fetchAggregationSnapshot = async () => {
    if (!copyTradingId) return
    setSnapshotLoading(true)
    try {
      const response = await apiService.copyTrading.getAggregationSnapshot({
        copyTradingId: parseInt(copyTradingId, 10)
      })
      if (response.data.code === 0 && response.data.data) {
        setAggregationSnapshot(response.data.data)
      } else {
        setAggregationSnapshot(null)
      }
    } catch (error) {
      console.error('获取聚合快照失败:', error)
      setAggregationSnapshot(null)
    } finally {
      setSnapshotLoading(false)
    }
  }

  const renderStage = (value: string) => {
    const config = stageMap[value] || { color: 'default', text: value }
    return <Tag color={config.color}>{config.text}</Tag>
  }

  const renderStatus = (value: string) => {
    const config = statusMap[value] || { color: 'default', text: value }
    return <Tag color={config.color}>{config.text}</Tag>
  }

  const renderEventType = (value: string) => {
    const config = eventTypeMap[value]
    if (!config) {
      return <Tag>{value}</Tag>
    }
    return <Tag color={config.color}>{config.text}</Tag>
  }

  const parseDetailItems = (detailJson?: string) => {
    if (!detailJson) return []
    try {
      const parsed = JSON.parse(detailJson)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return [{ key: 'detail', value: detailJson }]
      }
      return Object.entries(parsed)
        .filter(([, value]) => value !== null && value !== undefined && value !== '')
        .slice(0, 8)
        .map(([key, value]) => ({
          key,
          value: Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)
        }))
    } catch {
      return [{ key: 'detail', value: detailJson }]
    }
  }

  const renderEventDetail = (event: CopyTradingExecutionEvent) => (
    <div>
      <div>{event.message}</div>
      <div style={{ fontSize: 12, color: '#666', marginTop: 4, display: 'flex', flexDirection: 'column', gap: 2 }}>
        {event.aggregationTradeCount ? (
          <span>{`${t('copyTradingOrders.executionEventAggregationCount') || '聚合笔数'}: ${event.aggregationTradeCount}`}</span>
        ) : null}
        {event.leaderTradeId ? (
          <span>{`${t('copyTradingOrders.executionEventLeaderTradeId') || 'Leader 交易'}: ${event.leaderTradeId}`}</span>
        ) : null}
        {event.aggregationKey ? (
          <span style={{ wordBreak: 'break-all' }}>{`${t('copyTradingOrders.executionEventAggregationKey') || '聚合键'}: ${event.aggregationKey}`}</span>
        ) : null}
        {event.source ? (
          <span>{`${t('copyTradingOrders.executionEventSource') || '来源'}: ${event.source}`}</span>
        ) : null}
        {parseDetailItems(event.detailJson).map(item => (
          <span key={`${event.id}-${item.key}`} style={{ wordBreak: 'break-all' }}>
            {`${item.key}: ${item.value}`}
          </span>
        ))}
      </div>
    </div>
  )

  const formatDate = (timestamp: number) => {
    const date = new Date(timestamp)
    return isMobile
      ? `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
      : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
  }

  const renderAggregationSnapshot = () => {
    if (snapshotLoading) {
      return (
        <Card size="small" title={t('copyTradingOrders.aggregationSnapshot') || '聚合快照'}>
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <Spin />
          </div>
        </Card>
      )
    }

    if (!aggregationSnapshot) {
      return null
    }

    const sampleGroups = aggregationSnapshot.groups.slice(0, 3)

    return (
      <Card size="small" title={t('copyTradingOrders.aggregationSnapshot') || '聚合快照'}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: sampleGroups.length > 0 ? 12 : 0 }}>
          <Tag color="purple">
            {(t('copyTradingOrders.aggregationGroupCount') || '缓冲组数')}: {aggregationSnapshot.totalGroupCount}
          </Tag>
          <Tag color="blue">
            {(t('copyTradingOrders.aggregationTradeCount') || '缓冲交易数')}: {aggregationSnapshot.totalTradeCount}
          </Tag>
          <Tag color="default">
            {(t('copyTradingOrders.aggregationDuplicateIgnoredCount') || '重复忽略数')}: {aggregationSnapshot.totalDuplicateIgnoredCount}
          </Tag>
        </div>

        {sampleGroups.length > 0 ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {sampleGroups.map(group => (
              <div
                key={group.key}
                style={{
                  border: '1px solid #f0f0f0',
                  borderRadius: 8,
                  padding: 12,
                  background: '#fafafa'
                }}
              >
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
                  <Tag color={group.side === 'SELL' ? 'red' : 'green'}>{group.side}</Tag>
                  <Tag>{`${t('copyTradingOrders.market') || '市场'}: ${group.marketId}`}</Tag>
                  <Tag>{`${t('copyTradingOrders.executionEventAggregationCount') || '聚合笔数'}: ${group.tradeCount}`}</Tag>
                </div>
                <div style={{ fontSize: 12, color: '#666', display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span>{`${t('copyTradingOrders.executionEventAggregationKey') || '聚合键'}: ${group.key}`}</span>
                  <span>{`${t('copyTradingOrders.executionEventQuantity') || '数量'}: ${formatUSDC(group.totalLeaderQuantity)}`}</span>
                  <span>{`${t('copyTradingOrders.executionEventOrderAmount') || '金额'}: ${formatUSDC(group.totalLeaderOrderAmount)}`}</span>
                  <span>{`${t('copyTradingOrders.executionEventOrderPrice') || '均价'}: ${formatUSDC(group.averageTradePrice)}`}</span>
                  <span>{`${t('copyTradingOrders.aggregationBufferedWindow') || '缓冲窗口'}: ${formatDate(group.firstBufferedAt)} ~ ${formatDate(group.lastBufferedAt)}`}</span>
                  {group.sampleLeaderTradeIds.length > 0 ? (
                    <span style={{ wordBreak: 'break-all' }}>
                      {`${t('copyTradingOrders.executionEventLeaderTradeId') || 'Leader 交易'}: ${group.sampleLeaderTradeIds.join(', ')}`}
                    </span>
                  ) : null}
                </div>
              </div>
            ))}
            {aggregationSnapshot.groups.length > sampleGroups.length ? (
              <div style={{ fontSize: 12, color: '#999' }}>
                {t('copyTradingOrders.aggregationMoreGroups', { count: aggregationSnapshot.groups.length - sampleGroups.length }) || `另有 ${aggregationSnapshot.groups.length - sampleGroups.length} 个缓冲组未展开`}
              </div>
            ) : null}
          </div>
        ) : (
          <div style={{ color: '#999' }}>
            {t('copyTradingOrders.noAggregationSnapshot') || '当前没有活跃的聚合缓冲组'}
          </div>
        )}
      </Card>
    )
  }

  const columns = [
    {
      title: t('copyTradingOrders.market') || '市场',
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      width: 180,
      ellipsis: true,
      render: (_: string, record: CopyTradingExecutionEvent) => record.marketTitle || record.marketId || '-'
    },
    {
      title: t('copyTradingOrders.executionEventStage') || '阶段',
      dataIndex: 'stage',
      key: 'stage',
      width: 120,
      render: (value: string) => renderStage(value)
    },
    {
      title: t('copyTradingOrders.executionEventSource') || '来源',
      dataIndex: 'source',
      key: 'source',
      width: 120,
      render: (value?: string) => value || '-'
    },
    {
      title: t('copyTradingOrders.status') || '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: string) => renderStatus(value)
    },
    {
      title: t('copyTradingOrders.executionEventType') || '事件',
      dataIndex: 'eventType',
      key: 'eventType',
      width: 180,
      render: (value: string) => renderEventType(value)
    },
    {
      title: t('copyTradingOrders.executionEventMessage') || '说明',
      key: 'message',
      ellipsis: true,
      render: (_: string, record: CopyTradingExecutionEvent) => renderEventDetail(record)
    },
    {
      title: t('copyTradingOrders.executionEventQuantity') || '数量',
      key: 'calculatedQuantity',
      width: 120,
      render: (_: string, record: CopyTradingExecutionEvent) => {
        const value = record.calculatedQuantity || record.orderQuantity
        return value ? formatUSDC(value) : '-'
      }
    },
    {
      title: t('copyTradingOrders.createdAt') || '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value: number) => formatDate(value)
    }
  ]

  return (
    <Modal
      title={t('copyTradingOrders.executionEvents') || '执行事件'}
      open={open}
      onCancel={onClose}
      footer={null}
      width="90%"
      style={{ top: 20 }}
      bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 100px)', overflow: 'auto' }}
    >
      <div style={{ marginBottom: 16 }}>
        {renderAggregationSnapshot()}
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <Select
            placeholder={t('copyTradingOrders.executionEventStageFilter') || '按阶段筛选'}
            allowClear
            style={{ width: isMobile ? '100%' : 220 }}
            value={stage}
            onChange={(value) => setStage(value || undefined)}
          >
            <Option value="MONITOR">{t('copyTradingOrders.executionEventStages.monitor') || '监听/解析'}</Option>
            <Option value="DISCOVERY">{t('copyTradingOrders.executionEventStages.discovery') || '信号发现'}</Option>
            <Option value="FILTER">{t('copyTradingOrders.executionEventStages.filter') || '过滤/跳过'}</Option>
            <Option value="AGGREGATION">{t('copyTradingOrders.executionEventStages.aggregation') || '聚合'}</Option>
            <Option value="PRECHECK">{t('copyTradingOrders.executionEventStages.precheck') || '执行前诊断'}</Option>
            <Option value="EXECUTION">{t('copyTradingOrders.executionEventStages.execution') || '执行'}</Option>
          </Select>
          <Select
            placeholder={t('copyTradingOrders.executionEventSourceFilter') || '按来源筛选'}
            allowClear
            style={{ width: isMobile ? '100%' : 220 }}
            value={source}
            onChange={(value) => setSource(value || undefined)}
          >
            <Option value="activity-ws">activity-ws</Option>
            <Option value="onchain-ws">onchain-ws</Option>
            <Option value="aggregated">aggregated</Option>
          </Select>
        </div>
      </div>

      {isMobile ? (
        <div>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px' }}>
              <Spin size="large" />
            </div>
          ) : events.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
              {t('copyTradingOrders.noExecutionEvents') || '暂无执行事件'}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {events.map((event) => (
                <Card
                  key={event.id}
                  style={{
                    borderRadius: '12px',
                    boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                    border: '1px solid #e8e8e8'
                  }}
                  bodyStyle={{ padding: '16px' }}
                >
                  <div style={{ marginBottom: '12px' }}>
                    <div style={{ fontSize: '16px', fontWeight: 'bold', marginBottom: '8px', color: '#1890ff' }}>
                      {event.marketTitle || event.marketId || (t('copyTradingOrders.unknownMarket') || '未知市场')}
                    </div>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                      {renderStage(event.stage)}
                      {renderStatus(event.status)}
                      {renderEventType(event.eventType)}
                    </div>
                  </div>

                  <Divider style={{ margin: '12px 0' }} />

                  <div style={{ marginBottom: '12px', fontSize: '13px', color: '#333' }}>
                    {renderEventDetail(event)}
                  </div>

                  {(event.calculatedQuantity || event.orderQuantity || event.orderPrice) && (
                    <div style={{ marginBottom: '12px', fontSize: '12px', color: '#666' }}>
                      {t('copyTradingOrders.calculatedQuantity') || '计算数量'}: {event.calculatedQuantity ? formatUSDC(event.calculatedQuantity) : '-'}
                      {' | '}
                      {t('copyTradingOrders.executionEventOrderQuantity') || '下单数量'}: {event.orderQuantity ? formatUSDC(event.orderQuantity) : '-'}
                      {' | '}
                      {t('copyTradingOrders.executionEventOrderPrice') || '下单价格'}: {event.orderPrice ? formatUSDC(event.orderPrice) : '-'}
                    </div>
                  )}

                  <div style={{ fontSize: '12px', color: '#999' }}>
                    {t('copyTradingOrders.createdAt') || '时间'}: {formatDate(event.createdAt)}
                  </div>
                </Card>
              ))}
            </div>
          )}
        </div>
      ) : (
        <Table
          columns={columns}
          dataSource={events}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page,
            pageSize: limit,
            total,
            showSizeChanger: false,
            showTotal: (currentTotal) => `${t('common.total') || '共'} ${currentTotal} ${t('common.items') || '条'}`,
            onChange: (newPage) => setPage(newPage)
          }}
        />
      )}
    </Modal>
  )
}

export default ExecutionEventsModal
