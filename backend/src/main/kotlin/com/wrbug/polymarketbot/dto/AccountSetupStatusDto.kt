package com.wrbug.polymarketbot.dto

/**
 * 账户执行诊断单项结果
 */
data class AccountExecutionCheckDto(
    /** 诊断项编码 */
    val code: String,
    /** 诊断项名称 */
    val title: String,
    /** 诊断状态：success / warning / error / skipped */
    val status: String,
    /** 诊断说明 */
    val message: String,
    /** 额外细节（可选） */
    val detail: String? = null,
    /** 修复建议（可选） */
    val suggestion: String? = null
)

/**
 * 账户设置状态检查结果
 */
data class AccountSetupStatusDto(
    /**
     * 步骤1：代理钱包是否已部署
     */
    val proxyDeployed: Boolean,
    
    /**
     * 步骤2：交易是否已启用（API Key 是否已配置）
     */
    val tradingEnabled: Boolean,
    
    /**
     * 步骤3：代币是否已批准
     */
    val tokensApproved: Boolean,

    /**
     * 是否满足执行前校验
     */
    val executionReady: Boolean = false,

    /**
     * 账户当前是否启用
     */
    val accountEnabled: Boolean = true,

    /**
     * 钱包类型
     */
    val walletType: String? = null,

    /**
     * 推导出的签名类型（1=Magic, 2=Safe, 0=EOA）
     */
    val signatureType: Int? = null,

    /**
     * 预期的代理地址（根据 walletAddress + walletType 推导）
     */
    val expectedProxyAddress: String? = null,

    /**
     * 存储的代理地址是否与链上推导结果一致
     */
    val proxyAddressMatched: Boolean? = null,

    /**
     * 钱包地址格式是否正确
     */
    val walletAddressValid: Boolean = false,

    /**
     * 代理地址格式是否正确
     */
    val proxyAddressValid: Boolean = false,

    /**
     * 私钥是否与钱包地址匹配
     */
    val privateKeyMatchesWallet: Boolean = false,

    /**
     * API 凭证是否都已配置
     */
    val apiCredentialsConfigured: Boolean = false,

    /**
     * API 凭证是否可成功解密
     */
    val apiCredentialsDecryptable: Boolean = false,
    
    /**
     * 代币批准详情（各合约的授权额度）
     * Key: 合约名称（CTF_CONTRACT, CTF_EXCHANGE, NEG_RISK_EXCHANGE, NEG_RISK_ADAPTER）
     * Value: 授权额度（USDC，6位小数）
     */
    val approvalDetails: Map<String, String>? = null,
    
    /**
     * 检查错误信息（如果有）
     */
    val error: String? = null,

    /**
     * 详细诊断列表
     */
    val checks: List<AccountExecutionCheckDto> = emptyList(),

    /**
     * 检查时间
     */
    val checkedAt: Long = System.currentTimeMillis()
)

/**
 * 执行设置步骤请求
 */
data class ExecuteSetupStepRequest(
    /** 账户 ID */
    val accountId: Long? = null,
    /** 步骤：1=部署代理, 2=启用交易, 3=批准代币 */
    val step: Int? = null
)

/**
 * 执行设置步骤响应
 */
data class ExecuteSetupStepResponse(
    /** 是否由后端执行成功（步骤1 仅返回跳转链接，为 false） */
    val success: Boolean = false,
    /** 需跳转时由后端提供的 URL（步骤1 使用） */
    val redirectUrl: String? = null,
    /** 链上交易哈希（步骤3 批准代币成功时返回） */
    val transactionHash: String? = null
)

/**
 * 账户导入响应（扩展，包含设置状态）
 */
data class AccountImportResponse(
    val account: AccountDto,
    val setupStatus: AccountSetupStatusDto? = null  // 设置状态检查结果（可选）
)
