import { useEffect, useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Col,
  Empty,
  Row,
  Space,
  Spin,
  Statistic,
  Tag,
  Typography
} from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { apiService } from '../services/api'

const { Title, Text } = Typography

interface ApiHealthItem {
  name: string
  url: string
  status: string
  message: string
  responseTime?: number
  suggestion?: string
}

interface StartupHealthCheck {
  code: string
  title: string
  status: string
  message: string
  detail?: string
  suggestion?: string
}

interface StartupHealthSummary {
  status: string
  message: string
  checkedAt: number
  totalChecks: number
  successCount: number
  warningCount: number
  errorCount: number
  enabledCopyTradingCount: number
  unhealthyCopyTradingCount: number
  unhealthyAccountCount: number
  actionItems: string[]
}

interface StartupHealthAccount {
  accountId: number
  accountName: string
  enabledCopyTradingCount: number
  executionReady: boolean
  errorCount: number
  warningCount: number
  failedChecks: StartupHealthCheck[]
  checkedAt: number
}

interface StartupHealthResponse {
  apis: ApiHealthItem[]
  summary?: StartupHealthSummary
  unhealthyAccounts?: StartupHealthAccount[]
}

const ApiHealthStatus: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [apiHealthStatus, setApiHealthStatus] = useState<ApiHealthItem[]>([])
  const [summary, setSummary] = useState<StartupHealthSummary | null>(null)
  const [unhealthyAccounts, setUnhealthyAccounts] = useState<StartupHealthAccount[]>([])
  const [checkingApiHealth, setCheckingApiHealth] = useState(false)

  useEffect(() => {
    checkApiHealth()
  }, [])

  const checkApiHealth = async () => {
    setCheckingApiHealth(true)
    try {
      const response = await apiService.proxyConfig.checkApiHealth()
      const payload = response.data.data as StartupHealthResponse | null
      if (response.data.code === 0 && payload) {
        setApiHealthStatus(payload.apis || [])
        setSummary(payload.summary || null)
        setUnhealthyAccounts(payload.unhealthyAccounts || [])
      }
    } finally {
      setCheckingApiHealth(false)
    }
  }

  const getStatusColor = (status: string) => {
    if (status === 'success') {
      return '#52c41a'
    }
    if (status === 'warning') {
      return '#faad14'
    }
    if (status === 'skipped') {
      return '#999'
    }
    return '#ff4d4f'
  }

  const getBadgeStatus = (status: string): 'success' | 'processing' | 'default' | 'error' | 'warning' => {
    if (status === 'success') {
      return 'success'
    }
    if (status === 'warning') {
      return 'warning'
    }
    if (status === 'skipped') {
      return 'default'
    }
    return 'error'
  }

  const getStatusText = (status: string) => {
    if (status === 'success') {
      return t('apiHealthStatus.normal') || '正常'
    }
    if (status === 'warning') {
      return t('apiHealthStatus.warning') || '告警'
    }
    if (status === 'skipped') {
      return t('apiHealthStatus.notConfigured') || '未配置'
    }
    return t('apiHealthStatus.abnormal') || '异常'
  }

  const getAlertType = (status?: string): 'success' | 'info' | 'warning' | 'error' => {
    if (status === 'success') {
      return 'success'
    }
    if (status === 'warning') {
      return 'warning'
    }
    if (status === 'error') {
      return 'error'
    }
    return 'info'
  }

  const renderApiCard = (item: ApiHealthItem, index: number) => (
    <Col
      key={`${item.name}-${index}`}
      xs={24}
      sm={12}
      md={12}
      lg={8}
      xl={6}
    >
      <Card
        size="small"
        style={{
          borderLeft: `4px solid ${getStatusColor(item.status)}`,
          height: '100%'
        }}
        bodyStyle={{ padding: isMobile ? '12px' : '16px' }}
      >
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <div
            style={{
              display: 'flex',
              alignItems: isMobile ? 'flex-start' : 'center',
              justifyContent: 'space-between',
              flexDirection: isMobile ? 'column' : 'row',
              gap: '8px'
            }}
          >
            <Text strong style={{ fontSize: '14px' }}>
              {item.name}
            </Text>
            <Badge status={getBadgeStatus(item.status)} text={getStatusText(item.status)} />
          </div>

          <Text type="secondary" style={{ fontSize: '12px', wordBreak: 'break-all' }}>
            {item.url}
          </Text>

          <Text type={item.status === 'error' ? 'danger' : item.status === 'warning' ? 'warning' : 'secondary'}>
            {item.message}
          </Text>

          {item.suggestion && (item.status === 'error' || item.status === 'warning') && (
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {t('apiHealthStatus.fixSuggestion') || '修复建议'}: {item.suggestion}
            </Text>
          )}

          {item.responseTime !== undefined && item.responseTime !== null && (
            <Text type="secondary" style={{ fontSize: '12px' }}>
              {t('apiHealthStatus.responseTime') || '响应时间'}: <Text strong>{item.responseTime}ms</Text>
            </Text>
          )}
        </Space>
      </Card>
    </Col>
  )

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>
          {t('apiHealthStatus.startupTitle') || '启动前健康检查'}
        </Title>
        <Text type="secondary">
          {t('apiHealthStatus.startupDescription') || '统一检查外部依赖、监听链路与启用中的跟单账户执行前状态。'}
        </Text>
      </div>

      <Card
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={checkApiHealth}
            loading={checkingApiHealth}
            size="small"
          >
            {t('common.refresh') || '刷新'}
          </Button>
        }
      >
        <Spin spinning={checkingApiHealth}>
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            {summary && (
              <>
                <Alert
                  type={getAlertType(summary.status)}
                  showIcon
                  message={summary.message}
                  description={
                    <Space direction="vertical" size="small">
                      <Text type="secondary">
                        {(t('apiHealthStatus.lastCheck') || '最后检查')}: {new Date(summary.checkedAt).toLocaleString('zh-CN')}
                      </Text>
                      {summary.actionItems.length > 0 && (
                        <div>
                          <Text strong>{t('apiHealthStatus.topActions') || '优先处理'}:</Text>
                          <div style={{ marginTop: 8 }}>
                            {summary.actionItems.map((item, index) => (
                              <Tag key={`${item}-${index}`} color="orange" style={{ marginBottom: 8 }}>
                                {item}
                              </Tag>
                            ))}
                          </div>
                        </div>
                      )}
                    </Space>
                  }
                />

                <Row gutter={[16, 16]}>
                  <Col xs={24} sm={12} lg={6}>
                    <Card size="small">
                      <Statistic title={t('apiHealthStatus.totalChecks') || '检查项总数'} value={summary.totalChecks} />
                    </Card>
                  </Col>
                  <Col xs={24} sm={12} lg={6}>
                    <Card size="small">
                      <Statistic title={t('apiHealthStatus.errorCount') || '阻断项'} value={summary.errorCount} valueStyle={{ color: '#ff4d4f' }} />
                    </Card>
                  </Col>
                  <Col xs={24} sm={12} lg={6}>
                    <Card size="small">
                      <Statistic title={t('apiHealthStatus.warningCount') || '告警项'} value={summary.warningCount} valueStyle={{ color: '#faad14' }} />
                    </Card>
                  </Col>
                  <Col xs={24} sm={12} lg={6}>
                    <Card size="small">
                      <Statistic
                        title={t('apiHealthStatus.enabledCopyTradingCount') || '启用跟单配置'}
                        value={`${summary.enabledCopyTradingCount}/${summary.unhealthyCopyTradingCount}`}
                        suffix={t('apiHealthStatus.problemSuffix') || '异常'}
                      />
                    </Card>
                  </Col>
                </Row>
              </>
            )}

            <Card
              size="small"
              title={t('apiHealthStatus.accountSectionTitle') || '异常账户'}
              extra={
                summary ? (
                  <Text type="secondary">
                    {(t('apiHealthStatus.accountSectionCount') || '{{count}} 个账户待处理').replace('{{count}}', String(summary.unhealthyAccountCount))}
                  </Text>
                ) : null
              }
            >
              {unhealthyAccounts.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={t('apiHealthStatus.noAccountIssues') || '当前没有异常账户'}
                />
              ) : (
                <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                  {unhealthyAccounts.map((account) => (
                    <Card key={account.accountId} size="small" bodyStyle={{ padding: '16px' }}>
                      <Space direction="vertical" size="small" style={{ width: '100%' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
                          <Space wrap>
                            <Text strong>{account.accountName}</Text>
                            <Tag color="red">{`ID ${account.accountId}`}</Tag>
                            <Tag color="orange">
                              {(t('apiHealthStatus.copyTradingCount') || '{{count}} 个启用配置').replace('{{count}}', String(account.enabledCopyTradingCount))}
                            </Tag>
                          </Space>
                          <Text type="secondary">
                            {new Date(account.checkedAt).toLocaleString('zh-CN')}
                          </Text>
                        </div>

                        <Space wrap>
                          <Tag color="red">
                            {(t('apiHealthStatus.errorCountSimple') || '错误 {{count}}').replace('{{count}}', String(account.errorCount))}
                          </Tag>
                          <Tag color="gold">
                            {(t('apiHealthStatus.warningCountSimple') || '告警 {{count}}').replace('{{count}}', String(account.warningCount))}
                          </Tag>
                        </Space>

                        <Space direction="vertical" size="small" style={{ width: '100%' }}>
                          {account.failedChecks.map((check) => (
                            <Card key={`${account.accountId}-${check.code}`} size="small" type="inner">
                              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                                <Space wrap>
                                  <Text strong>{check.title}</Text>
                                  <Tag color={check.status === 'error' ? 'red' : 'gold'}>{getStatusText(check.status)}</Tag>
                                </Space>
                                <Text>{check.message}</Text>
                                {check.detail && (
                                  <Text type="secondary">{check.detail}</Text>
                                )}
                                {check.suggestion && (
                                  <Text type="secondary">
                                    {t('apiHealthStatus.fixSuggestion') || '修复建议'}: {check.suggestion}
                                  </Text>
                                )}
                              </Space>
                            </Card>
                          ))}
                        </Space>
                      </Space>
                    </Card>
                  ))}
                </Space>
              )}
            </Card>

            <div>
              <Title level={4} style={{ marginBottom: '12px' }}>
                {t('apiHealthStatus.apiSectionTitle') || '基础服务与监听链路'}
              </Title>
              <Row gutter={[16, 16]}>
                {apiHealthStatus.map(renderApiCard)}
              </Row>
            </div>
          </Space>
        </Spin>
      </Card>
    </div>
  )
}

export default ApiHealthStatus
