import React from 'react'
import { Button, Form, InputNumber, Space } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'

interface MultiplierTierEditorProps {
  name?: string
}

const MultiplierTierEditor: React.FC<MultiplierTierEditorProps> = ({ name = 'tieredMultipliers' }) => {
  return (
    <Form.List name={name}>
      {(fields, { add, remove }) => (
        <Space direction="vertical" style={{ width: '100%' }}>
          {fields.map((field) => (
            <Space key={field.key} align="start" wrap style={{ width: '100%' }}>
              <Form.Item
                {...field}
                label="Min"
                name={[field.name, 'min']}
                rules={[{ required: true, message: '请输入 min' }]}
                style={{ minWidth: 120 }}
              >
                <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                {...field}
                label="Max"
                name={[field.name, 'max']}
                style={{ minWidth: 120 }}
              >
                <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} placeholder="留空=∞" />
              </Form.Item>
              <Form.Item
                {...field}
                label="Multiplier"
                name={[field.name, 'multiplier']}
                rules={[{ required: true, message: '请输入 multiplier' }]}
                style={{ minWidth: 140 }}
              >
                <InputNumber min={0} step={0.0001} precision={4} style={{ width: '100%' }} />
              </Form.Item>
              <Button danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} />
            </Space>
          ))}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => add()}>
            新增分层 multiplier
          </Button>
        </Space>
      )}
    </Form.List>
  )
}

export default MultiplierTierEditor
