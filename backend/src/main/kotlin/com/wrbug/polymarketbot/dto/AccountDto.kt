package com.wrbug.polymarketbot.dto

/**
 * 账户导入请求
 */
data class AccountImportRequest(
    val privateKey: String,  // 私钥（前端加密后传输）
    val walletAddress: String,  // 钱包地址（前端从私钥推导，用于验证）
    val accountName: String? = null,
    val isEnabled: Boolean = true,  // 是否启用（用于订单推送等功能的开关）
    val walletType: String = "magic",  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
    val builderApiKey: String? = null,  // Builder API Key（账户级，可选）
    val builderSecret: String? = null,  // Builder Secret（账户级，可选）
    val builderPassphrase: String? = null  // Builder Passphrase（账户级，可选）
)

/**
 * 检查代理地址选项请求
 */
data class CheckProxyOptionsRequest(
    val walletAddress: String,  // EOA 地址（必需）
    val privateKey: String? = null,  // 私钥（加密，私钥导入时提供）
    val mnemonic: String? = null  // 助记词（加密，助记词导入时提供）
)

/**
 * 代理地址选项信息
 */
data class ProxyOptionDto(
    val walletType: String,  // "magic" 或 "safe"
    val proxyAddress: String,  // 代理地址
    val descriptionKey: String,  // 说明文案的多语言 key（如 "accountImport.proxyOption.magic.description"）
    val availableBalance: String,  // 可用余额
    val positionBalance: String,  // 仓位余额
    val totalBalance: String,  // 总余额
    val positionCount: Int,  // 持仓数量
    val hasAssets: Boolean,  // 是否有资产（余额>0 或持仓>0）
    val error: String? = null  // 获取失败时的错误信息（可选）
)

/**
 * 检查代理地址选项响应
 */
data class CheckProxyOptionsResponse(
    val options: List<ProxyOptionDto>  // 代理地址选项列表（私钥导入返回2个，助记词返回1个）
)

/**
 * 账户更新请求
 */
data class AccountUpdateRequest(
    val accountId: Long,
    val accountName: String? = null,
    val isEnabled: Boolean? = null,  // 是否启用（用于订单推送等功能的开关）
    val builderApiKey: String? = null,  // Builder API Key（账户级，可选）
    val builderSecret: String? = null,  // Builder Secret（账户级，可选）
    val builderPassphrase: String? = null  // Builder Passphrase（账户级，可选）
)

/**
 * 系统配置更新请求
 */
data class SystemConfigUpdateRequest(
    val builderApiKey: String? = null,  // Builder API Key（前端加密后传输）
    val builderSecret: String? = null,  // Builder Secret（前端加密后传输）
    val builderPassphrase: String? = null,  // Builder Passphrase（前端加密后传输）
    val autoRedeem: Boolean? = null  // 自动赎回（系统级别配置）
)

/**
 * 系统配置响应
 */
data class SystemConfigDto(
    val builderApiKeyConfigured: Boolean,  // Builder API Key 是否已配置
    val builderSecretConfigured: Boolean,  // Builder Secret 是否已配置
    val builderPassphraseConfigured: Boolean,  // Builder Passphrase 是否已配置
    val builderApiKeyDisplay: String? = null,  // Builder API Key 显示值（完整，用于前端展示）
    val builderSecretDisplay: String? = null,  // Builder Secret 显示值（完整，用于前端展示）
    val builderPassphraseDisplay: String? = null,  // Builder Passphrase 显示值（完整，用于前端展示）
    val autoRedeemEnabled: Boolean = true  // 自动赎回（系统级别配置，默认开启）
)

/**
 * 账户删除请求
 */
data class AccountDeleteRequest(
    val accountId: Long
)

/**
 * 账户详情请求
 */
data class AccountDetailRequest(
    val accountId: Long? = null  // 账户ID（必需）
)

/**
 * 账户余额请求
 */
data class AccountBalanceRequest(
    val accountId: Long? = null  // 账户ID（必需）
)

/**
 * 账户信息响应
 */
data class AccountDto(
    val id: Long,
    val walletAddress: String,
    val proxyAddress: String,  // Polymarket 代理钱包地址
    val accountName: String?,
    val isEnabled: Boolean,  // 是否启用（用于订单推送等功能的开关）
    val walletType: String = "magic",  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）
    val apiKeyConfigured: Boolean,  // API Key 是否已配置（不返回实际 Key）
    val apiSecretConfigured: Boolean,  // API Secret 是否已配置
    val apiPassphraseConfigured: Boolean,  // API Passphrase 是否已配置
    val builderApiKeyConfigured: Boolean = false,  // Builder API Key 是否已配置（账户级）
    val builderSecretConfigured: Boolean = false,  // Builder Secret 是否已配置（账户级）
    val builderPassphraseConfigured: Boolean = false,  // Builder Passphrase 是否已配置（账户级）
    val builderApiKeyDisplay: String? = null,  // Builder API Key 显示值（完整，用于前端展示）
    val builderSecretDisplay: String? = null,  // Builder Secret 显示值（完整，用于前端展示）
    val builderPassphraseDisplay: String? = null,  // Builder Passphrase 显示值（完整，用于前端展示）
    val balance: String? = null,  // 账户余额（可选）
    val totalOrders: Long? = null,  // 总订单数（可选）
    val totalPnl: String? = null,  // 总盈亏（可选）
    val activeOrders: Long? = null,  // 活跃订单数（可选）
    val completedOrders: Long? = null,  // 已完成订单数（可选）
    val positionCount: Long? = null  // 持仓数量（可选）
)

/**
 * 账户列表响应
 */
data class AccountListResponse(
    val list: List<AccountDto>,
    val total: Long
)

/**
 * 钱包余额响应（通用类，用于 Account 和 Leader）
 */
data class WalletBalanceResponse(
    val availableBalance: String,  // 可用余额（RPC 查询的 USDC 余额）
    val positionBalance: String,  // 仓位余额（持仓总价值）
    val totalBalance: String,  // 总余额 = 可用余额 + 仓位余额
    val positions: List<PositionDto> = emptyList()
)

/**
 * 账户余额响应
 */
data class AccountBalanceResponse(
    val availableBalance: String,  // 可用余额（RPC 查询的 USDC 余额）
    val positionBalance: String,  // 仓位余额（持仓总价值）
    val totalBalance: String,  // 总余额 = 可用余额 + 仓位余额
    val positions: List<PositionDto> = emptyList()
)

/**
 * 持仓信息
 */
data class PositionDto(
    val marketId: String,
    val title: String?,  // 市场名称
    val side: String,  // YES 或 NO
    val quantity: String,
    val avgPrice: String,
    val currentValue: String,
    val pnl: String? = null
)

/**
 * 账户仓位信息（用于仓位管理页面）
 */
data class AccountPositionDto(
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val proxyAddress: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,  // 显示用的 slug
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    val marketIcon: String?,  // 市场图标 URL
    val side: String,  // 结果名称（如 "YES", "NO", "Pakistan" 等）
    val outcomeIndex: Int? = null,  // 结果索引（0, 1, 2...），用于计算 tokenId
    val quantity: String,  // 显示用的数量（可能被截位）
    val originalQuantity: String? = null,  // 原始数量（保留完整精度，用于100%出售）
    val avgPrice: String,
    val currentPrice: String,
    val currentValue: String,
    val initialValue: String,
    val pnl: String,
    val percentPnl: String,
    val realizedPnl: String?,
    val percentRealizedPnl: String?,
    val redeemable: Boolean,
    val mergeable: Boolean,
    val endDate: String?,
    val isCurrent: Boolean = true  // true: 当前仓位（有持仓），false: 历史仓位（已平仓）
)

/**
 * 仓位列表响应
 */
data class PositionListResponse(
    val currentPositions: List<AccountPositionDto>,
    val historyPositions: List<AccountPositionDto>
)

/**
 * 仓位流水查询请求（单仓位）
 */
data class PositionActivityRequest(
    val accountId: Long,
    val marketId: String,
    val outcomeIndex: Int? = null,
    val side: String,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * 仓位流水项
 */
data class PositionActivityItemDto(
    val eventType: String,          // OPEN / ADD / REDUCE / CLOSE
    val tradeSide: String,          // BUY / SELL
    val eventTime: Long,            // 毫秒时间戳
    val price: String,
    val quantity: String,
    val actualAmount: String,       // 实际投入/减少金额：price * quantity
    val fee: String,                // 实际手续费
    val remainingQuantity: String,  // 成交后剩余数量
    val source: String,             // CLOB_TRADE / SYSTEM_ORDER
    val tradeId: String? = null,
    val orderId: String? = null
)

/**
 * 仓位流水响应
 */
data class PositionActivityResponse(
    val list: List<PositionActivityItemDto>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

/**
 * 仓位卖出请求
 */
data class PositionSellRequest(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（必需）
    val side: String,              // 结果名称（如 "YES", "NO", "Pakistan" 等）（必需）
    val outcomeIndex: Int? = null, // 结果索引（0, 1, 2...），用于计算 tokenId（推荐提供）
    val orderType: String,         // 订单类型：MARKET（市价）或 LIMIT（限价）（必需）
    val quantity: String? = null,  // 卖出数量（可选，BigDecimal字符串，手动输入时使用）
    val percent: String? = null,   // 卖出百分比（可选，BigDecimal字符串，支持小数，0-100之间，选择百分比按钮时使用）
    val price: String? = null      // 限价价格（限价订单必需，市价订单不需要）
)

/**
 * 仓位卖出响应
 */
data class PositionSellResponse(
    val orderId: String,            // 订单ID
    val marketId: String,          // 市场ID
    val side: String,               // 方向
    val orderType: String,         // 订单类型
    val quantity: String,          // 订单数量
    val price: String?,             // 订单价格（限价订单）
    val status: String,             // 订单状态
    val createdAt: Long             // 创建时间戳
)

/**
 * 一键平仓请求
 */
data class PositionCloseRequest(
    val positions: List<AccountClosePositionItem>  // 要平仓的仓位列表（支持多账户）
)

/**
 * 平仓仓位项（包含账户ID）
 */
data class AccountClosePositionItem(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（conditionId）
    val side: String,              // 结果名称（必需）
    val outcomeIndex: Int? = null  // 结果索引（推荐提供）
)

/**
 * 一键平仓响应
 */
data class PositionCloseResponse(
    val totalCount: Int,                         // 请求总数
    val successCount: Int,                       // 成功提交订单数
    val failedCount: Int,                        // 失败数
    val orders: List<PositionCloseOrderInfo>,    // 成功的订单
    val failedItems: List<PositionCloseFailedItem>, // 失败的仓位
    val createdAt: Long
)

/**
 * 一键平仓成功订单信息
 */
data class PositionCloseOrderInfo(
    val accountId: Long,
    val marketId: String,
    val side: String,
    val outcomeIndex: Int? = null,
    val orderId: String,
    val quantity: String,
    val price: String?,
    val status: String
)

/**
 * 一键平仓失败项
 */
data class PositionCloseFailedItem(
    val accountId: Long,
    val marketId: String,
    val side: String,
    val outcomeIndex: Int? = null,
    val reason: String
)

/**
 * 市场价格请求
 */
data class MarketPriceRequest(
    val marketId: String,  // 市场ID
    val outcomeIndex: Int? = null  // 结果索引（可选）：0, 1, 2...，用于确定需要查询哪个 outcome 的价格。如果提供了 outcomeIndex，会转换价格（1 - 第一个outcome的价格）
)

/**
 * 获取最新价请求（通过 tokenId）
 */
data class LatestPriceRequest(
    val tokenId: String  // token ID（通过 marketId 和 outcomeIndex 计算得出）
)

/**
 * 市场当前价格响应
 */
data class MarketPriceResponse(
    val marketId: String,
    val currentPrice: String   // 当前价格（通过 MarketPriceService 获取，支持多数据源降级）
)

/**
 * 仓位赎回请求
 */
data class PositionRedeemRequest(
    val positions: List<AccountRedeemPositionItem>  // 要赎回的仓位列表（支持多账户）
)

/**
 * 账户赎回仓位项（包含账户ID）
 */
data class AccountRedeemPositionItem(
    val accountId: Long,           // 账户ID（必需）
    val marketId: String,          // 市场ID（conditionId）
    val outcomeIndex: Int,          // 结果索引（0, 1, 2...）
    val side: String? = null        // 结果名称（可选，用于显示）
)

/**
 * 赎回仓位项
 */
data class RedeemPositionItem(
    val marketId: String,          // 市场ID（conditionId）
    val outcomeIndex: Int,         // 结果索引（0, 1, 2...）
    val side: String? = null      // 结果名称（可选，用于显示）
)

/**
 * 仓位赎回响应
 */
data class PositionRedeemResponse(
    val transactions: List<AccountRedeemTransaction>,  // 每个账户的赎回交易
    val totalRedeemedValue: String,  // 赎回总价值（USDC）
    val createdAt: Long             // 创建时间戳
)

/**
 * 账户赎回交易信息
 */
data class AccountRedeemTransaction(
    val accountId: Long,
    val accountName: String?,
    val transactionHash: String,    // 交易哈希
    val positions: List<RedeemedPositionInfo>  // 赎回的仓位信息
)

/**
 * 赎回的仓位信息
 */
data class RedeemedPositionInfo(
    val marketId: String,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,          // 赎回数量
    val value: String               // 赎回价值（USDC，1:1）
)

/**
 * 可赎回仓位统计响应
 */
data class RedeemablePositionsSummary(
    val totalCount: Int,            // 可赎回仓位总数
    val totalValue: String,        // 可赎回总价值（USDC）
    val positions: List<RedeemablePositionInfo>  // 可赎回仓位列表
)

/**
 * 可赎回仓位信息
 */
data class RedeemablePositionInfo(
    val accountId: Long,
    val accountName: String?,
    val marketId: String,
    val marketTitle: String?,
    val side: String,
    val outcomeIndex: Int,
    val quantity: String,
    val value: String               // 价值（USDC，1:1）
)
