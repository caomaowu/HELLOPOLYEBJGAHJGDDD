import { useEffect, useState } from 'react'
import { Card, Divider, Modal, Select, Spin, Table, Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../../services/api'
import type { CopyTradingExecutionEvent, CopyTradingExecutionEventListResponse } from '../../types'
import { formatUSDC } from '../../utils'

const { Option } = Select

interface ExecutionEventsModalProps {
  open: boolean
  onClose: () => void
  copyTradingId: string
}

const stageMap: Record<string, { color: string; text: string }> = {
  MONITOR: { color: 'cyan', text: '监听/解析' },
  DISCOVERY: { color: 'blue', text: '信号发现' },
  FILTER: { color: 'orange', text: '过滤/跳过' },
  AGGREGATION: { color: 'purple', text: '聚合' },
  PRECHECK: { color: 'gold', text: '执行前诊断' },
  EXECUTION: { color: 'green', text: '执行' }
}

const statusMap: Record<string, { color: string; text: string }> = {
  success: { color: 'success', text: '成功' },
  info: { color: 'processing', text: '进行中' },
  warning: { color: 'warning', text: '跳过' },
  error: { color: 'error', text: '失败' },
  skipped: { color: 'default', text: '跳过' }
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

  useEffect(() => {
    if (open && copyTradingId) {
      fetchExecutionEvents()
    }
  }, [open, copyTradingId, page, stage])

  const fetchExecutionEvents = async () => {
    if (!copyTradingId) return
    setLoading(true)
    try {
      const response = await apiService.copyTrading.getExecutionEvents({
        copyTradingId: parseInt(copyTradingId, 10),
        stage,
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

  const renderStage = (value: string) => {
    const config = stageMap[value] || { color: 'default', text: value }
    return <Tag color={config.color}>{config.text}</Tag>
  }

  const renderStatus = (value: string) => {
    const config = statusMap[value] || { color: 'default', text: value }
    return <Tag color={config.color}>{config.text}</Tag>
  }

  const formatDate = (timestamp: number) => {
    const date = new Date(timestamp)
    return isMobile
      ? `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
      : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
  }

  const columns = [
    {
      title: '市场',
      dataIndex: 'marketTitle',
      key: 'marketTitle',
      width: 180,
      ellipsis: true,
      render: (_: string, record: CopyTradingExecutionEvent) => record.marketTitle || record.marketId || '-'
    },
    {
      title: '阶段',
      dataIndex: 'stage',
      key: 'stage',
      width: 120,
      render: (value: string) => renderStage(value)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: string) => renderStatus(value)
    },
    {
      title: '事件',
      dataIndex: 'eventType',
      key: 'eventType',
      width: 180
    },
    {
      title: '说明',
      dataIndex: 'message',
      key: 'message',
      ellipsis: true
    },
    {
      title: '数量',
      dataIndex: 'calculatedQuantity',
      key: 'calculatedQuantity',
      width: 120,
      render: (value?: string) => value ? formatUSDC(value) : '-'
    },
    {
      title: '时间',
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
        <Select
          placeholder="按阶段筛选"
          allowClear
          style={{ width: isMobile ? '100%' : 220 }}
          value={stage}
          onChange={(value) => setStage(value || undefined)}
        >
          <Option value="MONITOR">监听/解析</Option>
          <Option value="DISCOVERY">信号发现</Option>
          <Option value="FILTER">过滤/跳过</Option>
          <Option value="AGGREGATION">聚合</Option>
          <Option value="PRECHECK">执行前诊断</Option>
          <Option value="EXECUTION">执行</Option>
        </Select>
      </div>

      {isMobile ? (
        <div>
          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px' }}>
              <Spin size="large" />
            </div>
          ) : events.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
              暂无执行事件
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
                      {event.marketTitle || event.marketId || '未知市场'}
                    </div>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center' }}>
                      {renderStage(event.stage)}
                      {renderStatus(event.status)}
                      <Tag>{event.eventType}</Tag>
                    </div>
                  </div>

                  <Divider style={{ margin: '12px 0' }} />

                  <div style={{ marginBottom: '12px', fontSize: '13px', color: '#333' }}>
                    {event.message}
                  </div>

                  {(event.calculatedQuantity || event.orderQuantity || event.orderPrice) && (
                    <div style={{ marginBottom: '12px', fontSize: '12px', color: '#666' }}>
                      计算数量: {event.calculatedQuantity ? formatUSDC(event.calculatedQuantity) : '-'}
                      {' | '}
                      下单数量: {event.orderQuantity ? formatUSDC(event.orderQuantity) : '-'}
                      {' | '}
                      下单价格: {event.orderPrice ? formatUSDC(event.orderPrice) : '-'}
                    </div>
                  )}

                  {event.aggregationTradeCount ? (
                    <div style={{ marginBottom: '12px', fontSize: '12px', color: '#666' }}>
                      聚合笔数: {event.aggregationTradeCount}
                    </div>
                  ) : null}

                  <div style={{ fontSize: '12px', color: '#999' }}>
                    时间: {formatDate(event.createdAt)}
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
