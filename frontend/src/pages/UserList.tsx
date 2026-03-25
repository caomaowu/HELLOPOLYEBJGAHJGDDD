import { useCallback, useEffect, useState } from 'react'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Typography, Modal, Form, Input } from 'antd'
import { PlusOutlined, ReloadOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

interface User {
  id: number
  username: string
  isDefault: boolean
  createdAt: number
  updatedAt: number
}

const UserList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(false)
  const [createModalVisible, setCreateModalVisible] = useState(false)
  const [updatePasswordModalVisible, setUpdatePasswordModalVisible] = useState(false)
  const [updateOwnPasswordModalVisible, setUpdateOwnPasswordModalVisible] = useState(false)
  const [selectedUser, setSelectedUser] = useState<User | null>(null)
  const [createForm] = Form.useForm()
  const [updatePasswordForm] = Form.useForm()
  const [updateOwnPasswordForm] = Form.useForm()
  
  // 获取当前用户（判断是否是默认账户）
  const currentUser = users.find(user => user.isDefault) || users[0]
  const isDefaultUser = currentUser?.isDefault || false

  const fetchUsers = useCallback(async () => {
    setLoading(true)
    try {
      const response = await apiService.users.list()
      if (response.data.code === 0 && response.data.data) {
        setUsers(response.data.data)
      } else {
        message.error(response.data.msg || t('userList.fetchFailed') || '获取用户列表失败')
      }
    } catch (error: any) {
      console.error('获取用户列表失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('userList.fetchFailed') || '获取用户列表失败'
      message.error(errorMsg)
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    void fetchUsers()
  }, [fetchUsers])

  const handleCreate = async (values: { username: string; password: string }) => {
    try {
      const response = await apiService.users.create({
        username: values.username,
        password: values.password
      })
      if (response.data.code === 0) {
        message.success(t('userList.createSuccess') || '创建用户成功')
        setCreateModalVisible(false)
        createForm.resetFields()
        fetchUsers()
      } else {
        message.error(response.data.msg || t('userList.createFailed') || '创建用户失败')
      }
    } catch (error: any) {
      console.error('创建用户失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('userList.createFailed') || '创建用户失败'
      message.error(errorMsg)
    }
  }

  const handleUpdatePassword = async (values: { newPassword: string }) => {
    if (!selectedUser) return
    
    try {
      const response = await apiService.users.updatePassword({
        userId: selectedUser.id,
        newPassword: values.newPassword
      })
      if (response.data.code === 0) {
        message.success(t('userList.updatePasswordSuccess') || '更新密码成功')
        setUpdatePasswordModalVisible(false)
        setSelectedUser(null)
        updatePasswordForm.resetFields()
        fetchUsers()
      } else {
        message.error(response.data.msg || t('userList.updatePasswordFailed') || '更新密码失败')
      }
    } catch (error: any) {
      console.error('更新密码失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('userList.updatePasswordFailed') || '更新密码失败'
      message.error(errorMsg)
    }
  }

  const handleUpdateOwnPassword = async (values: { newPassword: string }) => {
    try {
      const response = await apiService.users.updateOwnPassword({
        newPassword: values.newPassword
      })
      if (response.data.code === 0) {
        message.success(t('userList.updateOwnPasswordSuccess') || '修改密码成功，请重新登录')
        setUpdateOwnPasswordModalVisible(false)
        updateOwnPasswordForm.resetFields()
        // 延迟跳转到登录页
        setTimeout(() => {
          window.location.href = '/login'
        }, 1000)
      } else {
        message.error(response.data.msg || t('userList.updateOwnPasswordFailed') || '修改密码失败')
      }
    } catch (error: any) {
      console.error('修改密码失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('userList.updateOwnPasswordFailed') || '修改密码失败'
      message.error(errorMsg)
    }
  }

  const handleDelete = async (user: User) => {
    try {
      const response = await apiService.users.delete({ userId: user.id })
      if (response.data.code === 0) {
        message.success(t('userList.deleteSuccess') || '删除用户成功')
        fetchUsers()
      } else {
        message.error(response.data.msg || t('userList.deleteFailed') || '删除用户失败')
      }
    } catch (error: any) {
      console.error('删除用户失败:', error)
      const errorMsg = error.response?.data?.msg || error.message || t('userList.deleteFailed') || '删除用户失败'
      message.error(errorMsg)
    }
  }

  const columns = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80
    },
    {
      title: t('userList.username') || '用户名',
      dataIndex: 'username',
      key: 'username'
    },
    {
      title: t('userList.role') || '角色',
      dataIndex: 'isDefault',
      key: 'isDefault',
      width: 100,
      render: (isDefault: boolean) => (
        <Tag color={isDefault ? 'red' : 'blue'}>
          {isDefault ? t('userList.defaultAccount') || '默认账户' : t('userList.normalUser') || '普通用户'}
        </Tag>
      )
    },
    {
      title: t('common.createdAt') || '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString(i18n.language || 'zh-CN')
    },
    {
      title: t('common.actions') || '操作',
      key: 'action',
      width: 200,
      render: (_: any, record: User) => {
        // 如果是默认账户，可以管理所有用户
        if (isDefaultUser) {
          return (
            <Space size="small">
              {!record.isDefault && (
                <>
                  <Button
                    type="link"
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => {
                      setSelectedUser(record)
                      setUpdatePasswordModalVisible(true)
                    }}
                  >
                    {t('userList.updatePassword') || '修改密码'}
                  </Button>
                  <Popconfirm
                    title={t('userList.deleteConfirm') || '确定要删除这个用户吗？'}
                    onConfirm={() => handleDelete(record)}
                    okText={t('common.confirm') || '确定'}
                    cancelText={t('common.cancel') || '取消'}
                  >
                    <Button
                      type="link"
                      danger
                      size="small"
                      icon={<DeleteOutlined />}
                    >
                      {t('common.delete') || '删除'}
                    </Button>
                  </Popconfirm>
                </>
              )}
            </Space>
          )
        } else {
          // 非默认账户：只能看到自己的信息，不显示操作按钮（修改密码通过顶部按钮）
          return null
        }
      }
    }
  ]

  return (
    <div>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
          <Title level={4} style={{ margin: 0 }}>{t('userList.title') || '用户管理'}</Title>
          <Space>
            <Button
              icon={<EditOutlined />}
              onClick={() => setUpdateOwnPasswordModalVisible(true)}
            >
              {t('userList.updateMyPassword') || '修改我的密码'}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchUsers}
              loading={loading}
            >
              {t('common.refresh') || '刷新'}
            </Button>
            {isDefaultUser && (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => setCreateModalVisible(true)}
              >
                {t('userList.addUser') || '新增用户'}
              </Button>
            )}
          </Space>
        </div>
        <Table
          columns={columns}
          dataSource={users}
          rowKey="id"
          loading={loading}
          pagination={{
            pageSize: isMobile ? 10 : 20,
            showSizeChanger: !isMobile,
            showTotal: (total) => t('userList.total', { total }) || `共 ${total} 条`
          }}
          scroll={isMobile ? { x: 600 } : undefined}
        />
      </Card>

      {/* 创建用户弹窗 */}
      <Modal
        title={t('userList.addUser') || '新增用户'}
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false)
          createForm.resetFields()
        }}
        onOk={() => createForm.submit()}
        okText={t('userList.createUser') || '创建'}
        cancelText={t('common.cancel') || '取消'}
      >
        <Form
          form={createForm}
          onFinish={handleCreate}
          layout="vertical"
        >
          <Form.Item
            name="username"
            label={t('userList.username') || '用户名'}
            rules={[
              { required: true, message: t('userList.usernameRequired') || '请输入用户名' }
            ]}
          >
            <Input placeholder={t('userList.usernamePlaceholder') || '请输入用户名'} />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('userList.password') || '密码'}
            rules={[
              { required: true, message: t('userList.passwordRequired') || '请输入密码' },
              { min: 6, message: t('userList.passwordMinLength') || '密码至少6位' }
            ]}
          >
            <Input.Password placeholder={t('userList.passwordPlaceholder') || '至少6位'} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 修改密码弹窗（管理员修改其他用户密码） */}
      <Modal
        title={t('userList.updatePassword') || '修改密码'}
        open={updatePasswordModalVisible}
        onCancel={() => {
          setUpdatePasswordModalVisible(false)
          setSelectedUser(null)
          updatePasswordForm.resetFields()
        }}
        onOk={() => updatePasswordForm.submit()}
        okText={t('common.confirm') || '确定'}
        cancelText={t('common.cancel') || '取消'}
      >
        <Form
          form={updatePasswordForm}
          onFinish={handleUpdatePassword}
          layout="vertical"
        >
          <Form.Item
            name="newPassword"
            label={t('userList.newPassword') || '新密码'}
            rules={[
              { required: true, message: t('userList.newPasswordRequired') || '请输入新密码' },
              { min: 6, message: t('userList.passwordMinLength') || '密码至少6位' }
            ]}
          >
            <Input.Password placeholder={t('userList.passwordPlaceholder') || '至少6位'} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 修改我的密码弹窗（默认账户修改自己密码） */}
      <Modal
        title={t('userList.updateMyPasswordTitle') || '修改我的密码'}
        open={updateOwnPasswordModalVisible}
        onCancel={() => {
          setUpdateOwnPasswordModalVisible(false)
          updateOwnPasswordForm.resetFields()
        }}
        onOk={() => updateOwnPasswordForm.submit()}
        okText={t('common.confirm') || '确定'}
        cancelText={t('common.cancel') || '取消'}
      >
        <Form
          form={updateOwnPasswordForm}
          onFinish={handleUpdateOwnPassword}
          layout="vertical"
        >
          <Form.Item
            name="newPassword"
            label={t('userList.newPassword') || '新密码'}
            rules={[
              { required: true, message: t('userList.newPasswordRequired') || '请输入新密码' },
              { min: 6, message: t('userList.passwordMinLength') || '密码至少6位' }
            ]}
          >
            <Input.Password placeholder={t('userList.passwordPlaceholder') || '至少6位'} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default UserList
