import { useEffect, useCallback, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { ConfigProvider, notification, Spin } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { useTranslation } from 'react-i18next'
import Layout from './components/Layout'
import Login from './pages/Login'
import ResetPassword from './pages/ResetPassword'
import AccountList from './pages/AccountList'
import UserList from './pages/UserList'
import AccountImport from './pages/AccountImport'
import AccountDetail from './pages/AccountDetail'
import AccountEdit from './pages/AccountEdit'
import LeaderList from './pages/LeaderList'
import LeaderAdd from './pages/LeaderAdd'
import LeaderEdit from './pages/LeaderEdit'
import ConfigPage from './pages/ConfigPage'
import PositionList from './pages/PositionList'
import Statistics from './pages/Statistics'
import TemplateList from './pages/TemplateList'
import TemplateAdd from './pages/TemplateAdd'
import TemplateEdit from './pages/TemplateEdit'
import CopyTradingList from './pages/CopyTradingList'
import CopyTradingStatistics from './pages/CopyTradingStatistics'
import CopyTradingBuyOrders from './pages/CopyTradingBuyOrders'
import CopyTradingSellOrders from './pages/CopyTradingSellOrders'
import CopyTradingMatchedOrders from './pages/CopyTradingMatchedOrders'
import FilteredOrdersList from './pages/FilteredOrdersList'
import SystemSettings from './pages/SystemSettings'
import ApiHealthStatus from './pages/ApiHealthStatus'
import RpcNodeSettings from './pages/RpcNodeSettings'
import Announcements from './pages/Announcements'
import BacktestList from './pages/BacktestList'
import BacktestDetail from './pages/BacktestDetail'
import CryptoTailStrategyList from './pages/CryptoTailStrategyList'
import CryptoTailMonitor from './pages/CryptoTailMonitor'
import { wsManager } from './services/websocket'
import type { OrderPushMessage } from './types'
import { apiService } from './services/api'
import { hasToken } from './utils'

/**
 * 璺敱淇濇姢缁勪欢
 */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation()
  const isAuthPage = location.pathname === '/login' || location.pathname === '/reset-password'
  
  if (isAuthPage) {
    return <>{children}</>
  }
  
  if (!hasToken()) {
    return <Navigate to="/login" replace />
  }
  
  return <Layout>{children}</Layout>
}

function App() {
  const { t } = useTranslation()
  const [isFirstUse, setIsFirstUse] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(true)
  
  /**
   * 鑾峰彇璁㈠崟绫诲瀷鏂囨湰
   */
  const getOrderTypeText = useCallback((type: string): string => {
    switch (type) {
      case 'PLACEMENT':
        return t('order.create')
      case 'UPDATE':
        return t('order.update')
      case 'CANCELLATION':
        return t('order.cancel')
      default:
        return t('order.event')
    }
  }, [t])
  
  /**
   * 澶勭悊璁㈠崟鎺ㄩ€佹秷鎭紝鏄剧ず鍏ㄥ眬閫氱煡
   */
  const handleOrderPush = useCallback((message: OrderPushMessage) => {
    const { accountName, order, orderDetail, leaderName, configName } = message
    
    // 鏍规嵁璁㈠崟绫诲瀷鍜屾搷浣滅被鍨嬬‘瀹氶€氱煡鍐呭
    const orderTypeText = getOrderTypeText(order.type)
    const sideText = order.side === 'BUY' ? t('order.buy') : t('order.sell')
    
    // 濡傛灉鏈夊競鍦哄悕绉帮紝鍦ㄦ爣棰樹腑鏄剧ず
    const marketName = orderDetail?.marketName || order.market.substring(0, 8) + '...'
    
    // 鏋勫缓鏍囬锛氬鏋滄槸璺熷崟璁㈠崟锛屾樉绀?leader 澶囨敞鍜岃窡鍗曢厤缃悕
    let title = `${accountName} - ${orderTypeText}`
    if (leaderName || configName) {
      const parts: string[] = []
      if (configName) {
        parts.push(configName)
      }
      if (leaderName) {
        parts.push(`Leader: ${leaderName}`)
      }
      if (parts.length > 0) {
        title = `${accountName} (${parts.join(', ')}) - ${orderTypeText}`
      }
    }
    
    // 浼樺厛浣跨敤璁㈠崟璇︽儏涓殑鏁版嵁锛屽鏋滄病鏈夊垯浣跨敤 WebSocket 娑堟伅涓殑鏁版嵁
    const price = orderDetail ? parseFloat(orderDetail.price).toFixed(4) : parseFloat(order.price).toFixed(4)
    const size = orderDetail ? parseFloat(orderDetail.size).toFixed(2) : parseFloat(order.original_size).toFixed(2)
    const filled = orderDetail ? parseFloat(orderDetail.filled).toFixed(2) : parseFloat(order.size_matched).toFixed(2)
    const status = orderDetail?.status || 'UNKNOWN'
    
    // 鏋勫缓鎻忚堪淇℃伅
    let description = `${t('order.market')}: ${marketName}\n${sideText} ${size} @ ${price}`
    
    // 濡傛灉鏈夎鍗曡鎯咃紝鏄剧ず鏇磋缁嗙殑淇℃伅
    if (orderDetail) {
      description += `\n${t('order.status')}: ${status}`
      if (parseFloat(filled) > 0) {
        description += ` | ${t('order.filled')}: ${filled}`
      }
      const remaining = (parseFloat(size) - parseFloat(filled)).toFixed(2)
      if (parseFloat(remaining) > 0) {
        description += ` | ${t('order.remaining')}: ${remaining}`
      }
    } else if (order.type === 'UPDATE' && parseFloat(order.size_matched) > 0) {
      // 濡傛灉娌℃湁璁㈠崟璇︽儏锛屼娇鐢?WebSocket 娑堟伅涓殑宸叉垚浜ゆ暟閲?
      description += `\n${t('order.filled')}: ${filled}`
    }
    
    // 鏍规嵁璁㈠崟绫诲瀷閫夋嫨閫氱煡绫诲瀷
    let notificationType: 'info' | 'success' | 'warning' | 'error' = 'info'
    if (order.type === 'PLACEMENT') {
      notificationType = 'info'
    } else if (order.type === 'UPDATE') {
      notificationType = 'success'
    } else if (order.type === 'CANCELLATION') {
      notificationType = 'warning'
    }
    
    // 鏄剧ず閫氱煡
    notification[notificationType]({
      message: title,
      description: description,
      placement: 'topRight',
      duration: order.type === 'CANCELLATION' ? 3 : 5,  // 鍙栨秷璁㈠崟閫氱煡鏄剧ず鏃堕棿鐭竴浜?
      key: `order-${order.id}`,  // 浣跨敤璁㈠崟 ID 浣滀负 key锛岄伩鍏嶉噸澶嶉€氱煡
    })
  }, [getOrderTypeText])
  
  // 搴旂敤鍚姩鏃舵鏌ユ槸鍚﹂娆′娇鐢?
  useEffect(() => {
    const checkFirstUse = async () => {
      try {
        const response = await apiService.auth.checkFirstUse()
        if (response.data.code === 0 && response.data.data) {
          setIsFirstUse(response.data.data.isFirstUse)
        }
      } catch (error) {
        console.error('妫€鏌ラ娆′娇鐢ㄥけ璐?', error)
        setIsFirstUse(false)
      } finally {
        setChecking(false)
      }
    }
    
    checkFirstUse()
  }, [])
  
  // 搴旂敤鍚姩鏃剁珛鍗冲缓绔嬪叏灞€ WebSocket 杩炴帴锛堜粎鍦ㄥ凡鐧诲綍鏃讹級
  useEffect(() => {
    // 鍙湁鍦ㄥ凡鐧诲綍涓斾笉鏄娆′娇鐢ㄧ殑鎯呭喌涓嬫墠寤虹珛WebSocket杩炴帴
    if (!checking && isFirstUse === false && hasToken() && !wsManager.isConnected()) {
      wsManager.connect()
    } else if (!hasToken() && wsManager.isConnected()) {
      // 濡傛灉鏈櫥褰曚絾WebSocket宸茶繛鎺ワ紝鏂紑杩炴帴
      wsManager.disconnect()
    }
  }, [checking, isFirstUse])
  
  // 璁㈤槄璁㈠崟鎺ㄩ€佸苟鏄剧ず鍏ㄥ眬閫氱煡
  useEffect(() => {
    const unsubscribe = wsManager.subscribe('order', (data: OrderPushMessage) => {
      handleOrderPush(data)
    })
    
    return () => {
      unsubscribe()
    }
  }, [handleOrderPush])
  
  // 濡傛灉姝ｅ湪妫€鏌ラ娆′娇鐢紝鏄剧ず鍔犺浇涓?
  if (checking) {
    return (
      <ConfigProvider locale={zhCN}>
        <div style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100vh'
        }}>
          <Spin size="large" />
        </div>
      </ConfigProvider>
    )
  }
  
  // 濡傛灉棣栨浣跨敤锛岀洿鎺ヨ烦杞埌閲嶇疆瀵嗙爜椤甸潰
  if (isFirstUse === true) {
    return (
      <ConfigProvider locale={zhCN}>
        <BrowserRouter>
          <Routes>
            <Route path="/reset-password" element={<ResetPassword />} />
            <Route path="*" element={<Navigate to="/reset-password" replace />} />
          </Routes>
        </BrowserRouter>
      </ConfigProvider>
    )
  }
  
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <Routes>
          {/* 鍏紑璺敱锛堜笉闇€瑕侀壌鏉冿級 */}
          <Route path="/login" element={<Login />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          
          {/* 鍙椾繚鎶ょ殑璺敱 */}
          <Route path="/" element={<ProtectedRoute><Announcements /></ProtectedRoute>} />
          <Route path="/accounts" element={<ProtectedRoute><AccountList /></ProtectedRoute>} />
          <Route path="/accounts/import" element={<ProtectedRoute><AccountImport /></ProtectedRoute>} />
          <Route path="/accounts/detail" element={<ProtectedRoute><AccountDetail /></ProtectedRoute>} />
          <Route path="/accounts/edit" element={<ProtectedRoute><AccountEdit /></ProtectedRoute>} />
          <Route path="/leaders" element={<ProtectedRoute><LeaderList /></ProtectedRoute>} />
          <Route path="/leaders/add" element={<ProtectedRoute><LeaderAdd /></ProtectedRoute>} />
          <Route path="/leaders/edit" element={<ProtectedRoute><LeaderEdit /></ProtectedRoute>} />
          <Route path="/templates" element={<ProtectedRoute><TemplateList /></ProtectedRoute>} />
          <Route path="/templates/add" element={<ProtectedRoute><TemplateAdd /></ProtectedRoute>} />
          <Route path="/templates/edit/:id" element={<ProtectedRoute><TemplateEdit /></ProtectedRoute>} />
          <Route path="/copy-trading" element={<ProtectedRoute><CopyTradingList /></ProtectedRoute>} />
          <Route path="/crypto-tail-strategy" element={<ProtectedRoute><CryptoTailStrategyList /></ProtectedRoute>} />
          <Route path="/crypto-tail-monitor" element={<ProtectedRoute><CryptoTailMonitor /></ProtectedRoute>} />
          <Route path="/copy-trading/statistics/:copyTradingId" element={<ProtectedRoute><CopyTradingStatistics /></ProtectedRoute>} />
          {/* 淇濈暀鏃ц矾鐢变互淇濇寔鍚戝悗鍏煎 */}
          <Route path="/copy-trading/orders/buy/:copyTradingId" element={<ProtectedRoute><CopyTradingBuyOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/orders/sell/:copyTradingId" element={<ProtectedRoute><CopyTradingSellOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/orders/matched/:copyTradingId" element={<ProtectedRoute><CopyTradingMatchedOrders /></ProtectedRoute>} />
          <Route path="/copy-trading/filtered-orders/:id" element={<ProtectedRoute><FilteredOrdersList /></ProtectedRoute>} />
          <Route path="/backtest" element={<ProtectedRoute><BacktestList /></ProtectedRoute>} />
          <Route path="/backtest/detail/:id" element={<ProtectedRoute><BacktestDetail /></ProtectedRoute>} />
          <Route path="/config" element={<ProtectedRoute><ConfigPage /></ProtectedRoute>} />
          <Route path="/positions" element={<ProtectedRoute><PositionList /></ProtectedRoute>} />
          <Route path="/statistics" element={<ProtectedRoute><Statistics /></ProtectedRoute>} />
          <Route path="/users" element={<ProtectedRoute><UserList /></ProtectedRoute>} />
          <Route path="/announcements" element={<ProtectedRoute><Announcements /></ProtectedRoute>} />
          <Route path="/system-settings" element={<ProtectedRoute><SystemSettings /></ProtectedRoute>} />
          <Route path="/system-settings/rpc-nodes" element={<ProtectedRoute><RpcNodeSettings /></ProtectedRoute>} />          <Route path="/system-settings/api-health" element={<ProtectedRoute><ApiHealthStatus /></ProtectedRoute>} />
          
          {/* 榛樿閲嶅畾鍚戝埌鐧诲綍椤?*/}
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  )
}

export default App

