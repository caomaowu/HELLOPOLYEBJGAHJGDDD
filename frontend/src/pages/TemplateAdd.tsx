import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Input, Button, Radio, InputNumber, Switch, message, Typography, Space, Divider } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useTranslation } from 'react-i18next'
import MultiplierTierEditor from '../components/MultiplierTierEditor'
import { validateAndNormalizeMultiplierTiers } from '../utils'

const { Title } = Typography

const TemplateAdd: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED' | 'ADAPTIVE'>('RATIO')
  const [multiplierMode, setMultiplierMode] = useState<'NONE' | 'SINGLE' | 'TIERED'>('NONE')
  
  const handleSubmit = async (values: any) => {
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error(t('templateAdd.minOrderSizeError') || '最小金额必须 >= 1')
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error(t('templateAdd.fixedAmountRequired') || '请输入固定跟单金额')
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error(t('templateAdd.invalidNumber') || '请输入有效的数字')
        return
      }
      if (amount < 1) {
        message.error(t('templateAdd.fixedAmountError') || '固定金额必须 >= 1，请重新输入')
        return
      }
    }

    const normalizedTierResult = values.multiplierMode === 'TIERED'
      ? validateAndNormalizeMultiplierTiers(values.tieredMultipliers)
      : null
    if (normalizedTierResult && !normalizedTierResult.isValid) {
      message.error(normalizedTierResult.message || '分层 multiplier 配置不合法')
      return
    }
    
    setLoading(true)
    try {
      const response = await apiService.templates.create({
        templateName: values.templateName,
        copyMode: values.copyMode || 'RATIO',
        copyRatio: (values.copyMode === 'RATIO' || values.copyMode === 'ADAPTIVE') && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        adaptiveMinRatio: values.copyMode === 'ADAPTIVE' && values.adaptiveMinRatio != null ? (values.adaptiveMinRatio / 100).toString() : undefined,
        adaptiveMaxRatio: values.copyMode === 'ADAPTIVE' && values.adaptiveMaxRatio != null ? (values.adaptiveMaxRatio / 100).toString() : undefined,
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
        priceTolerance: values.priceTolerance?.toString(),
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        pushFilteredOrders: values.pushFilteredOrders ?? false
      })
      
      if (response.data.code === 0) {
        message.success(t('templateAdd.createSuccess') || '创建模板成功')
        navigate('/templates')
      } else {
        message.error(response.data.msg || t('templateAdd.createFailed') || '创建模板失败')
      }
    } catch (error: any) {
      message.error(error.message || t('templateAdd.createFailed') || '创建模板失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/templates')}
        >
          {t('templateAdd.back') || t('common.back') || '返回'}
        </Button>
      </div>
      
      <Card>
        <Title level={4}>{t('templateAdd.title') || '创建跟单模板'}</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100, // 默认 100%（显示为百分比）
            multiplierMode: 'NONE',
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyLoss: 10000,
            maxDailyOrders: 100,
            priceTolerance: 5,
            supportSell: true,
            pushFilteredOrders: false
          }}
        >
          <Form.Item
            label={t('templateAdd.templateName') || '模板名称'}
            name="templateName"
            tooltip={t('templateAdd.templateNameTooltip') || '模板的唯一标识名称，用于区分不同的跟单配置模板。模板名称必须唯一，不能与其他模板重名。'}
            rules={[{ required: true, message: t('templateAdd.templateNameRequired') || '请输入模板名称' }]}
          >
            <Input placeholder={t('templateAdd.templateNamePlaceholder') || '请输入模板名称'} />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('templateAdd.copyModeTooltip') || '选择跟单金额的计算方式。比例模式：跟单金额随 Leader 订单大小按比例变化；固定金额模式：无论 Leader 订单大小如何，跟单金额都固定不变。'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => setCopyMode(e.target.value)}>
              <Radio value="RATIO">{t('templateAdd.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('templateAdd.fixedAmountMode') || '固定金额模式'}</Radio>
              <Radio value="ADAPTIVE">{t('templateAdd.adaptiveMode') || '自适应模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {(copyMode === 'RATIO' || copyMode === 'ADAPTIVE') && (
            <Form.Item
              label={copyMode === 'ADAPTIVE' ? (t('templateAdd.baseCopyRatio') || '基础跟单比例') : (t('templateAdd.copyRatio') || '跟单比例')}
              name="copyRatio"
              tooltip={t('templateAdd.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder={t('templateAdd.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
                parser={(value) => {
                  const parsed = parseFloat(value || '0')
                  if (parsed > 10000) return 10000
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
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('templateAdd.fixedAmount') || '固定跟单金额 (USDC)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('templateAdd.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    // required 已经处理了空值情况，这里只处理非空值的校验
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('templateAdd.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('templateAdd.fixedAmountError') || '固定金额必须 >= 1，请重新输入'))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder={t('templateAdd.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}

          {copyMode === 'ADAPTIVE' && (
            <>
              <Form.Item label={t('templateAdd.adaptiveMinRatio') || '自适应最小比例'} name="adaptiveMinRatio" rules={[{ required: true, message: t('templateAdd.adaptiveMinRatioRequired') || '请输入自适应最小比例' }]}>
                <InputNumber min={0.01} max={10000} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
              <Form.Item label={t('templateAdd.adaptiveMaxRatio') || '自适应最大比例'} name="adaptiveMaxRatio" rules={[{ required: true, message: t('templateAdd.adaptiveMaxRatioRequired') || '请输入自适应最大比例' }]}>
                <InputNumber min={0.01} max={10000} step={0.01} precision={2} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
              <Form.Item label={t('templateAdd.adaptiveThreshold') || '自适应阈值 (USDC)'} name="adaptiveThreshold" rules={[{ required: true, message: t('templateAdd.adaptiveThresholdRequired') || '请输入自适应阈值' }]}>
                <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
              </Form.Item>
            </>
          )}
          
          <Divider>{t('templateAdd.sizingEnhancement') || 'Sizing 增强'}</Divider>

          <Form.Item label={t('templateAdd.multiplierMode') || 'Multiplier 模式'} name="multiplierMode">
            <Radio.Group onChange={(e) => setMultiplierMode(e.target.value)}>
              <Radio value="NONE">{t('templateAdd.multiplierModeNone') || '无'}</Radio>
              <Radio value="SINGLE">{t('templateAdd.multiplierModeSingle') || '单一倍率'}</Radio>
              <Radio value="TIERED">{t('templateAdd.multiplierModeTiered') || '分层倍率'}</Radio>
            </Radio.Group>
          </Form.Item>

          {multiplierMode === 'SINGLE' && (
            <Form.Item label={t('templateAdd.tradeMultiplier') || '倍率'} name="tradeMultiplier" rules={[{ required: true, message: t('templateAdd.tradeMultiplierRequired') || '请输入倍率' }]}>
              <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} addonAfter="x" />
            </Form.Item>
          )}

          {multiplierMode === 'TIERED' && (
            <Form.Item label={t('templateAdd.tieredMultipliers') || '分层倍率'} required>
              <MultiplierTierEditor />
            </Form.Item>
          )}

          <Form.Item
            label={t('templateAdd.maxOrderSize') || '单笔订单最大金额 (USDC)'}
            name="maxOrderSize"
          >
            <InputNumber min={0.0001} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item
            label={t('templateAdd.minOrderSize') || '单笔订单最小金额 (USDC)'}
            name="minOrderSize"
            rules={[
              {
                validator: (_, value) => {
                  if (value === undefined || value === null || value === '') {
                    return Promise.resolve()
                  }
                  if (typeof value === 'number' && value < 1) {
                    return Promise.reject(new Error(t('templateAdd.minOrderSizeError') || '最小金额必须 >= 1'))
                  }
                  return Promise.resolve()
                }
              }
            ]}
          >
            <InputNumber min={1} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item
            label={t('templateAdd.maxDailyLoss') || '每日最大亏损限制 (USDC)'}
            name="maxDailyLoss"
          >
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('templateAdd.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量，用于风险控制，防止过度交易。例如：设置为 50，当日跟单订单数达到 50 后，停止跟单，次日重置。'}
            >
              <InputNumber
                min={1}
                step={1}
                style={{ width: '100%' }}
                placeholder={t('templateAdd.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
              />
            </Form.Item>

          <Form.Item
            label={t('templateAdd.maxDailyVolume') || '每日最大成交额 (USDC)'}
            name="maxDailyVolume"
          >
            <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('templateAdd.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围，用于在 Leader 价格 ± 容忍度范围内调整价格，提高成交率。例如：设置为 5%，Leader 价格为 0.5，则跟单价格可在 0.475-0.525 范围内。'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.priceTolerancePlaceholder') || '默认 5%（可选）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.minOrderDepth') || '最小订单深度 (USDC)'}
            name="minOrderDepth"
            tooltip={t('templateAdd.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('templateAdd.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('templateAdd.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>{t('templateAdd.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('templateAdd.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('templateAdd.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('templateAdd.minPricePlaceholder') || '最低价（可选）'}
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
                  placeholder={t('templateAdd.maxPricePlaceholder') || '最高价（可选）'}
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
          
          {/* 跟单卖出 - 表单最底部 */}
          <Form.Item
            label={t('templateAdd.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('templateAdd.supportSellTooltip') || '是否跟单 Leader 的卖出订单。开启：跟单 Leader 的买入和卖出订单；关闭：只跟单 Leader 的买入订单，忽略卖出订单。'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('templateAdd.pushFilteredOrders') || '推送已过滤订单'}
            name="pushFilteredOrders"
            tooltip={t('templateAdd.pushFilteredOrdersTooltip') || '开启后，被过滤的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item shouldUpdate>
            {({ getFieldsError }) => {
              const errors = getFieldsError()
              const hasErrors = errors.some(({ errors }) => errors && errors.length > 0)
              return (
                <Space>
                  <Button
                    type="primary"
                    htmlType="submit"
                    icon={<SaveOutlined />}
                    loading={loading}
                    disabled={hasErrors}
                  >
                    {t('templateAdd.create') || '创建模板'}
                  </Button>
                  <Button onClick={() => navigate('/templates')}>
                    {t('common.cancel') || '取消'}
                  </Button>
                </Space>
              )
            }}
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default TemplateAdd

