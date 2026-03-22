import { useEffect, useState } from 'react'
import { Card, Form, Button, Switch, Input, InputNumber, message, Typography, Space, Alert, Select, Table, Tag, Popconfirm, Modal } from 'antd'
import { SaveOutlined, CheckCircleOutlined, ReloadOutlined, NotificationOutlined, KeyOutlined, LinkOutlined, PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'
import { useTranslation } from 'react-i18next'
import { isSystemUpdateEnabled } from '../config/runtime'
import type { SystemConfig, BuilderApiKeyUpdateRequest, NotificationConfig, NotificationConfigRequest, NotificationConfigUpdateRequest } from '../types'
import { TelegramConfigForm } from '../components/notifications'
import SystemUpdate from './SystemUpdate'

const { Title, Text, Paragraph } = Typography

interface ProxyConfig {
  id?: number
  type: 'HTTP' | 'HTTPS' | 'SOCKS5'
  enabled: boolean
  host?: string
  port?: number
  username?: string
  subscriptionUrl?: string
  lastSubscriptionUpdate?: number
  createdAt: number
  updatedAt: number
}

interface ProxyCheckResponse {
  success: boolean
  message: string
  responseTime?: number
  latency?: number
}

const PROXY_TYPE_OPTIONS = [
  { value: 'HTTP', label: 'HTTP' },
  { value: 'HTTPS', label: 'HTTPS' },
  { value: 'SOCKS5', label: 'SOCKS5' },
] as const

const SystemSettings: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const systemUpdateEnabled = isSystemUpdateEnabled()

  // 第一部分：消息推送设置
  const [notificationConfigs, setNotificationConfigs] = useState<NotificationConfig[]>([])
  const [notificationLoading, setNotificationLoading] = useState(false)
  const [notificationModalVisible, setNotificationModalVisible] = useState(false)
  const [editingNotificationConfig, setEditingNotificationConfig] = useState<NotificationConfig | null>(null)
  const [notificationForm] = Form.useForm()
  const [testLoading, setTestLoading] = useState(false)

  // 第二部分：Relayer配置
  const [relayerForm] = Form.useForm()
  const [autoRedeemForm] = Form.useForm()
  const [systemConfig, setSystemConfig] = useState<SystemConfig | null>(null)
  const [relayerLoading, setRelayerLoading] = useState(false)
  const [autoRedeemLoading, setAutoRedeemLoading] = useState(false)

  // 第三部分：代理设置
  const [proxyForm] = Form.useForm()
  const [proxyLoading, setProxyLoading] = useState(false)
  const [proxyChecking, setProxyChecking] = useState(false)
  const [proxyCheckResult, setProxyCheckResult] = useState<ProxyCheckResponse | null>(null)
  const [currentProxyConfig, setCurrentProxyConfig] = useState<ProxyConfig | null>(null)

  useEffect(() => {
    fetchNotificationConfigs()
    fetchSystemConfig()
    fetchProxyConfig()
  }, [])

  // ==================== 第一部分：消息推送设置 ====================
  const fetchNotificationConfigs = async () => {
    setNotificationLoading(true)
    try {
      const response = await apiService.notifications.list({ type: 'telegram' })
      if (response.data.code === 0 && response.data.data) {
        setNotificationConfigs(response.data.data)
      } else {
        message.error(response.data.msg || t('notificationSettings.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.fetchFailed'))
    } finally {
      setNotificationLoading(false)
    }
  }

  const handleNotificationCreate = () => {
    setEditingNotificationConfig(null)
    notificationForm.resetFields()
    notificationForm.setFieldsValue({
      type: 'telegram',
      enabled: true,
      config: {
        botToken: '',
        chatIds: []
      }
    })
    setNotificationModalVisible(true)
  }

  const handleNotificationEdit = (config: NotificationConfig) => {
    setEditingNotificationConfig(config)

    let botToken = ''
    let chatIds = ''

    if (config.config) {
      if ('data' in config.config && config.config.data) {
        const data = config.config.data as any
        botToken = data.botToken || ''
        if (data.chatIds) {
          if (Array.isArray(data.chatIds)) {
            chatIds = data.chatIds.join(',')
          } else if (typeof data.chatIds === 'string') {
            chatIds = data.chatIds
          }
        }
      } else {
        if ('botToken' in config.config) {
          botToken = (config.config as any).botToken || ''
        }
        if ('chatIds' in config.config) {
          const ids = (config.config as any).chatIds
          if (Array.isArray(ids)) {
            chatIds = ids.join(',')
          } else if (typeof ids === 'string') {
            chatIds = ids
          }
        }
      }
    }

    notificationForm.setFieldsValue({
      type: config.type,
      name: config.name,
      enabled: config.enabled,
      config: {
        botToken: botToken,
        chatIds: chatIds
      }
    })
    setNotificationModalVisible(true)
  }

  const handleNotificationDelete = async (id: number) => {
    try {
      const response = await apiService.notifications.delete({ id })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.deleteSuccess'))
        fetchNotificationConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.deleteFailed'))
    }
  }

  const handleNotificationUpdateEnabled = async (id: number, enabled: boolean) => {
    try {
      const response = await apiService.notifications.updateEnabled({ id, enabled })
      if (response.data.code === 0) {
        message.success(enabled ? t('notificationSettings.enableSuccess') : t('notificationSettings.disableSuccess'))
        fetchNotificationConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateStatusFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.updateStatusFailed'))
    }
  }

  const handleNotificationTest = async () => {
    setTestLoading(true)
    try {
      const response = await apiService.notifications.test({ message: '这是一条测试消息' })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.testFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.testFailed'))
    } finally {
      setTestLoading(false)
    }
  }

  const handleNotificationSubmit = async () => {
    try {
      const values = await notificationForm.validateFields()

      const chatIds = typeof values.config.chatIds === 'string'
        ? values.config.chatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
        : values.config.chatIds || []

      const configData: NotificationConfigRequest | NotificationConfigUpdateRequest = {
        type: values.type,
        name: values.name,
        enabled: values.enabled,
        config: {
          botToken: values.config.botToken,
          chatIds: chatIds
        }
      }

      if (editingNotificationConfig?.id) {
        const updateData = {
          ...configData,
          id: editingNotificationConfig.id
        } as NotificationConfigUpdateRequest

        const response = await apiService.notifications.update(updateData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.updateSuccess'))
          setNotificationModalVisible(false)
          fetchNotificationConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.updateFailed'))
        }
      } else {
        const response = await apiService.notifications.create(configData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.createSuccess'))
          setNotificationModalVisible(false)
          fetchNotificationConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.createFailed'))
        }
      }
    } catch (error: any) {
      if (error.errorFields) {
        return
      }
      message.error(error.message || t('message.error'))
    }
  }

  const notificationColumns = [
    {
      title: t('notificationSettings.configName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('notificationSettings.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color="blue">{type.toUpperCase()}</Tag>
    },
    {
      title: t('notificationSettings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>
          {enabled ? t('notificationSettings.enabledStatus') : t('notificationSettings.disabledStatus')}
        </Tag>
      )
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: isMobile ? 120 : 200,
      render: (_: any, record: NotificationConfig) => (
        <Space size="small" wrap>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleNotificationEdit(record)}
          >
            {t('notificationSettings.edit')}
          </Button>
          <Switch
            checked={record.enabled}
            size="small"
            onChange={(checked) => handleNotificationUpdateEnabled(record.id!, checked)}
          />
          <Button
            type="link"
            size="small"
            icon={<SendOutlined />}
            loading={testLoading}
            onClick={handleNotificationTest}
          >
            {t('notificationSettings.test')}
          </Button>
          <Popconfirm
            title={t('notificationSettings.deleteConfirm')}
            onConfirm={() => handleNotificationDelete(record.id!)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
            >
              {t('notificationSettings.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  // ==================== 第三部分：Relayer配置 ====================
  const fetchSystemConfig = async () => {
    try {
      const response = await apiService.systemConfig.get()
      if (response.data.code === 0 && response.data.data) {
        const config = response.data.data
        setSystemConfig(config)
        // 将已配置的值填充到输入框中
        relayerForm.setFieldsValue({
          builderApiKey: config.builderApiKeyDisplay || '',
          builderSecret: config.builderSecretDisplay || '',
          builderPassphrase: config.builderPassphraseDisplay || '',
        })
        autoRedeemForm.setFieldsValue({
          autoRedeemEnabled: config.autoRedeemEnabled
        })
      }
    } catch (error: any) {
      console.error('获取系统配置失败:', error)
    }
  }

  const handleRelayerSubmit = async (values: BuilderApiKeyUpdateRequest) => {
    setRelayerLoading(true)
    try {
      const updateData: BuilderApiKeyUpdateRequest = {}
      if (values.builderApiKey && values.builderApiKey.trim()) {
        updateData.builderApiKey = values.builderApiKey.trim()
      }
      if (values.builderSecret && values.builderSecret.trim()) {
        updateData.builderSecret = values.builderSecret.trim()
      }
      if (values.builderPassphrase && values.builderPassphrase.trim()) {
        updateData.builderPassphrase = values.builderPassphrase.trim()
      }

      if (!updateData.builderApiKey && !updateData.builderSecret && !updateData.builderPassphrase) {
        message.warning(t('builderApiKey.noChanges') || '没有需要更新的字段')
        setRelayerLoading(false)
        return
      }

      const response = await apiService.systemConfig.updateBuilderApiKey(updateData)
      if (response.data.code === 0) {
        message.success(t('builderApiKey.saveSuccess'))
        fetchSystemConfig()
        relayerForm.resetFields()
      } else {
        message.error(response.data.msg || t('builderApiKey.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('builderApiKey.saveFailed'))
    } finally {
      setRelayerLoading(false)
    }
  }

  const handleAutoRedeemSubmit = async (values: { autoRedeemEnabled: boolean }) => {
    setAutoRedeemLoading(true)
    try {
      const response = await apiService.systemConfig.updateAutoRedeem({ enabled: values.autoRedeemEnabled })
      if (response.data.code === 0) {
        message.success(t('systemSettings.autoRedeem.saveSuccess') || '自动赎回配置已保存')
        fetchSystemConfig()
      } else {
        message.error(response.data.msg || t('systemSettings.autoRedeem.saveFailed') || '保存自动赎回配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('systemSettings.autoRedeem.saveFailed') || '保存自动赎回配置失败')
    } finally {
      setAutoRedeemLoading(false)
    }
  }

  // ==================== 第四部分：代理设置 ====================
  const fetchProxyConfig = async () => {
    try {
      const response = await apiService.proxyConfig.get()
      if (response.data.code === 0) {
        const data = response.data.data
        setCurrentProxyConfig(data)
        if (data) {
          proxyForm.setFieldsValue({
            type: data.type || 'HTTP',
            enabled: data.enabled,
            host: data.host || '',
            port: data.port || undefined,
            username: data.username || '',
            password: '',
          })
        } else {
          proxyForm.setFieldsValue({
            type: 'HTTP',
            enabled: false,
            host: '',
            port: undefined,
            username: '',
            password: '',
          })
        }
      } else {
        message.error(response.data.msg || '获取代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取代理配置失败')
    }
  }

  const handleProxySubmit = async (values: any) => {
    setProxyLoading(true)
    try {
      const response = await apiService.proxyConfig.save({
        type: values.type,
        enabled: values.enabled || false,
        host: values.host,
        port: values.port,
        username: values.username || undefined,
        password: values.password || undefined,
      })
      if (response.data.code === 0) {
        message.success('保存代理配置成功。新配置将立即生效，已建立的 WebSocket 连接需要重新连接才能使用新代理。')
        fetchProxyConfig()
        setProxyCheckResult(null)
      } else {
        message.error(response.data.msg || '保存代理配置失败')
      }
    } catch (error: any) {
      message.error(error.message || '保存代理配置失败')
    } finally {
      setProxyLoading(false)
    }
  }

  const handleProxyCheck = async () => {
    setProxyChecking(true)
    setProxyCheckResult(null)
    try {
      const response = await apiService.proxyConfig.check()
      if (response.data.code === 0 && response.data.data) {
        const result = response.data.data
        setProxyCheckResult(result)
        if (result.success) {
          message.success(`代理检查成功：${result.message}${result.responseTime ? ` (响应时间: ${result.responseTime}ms)` : ''}`)
        } else {
          message.warning(`代理检查失败：${result.message}`)
        }
      } else {
        message.error(response.data.msg || '代理检查失败')
      }
    } catch (error: any) {
      message.error(error.message || '代理检查失败')
    } finally {
      setProxyChecking(false)
    }
  }

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('systemSettings.title') || '通用设置'}</Title>
      </div>

      {/* 系统更新 */}
      {systemUpdateEnabled ? (
        <SystemUpdate />
      ) : (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: '16px' }}
          message={t('systemUpdate.disabledTitle')}
          description={t('systemUpdate.disabledDescription')}
        />
      )}

      {/* 第一部分：消息推送设置 */}
      <Card
        title={
          <Space>
            <NotificationOutlined />
            <span>{t('systemSettings.notification.title') || '消息推送设置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={handleNotificationCreate}
          >
            {t('notificationSettings.addConfig')}
          </Button>
        }
      >
        <Table
          columns={notificationColumns}
          dataSource={notificationConfigs}
          loading={notificationLoading}
          rowKey="id"
          pagination={false}
          scroll={{ x: isMobile ? 600 : 'auto' }}
        />

        <Modal
          title={editingNotificationConfig ? t('notificationSettings.editConfig') : t('notificationSettings.addConfig')}
          open={notificationModalVisible}
          onOk={handleNotificationSubmit}
          onCancel={() => setNotificationModalVisible(false)}
          width={isMobile ? '90%' : 600}
          okText={t('common.confirm')}
          cancelText={t('common.cancel')}
        >
          <Form
            form={notificationForm}
            layout="vertical"
          >
            <Form.Item
              name="type"
              label={t('notificationSettings.type')}
              rules={[{ required: true, message: t('notificationSettings.typeRequired') }]}
            >
              <Input disabled value="telegram" />
            </Form.Item>

            <Form.Item
              name="name"
              label={t('notificationSettings.configName')}
              rules={[{ required: true, message: t('notificationSettings.configNameRequired') }]}
            >
              <Input placeholder={t('notificationSettings.configNamePlaceholder')} />
            </Form.Item>

            <Form.Item
              name="enabled"
              label={t('notificationSettings.enabled')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>

            <Form.Item shouldUpdate={(prevValues, currentValues) => {
              return prevValues.type !== currentValues.type ||
                prevValues.config !== currentValues.config
            }}>
              {() => {
                const currentType = notificationForm.getFieldValue('type') || 'telegram'
                if (currentType === 'telegram') {
                  return <TelegramConfigForm form={notificationForm} />
                }
                return null
              }}
            </Form.Item>
          </Form>
        </Modal>
      </Card>

      {/* 第二部分：Relayer配置 */}
      <Card
        title={
          <Space>
            <KeyOutlined />
            <span>{t('systemSettings.relayer.title') || 'Relayer 配置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        {/* Builder API Key 配置 */}
        <div style={{ marginBottom: '24px' }}>
          <Title level={4} style={{ marginBottom: '16px' }}>
            {t('builderApiKey.title') || 'Builder API Key'}
          </Title>
          <Alert
            message={t('builderApiKey.alertTitle')}
            description={
              <div>
                <Paragraph style={{ marginBottom: '8px' }}>
                  {t('builderApiKey.description')}
                </Paragraph>
                <Paragraph style={{ marginBottom: '8px' }}>
                  <Text strong>{t('builderApiKey.purposeTitle')}</Text>
                  <ul style={{ marginTop: '8px', marginBottom: 0, paddingLeft: '20px' }}>
                    <li>{t('builderApiKey.purpose1')}</li>
                    <li>{t('builderApiKey.purpose2')}</li>
                    <li>{t('builderApiKey.purpose3')}</li>
                  </ul>
                </Paragraph>
                <Paragraph style={{ marginBottom: 0 }}>
                  <Text strong>{t('builderApiKey.getApiKey')}</Text>
                  <Space style={{ marginLeft: '8px' }}>
                    <a
                      href="https://polymarket.com/settings?tab=builder"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <LinkOutlined /> {t('builderApiKey.openSettings')}
                    </a>
                  </Space>
                </Paragraph>
              </div>
            }
            type="info"
            showIcon
            style={{ marginBottom: '16px' }}
          />

          <Form
            form={relayerForm}
            layout="vertical"
            onFinish={handleRelayerSubmit}
            size={isMobile ? 'middle' : 'large'}
          >
            <Form.Item
              label={t('builderApiKey.apiKey')}
              name="builderApiKey"
            >
              <Input
                placeholder={t('builderApiKey.apiKeyPlaceholder')}
                style={{ fontFamily: 'monospace' }}
              />
            </Form.Item>

            <Form.Item
              label={t('builderApiKey.secret')}
              name="builderSecret"
            >
              <Input.Password
                placeholder={t('builderApiKey.secretPlaceholder')}
                style={{ fontFamily: 'monospace' }}
                iconRender={(visible) => (visible ? <span>👁️</span> : <span>👁️‍🗨️</span>)}
              />
            </Form.Item>

            <Form.Item
              label={t('builderApiKey.passphrase')}
              name="builderPassphrase"
            >
              <Input.Password
                placeholder={t('builderApiKey.passphrasePlaceholder')}
                style={{ fontFamily: 'monospace' }}
                iconRender={(visible) => (visible ? <span>👁️</span> : <span>👁️‍🗨️</span>)}
              />
            </Form.Item>

            <Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={relayerLoading}
              >
                {t('common.save') || '保存配置'}
              </Button>
            </Form.Item>
          </Form>
        </div>

        {/* 自动赎回配置 */}
        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: '24px' }}>
          <Title level={4} style={{ marginBottom: '16px' }}>
            {t('systemSettings.autoRedeem.title') || '自动赎回'}
          </Title>
          <Form
            form={autoRedeemForm}
            layout="vertical"
            onFinish={handleAutoRedeemSubmit}
            size={isMobile ? 'middle' : 'large'}
          >
            <Form.Item
              label={t('systemSettings.autoRedeem.label') || '启用自动赎回'}
              name="autoRedeemEnabled"
              tooltip={t('systemSettings.autoRedeem.tooltip') || '开启后，系统将自动赎回所有账户中可赎回的仓位。需要配置 Builder API Key 才能生效。'}
              valuePropName="checked"
            >
              <Switch loading={autoRedeemLoading} />
            </Form.Item>

            {!systemConfig?.builderApiKeyConfigured && (
              <Alert
                message={t('systemSettings.autoRedeem.builderApiKeyNotConfigured') || 'Builder API Key 未配置'}
                description={t('systemSettings.autoRedeem.builderApiKeyNotConfiguredDesc') || '自动赎回功能需要配置 Builder API Key 才能生效。'}
                type="warning"
                showIcon
                style={{ marginBottom: '16px' }}
              />
            )}

            <Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={autoRedeemLoading}
              >
                {t('common.save') || '保存配置'}
              </Button>
            </Form.Item>
          </Form>
        </div>
      </Card>

      {/* 第三部分：代理设置 */}
      <Card
        title={
          <Space>
            <LinkOutlined />
            <span>{t('systemSettings.proxy.title') || '代理设置'}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Form
          form={proxyForm}
          layout="vertical"
          onFinish={handleProxySubmit}
          size={isMobile ? 'middle' : 'large'}
          initialValues={{ type: 'HTTP', enabled: false }}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: '16px' }}
            message="代理协议说明"
            description="支持 HTTP、HTTPS、SOCKS5。HTTPS 代理仍通过 HTTP CONNECT 建链；如果第三方服务要求 SOCKS5 账号密码认证，请选择 SOCKS5 并填写用户名、密码。"
          />

          <Form.Item
            label="代理协议"
            name="type"
            rules={[{ required: true, message: '请选择代理协议' }]}
          >
            <Select options={PROXY_TYPE_OPTIONS.map((item) => ({ value: item.value, label: item.label }))} />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.enabled') || '启用代理'}
            name="enabled"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.host') || '代理主机'}
            name="host"
            rules={[
              { required: true, message: t('proxySettings.hostRequired') || '请输入代理主机地址' },
              { pattern: /^[\w\.-]+$/, message: t('proxySettings.hostInvalid') || '请输入有效的主机地址' }
            ]}
          >
            <Input placeholder={t('proxySettings.hostPlaceholder') || '例如：127.0.0.1 或 proxy.example.com'} />
          </Form.Item>

          <Form.Item
            label={t('proxySettings.port') || '代理端口'}
            name="port"
            rules={[
              { required: true, message: t('proxySettings.portRequired') || '请输入代理端口' },
              { type: 'number', min: 1, max: 65535, message: t('proxySettings.portInvalid') || '端口必须在 1-65535 之间' }
            ]}
          >
            <InputNumber
              min={1}
              max={65535}
              style={{ width: '100%' }}
              placeholder={t('proxySettings.portPlaceholder') || '例如：8888'}
            />
          </Form.Item>

          <Form.Item
            label="代理用户名（可选）"
            name="username"
          >
            <Input placeholder="如果代理需要认证，请输入用户名" />
          </Form.Item>

          <Form.Item
            label="代理密码（可选）"
            name="password"
            help={currentProxyConfig ? '留空则不更新密码，输入新密码则更新' : '如果代理需要认证，请输入密码'}
          >
            <Input.Password placeholder={currentProxyConfig ? '留空则不更新密码' : '如果代理需要认证，请输入密码'} />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={proxyLoading}
              >
                {t('common.save') || '保存配置'}
              </Button>
              <Button
                icon={<CheckCircleOutlined />}
                onClick={handleProxyCheck}
                loading={proxyChecking}
              >
                {t('proxySettings.check') || '检查代理'}
              </Button>
              {proxyCheckResult && (
                <Button
                  icon={<ReloadOutlined />}
                  onClick={fetchProxyConfig}
                >
                  {t('common.refresh') || '刷新配置'}
                </Button>
              )}
            </Space>
          </Form.Item>
        </Form>

        {proxyCheckResult && (
          <Alert
            type={proxyCheckResult.success ? 'success' : 'error'}
            message={proxyCheckResult.success ? (t('proxySettings.checkSuccess') || '代理检查成功') : (t('proxySettings.checkFailed') || '代理检查失败')}
            description={
              <div>
                <Text>{proxyCheckResult.message}</Text>
                {(proxyCheckResult.responseTime !== undefined || proxyCheckResult.latency !== undefined) && (
                  <div style={{ marginTop: '8px' }}>
                    <Text type="secondary">
                      {t('proxySettings.latency') || '延迟'}: {(proxyCheckResult.latency ?? proxyCheckResult.responseTime) ?? 0}ms
                    </Text>
                  </div>
                )}
              </div>
            }
            style={{ marginTop: '16px' }}
            showIcon
          />
        )}

      </Card>
    </div>
  )
}

export default SystemSettings
